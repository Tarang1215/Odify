#!/usr/bin/env python3
"""Build resumable, review-only YouTube link candidates from Odify MASTER CSV.

Reads metadata only through the supported YouTube Data API v3. NEVER downloads
audio, changes MASTER cells, or treats 'Official Audio' in a title as proof
of rights or an exact recording match.

Usage:
  YOUTUBE_API_KEY=... python scripts/odify_link_candidates.py \
    --master-csv Odify_MASTER.csv --output candidate_links.csv \
    --year 2026 --max-searches 25 --workers 4
  python scripts/odify_link_candidates.py --master-csv Odify_MASTER.csv --dry-run

The search endpoint costs 100 YouTube quota units per unique query. At the
default 10,000 units/day this limits new searches to ~100/day; cached queries
10,000-unit general read bucket. Cache hits reduce the number of calls.
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import re
import tempfile
import threading
import time
import unicodedata
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlencode, urlparse
from urllib.request import Request, urlopen

API = "https://www.googleapis.com/youtube/v3/"
VIDEO_ID = re.compile(r"^[A-Za-z0-9_-]{11}$")
CACHE_LOCK = threading.Lock()


def normalize(value: str) -> str:
    text = unicodedata.normalize("NFKC", value or "").casefold()
    return "".join(ch for ch in text if ch.isalnum())


def query_for(row: dict) -> str:
    return f"{row.get('아티스트', '').strip()} {row.get('곡명', '').strip()} official audio".strip()


def existing_video_id(url: str) -> str | None:
    parsed = urlparse(url.strip())
    if parsed.scheme != "https":
        return None
    if parsed.hostname in ("youtube.com", "www.youtube.com", "music.youtube.com", "m.youtube.com"):
        video = parse_qs(parsed.query).get("v", [""])[0]
    elif parsed.hostname == "youtu.be":
        video = parsed.path.lstrip("/").split("/")[0]
    else:
        return None
    return video if VIDEO_ID.fullmatch(video) else None


def pending(rows: list[dict], year: str | None = None) -> list[dict]:
    result = []
    for row in rows:
        if not row.get("Track ID", "").startswith("ODF"):
            continue
        if year and row.get("연도", "").strip() != year:
            continue
        if not row.get("아티스트", "").strip() or not row.get("곡명", "").strip():
            continue
        if row.get("YouTube 오디오 URL", "").strip() and row.get("링크검증", "").strip() == "검증완료":
            continue
        result.append(row)
    return result


def api_get(resource: str, params: dict, key: str) -> dict:
    full = API + resource + "?" + urlencode({**params, "key": key})
    for attempt in range(3):
        try:
            with urlopen(Request(full, headers={"User-Agent": "Odify-link-review/1.0"}), timeout=20) as r:
                return json.load(r)
        except HTTPError as ex:
            if ex.code in (403, 400):
                raise RuntimeError(f"YouTube quota/argument error HTTP {ex.code}") from ex
            if ex.code not in (429, 500, 502, 503, 504) or attempt == 2:
                raise
        except (URLError, TimeoutError):
            if attempt == 2:
                raise
        time.sleep(0.7 * (2**attempt))
    return {}


def load_cache(path: Path) -> dict:
    if not path.is_file():
        return {}
    data = json.loads(path.read_text(encoding="utf-8"))
    return data if isinstance(data, dict) else {}


def save_cache(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    # Never store the API key or raw OAuth credentials in this file.
    temp = path.with_suffix(path.suffix + ".tmp")
    temp.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")
    temp.replace(path)


def is_possible_track(row: dict, snippet: dict) -> bool:
    # A weak search result can contain only part of the original title.
    # Do not pass it along if neither the full title nor artist appears.
    title = normalize(snippet.get("title", ""))
    artist = normalize(row.get("아티스트", ""))
    track = normalize(row.get("곡명", ""))
    return bool(title and artist and track and track in title and artist in
                normalize(snippet.get("title", "") + " " + snippet.get("channelTitle", "")))


def evidence_rank(row: dict, snippet: dict, detail: dict) -> tuple[str, str]:
    title = snippet.get("title", "")
    channel = snippet.get("channelTitle", "")
    description = detail.get("snippet", {}).get("description", "")
    topic = channel.casefold().endswith(" - topic")
    provided = "provided to youtube by" in description.casefold()
    eligible = is_possible_track(row, snippet)
    if eligible and topic and provided:
        return ("우선검토", "제목·가수 일치 / Topic 채널 / Provided to YouTube 설명")
    if eligible:
        return ("검토필요", "제목·가수 검색 일치, 배급 근거 수동 확인 필요")
    return ("불일치가능", "곡명·아티스트 자동 대조 불충분")


def run(master: Path, output: Path, cache_file: Path, year: str | None,
        limit: int, workers: int, key: str, dry_run: bool) -> dict:
    with master.open(newline="", encoding="utf-8-sig") as fp:
        reader = csv.DictReader(fp)
        required = {"Track ID", "아티스트", "곡명", "YouTube 오디오 URL", "링크검증"}
        if not reader.fieldnames or not required.issubset(set(reader.fieldnames)):
            raise ValueError("MASTER CSV 필수 열이 누락됐습니다.")
        rows = list(reader)
    todo = pending(rows, year)
    # Existing unverified video IDs use a single batched videos.list call
    # (1 quota unit / up to 50 IDs), not costly search.list (100 each).
    queries = {query_for(row) for row in todo
               if existing_video_id(row.get("YouTube 오디오 URL", "")) is None}
    cache = load_cache(cache_file)
    missing = [q for q in queries if q not in cache]
    # Keep the same stable candidate order across executions.
    missing.sort()
    chosen = missing[:max(0, limit)]
    summary = {"pending_tracks": len(todo), "unique_queries": len(queries),
               "cached_queries": len(queries) - len(missing),
               "new_searches": len(chosen),
               "planned_search_calls": len(chosen)}
    if dry_run:
        return summary
    if not key and todo:
        raise ValueError("YouTube Data API key가 필요합니다 (YOUTUBE_API_KEY).")
    def search(q: str) -> tuple[str, dict]:
        return q, api_get("search", {
            "part": "snippet", "q": q, "type": "video",
            "maxResults": 5, "safeSearch": "moderate"
        }, key)
    with ThreadPoolExecutor(max_workers=max(1, min(workers, 6))) as pool:
        for future in as_completed([pool.submit(search, q) for q in chosen]):
            q, data = future.result()
            with CACHE_LOCK:
                cache[q] = data
    save_cache(cache_file, cache)

    video_ids = set()
    for row in todo:
        existing = existing_video_id(row.get("YouTube 오디오 URL", ""))
        if existing:
            video_ids.add(existing)
        for item in cache.get(query_for(row), {}).get("items", []):
            video = (item.get("id") or {}).get("videoId", "")
            if VIDEO_ID.fullmatch(video):
                video_ids.add(video)
    detail = {}
    ordered_ids = sorted(video_ids)
    for start in range(0, len(ordered_ids), 50):
        info = api_get("videos", {
            "part": "snippet,contentDetails,status",
            "id": ",".join(ordered_ids[start:start + 50])
        }, key)
        detail.update({x.get("id"): x for x in info.get("items", [])})
    records = []
    for row in todo:
        q = query_for(row)
        existing = existing_video_id(row.get("YouTube 오디오 URL", ""))
        items = ([{"id": {"videoId": existing},
                   "snippet": detail.get(existing, {}).get("snippet", {})}]
                 if existing else cache.get(q, {}).get("items", []))
        for item in items:
            vid = (item.get("id") or {}).get("videoId", "")
            if not vid or vid not in detail:
                continue
            meta = item.get("snippet") or {}
            status, why = evidence_rank(row, meta, detail[vid])
            if existing:
                why = "기존 MASTER URL 재검토 · " + why
            records.append({
                "Track ID": row["Track ID"], "연도": row.get("연도", ""),
                "아티스트": row.get("아티스트", ""), "곡명": row.get("곡명", ""),
                "후보 YouTube URL": "https://www.youtube.com/watch?v=" + vid,
                "채널": meta.get("channelTitle", ""),
                "영상 제목": meta.get("title", ""),
                "후보 상태": status, "검토 근거": why,
                "자동 확정": "아니오"
            })
    fields = ["Track ID", "연도", "아티스트", "곡명", "후보 YouTube URL",
              "채널", "영상 제목", "후보 상태", "검토 근거", "자동 확정"]
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8-sig", newline="") as fp:
        writer = csv.DictWriter(fp, fieldnames=fields)
        writer.writeheader()
        writer.writerows(records)
    return {**summary, "candidate_rows": len(records), "output": str(output)}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--master-csv", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path("odify_link_review.csv"))
    parser.add_argument("--cache", type=Path, default=Path(".local/odify_link_search_cache.json"))
    parser.add_argument("--year", help="Limit candidates to one chart year, e.g. 2026")
    parser.add_argument("--max-searches", type=int, default=25)
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    print(json.dumps(run(args.master_csv, args.output, args.cache, args.year,
                         args.max_searches, args.workers,
                         os.environ.get("YOUTUBE_API_KEY", ""), args.dry_run),
                     ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

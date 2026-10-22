#!/usr/bin/env python3
"""Odify: idempotent, resumable 2026 music collection from a fixed snapshot.

Run on a computer with ordinary network access and a configured Google Drive rclone
remote. Existing remote files are never re-downloaded. Recoverable failures stay
visible in CSV and JSON status reports. No credentials committed in this archive.
"""
from __future__ import annotations
import argparse
import csv
import hashlib
import importlib.util
import json
import re
import shutil
import sqlite3
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

BASE = Path(__file__).resolve().parent
DATA = BASE / "2026_tracks.csv"
YEAR = "2026"
TRACK_ID = re.compile(r"^ODF\d{6,}$")


def parse_snapshot(csv_path: Path) -> list[dict[str, str]]:
    with csv_path.open(encoding="utf-8-sig", newline="") as f:
        rows = list(csv.DictReader(f))
    ids = [r["Track ID"].strip() for r in rows]
    if len(rows) != 152 or len(ids) != len(set(ids)) or not all(TRACK_ID.fullmatch(x) for x in ids):
        raise ValueError("2026 snapshot changed: expected exactly 152 distinct ODF track IDs")
    if any(str(r.get("연도", "")).strip() != YEAR for r in rows):
        raise ValueError("2026 snapshot must contain only chart-year 2026")
    return rows


def get_worker() -> object:
    worker_path = BASE / "music_catalog.py"
    if not worker_path.exists():
        raise RuntimeError("music_catalog.py is missing. Extract the complete Odify ZIP again.")
    spec = importlib.util.spec_from_file_location("music_catalog", worker_path)
    if spec is None or spec.loader is None:
        raise RuntimeError("Cannot load music_catalog.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def run(args: list[str], *, timeout: int = 600) -> str:
    proc = subprocess.run(args, capture_output=True, text=True, errors="replace", timeout=timeout)
    if proc.returncode:
        raise RuntimeError((proc.stderr or proc.stdout or "command failed")[-1200:])
    return proc.stdout


def remote_files(remote: str) -> dict[str, str]:
    """Return only exact ODFxxxxxx media, index by Track ID."""
    raw = run(["rclone", "lsjson", "--recursive", "--files-only", "--max-depth", "3", remote])
    found = {}
    for obj in json.loads(raw):
        p = Path(str(obj.get("Path", "")))
        if p.suffix.lower() not in (".mp3", ".m4a", ".flac"):
            continue
        match = re.match(r"^(ODF\d{6,})(?:\b|\s|-|\.)", p.stem)
        if match:
            found[match.group(1)] = str(p)
    return found


def probe_youtube_access(records: list[dict[str, str]], existing: dict[str, str]) -> None:
    """Fail fast if the PC itself is being challenged as an automated server."""
    candidate = next((row for row in records
                      if row["Track ID"] not in existing
                      and row["링크검증"] == "검증완료"), None)
    if candidate is None:
        return
    command = [sys.executable, "-m", "yt_dlp", "--no-playlist", "--skip-download", "--dump-single-json",
               "--socket-timeout", "25", candidate["YouTube 오디오 URL"]]
    try:
        proc = subprocess.run(command, capture_output=True, text=True,
                              timeout=90, errors="replace")
    except subprocess.TimeoutExpired:
        print("YouTube access probe timed out; song-by-song processing will report issues")
        return
    if proc.returncode:
        diagnostic = (proc.stderr or proc.stdout or "")[-1200:]
        if "not a bot" in diagnostic.casefold() or "confirm you’re not a bot" in diagnostic.casefold():
            raise RuntimeError("This PC was challenged by YouTube. The batch was not started. "
                               "See the troubleshooting section in README; no account "
                               "passwords or cookies are requested.\n" + diagnostic)
        print("YouTube test video returned an error; batch can still try other songs:",
              diagnostic[-220:])
    else:
        print("YouTube metadata access on this PC: OK")


def repair_existing_artwork(collector, root: Path, remote: str, existing: dict[str, str],
                            rows: list[dict[str, str]]) -> dict[str, int]:
    """Fix missing embedded cover art on already uploaded MP3s without redownloading from YouTube."""
    from mutagen.id3 import ID3
    repaired, skipped, unavailable, failed = 0, 0, 0, 0
    for row in rows:
        track_id = row["Track ID"]
        remote_path = existing.get(track_id)
        if not remote_path or not remote_path.lower().endswith(".mp3"):
            continue
        local = root / "CoverRepair" / f"{track_id}.mp3"
        local.parent.mkdir(parents=True, exist_ok=True)
        try:
            source = remote.rstrip("/") + "/" + remote_path.replace("\\", "/")
            run(["rclone", "copyto", source, str(local)], timeout=300)
            if ID3(str(local)).getall("APIC"):
                skipped += 1
                continue
            cover = collector.try_cover(row["아티스트"], row["곡명"], row["앨범"])
            origin = "album"
            if cover is None:
                cover = collector.try_youtube_thumbnail(row["YouTube 오디오 URL"])
                origin = "youtube_thumbnail"
            if not cover:
                unavailable += 1
                print("COVER UNAVAILABLE", track_id, flush=True)
                continue
            collector.tag_audio(local, row["아티스트"], row["곡명"], row["앨범"],
                                row["장르"], cover, None)
            run(["rclone", "copyto", str(local), source, "--checksum"], timeout=900)
            remote_hash = run(["rclone", "md5sum", source], timeout=120).strip().split()[0]
            local_hash = hashlib.md5(local.read_bytes()).hexdigest()
            if remote_hash.casefold() != local_hash.casefold():
                raise RuntimeError("Uploaded cover-repaired file MD5 mismatch")
            repaired += 1
            print("COVER REPAIRED", track_id, origin, flush=True)
        except Exception as exc:
            failed += 1
            print("COVER REPAIR FAILED", track_id, str(exc)[-220:], flush=True)
        finally:
            if local.exists():
                local.unlink()
    return {"repaired": repaired, "already_embedded": skipped,
            "cover_unavailable": unavailable, "errors": failed}


def summary(db: sqlite3.Connection, snapshot: list[dict[str, str]], remote: dict) -> dict:
    byid = {r["Track ID"]: r for r in snapshot}
    report = {
        "updated_at": datetime.now(timezone.utc).isoformat(),
        "chart_year": 2026,
        "total_tracks": len(snapshot),
        "remote_audio_count": sum(i in remote for i in byid),
        "verified_link_count": sum(r["링크검증"] == "검증완료" for r in snapshot),
        "pending_link_review": [r["Track ID"] for r in snapshot if r["링크검증"] != "검증완료"],
        "statuses": {}, "failed": [], "pending": []
    }
    states = db.execute("SELECT id,status,cover_status,lyric_status,last_error FROM tracks").fetchall()
    for track_id, state, cover, lyrics, error in states:
        if track_id not in byid:
            continue
        report["statuses"][state] = report["statuses"].get(state, 0) + 1
        if state == "failed":
            report["failed"].append({"id": track_id, "error": error or ""})
        if state != "downloaded":
            report["pending"].append(track_id)
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description="Odify 2026 all 152 tracks, resumable")
    parser.add_argument("--root", type=Path, default=Path.home() / "Music" / "Odify_Library")
    parser.add_argument("--remote", default="mydrive:Odify_Auto_Upload")
    parser.add_argument("--limit", type=int, default=152)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--skip-cover-repair", action="store_true", help="do not inspect already uploaded MP3s")
    args = parser.parse_args()
    records = parse_snapshot(DATA)
    print(f"2026 snapshot: {len(records)} tracks; verified={sum(x['링크검증']=='검증완료' for x in records)}")
    if args.dry_run:
        print("DRY RUN: no downloads, no Google connection, no files changed")
        for r in records[:8]:
            print(r["Track ID"], r["아티스트"], "-", r["곡명"])
        return 0
    if args.limit < 1 or args.limit > 152:
        parser.error("--limit must be between 1 and 152")
    if importlib.util.find_spec('yt_dlp') is None:
        parser.error('yt-dlp is not installed in the selected Python environment.')
    for executable in ("ffmpeg", "rclone"):
        if not shutil.which(executable):
            parser.error(f"Missing {executable}. Install it first and reopen PowerShell.")
    root = args.root.expanduser().resolve() / "2026"
    root.mkdir(parents=True, exist_ok=True)
    collector = get_worker()
    conn = collector.connection(root / "catalog.sqlite3")
    try:
        print("Indexing 2026 snapshot...")
        collector.ingest(conn, DATA, default_year=YEAR)
        try:
            already = remote_files(args.remote)
        except Exception as exc:
            raise RuntimeError(f"Cannot access Drive remote {args.remote}: {exc}") from exc
        # Verify remote presence before declaring it completed. Older existing files can have
        # different embedded artwork or non-ODF names and must be investigated separately.
        for track_id in already:
            conn.execute("UPDATE tracks SET status='downloaded',last_error=NULL "
                         "WHERE id=?", (track_id,))
        # Previously recorded downloaded media which were subsequently removed from both
        # remote and local should be re-queued rather than silently skipped forever.
        for track_id, status, p in conn.execute("SELECT id,status,file_path FROM tracks").fetchall():
            if status != "downloaded" or track_id in already:
                continue
            if not p or not (root / p).is_file():
                conn.execute("UPDATE tracks SET status='queued',file_path=NULL,sha256=NULL WHERE id=?", (track_id,))
        conn.commit()
        print(f"Existing Drive tracks: {sum(i in already for i in [r['Track ID'] for r in records])}")
        probe_youtube_access(records, already)
        cover_repair = {"repaired": 0, "already_embedded": 0,
                        "cover_unavailable": 0, "errors": 0}
        if not args.skip_cover_repair:
            print("Checking existing Drive MP3 cover artwork...")
            cover_repair = repair_existing_artwork(collector, root, args.remote, already, records)
            print("Existing Drive covers:", cover_repair)
        conn.close()
        from argparse import Namespace
        collector.run_worker(Namespace(
            root=root, command="run", limit=args.limit, enrich=True,
            audio_format="mp3", accept_lyric_or_mv=True, dry_run=False,
            chart_year=YEAR))
        folder = root / "Audio"
        if folder.is_dir() and any(folder.glob("*.mp3")):
            print("Uploading MP3 to Drive; exact same names are updated, not duplicated...")
            run(["rclone", "copy", str(folder), args.remote,
                 "--transfers", "2", "--checkers", "4"], timeout=7200)
            run(["rclone", "check", str(folder), args.remote,
                 "--one-way"], timeout=7200)
        lyrics = root / "Lyrics"
        if lyrics.is_dir() and list(lyrics.glob("*.lrc")):
            run(["rclone", "copy", str(lyrics), args.remote.rstrip("/")+"/Lyrics",
                 "--transfers", "2"], timeout=7200)
        conn = collector.connection(root / "catalog.sqlite3")
        collector.export_status(conn, root / "status.csv")
        result = summary(conn, records, remote_files(args.remote))
        result["cover_repair"] = cover_repair
        (root / "odify_2026_report.json").write_text(
            json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps({k: result[k] for k in ("total_tracks", "remote_audio_count", "verified_link_count", "statuses")},
                         ensure_ascii=False, indent=2))
        print("Detailed report:", root / "odify_2026_report.json")
        print("Song-by-song report:", root / "status.csv")
        return 0 if not result["failed"] else 2
    finally:
        conn.close()


if __name__ == "__main__":
    raise SystemExit(main())

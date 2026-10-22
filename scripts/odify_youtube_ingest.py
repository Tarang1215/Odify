#!/usr/bin/env python3
"""Odify YouTube playlist audio ingestion companion for user-authorized media.

Takes a JSON export from Odify's YouTube playlist tab, retrieves accessible
audio using yt-dlp + ffmpeg, tags it with verified Google Sheets MASTER data
when supplied, and optionally uploads new audio to the user's Drive via rclone.

This is a one-shot desktop batch collector, not an always-on streaming server.
It does not bypass DRM, authentication, private-video controls or paywalls.

Usage:
    pip install yt-dlp mutagen
    python scripts/odify_youtube_ingest.py --manifest Odify_YouTube_x.json \
        --output ~/OdifyMusic --master-csv Odify_MASTER.csv --dry-run
    python scripts/odify_youtube_ingest.py --manifest Odify_YouTube_x.json \
        --output ~/OdifyMusic --master-csv Odify_MASTER.csv \
        --drive-remote 'gdrive:Odify_Music_Test/Odify_Auto_Upload' --run
Requires ffmpeg and optionally rclone installed separately.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
import sqlite3
import subprocess
import sys
import unicodedata
from pathlib import Path
from urllib.parse import parse_qs, urlparse

VALID_ID = re.compile(r"^[A-Za-z0-9_-]{11}$")
FORMATS = {".m4a", ".mp3", ".ogg", ".opus", ".flac", ".wav"}
KEY_ALIASES = {
    "title": ("곡명", "title"),
    "artist": ("아티스트", "artist"),
    "album": ("앨범", "album"),
    "id": ("Track ID", "track_id"),
    "youtube": ("YouTube 오디오 URL", "youtube_url"),
    "version": ("음원 버전", "version"),
    "drive": ("Drive 파일 URL", "drive_url"),
}


def normalize(text: str) -> str:
    return "".join(c for c in unicodedata.normalize("NFKC", text).casefold()
                   if c.isalnum())


def youtube_id(url: str) -> str | None:
    parsed = urlparse(url)
    host = parsed.netloc.casefold()
    if host in {"youtube.com", "www.youtube.com", "music.youtube.com",
                "m.youtube.com"}:
        candidate = parse_qs(parsed.query).get("v", [None])[0]
    elif host == "youtu.be":
        candidate = parsed.path.strip("/").split("/")[0]
    else:
        return None
    return candidate if candidate and VALID_ID.fullmatch(candidate) else None


def master_index(path: Path | None) -> dict[str, dict]:
    if not path:
        return {}
    with path.open("r", encoding="utf-8-sig", newline="") as f:
        rows = csv.DictReader(f)
        lookup = {}
        for record in rows:
            row = {
                target: next((record.get(k, "") for k in aliases if record.get(k)), "")
                for target, aliases in KEY_ALIASES.items()
            }
            video_id = youtube_id(row["youtube"])
            if video_id:
                lookup[video_id] = row
        return lookup


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def md5(path: Path) -> str:
    digest = hashlib.md5()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def safe_name(value: str) -> str:
    return re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", value).strip().rstrip(".")[:140] or "Unknown"


def connection(path: Path) -> sqlite3.Connection:
    conn = sqlite3.connect(path)
    conn.execute("""CREATE TABLE IF NOT EXISTS tracks(
        video_id TEXT PRIMARY KEY,
        title TEXT NOT NULL,
        artist TEXT NOT NULL,
        album TEXT NOT NULL,
        version TEXT NOT NULL,
        sha256 TEXT NOT NULL,
        md5 TEXT NOT NULL,
        file_name TEXT NOT NULL,
        uploaded INTEGER NOT NULL DEFAULT 0
    )""")
    conn.execute("CREATE UNIQUE INDEX IF NOT EXISTS files_by_sha ON tracks(sha256)")
    conn.commit()
    return conn


def local_metadata(path: Path) -> tuple[str, str, str]:
    try:
        import mutagen
        tags = mutagen.File(str(path), easy=True)
        if tags:
            def first(key: str) -> str:
                value = tags.get(key, [""])
                return str(value[0]) if value else ""
            return first("title"), first("artist"), first("album")
    except Exception:
        pass
    return "", "", ""


def known_tracks(folder: Path, db: sqlite3.Connection) -> set[tuple[str, str, str]]:
    known = {(normalize(t), normalize(a), normalize(b))
             for t, a, b in db.execute("SELECT title,artist,album FROM tracks")}
    for path in folder.glob("*"):
        if path.is_file() and path.suffix.lower() in FORMATS:
            title, artist, album = local_metadata(path)
            if title and artist:
                known.add((normalize(title), normalize(artist), normalize(album)))
    return known


def tag_audio(path: Path, song: dict, original_url: str) -> None:
    try:
        import mutagen
        from mutagen.mp4 import MP4, MP4Cover
        audio = MP4(str(path))
        for tag, val in {
            "\xa9nam": song.get("title", ""),
            "\xa9ART": song.get("artist", ""),
            "\xa9alb": song.get("album", ""),
            "\xa9cmt": f"Odify source: {original_url}",
        }.items():
            if val:
                audio[tag] = [str(val)]
        audio.save()
    except Exception as exc:
        print(f"  ! 태그 저장 실패 ({exc}); 오디오 파일은 보관됩니다.")


def remote_index(remote: str) -> tuple[set[str], dict[str, str]]:
    print(f"Drive MD5 인덱스 읽는 중: {remote}")
    p = subprocess.run(
        ["rclone", "lsjson", remote, "--recursive", "--hash"],
        capture_output=True, text=True, check=True
    )
    data = json.loads(p.stdout)
    hashes = set()
    names = {}
    for item in data:
        if item.get("IsDir"):
            continue
        fingerprint = item.get("Hashes", {}).get("MD5", "").lower()
        relative = str(item.get("Path", ""))
        if relative:
            names[relative] = fingerprint
        if fingerprint:
            hashes.add(fingerprint)
    return hashes, names


def download_audio(video_id: str, folder: Path) -> Path:
    try:
        from yt_dlp import YoutubeDL
    except ImportError as exc:
        raise RuntimeError("yt-dlp가 필요합니다: pip install yt-dlp mutagen") from exc
    before = {x.name for x in folder.glob(f"{video_id}.*")}
    opts = {
        "format": "bestaudio[ext=m4a]/bestaudio",
        "outtmpl": str(folder / f"{video_id}.%(ext)s"),
        "noplaylist": True,
        "quiet": True,
        "no_warnings": False,
        "retries": 3,
        "fragment_retries": 3,
        "postprocessors": [{"key": "FFmpegExtractAudio",
                            "preferredcodec": "m4a"}],
        "overwrites": False,
    }
    with YoutubeDL(opts) as downloader:
        downloader.download([f"https://www.youtube.com/watch?v={video_id}"])
    candidates = [p for p in folder.glob(f"{video_id}.*")
                  if p.suffix.lower() == ".m4a"]
    if not candidates:
        raise RuntimeError("m4a 음원 생성 실패 (ffmpeg 또는 접근 권한 확인 필요)")
    return max(candidates, key=lambda p: p.stat().st_mtime)


def ingest(args: argparse.Namespace) -> int:
    payload = json.loads(args.manifest.read_text(encoding="utf-8-sig"))
    if payload.get("format") != "odify-youtube-collection-v1":
        raise ValueError("Odify 수집 목록 JSON 형식이 아닙니다.")
    tracks = payload.get("tracks")
    if not isinstance(tracks, list):
        raise ValueError("JSON tracks 배열이 없습니다.")
    args.output.mkdir(parents=True, exist_ok=True)
    database = connection(args.output / "odify_ingest.sqlite3")
    master = master_index(args.master_csv)
    known = known_tracks(args.output, database)
    remote_md5, remote_names = (
        remote_index(args.drive_remote) if args.drive_remote and args.run
        else (set(), {})
    )
    complete = skipped = failures = 0
    for i, item in enumerate(tracks, 1):
        vid = str(item.get("youtube_video_id", ""))
        if not VALID_ID.fullmatch(vid):
            skipped += 1
            print(f"[{i}/{len(tracks)}] 잘못된 YouTube ID 건너뜀")
            continue
        if database.execute("SELECT 1 FROM tracks WHERE video_id=?", (vid,)).fetchone():
            skipped += 1
            print(f"[{i}/{len(tracks)}] 중복 YouTube ID: {vid}")
            continue
        info = master.get(vid, {})
        if info.get("drive"):
            skipped += 1
            print(f"[{i}/{len(tracks)}] MASTER에서 Drive 보유 표시: {vid}")
            continue
        display = str(item.get("title", "")).strip()
        pieces = display.split(" - ", 1)
        artist = info.get("artist") or (pieces[0].strip() if len(pieces) > 1 else "")
        title = info.get("title") or (pieces[1].strip() if len(pieces) > 1 else display)
        album = info.get("album", "")
        version = info.get("version", "")
        identity = (normalize(title), normalize(artist), normalize(album))
        if identity in known and artist:
            skipped += 1
            print(f"[{i}/{len(tracks)}] 동일 곡/앨범 보유: {artist} - {title}")
            continue
        print(f"[{i}/{len(tracks)}] {artist} - {title} | {vid}")
        if not args.run:
            continue
        try:
            temp = download_audio(vid, args.output)
            target = args.output / (safe_name(info.get("id") or vid) + ".m4a")
            if temp != target:
                if target.exists():
                    raise RuntimeError(f"목표 파일이 이미 존재합니다: {target}")
                temp.rename(target)
            tag_audio(target, {"title": title, "artist": artist,
                               "album": album}, f"https://youtu.be/{vid}")
            audio_sha = sha256(target)
            if database.execute("SELECT 1 FROM tracks WHERE sha256=?", (audio_sha,)).fetchone():
                target.unlink(missing_ok=True)
                skipped += 1
                print("  = 동일 바이트 파일 (SHA-256) 중복으로 제외")
                continue
            audio_md5 = md5(target)
            upload_done = 0
            if args.drive_remote:
                if audio_md5 in remote_md5:
                    upload_done = 1
                    print("  = Drive에 정확히 동일한 파일이 이미 존재")
                elif target.name in remote_names:
                    # Do not silently claim success when --ignore-existing
                    # leaves a different file under the exact same filename.
                    raise RuntimeError(
                        f"Drive 파일명 충돌: {target.name}. 기존 파일은 수정하지 않았습니다."
                    )
                else:
                    destination = args.drive_remote.rstrip("/") + "/" + target.name
                    subprocess.run(
                        ["rclone", "copyto", str(target), destination,
                         "--ignore-existing"], check=True
                    )
                    confirm = subprocess.run(
                        ["rclone", "md5sum", destination],
                        capture_output=True, text=True, check=True
                    )
                    actual = confirm.stdout.strip().split(" ")[0].lower()
                    if actual != audio_md5:
                        raise RuntimeError(
                            "Drive 업로드 결과 MD5 검증 실패. 파일을 중복 등록하지 않습니다."
                        )
                    remote_md5.add(audio_md5)
                    remote_names[target.name] = audio_md5
                    upload_done = 1
                    print("  + Drive 저장 및 원격 MD5 확인 완료")
            database.execute("""INSERT INTO tracks(
                video_id,title,artist,album,version,sha256,md5,file_name,uploaded
                ) VALUES(?,?,?,?,?,?,?,?,?)""",
                (vid,title,artist,album,version,audio_sha,audio_md5,target.name,upload_done))
            database.commit()
            known.add(identity)
            complete += 1
        except Exception as exc:
            failures += 1
            print(f"  ! 처리 실패: {exc}")
    print(f"완료={complete}, 중복·건너뜀={skipped}, 실패={failures}")
    return 1 if failures else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest",type=Path,required=True)
    parser.add_argument("--output",type=Path,required=True)
    parser.add_argument("--master-csv",type=Path)
    parser.add_argument("--drive-remote",help="e.g. gdrive:Odify_Music_Test/Odify_Auto_Upload")
    mode=parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--run",action="store_true",help="Download/ingest matching authorized audio")
    mode.add_argument("--dry-run",action="store_true",help="Preview without downloading")
    args=parser.parse_args()
    return ingest(args)


if __name__=="__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Odify collection worker v0.1.

Google Sheets CSV -> SQLite queue -> verified URL download -> M4A metadata ->
Drive-ready folder. No background daemon and no secrets in source control.

Needs Python 3.11+, yt-dlp and ffmpeg on PATH. Optional: mutagen for tags,
requests not required (stdlib urllib is used for enrichment).
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import subprocess
import sys
import time
from urllib.parse import urlencode
from urllib.request import Request, urlopen

EXTENSIONS = (".m4a", ".mp3", ".flac", ".opus", ".ogg", ".wav", ".aac")
ALLOWED_HOSTS = {"youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be",
                 "music.youtube.com"}
COLUMNS = ("Track ID", "연도", "국가", "장르", "아티스트", "곡명", "앨범",
           "YouTube 오디오 URL", "링크검증", "다운로드", "커버", "가사")
HEADER = "OdifyMusicBot/0.1 (personal test library)"


def validate_video_url(value: str) -> bool:
    from urllib.parse import urlsplit
    try:
        uri = urlsplit(value.strip())
        return uri.scheme == "https" and uri.hostname in ALLOWED_HOSTS and bool(uri.path)
    except ValueError:
        return False


def normalize(value: str) -> str:
    return re.sub(r"[^\w]+", "", value.casefold())


def connection(database: Path) -> sqlite3.Connection:
    database.parent.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(database)
    db.execute("PRAGMA journal_mode=WAL")
    db.execute("""CREATE TABLE IF NOT EXISTS tracks (
      id TEXT PRIMARY KEY,
      chart_year TEXT, country TEXT, genre TEXT, artist TEXT,
      title TEXT, album TEXT, video_url TEXT, verified TEXT,
      status TEXT DEFAULT 'queued',
      file_path TEXT, sha256 TEXT, cover_status TEXT DEFAULT 'pending',
      lyric_status TEXT DEFAULT 'pending',
      last_error TEXT, updated_at TEXT DEFAULT CURRENT_TIMESTAMP
    )""")
    db.execute("""CREATE TABLE IF NOT EXISTS duplicate_candidates (
      track_id TEXT PRIMARY KEY, canonical_track_id TEXT NOT NULL
    )""")
    db.commit()
    return db


def ingest(db: sqlite3.Connection, path: Path) -> tuple[int, int]:
    count, dupe = 0, 0
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        if not set(COLUMNS).issubset(reader.fieldnames or []):
            missing = set(COLUMNS).difference(reader.fieldnames or [])
            raise ValueError(f"MASTER CSV columns missing: {sorted(missing)}")
        for row in reader:
            track_id = row["Track ID"].strip()
            artist, title = row["아티스트"].strip(), row["곡명"].strip()
            if not re.fullmatch(r"ODF\d{6,}", track_id) or not artist or not title:
                continue
            db.execute("""INSERT INTO tracks
             (id, chart_year, country, genre, artist, title, album, video_url, verified)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
             ON CONFLICT(id) DO UPDATE SET
             chart_year=excluded.chart_year, country=excluded.country,
             genre=excluded.genre, artist=excluded.artist,
             title=excluded.title, album=excluded.album,
             video_url=excluded.video_url, verified=excluded.verified,
             updated_at=CURRENT_TIMESTAMP""",
             (track_id, row["연도"].strip(), row["국가"].strip(),
              row["장르"].strip(), artist, title, row["앨범"].strip(),
              row["YouTube 오디오 URL"].strip(), row["링크검증"].strip()))
            count += 1

    # Conservative duplicates: exact normalized artist + song title only.
    # Alternative takes, covers and remasters require manual review.
    db.execute("DELETE FROM duplicate_candidates")
    seen: dict[tuple[str, str], str] = {}
    for track_id, artist, title in db.execute(
            "SELECT id,artist,title FROM tracks ORDER BY id"):
        key = (normalize(artist), normalize(title))
        if key in seen:
            db.execute("INSERT INTO duplicate_candidates VALUES (?, ?)",
                       (track_id, seen[key]))
            dupe += 1
        else:
            seen[key] = track_id
    db.commit()
    return count, dupe


def fetch_json(url: str, timeout: int = 12) -> object:
    req = Request(url, headers={"User-Agent": HEADER})
    with urlopen(req, timeout=timeout) as response:
        if response.status != 200:
            raise ValueError(f"HTTP {response.status}")
        data = response.read(1024 * 1024 + 1)
        if len(data) > 1024 * 1024:
            raise ValueError("Metadata response exceeded 1MiB")
        return json.loads(data)


def try_cover(artist: str, title: str, album: str) -> bytes | None:
    """Best-effort exact-ish artist/title match, skip ambiguous artwork."""
    query = urlencode({"term": f"{artist} {title}", "entity": "song", "limit": 10})
    try:
        data = fetch_json("https://itunes.apple.com/search?" + query)
        if not isinstance(data, dict): return None
        matches = [x for x in data.get("results", [])
                   if isinstance(x, dict) and
                   normalize(str(x.get("artistName", ""))) == normalize(artist) and
                   normalize(str(x.get("trackName", ""))) == normalize(title)]
        if album:
            matches = [x for x in matches if
                       normalize(str(x.get("collectionName", ""))) == normalize(album)]
        if len(matches) != 1: return None
        art_url = str(matches[0].get("artworkUrl100", ""))
        if not art_url.startswith("https://"): return None
        art_url = art_url.replace("100x100bb", "600x600bb")
        req = Request(art_url, headers={"User-Agent": HEADER})
        with urlopen(req, timeout=15) as response:
            data = response.read(2 * 1024 * 1024 + 1)
            if (len(data) > 2 * 1024 * 1024 or not data.startswith(b"\xff\xd8")):
                return None
            return data
    except (OSError, ValueError, TimeoutError, json.JSONDecodeError):
        return None


def try_lyrics(artist: str, title: str, album: str) -> tuple[str | None, str | None]:
    params = {"track_name": title, "artist_name": artist}
    try:
        data = fetch_json("https://lrclib.net/api/search?" + urlencode(params))
        if not isinstance(data, list): return None, None
        matches = [row for row in data if
                   isinstance(row, dict) and
                   normalize(str(row.get("trackName", ""))) == normalize(title) and
                   normalize(str(row.get("artistName", ""))) == normalize(artist)]
        if album:
            matches = [row for row in matches if
                       normalize(str(row.get("albumName", ""))) == normalize(album)]
        if not matches: return None, None
        # Prefer synchronized text. Do not merge lyrics of different versions.
        matches.sort(key=lambda x: bool(x.get("syncedLyrics")), reverse=True)
        return matches[0].get("plainLyrics"), matches[0].get("syncedLyrics")
    except (OSError, ValueError, TimeoutError, json.JSONDecodeError):
        return None, None


def tag_audio(audio: Path, artist: str, title: str, album: str, genre: str,
              cover: bytes | None, lyrics: str | None) -> None:
    try:
        from mutagen.mp4 import MP4, MP4Cover
        from mutagen.id3 import ID3, TIT2, TPE1, TALB, TCON, USLT, APIC
    except ImportError as exc:
        raise RuntimeError("pip install mutagen for embedded audio tags") from exc
    if audio.suffix.lower() == ".m4a":
        tags = MP4(str(audio))
        tags["\xa9nam"] = [title]
        tags["\xa9ART"] = [artist]
        if album: tags["\xa9alb"] = [album]
        if genre: tags["\xa9gen"] = [genre]
        if cover: tags["covr"] = [MP4Cover(cover, imageformat=MP4Cover.FORMAT_JPEG)]
        if lyrics: tags["\xa9lyr"] = [lyrics]
        tags.save()
    elif audio.suffix.lower() == ".mp3":
        tags = ID3(str(audio))
        tags.setall("TIT2", [TIT2(encoding=3, text=title)])
        tags.setall("TPE1", [TPE1(encoding=3, text=artist)])
        if album: tags.setall("TALB", [TALB(encoding=3, text=album)])
        if genre: tags.setall("TCON", [TCON(encoding=3, text=genre)])
        if lyrics: tags.setall("USLT", [USLT(encoding=3, lang="eng", desc="", text=lyrics)])
        if cover: tags.setall("APIC", [APIC(encoding=3, mime="image/jpeg", type=3, desc="", data=cover)])
        tags.save()
    else:
        raise ValueError("Metadata worker supports MP3/M4A only; use --format m4a")


def status(db: sqlite3.Connection, track_id: str, state: str,
           path: str | None = None, checksum: str | None = None,
           cover: str | None = None, lyric: str | None = None,
           error: str | None = None) -> None:
    db.execute("""UPDATE tracks SET status=?, file_path=COALESCE(?,file_path),
      sha256=COALESCE(?,sha256), cover_status=COALESCE(?,cover_status),
      lyric_status=COALESCE(?,lyric_status), last_error=?,
      updated_at=CURRENT_TIMESTAMP WHERE id=?""",
      (state, path, checksum, cover, lyric, error, track_id))
    db.commit()



def inspect_audio_video(url: str, title: str) -> None:
    """Reject obvious music videos, concerts and unrelated results BEFORE download."""
    process = subprocess.run(
        ["yt-dlp", "--dump-single-json", "--skip-download",
         "--no-playlist", "--", url],
        capture_output=True, text=True, timeout=120)
    if process.returncode:
        raise RuntimeError("Cannot inspect video metadata: " + process.stderr[-450:])
    info = json.loads(process.stdout)
    if not isinstance(info, dict) or info.get("_type") == "playlist":
        raise ValueError("Expected one official audio video")
    duration = info.get("duration")
    if not isinstance(duration, (int, float)) or not (60 <= duration <= 900):
        raise ValueError("Unexpected audio duration; review the link")
    if info.get("is_live") or str(info.get("live_status", "")).lower() in (
            "is_live", "was_live", "is_upcoming"):
        raise ValueError("Live content excluded")
    video_title = str(info.get("title", ""))
    channel = str(info.get("channel") or info.get("uploader") or "")
    description = str(info.get("description") or "")
    lower = video_title.lower()
    blocked = ("official music video", "music video", "official mv",
               "concert", "performance video", "live session",
               "dance practice", "karaoke", "fancam",
               "뮤직비디오", "라이브", "직캠", "콘서트", "ライブ映像")
    if any(term in lower for term in blocked) or re.search(
            r"(^|[\\s\\[(])mv([\\s\\])]|$)", lower):
        raise ValueError("Music video or performance instead of studio audio")
    known_song = normalize(title)
    metadata_track = normalize(str(info.get("track") or ""))
    title_match = known_song in normalize(video_title) or (
        bool(metadata_track) and known_song == metadata_track)
    if not title_match:
        raise ValueError("YouTube audio title does not match master song title")
    studio_audio_marker = (
        channel.casefold().endswith(" - topic") or
        "auto-generated by youtube" in description.casefold() or
        any(mark in lower for mark in (
            "official audio", "audio only", "오디오", "音源", "audio track")))
    if not studio_audio_marker:
        raise ValueError("Cannot confirm studio audio; manual review required")


def download_one(db: sqlite3.Connection, row: tuple, root: Path,
                 enrich: bool, ffmpeg: bool) -> str:
    track_id, artist, title, album, genre, url = row
    inspect_audio_video(url, title)
    folder = root / "Audio"
    folder.mkdir(parents=True, exist_ok=True)
    output = folder / f"{track_id}.%(ext)s"
    cmd = ["yt-dlp", "--no-playlist", "--no-progress", "--restrict-filenames",
           "--format", "bestaudio[ext=m4a]/bestaudio",
           "--extract-audio", "--audio-format", "m4a",
           "--output", str(output), "--", url]
    if not ffmpeg:
        raise RuntimeError("ffmpeg is required for consistent M4A output")
    run = subprocess.run(cmd, capture_output=True, text=True, timeout=600)
    if run.returncode:
        raise RuntimeError((run.stderr or run.stdout)[-1200:] or "yt-dlp failed")
    audio = folder / f"{track_id}.m4a"
    if not audio.exists():
        raise FileNotFoundError("Expected M4A output not found")
    if audio.stat().st_size < 1024:
        raise ValueError("Audio is unexpectedly empty")
    plain = synced = None
    cover = None
    if enrich:
        cover = try_cover(artist, title, album)
        plain, synced = try_lyrics(artist, title, album)
        if synced:
            lyric_folder = root / "Lyrics"
            lyric_folder.mkdir(parents=True, exist_ok=True)
            (lyric_folder / f"{track_id}.lrc").write_text(synced, encoding="utf-8")
    # Always write title & artist tags; artwork and lyrics are best effort.
    tag_audio(audio, artist, title, album, genre, cover, plain)
    sha = hashlib.sha256()
    with audio.open("rb") as fp:
        for chunk in iter(lambda: fp.read(1024 * 1024), b""):
            sha.update(chunk)
    status(db, track_id, "downloaded", str(audio.relative_to(root)), sha.hexdigest(),
           "complete" if cover else "unavailable",
           "synced" if synced else ("plain" if plain else "unavailable"))
    return track_id


def export_status(db: sqlite3.Connection, output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(["Track ID", "다운로드", "커버", "가사", "파일 경로",
                         "SHA256", "오류"])
        for row in db.execute("""SELECT id, status, cover_status, lyric_status,
            file_path, sha256, last_error FROM tracks ORDER BY id"""):
            writer.writerow(row)


def run_worker(args: argparse.Namespace) -> None:
    db = connection(args.root / "catalog.sqlite3")
    if args.command == "ingest":
        total, duplicates = ingest(db, args.csv)
        print(f"CSV loaded: {total}; duplicate candidates: {duplicates}")
    elif args.command == "run":
        if not shutil.which("yt-dlp") or not shutil.which("ffmpeg"):
            raise RuntimeError("Install yt-dlp and ffmpeg, then add them to PATH")
        blocked = {r[0] for r in db.execute("SELECT track_id FROM duplicate_candidates")}
        records = db.execute("""SELECT id,artist,title,album,genre,video_url
          FROM tracks WHERE verified='검증완료' AND
          (status != 'downloaded' OR status IS NULL) ORDER BY id""").fetchall()
        count = 0
        for row in records:
            if count >= args.limit: break
            if row[0] in blocked or not validate_video_url(row[5]): continue
            count += 1
            if args.dry_run:
                print(f"QUEUED {row[0]} - {row[1]} / {row[2]}")
                continue
            try:
                done = download_one(db, row, args.root, args.enrich, ffmpeg=True)
                print("OK", done, flush=True)
            except Exception as error:
                status(db, row[0], "failed", error=str(error)[:500])
                print("FAILED", row[0], type(error).__name__, flush=True)
        print("Processed", count)
    elif args.command == "status":
        export_status(db, args.root / "status.csv")
        counts = db.execute("SELECT status,COUNT(*) FROM tracks GROUP BY status").fetchall()
        print(dict(counts), "->", args.root / "status.csv")
    elif args.command == "upload":
        if not shutil.which("rclone"):
            raise RuntimeError("Install rclone and configure a Google Drive remote first")
        subprocess.run(["rclone", "copy", str(args.root / "Audio"), args.remote + "/Audio",
                        "--transfers", "2", "--checkers", "4"], check=True)
        lyrics_dir = args.root / "Lyrics"
        if lyrics_dir.exists():
            subprocess.run(["rclone", "copy", str(lyrics_dir), args.remote + "/Lyrics",
                            "--transfers", "2"], check=True)
        print("Uploaded; verify files in Google Drive before deleting local backup.")
    db.close()


def main() -> None:
    parser = argparse.ArgumentParser(description="Odify collection manager")
    parser.add_argument("--root", type=Path, default=Path("Odify_Library"))
    sub = parser.add_subparsers(dest="command", required=True)
    load = sub.add_parser("ingest")
    load.add_argument("--csv", required=True, type=Path)
    run = sub.add_parser("run")
    run.add_argument("--limit", type=int, default=20)
    run.add_argument("--enrich", action="store_true", help="try artwork and lyrics")
    run.add_argument("--dry-run", action="store_true")
    sub.add_parser("status")
    upload = sub.add_parser("upload")
    upload.add_argument("--remote", required=True, help="e.g. mydrive:Odify_Music_Test")
    args = parser.parse_args()
    run_worker(args)


if __name__ == "__main__":
    main()

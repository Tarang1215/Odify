# Odify Drive Pilot + Music Collection Worker

Status: source-only pilot (2026-10-22). Branch: feature/drive-music-pilot-20261022.

## Android Google Drive test

1. Upload two or three MP3/M4A tracks into Odify_Music_Test:
https://drive.google.com/drive/folders/1rNYGC6P2TK9RpokkI--0jRfrmbLTteNW
2. On Galaxy S25 Ultra, log into the same Google account in the Android Google Drive app.
3. Build and install the feature branch APK, open Odify Home > Google Drive 음악 테스트.
4. Choose Google Drive에서 음악 선택, select tracks from Android DocumentsUI and tap a song.
5. Verify next/previous, lock-screen playback and repeat launch.

This initial pilot is OFFLINE IMPORT: selected cloud files are locally copied before playback.
It does NOT implement authenticated Google Drive direct streaming, automatic Drive folder sync,
or full library playlists. No new Google OAuth credentials are required for this pilot.
Existing metadata-only Drive appDataFolder synchronization remains unchanged.
Max 20 tracks per file picker operation, max 100MB per track. SHA256 deduplication.

## Overnight collection pipeline on a Windows PC

Install Python 3.11+, yt-dlp, FFmpeg, mutagen and (for Google Drive uploads) rclone.

    py -m pip install -U yt-dlp mutagen
    yt-dlp --version
    ffmpeg -version
    rclone version

Open Odify Music Master Google Sheet, select MASTER and export the current tab as CSV:
https://docs.google.com/spreadsheets/d/1T5b4z_db5IXUg0lMiy2nNJzed382bvkIiPZkiLmHwaE/edit

    py tools/music_catalog.py --root E:\Odify_Library ingest --csv E:\MASTER.csv
    py tools/music_catalog.py --root E:\Odify_Library run --limit 5 --dry-run
    py tools/music_catalog.py --root E:\Odify_Library run --limit 5 --enrich
    py tools/music_catalog.py --root E:\Odify_Library status
    py tools/music_catalog.py --root E:\Odify_Library upload --remote mydrive:Odify_Music_Test

An rclone remote named mydrive must first be configured by its own interactive config.
Output: catalog.sqlite3 tracks/success/failures, Audio/ODFxxxxxx.m4a, Lyrics/ODFxxxxxx.lrc,
and status.csv (importable into Sheets after manual verification).

Tracks are eligible only if MASTER link verification is 검증완료 and their URL belongs
to youtube.com or youtu.be. yt-dlp/FFmpeg creates M4A files, mutagen writes artist/title/album
and genre tags. Optional --enrich tries verified iTunes artwork and LRCLIB plain/synced lyrics.
Audio quality cannot be improved beyond its source. Cover/lyrics are best effort, not guaranteed.

Google Sheets integration used by ChatGPT does NOT automatically grant the external Python worker
Google API access. In this pilot, CSV is an explicit snapshot; direct Sheets API writes are future work.

## Device QA checklist

- Can select music from the Google Drive provider via file_picker 13.1.0
- Can play MP3 and M4A via the existing Android player and go to next song
- Can continue playback after device screen turns off
- Duplicate file selection does not create another item
- Can re-open Odify and see the same locally imported library
- Can remove a single imported file without affecting the original Drive item

## Next development gates

1. Android APK compilation and real hardware QA (not yet completed)
2. Scoped Drive API playback without full download; authenticated Range requests
3. Embedded cover art and .lrc display, playlist updates
4. Background Google Sheets -> local SQLite queue sync with safe revision handling
5. Performance test: Drive fetch versus local cache before scaling >100 tracks

All Google account credentials must be configured on the user's device only;
never commit secrets, OAuth tokens or account passwords.
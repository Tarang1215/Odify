# v12 YouTube playlist to Odify audio

## No embedded YouTube videos

The user rejected the YouTube WebView video player because it includes video ads and is unsuitable for a music-first player. The Odify YouTube screen therefore does not embed or stream YouTube videos. It uses the YouTube Data API solely for public playlist discovery.

Tracks matched to previously verified YouTube IDs in the Odify Google Sheets MASTER, or to an unambiguous existing artist + title, play via the Media3 Odify player from existing Google Drive or local phone music. Missing tracks show as unavailable until their files are collected.

## One-time audio ingestion when music is missing

1. In Odify, open Profile > YouTube public playlists and choose a playlist.
2. Export the JSON manifest of unmatched playlist entries with the button marked '미보유 ... 곡 수집목록 내보내기'.
3. Download the existing Google Sheets MASTER as CSV from Odify's Profile.
4. On macOS/Windows, install Python, ffmpeg, yt-dlp, mutagen, and optionally rclone for Google Drive uploads.
5. Dry-run the tool:

    python -m pip install yt-dlp mutagen
    python scripts/odify_youtube_ingest.py --manifest playlist.json --output ./OdifyMusic --master-csv Odify_MASTER.csv --dry-run

6. Run ingestion and upload to the same Drive account used by Odify:

    python scripts/odify_youtube_ingest.py --manifest playlist.json --output ./OdifyMusic --master-csv Odify_MASTER.csv --drive-remote gdrive:Odify_Music_Test/Odify_Auto_Upload --run

7. In Odify, refresh Drive songs. Once audio is uploaded, it plays with the PC powered off.

Only ingest content you are permitted to save; the tool does not bypass DRM, private access, geographic restrictions or paid access. The tool is run once on the user's own machine, not as a cloud always-on music server.

## Duplicate prevention

- Track already held in MASTER with Drive URL: skip.
- Already imported YouTube video ID recorded in collector SQLite: skip.
- Same normalized artist/title/album in local library: skip, unless versions need separate review.
- Identical local audio SHA-256: skip.
- Identical Drive MD5 discovered by rclone: do not upload again.
- For destination filename collision, rclone copyto --ignore-existing is used.

## Future work

Directly extracting on Android was not implemented. A zero-maintenance cloud collector would need an always-on service or scheduled desktop machine, as YouTube Data API does not expose playable audio files. Account selection still needs real handset validation and YouTube Data API access.

Do not change the fixed package com.odify.music.nativepreview.v11.debug or the existing pinned signing identity; only increment APK versionCode and versionName.
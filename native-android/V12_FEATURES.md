# Odify v12 — Google Sheets master, album-art fallback, synced lyrics and Drive uploads

## Music master DB
- Profile -> **음악 마스터 DB 조회 및 다운로드**: Finds the existing Google Sheets "Odify 음악 마스터" document by name without hard-coding its user-private ID. Exports the **first** sheet to CSV through Drive's files.export using the original read-only Drive OAuth scope. Verifies its required MASTER headers.
- Local CSV snapshot is available offline, and **CSV 저장** saves that exact snapshot using Android's CreateDocument picker. The Google Sheet itself is not modified by this feature.
- Compares the canonical master's Drive file ID with the on-device Drive catalog and classifies **Drive 보유 / 중복 의심 / 미보유**. Title + artist can be ambiguous; the app never deletes files or silently discards covers/alternate versions.
- Important: Sheets uses an actual Google Drive document, not the local SQLite cache. The Drive catalog is a separate on-device index.

## Album artwork without embedded images
- Embedded APIC/MP4 images are first priority.
- If no embedded artwork: match the exact Drive file id to a verified MASTER row containing a YouTube video URL, and download **only the image thumbnail** from `i.ytimg.com`, never YouTube media.
- If MASTER lacks Drive ID, it matches normalized track title + artist only where the candidate master row is verified, and caches thumbnail in local app storage. Artwork may be an official video thumbnail, not the album's actual jacket.
- On failure, the artist/title must be verified before manually changing the cover. The in-app cover picker remains available.

## Lyrics
- Open Now Playing -> **Subtitles** icon. Embedded lyrics appear; LRC timestamps highlight the active line and tapping a line seeks.
- **가사 추가** -> choose local .lrc/.txt; paste plain lyrics/LRC; or explicitly query LRCLIB for title + artist. User picks a candidate before storing it. A different arrangement/cover may require a distinct LRC.
- Imported/edited LRC is stored on the **phone** by track ID; it does **not yet sync between devices**. Embedded lyrics remain associated with the Drive audio itself.

## Cloud uploads without duplicates
- Profile -> **음악을 Drive에 저장** -> select existing local audio files.
- Only when the user selects files does Android Google Identity request limited `drive.file` write scope, separate from read-only browsing. The app stores the files in its own top-level `Odify_Cloud_Uploads` folder, without touching `Odify_Music_Test`, other Drive contents, or the user's MASTER sheet.
- Before uploading: enumerate current Odify music folders, stage the chosen file temporarily (128MiB cap), calculate MD5 and byte size; reject exact-byte matches; reject an already stored stable `ODF######` identifier; finally check normalized title + artist + album and verify the app-owned destination folder's latest Drive checksums.
- Google Drive upload uses the official resumable upload API, not YouTube music download. Only explicitly selected audio files may be uploaded. Uploaded files become browsable on **other devices** automatically because all logged-in Odify instances discover `Odify_Cloud_Uploads` by its folder name.
- If the user denies additional upload permissions, normal read-only streaming and CSV browsing still work.
- A drive.file-scoped app can't silently edit the user's unrelated existing Google Sheet; a separate, explicitly authorized Sheets write workflow would be needed for MASTER status updates.

## OAuth identity: DO NOT ROTATE
- Package: `com.odify.music.nativepreview.v11.debug`. Kept fixed for all v12 builds and future versions. Only versionCode/name changes.
- Expected permanent signing SHA-1: `65:6D:46:5E:5C:72:9B:DF:21:A4:02:6C:76:4D:FB:63:6F:18:99:E5`.
- CI fails rather than silently building an APK with a new certificate if the private GitHub Actions `ODIFY_DEBUG_KEYSTORE_BASE64` secret is missing/mismatched.
- Old v11 one-off signed APK (fingerprint starting F0:F9) cannot be upgraded in-place to this newer stable-signed APK. After the **one-time** migration, future fixed-signed versions can upgrade in place without changing Android OAuth credentials.

## Test plan
- Run unit tests for metadata MP3/M4A, LRC and duplicate classification.
- CI Gradle assembleDebug; validate app package & actual APK signer SHA-1.
- Galaxy S25 Ultra manual QA: OAuth read-only login; Profile MASTER CSV browse/export; lyrics icon/import/LRCLIB search; music file upload permission, successful upload, duplicate skip, cross-device discovery; album thumbnail on CORTIS REDRED.
- CI success cannot prove real account authorization or third-party thumbnail/lyrics endpoints are reachable from a particular mobile carrier.

# Odify v12 — verified YouTube thumbnail fallback, MASTER DB browser, LRC lyrics

## Android OAuth identity stays permanently unchanged
- Package: `com.odify.music.nativepreview.v11.debug`
- Expected stable signing SHA-1: `65:6D:46:5E:5C:72:9B:DF:21:A4:02:6C:76:4D:FB:63:6F:18:99:E5`
- Only `versionCode=12` and `versionName=1.0.0-preview12` changed.
- Existing Android v11 with the earlier random CI signing identity will require one-time uninstall before installing a new stable-signed APK. From then on: in-place upgrades.
- Private signing keystore must be installed as GitHub secret `ODIFY_DEBUG_KEYSTORE_BASE64`. CI refuses random-key APKs; source checks can still run with no key.

## Automatic artwork fallback
1. Keep any valid MP3/M4A embedded APIC/covr cover, plus user overrides.
2. Open the existing verified **Odify music MASTER** Google Sheet, CSV-exported read-only through Drive API. Filter for `링크검증=검증완료`.
3. For a Drive song without cover, choose the YouTube video ID from the exact **Drive file ID** match first; otherwise require both exact normalized artist and song title and an unassigned master Drive file URL. Never search YouTube by an ambiguous fuzzy song title or use a random fan thumbnail.
4. Fetch only `https://i.ytimg.com/vi/<verified-id>/hqdefault.jpg`, decode the bounded image as a small cached JPG. This is thumbnail imagery, not downloaded music or video. A music video may have a different thumbnail from the official album jacket.
5. Retry failures at most once per day. The app doesn't request new Google Cloud paid infrastructure or a YouTube Data API key.

## MASTER database
- **Profile → 음악 마스터 DB 조회 및 다운로드**: list/search MASTER rows (Track ID, artist, title, album, status) and compare with the current Drive catalog.
- Modes: **전체 / Drive 보유 / 중복 의심 / 미보유**.
- **CSV 저장** exports the current cached MASTER table locally to a user-selected destination using Android DocumentsUI. It does NOT edit the source Google Sheet.
- Same Drive file ID is a verified existing copy; another song with same artist/title is a possible duplicate, not a hard guarantee. An identical title by another artist is NOT treated as the same song; this protects covers.
- The previous real Google Sheet is still the master source. Do not create a second competing catalog file in Drive.
- **Not yet implemented:** downloading audio from MASTER's YouTube URLs, writing audio back to Drive, or updating MASTER status cells. These require a separate explicit Google Drive write-scope authorization and a dedupe/upload policy; they must not be silently activated with the read-only account.

## Lyrics inside player
- Press the new subtitles icon in the full player to swap the artwork area with a lyrics pane; audio and controls remain visible.
- Embedded USLT/`©lyr` lyrics are rendered when available. Valid `[mm:ss.xx]` lines are aligned to play position and can be tapped to seek; plain lyrics are scrollable.
- **가사 추가**: open a local `.lrc` or `.txt` document, paste LRC/plain text, or explicitly search **LRCLIB** by artist + title. LRCLIB has no API key, but requires an identified User-Agent and throttling; no bulk background searching is used.
- Online candidates expose their title, artist, approximate length, and whether they contain synced lyrics. The user chooses which result to apply, avoiding mismatched versions.
- For covers, lyrics wording, structure and offset may differ even if the song title is identical. Never auto-apply the original recording's LRC to a cover without confirmation.
- Manually chosen lyrics are stored per song ID in the app's private folder `files/lyrics/<sha256(id)>.lrc`, overriding embedded lyrics. The Drive audio is unchanged.
- **Not yet implemented:** cross-device synchronization for manual lyrics overrides; use selected external LRC documents or a future app-managed Drive `drive.file` folder.

## Status and validation
- A source-only GitHub Actions workflow runs JVM tests and compiles Kotlin to catch regressions without weakening the fixed-signature security rules.
- The packaged APK can be built by the existing signed GitHub workflow only when the stable signing Secret has been provided.

# Odify v8 — Google Drive music server setup

## Features
- Default root: the first accessible folder named `Odify_Music_Test` (not a hardcoded private folder ID).
- Scan the folder recursively, including `Odify_Auto_Upload`.
- On launch after account authorization: refresh the SQLite catalog automatically.
- While open: refresh every 15 minutes, or use **Profile → Drive 음악 새로고침**.
- Tracks are indexed as Google Drive file IDs, searchable by filename/title, artist, album or genre (genre initially inherited from folder path).
- Streaming: Media3 requests `https://www.googleapis.com/drive/v3/files/{id}?alt=media` with an OAuth bearer token, and uses a 192MiB LRU disk cache. The Google API supports byte ranges. No default full-file copy to Odify's offline folder.
- For MP3 music with an embedded ID3v2 APIC image in the first 1MiB, the app downloads only the audio prefix (HTTP Range) and stores a small cover JPEG locally. Other formats may display a fallback thumbnail until additional metadata extractors are implemented.

## Google Cloud OAuth — one-time external setup required

The ChatGPT-connected Google Drive account cannot grant permissions to a separately installed Android app. Do this once for your own personal Google Cloud project:

1. Google Cloud Console → create/select project → enable **Google Drive API**.
2. Configure **Google Auth Platform / OAuth consent**. Add your personal Google account as a test user if in Testing mode. Scope: `https://www.googleapis.com/auth/drive.readonly` (restricted scope; public production release may require additional verification).
3. Create an **Android OAuth client** with package name `com.odify.music.nativepreview.v8.debug` and the SHA-1 fingerprint of the signing certificate shown in the successful GitHub Actions run log under **Show signing certificate SHA-1 for OAuth setup**.
4. Install the APK and open **Profile → Google Drive 계정 연결**. Choose the Google account containing the Odify music folder and grant read-only access. No password or OAuth token should be entered into this repo or sent to ChatGPT.
5. Search now includes all indexed music files and genre chips. Existing MP3s imported through Android DocumentsUI also continue to work.

### Stable APK signing key across future builds

GitHub-hosted runners generate a new debug keystore on each fresh build unless supplied with a stable one. That changes SHA-1 and would break Google OAuth for updated APKs. To pin the same debug signing identity across future CI runs:

- Generate a private debug keystore **on your own computer**, alias `androiddebugkey`, store and key passwords `android` for the standard Android debug signing configuration.
- Add a GitHub Actions secret called `ODIFY_DEBUG_KEYSTORE_BASE64` containing its Base64 encoding. The workflow writes it to `$HOME/.android/debug.keystore` before Gradle and prints the **SHA-1 only** for OAuth registration.
- Keep the raw keystore private and outside the repository; never commit it or put its Base64 in chat.
- Register the SHA-1 of that stable certificate in Google Cloud Android OAuth.

Without this client setup, the compiled APK can install, use offline/old DocumentsUI playback and show the cached catalog, but **new Drive API authorization will not necessarily succeed**.

## Known limits
- Initial sync requires Google OAuth. It may fail with an explicit message if the wrong Google account is chosen or no folder named Odify_Music_Test exists.
- On-app-start and while-open refresh is implemented. Updates arriving while the app is completely closed are picked up on the next launch, not via a push notification.
- Large public distribution with restricted Drive scopes may require Google verification/security review; this preview is for the user's personal Google account.
- The embedded-art parser currently supports MP3 ID3v2 APIC in early bytes; FLAC/M4A and non-embedded cover art need separate artwork support.
- Google Drive is not a streaming-specialized CDN; error rates and quota/rate limits should be tested on a real device.
- This is a debug preview. Media playback, seeking, consent and account selection require S25 Ultra hardware QA.

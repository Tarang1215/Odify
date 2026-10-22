# Odify 15GB free-tier field test

This is a **personal Google Drive 15GB pilot**. No paid Google Cloud VM, billing account or 5TB subscription is required by the app code. Google API/OAuth consent and quota rules remain external dependencies.

## Prerequisite (required for new Drive library API)
- Enable Google Drive API on your own Google Cloud project.
- Register a Google Android OAuth client **for the actual installed package and SHA-1**. Version v9 debug package: `com.odify.music.nativepreview.v9.debug`.
- Open **Profile → OAuth 연결 진단** on the installed APK and tap **Google Cloud 등록용 SHA-1 복사**; register that fingerprint. CI runners can generate a different debug key across builds: for upgrades use a stable private key via GitHub Actions secret, never commit it.
- Add your own Google account as an OAuth test user where required, grant read-only Drive scope. Never share passwords, tokens, key stores or recovery codes.

## Functional acceptance, use your free 15GB storage
1. Create or use `Odify_Music_Test`, containing 3–10 small, personally authorized MP3 or M4A files, and a genre subfolder if desired.
2. Open **Profile → Google Drive 계정 연결**, authorize with the SAME Google account, wait for **음악 N곡 동기화 완료**.
3. Use Search to look up a song and optionally choose a genre chip; confirm that a file added to Drive appears after **Drive 음악 새로고침** without using the Android file picker.
4. On Wi-Fi, play a Drive song and seek to the middle; verify playback and switching to the next song. Repeat with mobile network if comfortable with data charges. Check that Profile shows the stream cache increasing but **오프라인 음원** does not grow by the entire track size.
5. Verify whether an MP3 with an ID3 embedded APIC cover displays art. For M4A/FLAC and tracks with no embedded cover, artwork is not yet guaranteed; fallback artwork is expected.
6. Switch off internet and try an *uncached* Drive song: it should not claim guaranteed offline playback. A fully imported offline song should still play.
7. Check the Profile network status, cache size (192MiB LRU), offline song size, last Drive sync and OAuth SHA-1.

## Boundaries / safety
- The app reports only its own audio, art and Media3 cache bytes, **not** data stored in the external Google Drive app's cache.
- Current Media3 audio HTTP Range may encounter Drive API quota, 401/403, redirects or host-specific failures. An in-app player error toast now displays an error code rather than silently doing nothing.
- True PCM waveform extraction is retained for **local** files. For remote songs the honest seek progress line is shown to prevent a second full download and reduce mobile data usage.
- Index sync occurs at launch after reauthorization, every 15 minutes while open, or on explicit refresh. It does not run as an always-on background service when the app is closed.
- Artist and genre are derived from filenames and folder paths unless manually corrected. Search-by-lyrics and auto-cover-song collection are not yet implemented.
- The app does not purchase cloud storage or provision Google Cloud billing resources.

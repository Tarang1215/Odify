# Odify Google Drive OAuth — Android pilot (no APK deploy yet)

Status: **source integrated; real Google account authentication remains unverified until a dedicated OAuth project and a stable APK signing certificate are configured.** No Trove/Supabase project is used.

## Google Cloud configuration

1. Create a new independent **Odify** Google Cloud project. Do not reuse Trove.
2. Enable **Google Drive API**. Configure OAuth consent, add the personal Google account as a test user while the app is in testing.
3. Register an **Android OAuth client** with package `com.odify.music` and the **SHA-1 fingerprint of the APK signing certificate actually used to install the app**. Check debug vs release certificate separately.
4. Register a **Web application OAuth client** in the same Google Cloud project. Copy its **client ID** (public identifier, *not* the client secret). Android's Google Sign-In 7.2 adapter needs this Web client ID as its `serverClientId` if no `google-services.json` is used.
5. Configure a **stable debug/release signing identity** for all future APK updates; GitHub-hosted debug signing may change between runs. Store keystore passwords and keystore itself only in GitHub encrypted secrets or secure local machines. Do not commit certificate private keys.
6. Build explicitly with:
   ```bash
   flutter build apk --dart-define=ODIFY_GOOGLE_SERVER_CLIENT_ID=YOUR_PUBLIC_WEB_CLIENT_ID
   ```
   This step is illustrative; Odify's pinned Flutter/Rust/Android assembly and native build prerequisites still apply.
7. When the user opens **Odify → 동기화 및 백업 → Google 계정 연결**, request only `https://www.googleapis.com/auth/drive.appdata`. Google authentication is distinct from Drive scope authorization.
8. Verify account selection, 401/re-consent, sign-out, and reauthentication on the *installed* Galaxy. Then connect a second installation and confirm playlist operations converge.

### Permissions & privacy

- Drive data is confined to the hidden **appDataFolder**. That space is private to the Google Cloud OAuth application and does not show in the user's normal Drive listing.
- `drive.appdata` is the only requested Drive scope. Do not request full `drive`.
- OAuth access tokens are supplied just-in-time by `google_sign_in`. No client secrets, Google passwords or bearer tokens are placed in sync JSON, logs or GitHub.
- Client-only syncing means no always-on Odify server; the phone performs Drive requests only after explicit authorization and manual foreground sync in this stage.
- Drive appDataFolder metadata **is not end-to-end encrypted**. Sensitive listening history is not uploaded in this version.
- Each Google account has a separate local SQLite file keyed by a **deterministic SHA-256 hash of Google's stable account ID**. A hash hides the account ID from the filename but does not itself encrypt data. App sandbox security is still required.
- Existing SpotiFLAC local playlists/likes do **not** yet flow into the new event log. The first visible beta covers creating, renaming, deleting and reading Odify sync playlist **names**, not tracks. Do not advertise full synchronization until those event emitters and consumers are wired.

### Windows-first next stage

Flutter's official `google_sign_in` plugin does not offer Windows native integration. Add a separate Windows OAuth installed-app adapter using a secure system-browser flow + loopback redirect/PKCE, or a supported browser-based client. Reuse `OdifySyncCoordinator`, `OdifySyncRepository`, `OdifyDriveSyncTransport`, and the pure Dart event reducer. Do **not** ship the Android SDK on Windows. OAuth tokens need Windows platform-secure storage; there is no embedded secret in the Windows app.

## Sources

- https://pub.dev/packages/google_sign_in
- https://pub.dev/packages/google_sign_in_android
- https://developers.google.com/workspace/drive/api/guides/appdata
- https://developers.google.com/identity/protocols/oauth2/scopes

## Current QA gates

- Offline assembly test including pubspec dependency injection
- Flutter static analyzer (including new Google SDK API)
- Widget test for no-OAuth-config disabled login
- Per-Google-account SQLite partition test
- Existing mock-Drive bootstrap/upload/retry tests
- **No Gradle / native Android build or APK distribution during this increment**, at user's request.

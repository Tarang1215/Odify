# Odify Drive Sync — Implementation Architecture (v1, 2026-10-20)

Status: **Architecture approved for implementation; NOT implemented yet.** No live OAuth client, Drive API access, or multi-device test has been configured. This document does not claim an APK feature.

## 1. Design goals and hard constraints

- Cross-device personal sync: Android (Galaxy S25 Ultra) first; Windows/macOS/iPad later.
- Google Drive is storage/transport only. **No Supabase project, no Trove infrastructure, no personal always-on PC, no app-hosted database or proxy.**
- Keep local-first music playback/search/library responsive and usable offline. Never make local player actions depend on a Drive round-trip.
- Sync tiny **metadata only**: playlists and ordered entries, likes, optionally recent listening/recommendation preferences and resume markers. Do NOT sync downloaded MP3/FLAC bytes, DRM data, session cookies, signed/expiring stream URLs, local absolute file paths, OAuth tokens, or Samsung Music private app data.
- A playlist entry may appear on every device yet be unplayable on some platforms; each device resolves its own legally available source.
- Encryption, robust merge, revocation, export/restore, and account isolation must be considered even in a personal app.

## 2. Identity and auth

- Create **one dedicated Google Cloud project for Odify**, unrelated to Trove. Enable Google Drive API, configure Google OAuth consent and test users.
- Configure platform-specific OAuth clients within that same Google Cloud project: Android package `com.odify.music` and correct SHA-1/SHA-256 signing fingerprints; iOS bundle ID; desktop OAuth client for Windows/macOS; web client only if browser PWA is chosen.
- Important build blocker: CI debug signing credentials may vary across runners. Use a **consistent development signing keystore supplied via GitHub Secrets**, never committed, before depending on Android OAuth fingerprints; eventually configure production signing separately.
- Flutter `google_sign_in`: obtain account identity, request Drive authorization only after user selects **Enable Sync** using `authorizationClient.authorizeScopes` as necessary. Request **only** `https://www.googleapis.com/auth/drive.appdata` for Drive operations. Treat access tokens as short-lived; refresh/re-authorize via platform SDK.
- Persist credentials only using OS-protected mechanisms (Keychain/Android Keystore/OS credential store as provided by auth SDK). No client secret, service-account key or OAuth refresh token in source, repo, Drive JSON, or logs.
- Google account change means another isolated local sync namespace. Ask whether to switch data sets / import as a separate action; never silently merge two accounts.
- User can use Odify **without** signing in; syncing is opt-in.

Authoritative references:
- https://developers.google.com/workspace/drive/api/guides/appdata
- https://developers.google.com/identity/protocols/oauth2/scopes
- https://pub.dev/packages/google_sign_in
- https://developers.google.com/identity/sign-in/ios/api-access

## 3. Drive API and storage model

Use Google Drive API v3 `appDataFolder` (app-private hidden data, not normal visible Drive folders).

- Create: `files.create` with metadata `{"name":"odify-v1-<filename>.json.gz", "parents":["appDataFolder"],"mimeType":"application/gzip"}` and multipart or resumable content upload.
- Initial enumeration: `files.list(spaces="appDataFolder", fields="nextPageToken,files(id,name,mimeType,size,modifiedTime,md5Checksum)",pageSize=1000)`; page until no token.
- Incremental detection: `changes.getStartPageToken` **before** initial scan, then replay `changes.list` from that token after initial scan to avoid missing concurrent writes; persist `newStartPageToken` after successful apply. For subsequent sync, consume `changes.list` pages.
- Download by `files.get(fileId, alt=media)`, verify schema, decompressed size (bounded), event id, checksum where applicable. Do not trust remote metadata as executable instructions.
- `appDataFolder` data is invisible in normal Drive UI; offer a separate **Export Backup JSON** user action (system share sheet or user-selected file) for human-readable backup.

**Never** update one common `playlists.json` from multiple devices. Concurrent clients overwriting a shared file can lose changes.

Recommended immutable flat naming scheme:

```odify-v1-batch-<deviceId>-<ULID>.json.gz```

Each batch contains 1–100 **immutable** operation events from a single local device. Unique filenames/device IDs avoid concurrent write collisions. On timeout, retry uses the **same batch/event IDs**; reconcile by ID to prevent duplicate application. Files are scoped to the same signed-in user's appDataFolder. Use one app-local random `deviceId` per installation; never use IMEI or Android identifiers.

No shared writable manifest is needed in v1. Optionally add immutable snapshots for compaction later, after verifying full-state restore and device acknowledgments.

## 4. Local schemas

Add an Odify sync domain independent of SpotiFLAC/Mornye's local library schema:

- `sync_events(event_id TEXT PRIMARY KEY, device_id, hlc, kind, payload_json, applied_at, uploaded_at, batch_id)`
- `sync_outbox(event_id UNIQUE, batch_id, retry_count, next_attempt_at)`
- `sync_remote_files(file_id PRIMARY KEY, md5_checksum, applied_at)`
- `sync_cursor(account_sub PRIMARY KEY, changes_page_token, last_success_at)`
- `sync_devices(device_id PRIMARY KEY, label, last_seen_at)`
- `sync_playlists(playlist_id PRIMARY KEY, name, created_at, deleted_hlc)`
- `sync_playlist_entries(entry_id PRIMARY KEY, playlist_id, source_ref_json, position_key, deleted_hlc)`
- `sync_likes(source_key PRIMARY KEY, liked BOOLEAN, last_hlc)`
- `sync_playback_summary(source_key, day, play_count, played_ms)` (optional, aggregate only)

All client storage is account-partitioned. Migration must preserve existing `odify_liked_tracks_v1` shared-preferences likes and the existing local listening history. The current normalized `artist|title` keys can be retained as a fallback, but **new** sync likes should use stable source references.

Typed source references:

```json
{"kind":"youtube","videoId":"AaBbCcDdE01","title":"Example","artist":"Example Artist","durationMs":213000}
{"kind":"local","fingerprint":"sha256:<optional-file-content-hash>","title":"Example","artist":"Example Artist","durationMs":213000,"availableOnThisDevice":false}
```

- YouTube IDs are **case-sensitive**. Never sync expiring playback URLs.
- `local` metadata is a placeholder; do not sync `content://` URIs or private `/storage/...` paths. Device-specific matching of local files uses a fingerprint if available or conservative metadata match with explicit user confirmation.
- Playlist/entry IDs are UUIDs and remain stable even if an item moves or metadata changes.
- Media licensing and platform availability are enforced locally. A matching playlist does not imply the source can be played on iPad.

Operation event example:

```json
{
  "schemaVersion":1,
  "eventId":"018f0000-0000-7000-8000-000000000001",
  "deviceId":"c732aa5f-3b9f-46ef-bc90-9f0000000001",
  "hlc":"2026-10-20T07:30:00.000Z#0005",
  "op":"playlist.entry.add",
  "playlistId":"3e24b860-dbf4-4e37-9810-0f0000000001",
  "entryId":"5fe72283-0f00-4000-8000-0a0000000002",
  "positionKey":"m",
  "sourceRef":{"kind":"youtube","videoId":"AaBbCcDdE01"}
}
```

Event types for MVP: `playlist.create`, `playlist.rename`, `playlist.delete`, `playlist.entry.add`, `playlist.entry.remove`, `playlist.entry.move`, `like.set`.
Later: `playback.summary` and `resume.set`.

## 5. Deterministic concurrent merge (no central server)

1. Every local edit **first** commits to local DB and outbox atomically. UI updates immediately.
2. Each event carries a hybrid logical clock (HLC), unique event ID, and device ID. Received events advance the local HLC.
3. On sync, retrieve and validate unseen immutable batches, dedupe by eventId, apply all valid operations in a DB transaction, persist cursor only after successful commit.
4. LWW register for playlist names, per-entry move position and likes; compare HLC, then device ID/event ID as deterministic tie-breaker. Wall-clock timestamps are informational, not the sole consistency mechanism.
5. Playlist membership is represented by stable `entryId` plus **tombstones** for remove/delete, not a whole-file array overwrite. Adding different entries on two devices preserves both. Deleting a playlist dominates updates to that playlist; undelete requires a new explicit action/new playlist ID.
6. Position uses fractional sortable keys; if two adds receive the same key, order deterministically by entryId; entry moves are merged using latest operation. Periodic position rebalance requires separate tested maintenance semantics.
7. History is idempotent by eventId. Offline changes remain queued until acknowledged; 401, 403, 429, 5xx and network errors must never discard them.
8. Retries use exponential backoff + jitter with UI status. Multiple simultaneous sync calls collapse into one per account.

Explicit conflict examples to test:
- Galaxy adds A, iPad adds B offline -> both show A & B after sync.
- Galaxy renames a playlist, PC reorders entries -> both preserved.
- Two devices rename same playlist -> deterministic most recent HLC, never random per-client.
- One removes entry while other reorders it -> tombstone wins.
- Offline reinstall/old app with preexisting device ID -> bootstrap full history, never destroy remote state.
- Multiple accounts -> no data leakage between local account partitions.

## 6. Sync lifecycle, power and quota

- Trigger initial sync after explicit Google authorization; then foreground/resume, local edits (debounce ~5–15 seconds), and manual Refresh.
- While app is active, a low-frequency periodic safety check (e.g. 10–30 minutes). Don't promise guaranteed iOS background sync; OS controls it.
- Playback and local search never wait for sync; offline mode fully supported.
- Load only new changes via `changes.list` where possible. Batch up to 100 events to reduce request count; avoid network calls after each seek/position tick.
- On initial bootstrap: save start page token -> list/download/apply all batches -> catch up with changes feed -> upload local pending batches -> final catch-up.
- Bound upload bytes, downloaded archive size, retry burst, concurrent Drive requests. Acknowledge an outbox batch only after Drive create success **or** remote idempotency check.
- V1 retains operation log indefinitely for a small private library. V2 checkpoint/compaction can reclaim old files **only** with proven restore, active-device acknowledgments, and a backup path for very old devices.
- `appDataFolder` consumes user's Drive storage allocation. API quotas and billing terms can change; monitor actual usage instead of promising unlimited free service.

## 7. UI and privacy controls

Odify Settings -> **Sync & Backup**:

- `Connect Google account` / `Disconnect` / `Sync now`
- Status: `Up to date`, `3 pending edits`, `Offline`, `Sign-in required`, `Last sync 14:32`
- Toggle `Sync playlists and likes` (default on after consent), `Include listening history` (default off)
- `Export JSON backup` and `Import backup` with preview; `Delete Odify cloud sync data` with explicit confirmation
- Error telemetry remains on device and excludes tokens and media URLs.

Drive `appDataFolder` is app-private but **not end-to-end encrypted by default**. The user must explicitly enable history sync; a later encrypted archive mode may be supported, requiring safe key recovery design.

## 8. Repo module layout (proposed, not yet created)

```
lib/services/odify_sync/
  sync_models.dart          # typed source refs, event schema, serialization
  sync_clock.dart           # HLC and total-order comparison
  sync_merge.dart           # pure deterministic reducer
  sync_repository.dart      # SQLite adapters/migrations/outbox
  drive_sync_api.dart       # Drive API v3, appDataFolder/changes
  google_auth_adapter.dart  # OAuth/secure token adapter
  sync_coordinator.dart     # offline-first lifecycle/retries
lib/widgets/odify_sync_settings.dart
test/odify_sync_merge_test.dart
test/odify_sync_mock_drive_test.dart
```

Keep most sync logic pure Dart, reusable by Flutter Android, iOS, Windows, macOS, and future web. Keep player/extractor platform-specific.

## 9. Acceptance gates (ship in this order)

**Stage A — no Google account required**
- Models/migrations + deterministic merge unit tests + mock Drive integration tests.
- Existing Odify likes and playlists preserved in migration.
- Mixed offline edits converge; retries do not duplicate likes/items.

**Stage B — Android personal Drive opt-in**
- Dedicated Google Cloud project and OAuth Android client with **stable APK signing fingerprint**.
- Sign-in and Drive `appDataFolder` read/write with least-privilege scope.
- Two independent Android installations or emulator/device can exchange edits.
- Flight mode edits replay correctly without loss.

**Stage C — actual cross-platform clients**
- iOS/macOS/Windows OAuth clients in same Google Cloud project.
- Same playlist contents, ordering and likes on Galaxy, iPad, and desktop.
- Platform-specific source resolver; unavailable files display honestly.

**Stage D — durability/security**
- Drive history cursor recovery, backups, account switch, sign-out, forbidden access, clock skew, stale devices, 429 backoff, quota alerts, log compaction.

## 10. Decisions deliberately deferred

- Syncing MP3/FLAC audio files (separate opt-in and storage/bandwidth/privacy planning).
- Realtime/push notifications or backend/cloud functions (not needed).
- Server-side personalization or recommendations (do them locally).
- Whether to distribute broadly, and licensing/legal implications of the platform-specific music source layer.

**Immediate next code task:** Stage A with mocked Drive and merge tests; Google Cloud OAuth setup is Stage B and requires the user's authorization/configuration. Do not access or modify Trove/Supabase.


## 11. Platform priority and iPad distribution ADR (2026-10-20)

**User-confirmed development order:**
1. **Galaxy Android:** repair visibility of synced Samsung/shared audio in unified Library; verify native playback; then build local sync-event/outbox merger.
2. **Windows 11 PC:** shared playlist/likes UI, locally managed desktop audio paths, desktop Google OAuth, Windows media playback. Platform-specific extractor/bridge must be developed independently of Android Kotlin. No background server on the PC.
3. **iPad (2020 iPad Pro):** **PWA first** to avoid free-Apple-ID 7-day IPA resigning. Desktop/local catalog sync is metadata only; web can show unified playlists via Drive appDataFolder OAuth web flow. Evaluate iPadOS standalone web-app audio/background/Media Session and source availability on device; do not claim full native feature parity before testing. iPad native IPA remains a later optional variant.
4. **Mac:** Flutter macOS native or hosted web client after Windows and iPad are functional; share domain sync and resolver design.

**One-install iPad decision:** on ordinary stock iPadOS, an arbitrary unsigned IPA cannot be installed forever like an APK. Free Apple ID provisioning expires after 7 days. SideStore/AltStore can ease refresh but not eliminate certificate expiry. TestFlight builds expire after 90 days and require Apple developer distribution. TrollStore offers persistence only on specific exploitable OS versions; NEVER assume compatibility or advise OS downgrades without confirming device firmware. Paid Apple Developer Program and approved App Store distribution avoid weekly expiry but have policy, cost and legal implications; a yt-dlp-based streaming app may face app review/terms compliance issues. To keep GoodNotes and other installed apps untouched, **do not base Odify on LiveContainer**.

**PWA caveat:** no IPA-signing maintenance, but browser source integrations, iPadOS background playback, native local-file support, service worker caching, and Apple platform policies require separate device tests. Use a compliant source resolver instead of embedding Android yt-dlp into web. No 24/7 self-hosted backend; static hosting is acceptable, but appDataFolder OAuth and encrypted data should be reviewed for secure browser token handling.

**Shared app interfaces:** `OdifySyncStore` (local events/outbox), `OdifyRemoteStore` (Drive appDataFolder; replaceable mock), `OdifyPlaybackResolver` (Android, Windows, web, macOS platform implementation), `OdifyLocalFileMatcher` (device-specific local files). Every `playlist_entry` contains a portable source ref, never a local OS path.

## 12. Galaxy MediaStore library visibility bug & mitigation

The existing Odify Home browser retrieves MediaStore audio independently, so songs can be found even if SpotiFLAC's `AppSettings.localLibraryEnabled` remains at its **default false**. SpotiFLAC's `libraryBrowseProvider` honors that setting and excludes all local rows. Additionally, upstream `library_sources` uses virtual `legacy://local-library` for legacy MediaStore rows; source-availability probing with `Directory(path).exists()` may falsely mark this virtual source unavailable, hiding rows in the `library_visible` SQL view.

Fix in Android overlay:
- On explicit Odify library sync, turn on local-library visibility via `settingsProvider.notifier.setLocalLibraryEnabled(true)`.
- Explicitly reload the `localLibraryProvider` summary and invalidate `libraryBrowseProvider` to show added albums/artists without restarting.
- Treat virtual `legacy://local-library` as available, and skip scanning that virtual source as an actual directory. Keep normal availability probing for real folders.
- Avoid complete MediaStore re-index on every Home visit: throttle automatic background sync to ~6 hours, retain explicit user `Sync now`.
- On first migration from an older Odify install, reuse already-indexed entries rather than copying music files.
- QA: open Home MediaStore browser, press **Odify 라이브러리에 동기화**, then open Library and verify albums/tracks appear. Confirm permission denial and source removal behave without destroying unrelated folders.

Build check and Galaxy device verification are separate tasks; this ADR records the code-level mitigation.

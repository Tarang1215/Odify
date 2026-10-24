# Audio change requests (preview12.17 / 29)

Now Playing → ⋮ → 오디오 변경 요청 → Report / Paste link.

Reports are saved to a separate SQLite outbox before any network request. The
current music, original MASTER links, playlists and local offline exports remain
intact. Paste link accepts one HTTPS YouTube video and requires an exact Drive
song ID. Local-only reports are retained without guessing a cloud target.

With the existing `drive.file` consent, the phone creates one UUID-named JSON
document per request in `Odify_Audio_Requests`. PC processing updates that same
document with the result. Failed/cancelled consent retains the outbox, and an
open connected app retries quietly every minute. This is not an Android download
service: closing the app pauses its outbox sync. Once uploaded, requests remain
in Drive until a PC processor handles them. The status is visible on reopening
the request dialog. Drive catalog sync refreshes audio checksums; a new checksum
uses a new Media3 cache key. Already exported offline copies remain independent.

PC worker: `tools/v46/audio_requests.py`, root `D:\Odify\AudioRequests`.

* Default run: ingest reports into `audio_requests.sqlite3`, no audio changes.
* `--process-links`: process user-selected replacement video URLs.
* `--resolve-reports`: explicitly retry reported recordings using the existing
  duration/title/non-live matcher. Reports alone never trigger extraction.
* `--watch-seconds 43200`: one bounded foreground/hidden PC process, no scheduler.

Before upload the worker verifies the requested Drive MD5, backs up and verifies
the original locally, pins its Drive revision, checks for concurrent edits, then
PATCHes content on the exact file ID. A successful completion requires the same
ID, exact size and new MD5. Source overrides are additive records keyed by Drive
ID; no MASTER source URL is edited. Interrupted completion can reconcile the new
MD5 without downloading or replacing twice. Failures preserve backups. The new
local version is kept beside the request; existing external music is not erased.

YouTube 429/authentication restrictions persist as `SOURCE_PAUSED`, and the
existing history queue's source restrictions also pause this worker. No cookies,
client-switching, DRM or session-check bypass is implemented. Source extraction
must be resumed only after the source restriction has been resolved. No live
replacement of user audio is run as a test.

## Mobile download feasibility checked 2026-10-25

The older Flutter overlay includes `OdifyYtDlpPlugin.kt`, using youtubedl-android
and FFmpeg. That adapter is not included in the current native preview. Adding a
mobile executor is technically possible but needs runtime packaging, persistent
Android work, progress/cancel/recovery, media tagging, and the same replacement
receipt protocol. Embedding it does not guarantee access through a source gate.

Mornye's current `.sflx` download providers are JS packages executed by a Rust /
QuickJS host. Tidal and Qobuz manifests require `signedSession@3` and
`sessionGrant@1` against the separate Zarz gateway, plus segment or prepared
context capabilities. Copying registry JSON into the native app is insufficient.
The registry's current live LICENSE is LGPL-3.0-only with incorporated GPL terms;
older cached README content saying Apache is inconsistent with that current
license. No provider code, keys, authentication identity or proprietary gateway
access has been copied into Odify.

Sources: [Android wrapper](https://github.com/yausername/youtubedl-android),
[Tidal manifest](https://github.com/mornyeapp/Mornye-Extension/blob/main/sources/tidal-web/manifest.json),
[Qobuz manifest](https://github.com/mornyeapp/Mornye-Extension/blob/main/sources/qobuz-web/manifest.json),
[extension runtime](https://github.com/mornyeapp/Mornye/blob/main/rust_backend/crates/extensions/Cargo.toml),
[current license](https://github.com/mornyeapp/Mornye-Extension/blob/main/LICENSE),
[Drive update API](https://developers.google.com/workspace/drive/api/reference/rest/v3/files/update).

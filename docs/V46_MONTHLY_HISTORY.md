# Monthly chart history and album metadata — 2026-10-25

The private catalog was extended additively, retaining all baseline recordings,
album membership and chart-entry tuples. The seed database and MASTER CSV links
were not edited. Audio collection is scoped to monthly chart songs first; full
album audio is deferred, as selected by the user.

## Verified export

`D:\Odify\Metadata\History_2024_2026_20261025` contains a consistent SQLite
snapshot, coverage reports, source-edition album tracks, and per-month chart
CSV/JSON files under `YYYY/MM/country`. Its SQLite SHA-256 is
`71a6737dca7d0cb8c2da0f7aead463978462695d31fd833dd5203a71d32c0678`.

There are 102 country-month lists: KR/JP/US × (12 months in 2024 + 12 in 2025 +
10 in 2026), exactly 100 ranks each, totalling 10,200 chart entries and 2,759
normalized chart recordings. October 2026 is explicitly month-to-date.

The resulting full catalog contains 55,070 recordings, 2,557 albums, 20,804 album
memberships and 339,091 chart entries. Of the monthly-chart recordings, 2,615
have a complete published album tracklist, 32 are partial, and 112 are unresolved.
Complete published metadata does not assert that every audio file is available.
Provider-specific editions and physical/CD-only tracks remain distinguishable.

Sources and period semantics:

* KR: official Circle monthly digital chart through September 2026. October uses
  available published weekly charts ending through October 17, marked provisional.
* JP: Billboard Japan Hot 100 weekly publications, aggregated by publication date.
* US: the pre-existing mhollingshead/billboard-hot-100 community archive, weekly
  issue dates. This is not presented as an independently licensed monthly chart.
* Weekly-derived month scores sum `101 - rank`; ties use appearances, best rank,
  title and stable recording ID. These aggregate charts are labelled as derived.
* Album metadata: public Apple catalog, Bugs track/album pages, and Billboard
  Japan-linked published physical tracklists. Missing editions are not invented.

Export checks: `integrity_check=ok`, no foreign-key errors, 102 complete rank
sequences, and every seed recording, album-track and chart-entry tuple retained.
The source fetch issue ledger is empty; album coverage gaps remain explicit.

## Audio and background status

Audio lives under `D:\Odify\History\YYYY\MM\country\Audio`. A recording is
stored at its first chart appearance; every month's `download_status.csv` and
`available.m3u8` references it. This avoids copying one recording into many months.
`chart_status.json` and `drive_upload_status.json` hold local SHA-256 and verified
Drive IDs/checksums. Drive uses the matching `History/YYYY/MM/country/Audio` path
for new files; existing identical Drive audio is reused by MD5 plus size.

After the 2026-10-25 finalization: 528 new downloads, 270 reused local entries and
5 reused history entries, or 803 ready recording entries across 798 distinct
local paths. All local hashes were checked; no changed files remain. An older
different-byte monthly file was retained and its verified pool version recovered
under a checksum-suffixed name. No existing file was overwritten. Two previously
misidentified EASY English-version copies were held separately; their original
MASTER source was preserved and excluded from further reuse.

The remaining 1,956 entries are not complete. YouTube returned HTTP 429 and then
an authentication/bot challenge. Fresh extraction was stopped. No cookie reuse,
client switching, authentication or DRM workaround was attempted. A hidden,
one-shot supervisor completed local verification and Drive upload independently
of the chat. It then exited as `source_paused`; it is not still downloading.
Drive publication uses a 1 GiB headroom reserve, immutable copies and checksum
verification, with no sync/delete operation.

The metadata export completed checksum-verified Drive backup under
`History/Catalog_20261025`: 210 files, 320,881,274 bytes. The database SHA-256 is
`71a6737dca7d0cb8c2da0f7aead463978462695d31fd833dd5203a71d32c0678`.
Its completed upload receipt is stored privately at
`.local/history2024/catalog_drive_receipt.json`. Drive had 4,259,441,097 bytes
free after that backup; this is a point-in-time observation, not a space guarantee.

## Reproducible code and checks

Collectors: `scripts/odify_recent_history.py`, `odify_recent_albums.py`,
`odify_history_bugs.py`, `odify_history_japan_albums.py`, and
`odify_history_export.py`. Audio: `tools/v46/history_music.py`,
`history_music_runtime_search.py`, `publish_history_music.py`, and
`history_background.py`. Each CLI exposes `--help`; existing export directories
are protected against replacement. Download source access remains paused until
the operator resolves the restriction.

Offline regressions after the audio-request feature: 110 script tests and 50 V4.6
tests passed. Collector tests previously passed (16); Android request/URL/cache
tests are included in the native build and device suite. Private databases,
tokens, source caches, music files and APKs remain outside Git.

## External-only continuation and album audio (2026-10-25)

The subsequent authorized resume reached 1,853 newly downloaded recordings,
270 reused originals and 12 recordings reused across history (2,135 ready).
The remaining chart queue includes 434 queued, seven link-selected, 111 without
reference metadata and 72 held entries. YouTube again returned bot-verification
and 403 errors, so fresh audio extraction paused. These are incomplete counts,
not a claim that all 2,759 chart songs were downloaded.

The user explicitly deferred further Drive upload until later, after VPN use.
The running local finalizer and saved resume command have no Drive configuration
or upload step. Already uploaded files/receipts remain unchanged. No VPN server
rotation, cookie export or authentication-challenge workaround is performed.

The user then authorized album-track audio. `tools/v46/album_music.py` prepares
cataloged, chart-associated albums released from 2024-01-01 through 2026-10-25,
including Apple and KR/JP supplemental album links. This is not every album
released worldwide. The initial combined queue contains 1,878 albums and 11,513
distinct recordings. Future releases and pre-2024 albums are excluded. The
year/month folders use album release month; repeated recordings reuse verified
bytes, with each album retaining its own track manifest and playlist reference.

Album audio is stored under `D:\Odify\Albums\YYYY\MM\Artist - Album [id]\Audio`.
The external queue's `album_scope.json`, `chart_status.json`, album JSON and
per-album download receipts expose missing metadata/links/files. Selected links
and transfer evidence live in the separate `album_audio_evidence` SQLite table;
existing MASTER/source approvals are never promoted or overwritten by this queue.
The shared collector uses matching duration/title, rejects alternate/live uploads
and prefers official then lyrics candidates according to the user's policy.

The supervisor first reuses checksum-verified chart/original files, enriches
missing runtimes from catalog metadata, and checks the chart source restriction
before requesting new audio. If gated, it can still reconcile cached candidates
without network extraction, save the DB and exit paused. Album metadata work is
independent of the chat. The disk must stay connected and the PC awake; a stopped
process is not described as running. New transfers reserve 2 GiB of disk space.
The source restriction must be cleared before new album audio can proceed.

The V4.6 regression suite now passes 55 cases, including album cutoff/dedup,
checksum reuse, changed-file protection, preserved existing links, global source
gates versus per-track age gates, and cache-only selection without source calls.

## Latest external-only continuation (2026-10-25)

The operator changed their network and explicitly requested continuation. A normal
one-recording canary succeeded; subsequent verified transfers reached 2,272 new
chart files, 270 reused originals and 16 history reuses (2,558 ready of 2,759).
The 111 missing-metadata and 90 held rows remain explicit. This supersedes the
older 2,135-ready snapshot above; it is not complete chart coverage. No further
Drive upload ran during this continuation.

Only the exact old source-error fingerprints preceding that proven recovery are
acknowledged. New authentication/rate-limit responses still stop source calls.
A pure transient SSL EOF is distinguished from an authentication gate; a combined
SSL/authentication error remains gated. Album metadata and audio run independently.
The album worker can resume its existing queue without rebuilding all manifests.
Album audio has now produced additional verified files under its release-year/month
folders; use its live chart_status.json for counts, rather than treating all
11,513 queued recordings as downloaded. The metadata-only runtime pass completed
2,342 attempts, resolving 769 missing runtimes; remaining gaps are preserved.
The V4.6 Python regression suite passes 58 cases.

## User download controls

Open `D:\Odify\음악 다운로드 관리.lnk` (the PowerShell entry point is
`D:\Odify\Manage-Odify-Downloads.ps1`). The local window shows new downloads,
verified reuses, active transfers, queued rows, missing metadata and held rows.
Close the window without stopping the worker. Pause writes `Albums/control.json`;
a controlled worker completes only its currently active files, then waits.
Resume clears the flag or launches the resumable worker if it has exited. It does
not delete/reset original files, row receipts, MASTER links or Drive content.
The pause/resume handshake is exposed in `Albums/manager_status.json`; the
full per-recording evidence remains in `chart_status.json` and month manifests.
The legacy worker was replaced at an idle audio boundary, preserving its receipt;
only outstanding read-only metadata probes were discarded during that handoff.
The controlled batch size is at most its four active workers, so a pause does not
start another queue of 20 behind those transfers. Source gates still stop audio
requests; Resume does not silently authorize a gate bypass. No Drive upload step
is present.

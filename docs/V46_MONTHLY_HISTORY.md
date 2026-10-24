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

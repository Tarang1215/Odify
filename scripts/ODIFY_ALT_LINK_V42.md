# Odify V4.2 — independent music catalog cross-check (2026-10-24)

## Status

Implemented and independently run with three music catalogs. **All actions are read-only.**
54 review tracks: 39 V4 ambiguous songs, 14 offline V4.1 recovered songs, and one live V4.1 recovered song. Existing V4 selection URLs, Google MASTER and Apps Script remain unchanged.

### Execution log

- MusicBrainz + Apple actual catalog run: https://github.com/Tarang1215/Odify/actions/runs/40004190912
- Network outage-aware offline rereport: https://github.com/Tarang1215/Odify/actions/runs/40004639486
- Deezer actual public API requests: https://github.com/Tarang1215/Odify/actions/runs/40005013599
- **Final** short-audio exclusion and duplicate-ID detection (offline replay): https://github.com/Tarang1215/Odify/actions/runs/40005496849

### Final metrics — 54 tracks

| Measure | Result |
| --- | ---: |
| Deezer HTTP 200 | 54 |
| Apple HTTP 200 | 28 (then 429 / stop) |
| MusicBrainz HTTP 200 | 1 (then 503 / stop; no usable match) |
| Cross-source Apple/Deezer artist, song title and 60–600 second duration agreement <=7 sec | 9 chart rows |
| Distinct paired Apple trackId / Deezer trackId records | **8** |
| Same catalog ID pair repeated under two alternative chart artist credits | 2 rows (RM / RM, youjeen) |
| Matching Apple and Deezer album names | 2 chart rows for **1** distinct recording |
| Video review candidates within 7 sec of dual-catalog audio length | 65 URLs over 9 chart rows |
| Rejected 46-sec excerpt | 1 (BTS, Blood Sweat & Tears - Live) |
| Catalog version warning | 1 (Seven explicit / cleaned-content conflict) |
| Existing video URL changes | 0 |
| Google MASTER writes / auto approvals | 0 |

### Correctness and risk interpretation

- The 9 chart rows and 8 distinct catalog pairs are metadata agreements, **not listening-verified YouTube videos**.
- The 65 review candidates can contain duplicate uploads, lyric videos, full-length music videos, alternate versions, and misattributed users. Duration match alone never approves a video.
- Album name agreement is present for only one distinct recording pair.
- MusicBrainz 503 and Apple 429 failures are **provider unavailable**, not evidence a song does not exist.
- Short 46-second catalog items are never considered confirmation of a full studio recording.
- Explicit, Clean, Live, Remaster and other variant contradictions stay unresolved.
- User-created title/artist aliases remain unverified hypotheses unless backed by explicit official metadata and identity IDs.
- Automatic import for the 41,416-track collection is not enabled.

### Code

- scripts/odify_altlink_v42_catalog.py — MusicBrainz and Apple independent artist IDs, album/version/time, API status and checkpoint handling.
- scripts/odify_altlink_v42_deezer.py — Apple/Deezer independent catalog evidence, duration check, deduplication and review-only decisions.
- scripts/tests/test_odify_altlink_v42_catalog.py and scripts/tests/test_odify_altlink_v42_deezer.py — unit and security tests.
- .github/workflows/odify-altlink-v42-deezer.yml — live requests require manual triggering.
- .github/workflows/odify-altlink-v42-deezer-offline.yml — replays archived responses without network.

### Audit file roles

- v42_apple_deezer_catalog_54.csv — each song’s artist IDs, album and track IDs, version and time comparisons, source warnings, distinct record identity.
- v42_independent_duration_video_hints.csv — 65 review-only video URL metadata suggestions.
- v42_deezer_requests.csv — audited API requests and statuses.
- checkpoints/*.json — raw response data, resumable and reproducible.
- summary_v42_deezer.json — latest corrected deduplicated report.

**Release gate**: human or official distributor confirmation (album, ISRC, exact version and uploader), then controlled separate import after verified MASTER links are protected. No automatic URL verification, audio downloads or rights claims.

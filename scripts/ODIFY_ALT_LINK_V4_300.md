# Odify Piped alternative-link collector V4 — 300 song live benchmark

Status: pilot only; **not** a production launcher and **never writes Google MASTER**.

## Why V4

The previous live 100-song Piped benchmark got metadata search results on 100/100 songs, but just 67 songs passed the original artist/title/version filter (KR 9/25, JP 12/25, US 23/25, global 23/25). V3 offline reranking recovered 84/100 candidates using the same existing API responses. That was a coverage increase in metadata eligibility, **not proof of audio correctness**.

The V4 trial tests whether targeted additional Piped searches can recover candidates that V3 would miss, with strict read-only rules and explicit origin tracking. This is neither a YouTube Music API nor guaranteed public API access.

## Pilot sampling and request budget

- Exactly **75 entries each** from Korea, Japan, USA, and global chart histories = **300 unique recording IDs**.
- Preserve as many original V2 100-song checkpoints as possible. The original 100 entries contained a cross-country recording duplicate; that duplicate must be replaced with a fresh recording. Expected sample is 99 cached distinct V2 songs plus 201 newly selected songs.
- Default first query: `artist title audio`, Piped `filter=music_songs`.
- Only for titles with no eligible candidate, missing artist evidence, or a near tie: a second query `artist "title" official audio`, Piped `filter=videos`.
- Maximum two queries per song, success-aware fallback on network/server errors, short timeout and explicit delay. No rate-limit evasion: HTTP 403/429 halts requests for this run.
- Every song's checkpoint is JSON under `checkpoints/<country>_<recording_id>.json`, with `progress.json` refreshed after every processed song. Re-running in the same output folder reuses successful checkpoints. Different source sample or settings are refused.

## V4 ranking and safety gate

- Reuse V3's base ranking and version safeguards; only add targeted structural Japanese song-title extraction (e.g. `Artist「Song」`) and artist-title concatenation if the artist is explicitly present.
- Handle album and exact duration as additional *metadata signals*, not proof of a specific release.
- Normalize video IDs to deduplicate repeated hits across query types.
- Label missing artist evidence as `artist_review`; multiple near-scoring versions as `ambiguous_review`; incompatible remix/live/clean/explicit versions remain excluded. No link is automatically marked verified or written to MASTER.
- Keep a holdout reference dataset from existing verified MASTER URLs **only for evaluation**, not to boost candidate scores.

## Reproducible experiment

Source workflow: [odify-altlink-v4-300.yml](../.github/workflows/odify-altlink-v4-300.yml)

```bash
python -m unittest discover -s scripts/tests -p 'test_odify_altlink_v4_300.py' -v

python scripts/odify_altlink_v4_300.py \
  --catalog .local/odify_catalog.sqlite3 \
  --cached-v2 .local/odify_altlink_pilot_v2 \
  --out .local/odify_v4_300 \
  --limit 300 --max-queries 2 --timeout 7 --delay 0.8
```

Result files:

- `sample_300.json`, `sample_300.csv`: chart source, rank, artist and title
- `checkpoints/*.json`: per-song candidate and HTTP attempts; restartable
- `attempts_300_v4.csv`: per-request host/status/latency/error
- `candidates_300_v4.csv`: all ranked video IDs and rejection reasons
- `audit_300_v4.csv`: initial V3 vs expanded V4 metadata review status
- `proposals_review_only_300.csv`: never-approved candidate URLs
- `summary_300_v4.json`: region and aggregate metrics (not verified audio accuracy)

## How to make V5/production better after V4 trial

1. Use trusted artist IDs and multilingual aliases (Hangul/Roman letters/Kana) from an actual catalog service, not guessed name matches.
2. Enrich reference album, version, ISRC where available and reference duration before ranking; compare title AND full artist credits AND recording ID; handle music video, remix, cover, live, clean, explicit as **distinct versions**.
3. Calibrate scoring on manually verified ground truth, and record precision at fixed retrieval coverage; use holdout reference video IDs solely as evaluation labels.
4. Keep Piped as optional metadata candidate provider; use production-supported search interfaces where feasible. Treat public volunteer API availability as best effort.
5. Never overwrite an existing MASTER link; never auto-classify a candidate as `검증완료` without independent review.

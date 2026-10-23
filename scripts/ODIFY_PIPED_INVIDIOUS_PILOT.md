# Piped + Invidious real API pilot — 2026-10-23

This collector is **read-only with respect to the user's MASTER Google Sheets**. It never downloads audio and never stores expiring stream URLs.

## Implementation and test links
- Collector: `scripts/odify_link_multi_backend.py`.
- Strict match engine reused: `scripts/odify_link_consensus.py`.
- Unit tests: `scripts/tests/test_odify_link_multi_backend.py`.
- Live action: https://github.com/Tarang1215/Odify/actions/workflows/odify-link-provider-pilot.yml
- Four-region catalog source: prior verified `odify-catalog-us-full-4-regions` workflow artifact, run 39909115368.

## The 100-song sample

25 **distinct recordings** for each of KR, JP, US, Global, drawn from the most recent recorded Apple Music storefront or ListenBrainz sitewide chart, evenly spread across rank 1–100. Globally duplicated recording IDs are reassigned to another candidate in that same region, with preference for a nearby rank. This ensures **exactly 100 distinct recordings** from a real existing Odify DB, not 100 handpicked easy titles.

A Korea, Japan, or USA result reflects the Apple country store charts. Global reflects **ListenBrainz volunteer listening**, not an official world sales chart.

## Metrics

- API attempt success rate = valid 200+JSON result / actual completed HTTP request attempts, including valid zero-candidate searches.
- Error rate separately counts errors, timeouts, 403s, and instance failure.
- Latency = client-end elapsed wall time per provider (includes fallbacks), median and p95.
- Candidate match coverage = share of processed songs with at least one video matching title/artist/duration and no obvious unwanted cover/live/remix/language version.
- High confidence means **metadata evidence only**: length consensus with 3 independent uploader channels, not multiple backends returning the same uploader/video ID.
- **Verified matching accuracy is not measurable without human-labeled ground truth**. Do not claim an automatic exact match percentage is a human-verified correctness score.
- Data from Piped and Invidious is considered untrusted even when HTTP 200.

## Resume

```bash
python scripts/odify_link_multi_backend.py \
  --db .local/odify_catalog.sqlite3 \
  --state .local/odify_link_provider_state.sqlite3 \
  --output-dir .local/link_pilot \
  --limit-per-country 25 --timeout 5 --delay 1.2

# Retry previously failed HTTP providers while preserving success:
python scripts/odify_link_multi_backend.py \
  --db .local/odify_catalog.sqlite3 \
  --state .local/odify_link_provider_state.sqlite3 \
  --output-dir .local/link_pilot --retry-errors
```

Provide comma-separated HTTPS instance base URLs using `--piped-instances` and `--invidious-instances`. Respect operator policies, quotas and rate limits; public Piped and Invidious servers are volunteer-operated. Do not automatically circumvent access protections with proxy rotation or unbounded retry.

All results are written to `.local/link_pilot/`:
- `pilot_100_sample.csv`: 100 real source songs and rank positions.
- `pilot_provider_attempts.csv`: each API, HTTP status, latency, candidate count and error.
- `pilot_candidates.csv`: video candidates, eligibility and rejection reasons.
- `pilot_link_proposals.csv`: a separate proposal table with confidence and status.
- `pilot_summary.json`: measured provider metrics and caveats.
- `progress.json`: current progress / final summary.
- `.local/odify_link_provider_state.sqlite3`: immutable existing results / resumable checkpoint.

The Github Actions run streams `ODIFY_LINK_PROGRESS` records during live requests and attaches these files as an artifact for inspection. MASTER cells cannot be changed by this code. A future optional import must check **the URL is blank at commit time** and set status **재검토**, never **검증완료**.

# Odify Piped/Invidious V3 — cached 100-track reranking (2026-10-24)

## Scope and reproducibility

V3 adds `scripts/odify_altlink_rerank.py`. It **only** reads the 100 JSON checkpoints from live Piped/Invidious V2 action [39967688421](https://github.com/Tarang1215/Odify/actions/runs/39967688421) and writes **review-only** CSV/JSON reports. No audio is downloaded, no provider API calls are made, and MASTER/Apps Script V4/previous checkpoints are never edited.

Source: [passing V3 test and evaluation run 39987960493](https://github.com/Tarang1215/Odify/actions/runs/39987960493). Source branch: `feature/odify-v12-master-db`.

### Offline results

| Metric | V2 | V3 |
| --- | ---: | ---: |
| Songs benchmarked | 100 | 100 |
| Piped candidates searched (unique per track) | 2,011 | 2,011 (reused) |
| Songs with an eligible metadata candidate | 67 | 84 |
| Exact ID match against available MASTER verified references (19 cases) | 9 | 13 |

- V3 states: 50 `manual_review`, 22 `ambiguous_review`, 12 `artist_review`, 16 `no_eligible_candidate`.
- The 19-row reference comparison was made separately against the user's already verified MASTER rows. The V3 GitHub Actions run has no private MASTER input, and therefore correctly reports reference denominator 0, not a fabricated agreement rate.
- The search candidates contained the verified reference ID in 14 of the 19 cases. 13 of those 14 were selected by V3, but selecting a different video ID is not necessarily a wrong audio recording.
- New metadata candidate coverage and exact known-video ID agreement are **not** audio listening or manually labeled accuracy. We do not use reference IDs in ranking (holdout).

### What changed

V3 separates *candidate discovery*, *matching*, and *approval*. It handles exact track titles from music-song endpoints where artist is only in the uploader field, takes optional artist aliases from an authorized MASTER CSV, downranks missing artist/album/duration evidence, rejects obvious incompatible versions (remixes, live, instrumental, explicit vs clean, previews), scores Topic/official/distributor **metadata evidence** without assuming authenticity, and explicitly separates uncertain and near-tied candidates.

The new algorithm does not upgrade existing `검증완료` links or fill blank MASTER URLs. Existing URLs are protected, including unverified ones. Every proposed new URL has `verified=NO` and `MANUAL_REVIEW_ONLY`.

### Run

```bash
python -m unittest discover -s scripts/tests -p 'test_odify_altlink_rerank.py' -v
python scripts/odify_altlink_rerank.py \
  --input .local/odify_altlink_pilot_v2 \
  --output .local/odify_altlink_rerank_v3

# Optional locally exported MASTER.csv for holdout comparison and aliases:
python scripts/odify_altlink_rerank.py \
  --input .local/odify_altlink_pilot_v2 \
  --output .local/odify_altlink_rerank_v3_masterbench \
  --master-references .local/MASTER.csv
```

V3 accepts a saved checkpoints directory or GitHub Actions artifact ZIP via `--input`. It produces `ranked_candidates_v3.csv`, `review_only_proposals_v3.csv`, `baseline_comparison_v3.csv`, and `rerank_summary_v3.json`.

### Remaining limitations

The pilot is not a complete production linker. Romanization/translation differences (e.g. Korean `아이유` vs uploader `IU`, Japanese artist names) remain only partially matched unless a trusted artist-alias source is supplied. Ambiguous versions and albums still require independent metadata verification, manual review, and/or listening. Piped is a volatile volunteer-operated metadata service; Invidious was blocked in the live benchmark. **Do not automatically apply V3 scores to the 41,416-song catalog or remove the existing API fallback.**

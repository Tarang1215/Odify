# Odify V4.1 — 300-track gap recovery and ambiguity audit (2026-10-24)

## Completed source-backed work

The audited input is the 300-track V4 experiment with 8,612 deduplicated video candidates. V4 already retained known original choices and had `281/300` songs with at least one *metadata-eligible* candidate. This is **not** 281 listening-verified tracks.

- Full 300-song offline review run: https://github.com/Tarang1215/Odify/actions/runs/40000817443
- Targeted five-track **real HTTP API** follow-up run: https://github.com/Tarang1215/Odify/actions/runs/40001229423
- Scripts: `odify_altlink_v41_review.py`, `odify_altlink_v41_targeted.py`
- Tests: `scripts/tests/test_odify_altlink_v41_review.py` (6 passed), `scripts/tests/test_odify_altlink_v41_targeted.py` (3 passed).
- Existing V4 safety tests were also run in the offline review workflow.

## Measured findings

| Category | Initial V4 | V4.1 outcome |
| --- | ---: | --- |
| No V4 eligible candidate | 19 | 14 have 120 potential **review-only** URLs in original cached searches |
| Remaining without a new review candidate after offline run | 5 | After 10 new live Piped HTTP requests (10 × HTTP 200), 1 more has a tentative review candidate |
| Remaining without a review candidate |  | **4** |
| V4 ambiguous tracks | 39 | Among their eligible video lists, 20 have at least one same-uploader/similar-title/similar-duration pair; **19** do not. These classifications are not resolved song identities |
| KR/JP/romanized artist spelling hints |  | 15 **PENDING_HUMAN_REVIEW** alias pairs, not trusted artist identities |
| MASTER URL updates | 0 | 0 |
| Previously selected V4 video IDs overwritten | 0 | 0 |

Five unresolved tracks after the offline review were Shane — 없어, マルーン5 — マップス, 宇多田ヒカル — First Love (2014 Remastered), =LOVE — 劇薬中毒, and パスピエ — 裏の裏.

The targeted API search found another *unverified candidate* for **宇多田ヒカル — First Love (2014 Remastered)** from a nonofficial uploader. It still requires source and version review and has not been approved. The four tracks **Shane — 없어, マルーン5 — マップス, =LOVE — 劇薬中毒, パスピエ — 裏の裏** still need follow-up using trusted catalog IDs, additional title transliterations, and direct official metadata rather than unbounded Piped retries.

### Strict distinctions

- 281/300: V4 strict *metadata eligibility* from archived results.
- An additional 15/19: **potential** candidates for songs that lacked V4 eligible candidates, not passed/verified matches. Thus **do not add 15 to the 281 eligible count**.
- 20/39 ambiguous songs: at least one pair of nearly identical video metadata from the *same uploader*, so such duplicates should **not** count as independent confirmation. This does not imply that all 20 have been successfully disambiguated.
- Artist translations and English spellings in `artist_aliases_pending_review.csv` are hypotheses. To promote an alias, require a reliable identity/source match and store its provenance. Unverified aliases must not grant uploader authenticity.
- Channel names, `- Topic`, and `Official Audio` claims are not sufficient evidence of authenticity, rights, version or release identity.

### Implementation boundaries

Both new scripts are sidecar workflows; they **never** edit Google MASTER, current selected URLs, Apps Script V4, old cached checkpoints or the production 41,416-track linker. V4.1 candidate rows have `review_required=YES`, `auto_apply=NO`, and all MASTER write actions are `NO_WRITE`. The 5-track online test makes at most 2 Piped metadata queries per track, delays between attempts, records 1 checkpoint per song, and ceases service attempts on rate limiting/403.

Do not replace the existing production provider or auto-accept candidate URLs. Next gate is independent official title/artist/album/duration verification and manually labeled versions in the 15 recovered + 39 ambiguous songs; only after that should a production import path be considered.

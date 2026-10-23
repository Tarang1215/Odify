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


## 실제 300곡 테스트 및 V4 최종 안전 보정 결과 (2026-10-24)

- 실측 GitHub Actions: https://github.com/Tarang1215/Odify/actions/runs/39991741952 (성공)
- 최종 안전 보정 GitHub Actions: https://github.com/Tarang1215/Odify/actions/runs/39995104535 (성공; 원본 300개 체크포인트만 재평가, 추가 API 호출 없음)
- 75 KR + 75 JP + 75 US + 75 Global = 중복 녹음 ID가 없는 300곡.
- 99개 V2 검색 캐시 재사용 + 201곡 새 검색.
- 343 HTTP 요청, 전부 HTTP 200; 성공 응답 중앙값 807ms, p95 999ms.
- 첫 조회/V3 적격 후보 258/300 (86.0%) → V4 확장 적격 후보 281/300 (93.7%).
- KR 69/75, JP 66/75, US 75/75, Global 71/75.
- 최초 V4의 점수 기반 재선정은 원곡보다 Instrumental/Unofficial Audio/팬 제작 가사 영상·홍보 PV를 잘못 우선하는 문제가 있었다. V4 최종판은 원본 영상 제목에 대한 버전 검증, 홍보/가사 업로드 감점, 아티스트 증거 합성 금지, 근거 없는 2차 후보의 1차 선택 덮어쓰기 금지를 도입하였다.
- 안전 보정 후: manual_review 177, ambiguous_review 39, artist_review 10, baseline_retained_review 55, no_eligible_candidate 19.
- 기존 MASTER '검증완료' 링크의 동일 아티스트·곡명 기준 29개 대조: 기준 영상 후보 존재 22/29, 1차 V3와 기준 영상 ID 일치 20/29, 안전 보정 V4 일치 20/29, 기존 일치의 역행 0건.
- 정확한 음원 청취 검증은 **미실시**: 영상 ID 일치는 품질의 제한적인 대리 지표이며 281곡을 정확한 노래라고 주장하지 않는다.
- Google MASTER URL·Apps Script V4 수정 0건. 300곡 결과 및 모든 제안은 검토 전용이다.
- 이번 수치는 V3 첫 후보와 V4 확장 후보 간 차이이므로 23곡 개선의 원인을 검색 확대와 알고리즘의 단독 효과로 분리해 주장할 수 없다.
- 글로벌 75곡 표본은 보유 글로벌 차트 범위상 2026년 곡으로 구성됨.

### Production rollout gate
Keep original provider/API fallback and MASTER links. Do not enable automatic URL insertion or verification on the 41,416-track DB. Before any promotion, independently verify artist aliases, album/version and track durations; gather manually labeled cross-region holdout accuracy, test the 19 no-eligible and 39 ambiguous review songs, and verify recurring provider availability. The rate-limit/circuit-breaker and checkpoint protection stay enabled.

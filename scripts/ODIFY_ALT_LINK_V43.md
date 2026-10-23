# Odify V4.3 — 41,416곡 DB를 위한 배치 수집 및 앱 연동용 카탈로그

## 산출물

**전체 DB를 한 번에 검색한 것이 아니라, 41,416곡 전체를 카탈로그로 유지하며 1,000곡을 실제 검색하는 단계적 확장 검증이다.**

- 원본 음악 DB: 41,416 고유 Recording ID (읽기 전용).
- 첫 시험 1,000개 Recording ID: KR 300, JP 300, US 321, GL 79.
- 이전 V4 300곡의 실측 체크포인트 재사용; 신규 최대 700곡만 Piped 메타데이터 조회.
- 동일한 카탈로그 SHA256, 설정과 표본 ID로 재실행: 처리 완료 곡은 건너뜀.
- HTTP 차단/실패를 처리 완료 검색 성공으로 계산하지 않음. 원격 공급자가 차단되면 새 호출을 중단하고 결과를 보존.
- 녹음별 JSON 체크포인트, `progress.json`, `audit_1000.csv`, `summary_v43.json`, 전체 음악 DB 및 후보 영상·상태를 포함하는 SQLite.
- 후보 URL은 모두 review-only 상태로 저장하고, 기존 Google MASTER를 직접 변경하지 않음.

## 앱 데이터베이스 테이블

| 테이블 | 내용 |
| --- | --- |
| artist | 원본 Artist ID 및 이름 |
| recording | 41,416개 Recording ID, Artist ID, 국가, 버전 및 일부 앨범 정보 |
| album | 원본 공식 카탈로그의 앨범 레코드 — 원본에는 115개뿐임 |
| album_track | 원본 앨범-수록곡 관계 1,586개 |
| external_recording_identity | 원본에 존재하는 Apple 트랙 ID 1,883개, 자동 검증 처리하지 않음 |
| source_candidate | 녹음당 최대 5개 재검토용 YouTube 영상 후보, 영상 메타데이터, 점수 |
| link_state | unprocessed / manual_review / ambiguous_review / existing_verified_protected / provider_unavailable 등 |
| search_attempt | 새 API 요청의 HTTP 상태 및 경과 시간 |

`odify_altlink_v43_enrich_export.py`를 사용하면 원본의 앨범 및 외부 ID 관계까지 안전하게 추가한 **새로운** SQLite 파일을 만들 수 있다. 기존 SQLite 및 앱 내 라이브러리는 수정하지 않는다.

## 중요한 정확성 구분

- **metadata_eligible**: 검색 결과의 곡명·아티스트·버전·길이 규칙을 만족하는 후보를 발견한 곡. 실제 재생/음원 동일성이 검증된 곡이 아니다.
- `strong_metadata_review` 등도 실제 검증 상태는 아니다.
- `existing_verified_protected`: 별도의 Google MASTER 스냅샷에서 이미 링크 검증완료로 관리된 것을 보호하는 상태. 신규 후보의 검증승인과 다르다.
- 원본 DB의 아티스트 ID는 음악 카탈로그 내부용이며 전 세계 동일 아티스트 ID라고 볼 수 없다.
- 41,416개 녹음 중 실제 앨범 관계가 있는 레코드는 일부이다. 원본에 없는 앨범·수록곡은 생성하지 않는다.
- Piped는 비공식 공개 인스턴스이므로 41,416개 전체에 대해 안정적인 무제한 접속을 가정해서는 안 된다.
- 사용자에게 제공되는 최종 DB가 앱에서 조회 가능한 스키마를 갖췄다는 것은 앱 코드가 그 DB를 **자동으로 읽도록 연결된 상태**라는 의미가 아니다.

## 실행

```sh
python scripts/odify_altlink_v43_batch.py \
  --catalog .local/odify_catalog.sqlite3 \
  --v4-cache .local/v4_300 \
  --out .local/v43 \
  --limit 1000 --max-queries 1 --delay 0.8
```

같은 입력과 출력 디렉터리로 다시 실행하면 완료된 체크포인트를 재사용한다. 설정, 표본, 원본 SHA가 바뀌면 안전하게 거부한다. GitHub Actions 재시작 시 이전 실행 ID의 아티팩트를 복구하도록 workflow_dispatch가 지원된다.

```sh
python scripts/odify_altlink_v43_enrich_export.py \
  --catalog .local/odify_catalog.sqlite3 \
  --links .local/v43/odify_catalog_links_v43.sqlite3 \
  --out .local/odify_catalog_links_v43_enriched.sqlite3
```

기존 MASTER와 일치하는 링크를 **새 DB 사본에만** 보존하려면:

```sh
python scripts/odify_altlink_v43_master_overlay.py \
  --input .local/odify_catalog_links_v43_enriched.sqlite3 \
  --master .local/MASTER.csv \
  --out .local/odify_catalog_links_v43_master_protected.sqlite3
```

MASTER의 링크는 정확히 하나의 Recording ID에 대응하고, 아티스트·곡명과 URL이 충돌하지 않을 때만 기존 링크로 기록한다. 번역명이나 중복 버전은 별도의 충돌 검토 CSV로 저장한다. Google Sheets를 직접 수정하지 않는다.

## 점진적 확장 관문

1. 1,000곡 실측 완료, 메타데이터 적격/실패/중단 유형 분석.
2. 별도 검증 링크와 수동 정답 표본에서 잘못된 버전 매칭을 추적.
3. 5,000곡 확대 실행 전에 API 유효성, 요청 제한, 아티팩트 파일 크기, 재시작 능력을 다시 검증.
4. 모든 조회 결과는 승인 전 재검토 상태를 유지; 앱 재생 소스로 자동 주입 금지.
5. 41,416곡 전체는 이전 작업 결과를 재사용할 수 있는 대량 배치 순회로 구현하되, 실제 풀스케일 검증은 별도 단계.

실제 1,000곡 실행: https://github.com/Tarang1215/Odify/actions/runs/40008865324

기존 MASTER URL 직접 변경: **0건**. 앱 프로덕션 바이너리 변경: **0건**.

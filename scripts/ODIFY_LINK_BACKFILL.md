# Odify MASTER 2026→1980: 링크 후보 일괄 수집

기존 Google Sheet: **Odify 음악 마스터 | 2026→1980**. 수집 대상
연도별 탭과 MASTER의 **확인되지 않은 곡**을 기준으로 처리한다.
이 스크립트는 YouTube **Data API v3의 공개 메타데이터**만 조회한다.
오디오 다운로드/광고 회피/원본 Drive 파일 편집 기능은 없다.

## 실행

1. MASTER 탭을 CSV로 내보내 `Odify_MASTER.csv`로 저장한다.
2. Google Cloud에서 YouTube Data API v3를 활성화한다.
3. API 키는 코드/마스터 시트에 넣지 말고 `YOUTUBE_API_KEY` 환경변수로 공급한다.
4. 먼저 네트워크 호출 없는 대상 통계를 확인한다.

```bash
python scripts/odify_link_candidates.py --master-csv Odify_MASTER.csv --dry-run --year 2026
```

API 할당량 범위에서 후보를 병렬 조회하고 로컬 캐시를 유지한다.

```bash
YOUTUBE_API_KEY=YOUR_KEY python scripts/odify_link_candidates.py \
  --master-csv Odify_MASTER.csv --year 2026 \
  --max-searches 25 --workers 4 \
  --cache .local/odify_link_search_cache.json \
  --output .local/odify_2026_candidates.csv
```

다음 실행도 같은 캐시를 사용하므로 이미 조회한 곡은 다시 검색하지
않는다. 여러 사람이 결과를 검토할 경우, 검증된 후보만 Track ID로
MASTER 열을 업데이트하고 연도별 시트의 자동 수식은 건드리지 않는다.

## 신규: 재생시간 다수 합의로 링크 자동 선택 (2026-10-23)

- **공식 업로더 여부는 필수 조건이 아니다.** 동일 곡을 올린 서로 다른 채널의 후보를 모아 대표 재생시간 ±2초 안에서 3개 이상 독립 채널이 일치하면 자동 선택한다. 같은 업로더의 중복 영상은 한 표로 계산한다.
- 제목·아티스트를 먼저 확인하고, `English ver.`, `Japanese ver.`, Remix, Cover, Live, Instrumental, Remaster 연도 및 필수 피처링 충돌은 구분한다. MV/가사 영상은 배제하지 않는다.
- 다른 재생시간 구간에서 비슷한 수의 영상이 발견되면 안전을 위해 검토 대상으로 분류한다. 여러 버전의 길이가 동일한 `LE SSERAFIM - EASY` 같은 곡은 **길이만으로 언어 버전을 구분할 수 없다.**
- 가장 유력한 **아직 비어 있는 URL만** `재검토` 상태로 자동 입력한다. 기존 비어 있지 않은 링크와 `검증완료` 링크는 절대 덮어쓰지 않는다. 이는 청취 완료를 뜻하지 않는다.

### 이미 수집한 V2 후보 CSV로 즉시 적용 (Python)

```bash
python scripts/odify_link_consensus.py \\
  --master Odify_MASTER.csv \\
  --candidates 링크검색_v2_후보.csv \\
  --metadata 링크선택_v2_메타데이터.json \\
  --output-dir review --apply
```

출력: 제안/선정 MASTER CSV, Track ID별 추가 패치 CSV, 전곡 감사표, 영상 후보별 선정표. `--apply`를 생략하면 **미리보기만** 생성한다.

향후 수집은 `scripts/odify_link_candidates.py`가 **검색 1회당 최대 15개 후보**와 길이·조회수 정보를 모으고, 그 결과 CSV를 그대로 `--candidates` 인수로 넘기면 된다. API 호출은 캐시를 재사용한다.

### PC를 꺼도 작동하는 Google Sheets 자동화

Google Sheets의 `확장 프로그램 → Apps Script`에 `scripts/odify_autolink_sheets.gs` 내용을 넣는다. 원래 MASTER가 있는 문서에서 다음을 한 번씩 실행한다.

1. 시트를 다시 열고 상단 `Odify 자동 링크` 메뉴에서 **YouTube API 키 입력** (스크립트 비공개 속성에 저장)
2. **매일 자동실행 설정**을 눌러 설치형 트리거 권한 승인
3. **지금 링크 검색**으로 최초 시험

기본 최대 **45곡/회, 검색 90회/태평양 시간 기준 하루**, 한 곡당 최대 15개 영상 후보를 검토한다. `ODIFY_LINK_SCAN` 탭으로 재조회 이력을 저장하고, `ODIFY_LINK_AUDIT` 탭에 이유와 자동 선택 내역을 남긴다. 링크가 없는 신규 곡도 다음 실행에서 처리된다. API 키와 트리거를 실제 연결하기 전에는 서버측 자동실행이 시작되지 않는다. 현재 검색 기본 한도 참고: <https://developers.google.com/youtube/data/determine_quota_cost>.

## 속도 / 할당량

- 기존 미검증 YouTube 영상 ID는 `videos.list`로 최대 **50개씩 일괄 조회**한다.
  `search.list`를 다시 실행하지 않는다.
- URL이 없는 곡은 `search.list` 요청을 병렬 처리한다 (최대 6 workers).
  2026년 기준 공식 API는 `search.list` 요청을 별도의 기본 하루 100회
  호출 버킷으로 제한한다 (각 호출 1 unit). 일반 조회 작업은 별도
  10,000 units/day 버킷이다. 여러 연도를 한 번에 무제한 수집할 수 없다.
- 후보 수집 단계의 **자동 확정=아니오**는 수집 자체가 청취 검증이 아니라는 표시이다.
  이후 재생시간 합의 도구가 빈칸을 `재검토`로 입력할 수 있으나,
  자동으로 `검증완료`를 부여하지 않는다.
- 연도별 최초 100곡을 넘어 앨범 전체와 발매곡까지 확장하려면
  곡 메타데이터와 신뢰할 만한 차트·앨범 트랙리스트 확보가 먼저다.
- 2026→1980 탭이 이미 생성되어 있어도 빈 연도에 차트 이력이
  채워졌다는 뜻은 아니다. **연도별 원천 차트 확보**가 링크 매칭의 선행 작업이다.

이 도구는 현재 Android 앱이 아닌 PC/Mac에서 실행하며, 생성한 CSV를
검토한 후 Sheets MASTER에 반영한다. Python 표준 라이브러리만 사용한다.

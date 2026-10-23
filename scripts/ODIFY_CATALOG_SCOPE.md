# Odify 음악 카탈로그 수집 범위 (2026-10-23 확정)

## 국가 및 연도

| 지역 코드 | 최우선 차트 범위 | 1980~1999 | 최신/과거 데이터 현실 |
|---|---|---|---|
| `kr` 한국 | **2000~2026**, 월별 우선 | 후순위(옵션) | 차트 공급처에 따라 제공 연도가 다름. 자료 없는 달은 빈 상태로 표시 |
| `jp` 일본 | **2003~2026**, 월별 우선 | **수집하지 않음** | 오리콘 you大樹 2003~ 월간 차트는 유료 서비스. 무료 재사용 가능한 출처를 검증해서 순차 도입 |
| `us` 미국 | **2000~2026**, 월별 우선 | 후순위(옵션) | Billboard Hot 100 공개 커뮤니티 주간 아카이브를 증분 수집; 매월 자체 집계 |
| `gl` 글로벌 | **2000~2026**, 월별 우선 | 후순위(옵션) | ListenBrainz 사이트 전체 청취 지표는 공개 시점 이후 추적 가능. 2000년부터 전세계 동일 기준 순위를 제공하는 과거 데이터는 아직 확보되지 않음 |

연도 종료를 고정하지 않으며 **2027년부터는 최신 차트 수집을 이어서 진행**한다. 현재 수집 대상의 핵심은 **2000~2026년(일본 2003~)**. 1980~1999 데이터는 핵심 구간이 채워진 이후에만, 일본 외 국가에서 `--include-archive` 옵션으로 추가한다.

## 데이터 구조

- `artist`, `recording`: 동일 가수·곡·버전을 하나로 저장 (가능하면 외부 MusicBrainz/Apple ID 매핑). 영상 링크는 별도
- `chart_edition` + `chart_entry`: 지역별·날짜별·차트 종류별 순위를 보관. 차트에 5번 등장해도 곡 자체는 중복 생성하지 않음
- `album` + `album_track`: 차트 히트곡이 포함된 앨범을 찾고 전 수록곡 저장
- `recording_genre`: 장르명과 출처를 함께 기록. **록, 인디, 밴드, J-rock, 클래식, 피아노 커버** 등 별도 태그 가능. 분류할 수 없는 곡에 장르를 임의 지정하지 않음
- **월간 자체 집계**는 주간 차트 순위별 가중치 `101 - 주간 순위`를 한 달간 합산한다. 공식 월간 차트와 혼동되지 않도록 `monthly-derived` 종류와 출처를 사용
- 한국·일본 원문/로마자 표기, 원곡과 영어판·라이브·커버·리믹스는 서로 다른 녹음일 수 있으므로 ID 매칭을 별도로 검토

## 실행 (비상업적 개인용 메타데이터 DB)

Python 3.10 이상. 표준 라이브러리 SQLite. 모든 명령은 **재실행해도 진행 지점을 유지**한다. 오디오 파일은 내려받지 않는다.

**권장 실행은 단 하나의 명령**이다. 현재 4개 권역 최신 차트, 미국 2000~현재의 주간 기록(최근부터 25주분씩), 앨범 수록곡 조회(15곡/회), 랭킹 JSON 내보내기를 연속 처리한다. Windows 작업 스케줄러 또는 Mac cron에 등록해 매일 실행할 수 있고, 이전에 수집한 차트는 건너뛴다.

```bash
python scripts/odify_catalog_pipeline.py run --limit 25 --albums-per-run 15
```

이 명령은 사용자의 컴퓨터가 켜진 동안만 작업한다. PC가 꺼져 있어도 실행되려면 별도 무료 원격 실행 환경과 **개인용 DB의 비공개 영속 저장소**를 연동해야 한다. 아직 연결하지 않은 서버/계정에 클라우드 자동화가 설치됐다고 주장하지 않는다.

```bash
# 빈 데이터베이스 최초 1회
python scripts/odify_catalog_pipeline.py init

# 현재 한국·일본·미국의 Apple 차트와 글로벌 ListenBrainz 샘플 수집
python scripts/odify_catalog_pipeline.py chart-feed

# 미국 주간 아카이브: 2000~현재, 최근→과거 순으로 40회분씩 수집
# 재실행해도 이미 저장한 날짜는 넘어감
python scripts/odify_catalog_pipeline.py backfill-us --limit 40

# 주간 차트를 월간 자체 집계로 다시 계산 가능
python scripts/odify_catalog_pipeline.py rebuild-monthly \
  --country us --provider billboard-hot-100-community-archive --year-month 2005-06

# 차트에 나온 곡을 기준으로 iTunes 앨범/전곡 조회, 회당 25곡
python scripts/odify_catalog_pipeline.py expand-albums --limit 25

# 한국/일본/글로벌 과거 차트를 출처가 확인된 CSV로 얻었을 때
python scripts/odify_catalog_pipeline.py import-chart --csv historical_charts.csv

# 1980~1999 미국 차트는 나중에 명시적 실행할 때만
python scripts/odify_catalog_pipeline.py backfill-us --include-archive --limit 40

# 인디/록/밴드 등 장르별 CSV: country,source,chart_date,rank,
# artist,title,category,genre 등을 넣으면 별도 차트/태그로 수집
python scripts/odify_catalog_pipeline.py import-chart --csv indie_rock_charts.csv

# 앱 연동용 최신 랭킹 정적 JSON + 통계
python scripts/odify_catalog_pipeline.py export
```

실제 SQLite 경로: `.local/odify_catalog.sqlite3`. **기존 508곡 Google MASTER를 대량으로 덮어쓰지 않는다.** 카탈로그 수집 후 검증된 항목만 기존 플레이백 링크 데이터와 조인한다.

## 2003년 일본 역사 데이터에 대한 주의

오리콘의 공식 유료 `you大樹` 서비스에는 2003년부터 월간 차트가 있다. 그러나 **해당 데이터가 무료로 대량 제공된다는 뜻은 아니다**. `2003년 이후만`이라는 필터는 구현되어 있지만 **일본 2003~2025 월별 데이터를 전부 채운 상태는 아니다**. 추후 확보되는 적법한 공개/사용 가능 CSV를 `import-chart`로 받아 채우되 연도·국가·순위·출처를 보존한다.

관련 참고:
- Billboard 공개 커뮤니티 아카이브: https://github.com/mhollingshead/billboard-hot-100
- 일본 오리콘 아카이브 범위·유료서비스: https://ranking.oricon.co.jp/new/index.asp
- Apple Music RSS: https://rss.marketingtools.apple.com
- ListenBrainz 통계: https://listenbrainz.readthedocs.io/en/latest/users/api/statistics.html

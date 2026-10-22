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

## 속도 / 할당량

- 기존 미검증 YouTube 영상 ID는 `videos.list`로 최대 **50개씩 일괄 조회**한다.
  `search.list`를 다시 실행하지 않는다.
- URL이 없는 곡은 `search.list` 요청을 병렬 처리한다 (최대 6 workers).
  2026년 기준 공식 API는 `search.list` 요청을 별도의 기본 하루 100회
  호출 버킷으로 제한한다 (각 호출 1 unit). 일반 조회 작업은 별도
  10,000 units/day 버킷이다. 여러 연도를 한 번에 무제한 수집할 수 없다.
- 후보 CSV의 **자동 확정=아니오**는 의도적인 안전 장치이다.
  제목만 비슷한 커버·리메이크·라이브·다른 아티스트 버전을
  마스터의 `검증완료`로 자동 승격해서는 안 된다.
- 연도별 최초 100곡을 넘어 앨범 전체와 발매곡까지 확장하려면
  곡 메타데이터/공식 카탈로그 확보와 저작권 상태 검토가 먼저다.
- 2026→1980 탭이 이미 생성되어 있어도 빈 연도에 차트 이력이
  채워졌다는 뜻은 아니다. **연도별 원천 차트 확보**가 링크 매칭의 선행 작업이다.

이 도구는 현재 Android 앱이 아닌 PC/Mac에서 실행하며, 생성한 CSV를
검토한 후 Sheets MASTER에 반영한다. Python 표준 라이브러리만 사용한다.

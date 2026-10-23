# Odify Piped + Invidious 링크 검색 벤치마크

**2026-10-23 실행 범위:** Odify SQLite 41,416곡 기준 **KR/JP/US/Global 각 25곡 = 100곡**을 뽑아 실제 Piped 및 Invidious **공개 검색 API**에 요청. 개발자 테스트이며 어떠한 YouTube 음원도 다운로드하지 않는다.

## 설계

- 입력: 별도 음악 카탈로그 SQLite 파일. MASTER Google Sheet의 508곡은 읽거나 수정하지 않는다. 필드 `country, recording.id, artist, title, chart date, rank`만 샘플링.
- 검색: Piped의 `/search?filter=music_songs`, Invidious의 `/api/v1/search?type=video`를 각각 호출. **YouTube 공식 API 호출/할당량 없음**. 다만 자원봉사자가 운영하는 공개 서버 자체의 제한은 반드시 존중.
- 파서: `videoId,title,uploader/author,duration,views`를 공통 객체로 변환해 영상 ID 중복 제거. 대응 실패/빈 응답은 성공으로 세지 않음.
- 검증: 기존 `scripts/odify_link_consensus.py`의 언어판·리믹스·커버·라이브 배제, 가수/곡명, 재생시간 클러스터 합의 알고리즘 재사용.
- 매칭 정확도: **동영상 제목/가수 일치(자동 적격률)**, **세 채널 합의 자동 고신뢰율**, 별도 `--master-references`의 검증 영상 ID가 있는 경우에만 **기존 영상 ID 동일률**을 구분해서 표기. 자동 판정은 청취 정확도와 같지 않음.
- 보호: **DB만 읽고** `review_only_proposals.csv` 작성. 검증완료/기존 URL은 1건도 덮어쓰지 않고, 검증되지 않은 결과를 확정/재생 가능으로 분류하지 않음.
- 중단/재개: `checkpoints/kr_<recording_id>.json` 등 곡별 저장. 같은 샘플/출력 디렉터리로 다시 실행하면 기록된 100곡은 재조회하지 않음. 샘플 구성이 달라지면 명시적 오류. 공개 서버 장애·429·403에는 circuit breaker 적용.
- 진행률: 실시간 stdout `ODIFY_PILOT_PROGRESS`, 파일 `progress.json`, `summary.json`, `audit_100.csv`, `review_only_proposals.csv`.
- 클라우드 자동 벤치마크: [.github/workflows/odify-altlink-pilot.yml](../.github/workflows/odify-altlink-pilot.yml). 41,416곡 SQLite 백업을 GitHub Actions 아티팩트에서 임시 복원하고 실제 요청 후 100곡 리포트를 다시 아티팩트로 저장.

## 실행

```bash
python scripts/odify_altlink_pilot.py \
  --catalog .local/odify_catalog.sqlite3 \
  --out .local/odify_altlink_pilot --workers 3 --timeout 6 --limit 100

# 검증완료된 MASTER CSV가 있는 환경에서만 추가 정확도 측정
python scripts/odify_altlink_pilot.py \
  --catalog .local/odify_catalog.sqlite3 \
  --master-references MASTER.csv \
  --out .local/odify_altlink_pilot_with_reference
```

### 실측 결과를 읽을 때 주의

2026-10-23 1차 [100곡 실제 네트워크 실행](https://github.com/Tarang1215/Odify/actions/runs/39967230482):

- 정확히 100곡 처리, 지역별 25곡씩
- **Piped 정상 응답 0/100**, 서버 실패 42건·접근/속도 차단 58건. HTTP 요청 시도 142회
- **Invidious 정상 응답 0/100**, 100건 차단, HTTP 요청 시도 100회
- 후보 확보 0, 검증할 제목/아티스트 응답이 없으므로 **매칭 정확도는 측정 불가**
- 서버가 실패하는데도 실제 오디오/URL 성공이라고 표시하지 않는다. 2차 실행부터 요청 실패 시 인스턴스 circuit breaker 적용
- 실제 HTTP 오류는 인스턴스·지역·시간에 따라 달라질 수 있고, GitHub Actions 서버 IP 기반 차단과 개인 Galaxy 환경의 결과는 다를 수 있음.

2차 테스트: maintained 인스턴스 목록 교체 및 403·429 중단, `--out .local/odify_altlink_pilot_v2`. 활성 공개 인스턴스는 언제든 변경될 수 있다.

**프로덕션 도입 기준:** 단순 HTTP 200이 아니라 후보/곡 매칭 비율, 수동 검증 정밀도, API 요청 실패·429 비율, 평균/p95 지연시간을 충족해야 함. 그렇지 않으면 기존 YouTube Data API를 제거하지 않고 검색 프로바이더를 비활성화한다.

Piped API 문서 https://docs.piped.video/docs/api-documentation/
Invidious API 문서 https://docs.invidious.io/api/
Piped 인스턴스 https://github.com/TeamPiped/documentation/blob/main/content/docs/public-instances/index.md
Invidious 인스턴스 https://docs.invidious.io/instances/

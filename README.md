# Odify — SpotiFLAC 기반 Android 음악 앱

SpotiFLAC Mobile의 MIT 라이선스를 준수하는 **고정 업스트림 + Odify 변경사항(overlay)** 구조입니다. 기존 Flutter/Rust/Android 프로젝트를 처음부터 다시 구현하지 않습니다.

- 고정 원본 커밋: `21ee30c46d405aa72dd650d0cc6e3da0df831c12` (2026-10-19)
- 앱 이름: **Odify** / Application ID: `com.odify.music`
- Flutter 내부 패키지명 `spotiflac_android`와 원본 네이티브 패키지 `com.zarz.spotiflac`는 기존 코드 호환을 위해 유지합니다.
- 오디오 엔진, FLAC 다운로드·가사·홈·컬렉션 UI는 **기존 SpotiFLAC 코드**를 사용합니다.
- Actions는 자동으로 실행하지 않습니다.

## 추가된 기능

1. Android MediaStore에서 Samsung Music을 포함한 기기의 **공유 음악 파일 목록**을 조회합니다. 삼성 앱의 비공개 데이터에는 접근하지 않습니다.
2. 홈의 **내 휴대폰 음악**에서 음악을 검색하고 Odify 내장 플레이어로 바로 재생하거나 대기열에 추가합니다.
3. **Odify 라이브러리에 동기화**를 선택하면 음악 파일을 복사하지 않고 `content://` URI로 기존 로컬 라이브러리에 등록합니다. 처음 동의한 이후에는 홈 화면 진입 시 재동기화를 시도하며, 수동 새로고침도 지원합니다.
4. Odify 고유의 ❤️ 좋아요를 저장하고, 최근 60일 청취 기록 및 SpotiFLAC 개인화 학습 모델과 함께 자동재생 점수에 반영합니다.
5. Samsung Music에서 내보낸 M3U/M3U8 재생목록은 원본 SpotiFLAC 가져오기 기능을 이용할 수 있습니다.

## 조립 및 검사

```bash
python3 scripts/check_overlay.py
python3 scripts/assemble.py
cd build/Odify
flutter pub get
flutter analyze
flutter test
```

원본의 **Flutter SDK, Rust, Android SDK/NDK, Java 25** 설정이 필요하며, 현재 여기서는 실행 가능한 APK 빌드를 검증하지 못했습니다.

## 남은 작업

- Android 실컴파일 및 삼성 갤럭시 실기기 재생 테스트
- 삼성 앱만의 좋아요·재생 기록 동기화: 삼성의 비공개 DB라 가져올 수 없음
- YouTube 커버/라이브 검색·스트리밍·MP3 저장 코드는 구현했으나, 실제 기기 재생과 저장 성공 여부는 미검증
- Spotify 스타일 UI 세부 리디자인 및 홈 개인화 추천 피드
- 자동 변경 감지(`ContentObserver`)/백그라운드 간격 동기화
- 실제 Samsung Music 재생목록의 M3U 경로 매칭 검증

## 라이선스

원본 SpotiFLAC Mobile 및 라이선스 고지를 유지하고, 추가 의존성의 라이선스를 별도 확인합니다. MusicStore 인덱싱은 YouTube 로그인이나 Samsung 계정 로그인 없이 동작합니다.

## v0.4 — YouTube 검색·앱 내 재생·MP3 저장 (소스 단계)

- 홈의 **Discover music & covers** 카드를 통해 YouTube 원곡·커버곡·라이브를 검색하며, 공개 재생목록 URL도 열람할 수 있습니다.
- Android 네이티브 `youtubedl-android` (`yt-dlp` 기반)가 메타데이터를 검색하고, 선택한 곡에 대해서만 오디오 URL을 새로 추출합니다.
- 스트림을 **기존 SpotiFLAC `MusicPlayerController` 및 네트워크 플레이어**에 전달하므로 별도 브라우저로 이동하지 않습니다.
- 사용자에게 적법한 이용 권한이 있는 음원은 MP3로 변환해 앱 전용 폴더에 보관하고, 기존 Odify 라이브러리 DB에 인덱싱합니다.
- 기존 `odify_taste` 및 좋아요 추천은 그대로 유지합니다.
- yt-dlp를 포함한 네이티브 라이브러리는 GPL-3.0 라이선스입니다. 배포 시 SpotiFLAC(MIT), yt-dlp, youtubedl-android 및 기타 의존성의 저작권·라이선스 고지 및 소스 공개 의무를 별도 점검해야 합니다.
- 실제 YouTube 스트리밍과 MP3 변환, 네이티브 라이브러리 의존성 빌드는 **Android SDK/Flutter/Rust 환경에서 아직 검증되지 않았습니다**. 로그인 필요 영상, 서명 URL 만료, 오디오 포맷별 재생 오류 가능성이 있습니다. 서비스 약관 및 콘텐츠 권리를 준수하세요.
- `scripts/assemble.py`는 원본 커밋을 고정하여 조립하고, GitHub Actions는 자동 실행하지 않습니다.

## v0.5 — 안정적인 YouTube 재생 대기열 (소스 단계)

- v0.4가 서명된 실제 스트림 URL을 큐에 저장하던 문제를 해결합니다. 이제 Odify 재생목록/세션에는 `odify-youtube://video/<11자 영상 ID>`만 저장됩니다.
- SpotiFLAC의 `_resolveSource`가 재생을 준비할 때 네이티브 yt-dlp로 최신 오디오 주소를 추출하며, 앱이 재실행되더라도 저장된 영상 ID를 다시 해석할 수 있습니다.
- 영상 ID의 대소문자를 보존하기 위해 URI 호스트가 아닌 경로에 보관합니다.
- `python3 -m unittest discover -s tests -v`로 실제 GitHub/Android 빌드 없이 오버레이 조립과 핵심 불변식을 확인할 수 있습니다.
- 실제 컴파일이나 갤럭시에서의 재생 성공은 아직 검증되지 않았습니다.

### 주의
Google/YouTube의 재생 권한·광고·요청 제한이나 MP3 저작권 문제를 해결하는 기능이 아닙니다. 이 프로젝트는 접근 가능한 자료와 이용 권한이 있는 콘텐츠를 대상으로 테스트해야 합니다.

## v0.6 — 취향 기반 Discover 추천 (소스 단계)

- 홈의 **Discover · For You**에 들어가면 좋아요와 최근 60일 청취 통계를 기기 내에서 조회해 최대 5개의 YouTube 검색 시드를 구성합니다. 서버에는 취향 원본 기록을 업로드하지 않고 일반 검색어만 전달합니다.
- 좋아하는 곡의 다른 커버, 선호 아티스트의 노래를 검색해 최대 20곡을 추천합니다. 영상 ID 중복을 제거하고, 너무 짧은 클립·긴 믹스를 제외하며, 단일 업로더가 목록을 독점하지 않도록 최대 3개로 제한합니다.
- 이미 들은 원곡의 중복 추천은 줄이지만 다른 연주자가 부른 **커버곡은 유지**합니다. 추천 이유를 표시하고 추천 결과에서도 좋아요·즉시 재생·MP3 저장을 사용할 수 있습니다.
- 검색 한 번당 최대 12개 결과로 제한하고, 검색을 순차 실행하며, 결과는 최대 20분간 캐시합니다. 좋아요 변경 시 캐시가 무효화됩니다. YouTube의 공식 추천 알고리즘과 동일하다는 의미는 아닙니다.
- 테스트: `overlay/test/odify_discovery_test.dart`에 순수 랭킹 로직 테스트 5개를 추가했습니다. 해당 Dart 테스트와 실제 Android 빌드는 Flutter/Android 빌드 환경에서 아직 실행하지 않았습니다. 로컬 Python 조립 테스트 3개와 정적 불변식 검사는 통과했습니다.
- 다음 단계: 음악 메타데이터 정확도 향상(영상 업로더와 실제 가수 분리), 신곡 발매 시점 검증, 검색 실패 복구·동기화 오류, 전체 APK 컴파일/실기기 검증.

## v0.7 — 아티스트 메타데이터 및 통합 CI

- YouTube 영상 **업로더(채널)** 와 음악 **아티스트** 정보를 별도 필드로 유지합니다.
- 확인 가능한 음악 아티스트 메타데이터를 우선하고, 공식 아티스트 채널의 제목에 포함된 "가수 - 곡명" 구조만 제한적으로 정리합니다.
- 커버곡은 원곡 가수가 아닌 **커버를 업로드한 공연자**를 우선 표시합니다.
- 원본 영상 제목도 보존하여 메타데이터 오판 시 추적할 수 있습니다.
- 5개의 Flutter 메타데이터 회귀 테스트를 추가했습니다.
- `.github/workflows/odify-verify.yml`: 원본 고정 커밋 조립 → Python 회귀 테스트 → Flutter 분석·추천 테스트 → Java/Rust/Android SDK 기반 debug APK 컴파일. 일반 소스코드 커밋에는 자동 실행되지 않습니다. GitHub Actions 탭에서 `Run workflow`로 수동 실행하거나 `.github/ci-trigger` 변경 시 실행합니다.
- GitHub Actions 1차에서 **테스트 변수명 불일치**를 확인해 수정했습니다. 2차에서는 Flutter 분석·추천 테스트까지 통과했으며, 네이티브 APK 결과는 별도 확인이 필요합니다.

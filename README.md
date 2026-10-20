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

## 2026-10-20 UI 진입점 수정: Discover 카드가 보이지 않던 문제

첫 설치 APK에서 Odify 카드가 전혀 보이지 않고, 원본 검색 탭에 `검색 제공자가 없음` 메시지가 표시되는 문제가 확인됐다.

**원인:** Odify 카드가 SpotiFLAC의 `_HomeSections` 안에 주입되어 있었고, 해당 섹션은 빈 홈/검색 제공자 등의 화면 상태에 따라 통째로 숨겨질 수 있었다.

**수정:** 검색 제공자와 관계없이 렌더링되는 `HomeTab`의 상위 `CustomScrollView.slivers`에 `OdifyQuickAccess`를 직접 추가했다. 홈에는 Discover + 휴대폰 음악을, 별도 검색 탭에는 Discover를 먼저 표시한다. 기존 SpotiFLAC 검색 제공자 오류는 기존 검색 기능용 안내이며, **Odify Discover는 그 확장 기능 없이 별도 화면으로 실행**한다.

**검증:** `tests/test_assembly.py`에 조건부 섹션에서 벗어났는지 검사하는 회귀 테스트를 넣고, `overlay/test/odify_quick_access_test.dart`에 플러그인 없이 Discover 진입 카드가 그려지는지 확인하는 Flutter 위젯 테스트를 넣었다. CI 통과 및 실기기 동작은 별도 확인이 필요하다.

**APK 교체 주의:** GitHub-hosted runner의 debug signing key가 빌드마다 달라질 수 있어 기존 debug APK 위에 설치가 거부될 수 있다. 기존 앱을 지우면 앱 내부 좋아요/기록 등 로컬 데이터도 삭제될 수 있으므로, 지우기 전 필요한 데이터를 보존할 것. 공식 릴리스용 서명/업데이트 설계는 후속 과제다.


## v0.8 — Galaxy Library 표시 수정 + Windows 우선 크로스디바이스 기반

사용자 실기기 테스트에서 **휴대폰 음악 브라우저에는 곡이 나타나지만 통합 Library에는 보이지 않는** 문제가 보고됐다. 원본 설정의 `localLibraryEnabled=false` 기본값과 가상 `legacy://local-library` 소스의 가용성 판정을 수정했다. 동기화 후 라이브러리 목록을 갱신하고 인덱스 곡 수를 홈에 표시한다. 홈마다 전체 MediaStore를 재스캔하지 않도록 기본 자동 검사 간격을 6시간으로 제한했다. **새 APK에서 실기기 확인이 필요하다.**

Google Drive 동기화 공통 엔진의 기반을 `lib/services/odify_sync/sync_merge.dart`에 추가했다. 이 단계는 **순수 Dart 오프라인 이벤트 병합**으로 Google 로그인/Drive 파일 동기화가 아직 붙지 않았다. 재생목록 동시 추가·이름/곡 순서 변경·삭제와 좋아요 충돌·반복 업로드 중복 처리에 대한 테스트가 있다.

**플랫폼 구현 순서**: 갤럭시 Android → Windows 11 PC → iPad → Mac. iPad는 IPA를 7일마다 갱신해야 하는 무료 개발자 서명을 피하도록 PWA(홈 화면 설치형 웹앱)를 먼저 검토하되, 백그라운드 재생 및 플랫폼별 스트림·로컬 파일 접근은 보장할 수 없다. 일반 iPadOS에서 임의 IPA를 APK처럼 영구 설치할 수 있는 범용 무료 방법은 없다. Apple App Store에 배포할 경우 제3자 미디어 다운로드 권한과 정책을 별도로 확인해야 한다.

자세한 내용은 [Google Drive 동기화 설계](docs/DRIVE_SYNC_ARCHITECTURE.md)를 참조한다. Trove/Supabase와는 완전히 독립된다.


## v0.9 — 재생 대기열과 Google Drive 동기화 기반 (APK 미배포)

**플레이어 이전 곡/다음 곡:** 홈 '내 휴대폰 음악'과 Odify Discover에서 `playSingle()`로 노래 하나만 대기열에 넣던 문제를 수정했다. 현재 검색/표시 목록을 `playAll(items, initialIndex: selected)`로 전달하여 플레이어에서 이전/다음 곡으로 이동하도록 구성했다. 휴대폰 브라우저는 현재 불러온 목록에 한해 대기열을 만든다. 실기기 검증은 추후 APK 빌드 때 진행한다.

**오프라인 동기화 엔진:** `lib/services/odify_sync/`에 HLC 변경 시계, 로컬 SQLite 이벤트 로그 및 업로드 대기열, 원격 변경 병합, 업로드 재시도, 메모리 테스트 전송 계층을 추가했다. 순수 Dart 구조로 갤럭시 → Windows → iPad → Mac 개발순서에서 재사용한다.

**Drive API 어댑터:** `drive_sync_transport.dart`는 Google Drive `appDataFolder` 숨김 파일을 변경 토큰으로 불러오며 수정 기록을 불변 배치로 기록한다. `drive.appdata`로 승인받은 짧은 액세스 토큰은 OS별 Google OAuth 계층에서 전달받아야 한다. **실제 OAuth 로그인 UI와 Drive 계정 연결은 아직 구현되지 않았다.** Trove/Supabase와 무관하다.

**배포 방침:** 이번 변경에는 Android APK 빌드나 APK 업로드를 하지 않는다. 경량 Dart 분석 및 모의 Drive 테스트만 수행한다. 이전 APK를 설치한 상태에서는 아직 이전/다음 곡 수정과 Drive 기능이 반영되지 않았다.


## v1.0 — 선택형 Google Drive 계정 연결·동기화 관리 UI (APK 미배포)

`Odify → 동기화 및 백업` 진입 버튼을 홈과 검색에 추가했다. `google_sign_in ^7.2.0` 기반 Android OAuth 어댑터는 **사용자가 직접 동기화 연결 버튼을 누른 경우에만** Google Drive `drive.appdata` 권한을 요청한다. 백그라운드에서 임의로 로그인 화면을 띄우지 않으며, 로그인하지 않아도 음악 플레이어를 사용할 수 있다.

계정마다 SHA-256 기반 서로 다른 SQLite 파일에 변경 기록과 Drive 커서를 저장하여 Google 계정 전환 시 데이터가 뒤섞이는 것을 방지했다. 재생목록 **생성/이름 변경/삭제**, 수동 동기화, Drive 권한 갱신, 연결 해제 버튼을 구현했다. 비밀번호·토큰은 동기화 DB에 저장하지 않는다.

**현재 단계의 제한:** Google Cloud의 Odify 전용 프로젝트/Android OAuth 패키지 및 서명 SHA-1/Web OAuth 클라이언트 ID를 아직 연결하지 않았으므로 일반 APK에서는 로그인 버튼을 비활성화한다. 또한 **기존 SpotiFLAC 재생목록과 좋아요 자동 이관, 곡 추가·순서 변경**은 미완성이다. 여기서 구현된 것은 Odify 동기화 전용 재생목록 이름·변경 기록의 로컬/클라우드 연결 기반이다. 실제 Google Drive 계정에서의 로그인·복원과 다기기 통합 테스트는 아직 수행하지 않았다.

[OAuth 및 고정 APK 서명 설정 방법](docs/GOOGLE_DRIVE_OAUTH_SETUP.md)

[2026-10-20 Dart 전용 정적 분석 및 테스트 성공](https://github.com/Tarang1215/Odify/actions/runs/39404410457) — **Rust/Android 컴파일·APK 재배포 없음**. 갤럭시 기기에는 마지막으로 설치한 이전 APK가 그대로 남아 있다.


## v1.0 — 홈 인기 음악 탐색 및 추천 미리보기 (미배포)

- 홈에서 **오늘의 음악 탐색** (한국/일본/글로벌) 및 **나를 위한 추천**을 별도 화면에 들어가지 않아도 미리 볼 수 있게 변경했다. 각 최대 4곡 노출, 곡 선택 시 전체 목록 대기열 재생. 하트 버튼으로 좋아요를 저장하면 3초 디바운스 후 취향 추천을 갱신한다.
- **주의:** 인기 탐색은 YouTube 검색 결과 샘플이다. YouTube 공식 실시간 Trending 순위나 국내 음원 차트의 순위라고 주장하지 않는다.
- 최초 로딩의 네이티브 검색은 첫 화면 그리기 900ms 이후로 지연한다. 홈 기본 검색은 인기 탐색 1회와 취향 기반 검색 최대 1회만 순차 실행하며, 결과는 30분 메모리 캐시를 활용한다.
- 듣기/좋아요가 하나도 없는 신규 사용자는 빈 추천 목록 대신 음악 탐색 결과를 취향 학습 전 예시로 표시한다. Discover 화면에는 사용법 안내와 홈 지역별 검색 더 보기 연결을 추가했다.
- MediaStore 자동 스캔은 홈 시작 12초 뒤로 미루고, 자동 재스캔 주기를 24시간으로 조정했다. Riverpod은 필요한 로컬 라이브러리 요약 필드 변경 때만 홈 카드를 갱신한다.
- 앞서 배포된 APK는 **debug 빌드**다. 사용자가 느끼는 버벅임을 실제 최적화 문제로 판정하려면 향후 안정적인 서명 키를 사용해 **profile/release** 앱으로 Flutter 프레임 성능, yt-dlp 초기화 비용, MediaStore 스캔을 실기기 측정해야 한다.
- 소스 및 경량 Flutter 회귀 테스트만 수정했다. **Android APK 전체 빌드/배포는 이번 변경에서 수행하지 않았다.** 현재 설치된 APK에는 포함되지 않는다.

상세 작업 내역: [Home/Discover 성능 설계](docs/HOME_PERFORMANCE_DISCOVERY.md).

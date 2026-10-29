# Odify iOS / LiveContainer 빌드

사용자가 2026-10-29 공개 저장소의 클라우드 Mac 빌드와 IPA 결과 보관을 명시적으로 승인했다. Apple 계정·서명 인증서·개인 음악 파일은 빌드에 사용하거나 업로드하지 않는다. SideStore/LiveContainer의 기존 사용자 서명 설정을 이용하는 **unsigned Release AOT IPA**를 준비한다.

## 빌드 범위

- iOS 번들 ID: `com.odify.music.ios`, 버전 1.0.0 (1), iPhone/iPad arm64, 최소 iOS 16.
- 기존 고정 Flutter/iOS 공통 앱을 재사용한다. Mornye 5.2.1 원본 `8e4504e72ddcda6bdf6df5e1275396f46e9dfb9f`, Odify 보관 스냅샷 `191fd35e1d9cf147a54312453c39e717408d0454` 및 기존 라이선스/저작권 고지를 유지한다.
- **Android native preview12.69의 Kotlin/Compose 화면을 변환한 앱이 아니다.** 최근 Android 전용 UI, Google 계정 Drive 직접 동기화, Android yt-dlp/MediaStore 연결, 삼성 상태바·위젯 개선은 포함되지 않는다. Android 전용 홈 진입점은 iOS에 노출하지 않는다.
- 기존 iOS 파일/폴더 음악 라이브러리와 재생 엔진을 사용한다. Drive 음악은 iOS ‘파일’ 앱의 Google Drive 제공처에서 가져오는 경로로 안내한다. iOS 계정 직접 동기화는 별도 이식/설정이 필요하다.
- 기존 Android Odify의 실제 음표 아이콘을 재사용한다. 앱 이름과 번들 식별자, 파일 공유, 백그라운드 오디오 설정을 iOS에 맞춘다.

## 생성·검증

`Odify iPhone release IPA` Actions가 macOS 26에서 고정 Flutter 3.47.7 / Rust 1.99.0 및 Xcode로 실제 기기용 릴리스 앱을 만든다. `--no-codesign`으로 Apple 로그인 없이 빌드한다. 배포 단계에서 LiveContainer에 불필요한 WidgetKit 확장을 제외하므로 이 IPA 자체의 iOS 홈 위젯은 제공하지 않는다.

검증기는 Runner, Flutter 엔진 및 AOT App.framework가 실제로 존재하는지, Mach-O가 iOS 기기용 arm64인지, Payload 구조와 실행 권한·해시가 정상인지 확인한다. 시뮬레이터 앱을 이름만 IPA로 바꿔 전달하지 않는다.

현재 로컬 Dart 정적 검사는 컴파일 오류 없이 완료됐고 기존 코드의 스타일 정보 26개가 남아 있다. 실제 IPA 빌드와 결과는 아직 진행 중이며, 성공 후 여기에 기록한다. LiveContainer/iPhone·iPad 실기기에서 직접 실행·재생한 것으로 보고하지 않는다.

## 설치

빌드가 성공하면 `Odify-iOS-IPA` artifact ZIP 안의 `.ipa` 파일을 iPhone/iPad ‘파일’ 앱에 저장한다. LiveContainer의 앱 추가(+)에서 IPA를 선택한다. 최신 iOS에서는 LiveContainer의 현재 인증서/JIT-less 설정이 정상이어야 하며, 사용자 Apple 로그인은 사용자 SideStore 안에서만 처리한다. 개발자 프로그램 유료 가입이나 계정 정보를 이 저장소에 제공할 필요가 없다.

파일 선택창에 문제가 있으면 공식 LiveContainer 안내의 앱별 `Fix File Picker` 설정을 확인한다. 백그라운드 재생·권한·앱 실행은 컨테이너 상태에도 영향을 받으므로 설치 후 실제 기기에서 확인해야 한다.

공식 안내: [LiveContainer](https://github.com/LiveContainer/LiveContainer), [SideStore 설치·갱신](https://docs.sidestore.io/docs/installation/install).

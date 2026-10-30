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

로컬 Dart 정적 검사는 컴파일 오류 없이 완료됐고 기존 코드의 스타일 정보 26개가 남아 있다. 로컬 Flutter 검사 26개와 패키징/식별자 회귀 검사 5개를 통과했다. 클라우드에서도 해당 검사를 거쳐 iOS 네이티브 엔진, Xcode 기기용 Release 앱, Mach-O/AOT/IPA 검증 및 artifact 업로드가 모두 완료됐다. LiveContainer/iPhone·iPad 실기기에서 직접 실행·재생한 것으로 보고하지 않는다.

## 완성된 IPA

- 앱 소스: `6746d09318e2ec37b2f52676b4edf31e95fe5a1c`.
- [클라우드 Mac 빌드](https://github.com/Tarang1215/Odify/actions/runs/40817895796), Xcode 26.6 (17F113), macOS 26.
- [IPA 다운로드 — artifact ZIP](https://github.com/Tarang1215/Odify/actions/runs/40817895796/artifacts/12874421366). GitHub 로그인 후 ZIP을 받아 압축을 풀면 `Odify-iOS-1.0.0.ipa`가 있다. 보관 기한은 2026-11-12다.
- IPA 크기: **32,530,421 bytes**, SHA256 `b3a5ad3fa059366aaae7fc6e0566bf0e59e3bebc5a6196f8b2b37521d3950eb2`.
- artifact ZIP SHA256: `d613b7aeef6c9f6d607db3cf51c82b8d59b5606bd0a84667ca52ed6e5bcae02e`.
- 실제 다운로드한 파일도 독립 검증했다. Runner, App.framework, Flutter.framework의 thin/fat Mach-O를 읽어 iOS 기기 플랫폼과 arm64를 확인했다. IPA의 CRC/체크섬, iPhone/iPad device family `[1,2]`, 최소 OS 16.0, 실행 권한, 원본 MIT 라이선스 포함, 확장 0개, embedded.mobileprovision 미포함을 확인했다.
- 완성 IPA는 서명 전 릴리스 파일이며, 사용자의 기존 LiveContainer 인증서 설정으로 가져와 서명/실행한다. 코드 또는 개인정보를 새로 제공할 필요는 없다.

## 설치

빌드가 성공하면 `Odify-iOS-IPA` artifact ZIP 안의 `.ipa` 파일을 iPhone/iPad ‘파일’ 앱에 저장한다. LiveContainer의 앱 추가(+)에서 IPA를 선택한다. 최신 iOS에서는 LiveContainer의 현재 인증서/JIT-less 설정이 정상이어야 하며, 사용자 Apple 로그인은 사용자 SideStore 안에서만 처리한다. 개발자 프로그램 유료 가입이나 계정 정보를 이 저장소에 제공할 필요가 없다.

파일 선택창에 문제가 있으면 공식 LiveContainer 안내의 앱별 `Fix File Picker` 설정을 확인한다. 백그라운드 재생·권한·앱 실행은 컨테이너 상태에도 영향을 받으므로 설치 후 실제 기기에서 확인해야 한다.

공식 안내: [LiveContainer](https://github.com/LiveContainer/LiveContainer), [SideStore 설치·갱신](https://docs.sidestore.io/docs/installation/install).

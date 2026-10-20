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
- YouTube 커버/라이브 검색, 합법적으로 접근 가능한 콘텐츠의 앱 내 스트리밍/저장 연동
- Spotify 스타일 UI 세부 리디자인 및 홈 개인화 추천 피드
- 자동 변경 감지(`ContentObserver`)/백그라운드 간격 동기화
- 실제 Samsung Music 재생목록의 M3U 경로 매칭 검증

## 라이선스

원본 SpotiFLAC Mobile 및 라이선스 고지를 유지하고, 추가 의존성의 라이선스를 별도 확인합니다. MusicStore 인덱싱은 YouTube 로그인이나 Samsung 계정 로그인 없이 동작합니다.

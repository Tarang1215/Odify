# Odify — Galaxy S25 Ultra 출시 전 실행 검증

## 최신 작업 브랜치

- 코드: [PR #1](https://github.com/Tarang1215/Odify/pull/1) (`fix/startup-drive-ui-20261021`)
- 자동 검사: [GitHub Actions](https://github.com/Tarang1215/Odify/actions/workflows/odify-verify.yml)
- 실제 기기에서 30초 이상 실행되는지 확인하지 않고 '실행 오류 해결 완료'라고 표현하지 않습니다.
- Flutter Debug APK는 JIT `kernel_blob.bin`을, Release APK는 AOT `libapp.so`를 포함합니다. 서로 다른 파일이므로 각 빌드 방식에 맞게 검증합니다.
- 갤럭시 사전 테스트는 **독립 Debug 패키지 `com.odify.music.debug`** 를 우선 권장합니다. 기존 Release `com.odify.music` 앱과 데이터는 삭제하지 마세요.
- Google 로그인은 기본 빌드에서 비활성화되어 있을 수 있습니다. Odify Google Cloud 프로젝트와 해당 APK 서명 SHA-1 등록 및 공개 Web client ID 설정이 필요합니다.
- PR 작업에서 YouTube 재생목록 항목(영상 ID, 곡명, 아티스트) 추가·제거 및 재생 경로를 구현했지만 실제 Google 계정·기기간 재생 테스트는 별도로 필요합니다.

## 설치

1. GitHub 로그인 후 위 아티팩트 링크에서 ZIP을 다운로드하고 압축 해제한다.
2. APK가 여러 개면 **`arm64-v8a`** 포함 파일을 Galaxy S25 Ultra에서 우선 선택한다. 해당 파일이 없다면 `universal` 파일을 선택한다. 디버그 APK이므로 Play Store 앱은 아니다.
3. 휴대전화에 APK를 복사해 열고, Android의 해당 파일 출처에 대한 **알 수 없는 앱 설치** 승인을 진행한다. 설치 완료 후에는 해당 출처의 허용 설정을 다시 끄는 것을 권장한다.
4. **테스트용 Debug APK**는 `com.odify.music.debug`, Release APK는 `com.odify.music`입니다. 서로 저장소가 분리되므로 기존 Odify 데이터를 지우지 말고 먼저 Debug로 테스트합니다. Samsung Music을 삭제할 필요도 없습니다.

## 기능별 확인 순서

- [ ] 앱 첫 실행 후 30초 이상 종료되지 않음, 홈 화면 표시 및 다크 글래스 UI 확인
- [ ] **Your phone music**에서 권한 승인 후 삼성 뮤직과 공유되는 MP3/FLAC 검색
- [ ] 음악 1곡을 Odify 플레이어에서 재생·일시정지·다음 곡 이동
- [ ] **내 라이브러리에 동기화** 후 중복 곡 발생 여부 확인
- [ ] 화면 잠금 후 백그라운드 재생/미디어 알림 확인
- [ ] **Discover · For You** 진입 후 곡/아티스트/커버 검색
- [ ] 검색한 곡 온라인 재생, 2번째 곡으로 전환 후 다시 1번째 곡 재생
- [ ] 이용 권한이 있는 음원의 MP3 저장 및 오프라인 재생 확인
- [ ] 좋아요한 노래 반영 및 추천 새로고침 확인
- [ ] 원곡과 커버곡의 이름/아티스트/채널 표시 확인
- [ ] Wi-Fi를 끈 상태에서 로컬 음악 재생 및 네트워크 음악 오류 문구 확인
- [ ] Google OAuth 설정된 빌드에서 Drive 계정 연결 → 재생목록 만들기 → YouTube 영상 URL/ID 입력 → 동기화 → 재설치가 아닌 두 번째 기기에서 목록·음원 ID 복원 확인
- [ ] 동일 재생목록에서 곡 재생·삭제·재동기화 후 다른 기기에도 일치하는지 확인

## 문제 발생 시

오류를 기록할 때 **어떤 기능에서, 어떤 곡에서, 어떤 버튼을 누른 직후** 오류가 발생했는지 적어두면 원인 분석이 빨라진다. 가능하면 스크린샷, 설치한 APK 파일명, Android 버전을 함께 기록한다.

USB 디버깅이 가능한 Windows PC에서 문제가 재현된다면 다음 명령으로 앱의 로그를 수집할 수 있다. 로그를 공유하기 전에는 토큰·계정 정보·개인 경로 등 민감한 문자열을 확인하고 삭제한다.

```powershell
adb logcat -c
# Odify 앱에서 문제를 재현한다.
adb logcat -d -v threadtime > odify-log.txt
```

## 유의사항

- GitHub Actions 컴파일 성공은 YouTube의 실시간 스트리밍 성공을 보증하지 않는다.
- yt-dlp/ffmpeg 네이티브 라이브러리는 용량이 크고, 일부 URL·지역·로그인 필요 콘텐츠는 재생되지 않을 수 있다.
- Samsung Music의 개인 앱 내부 재생목록·좋아요는 자동으로 읽을 수 없다.
- 공개 배포/재배포 시 SpotiFLAC, yt-dlp, ffmpeg 및 기타 의존성 라이선스와 콘텐츠 권리를 별도로 점검한다.

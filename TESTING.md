# Odify — Galaxy S25 Ultra 첫 실기기 테스트

## 검증된 빌드

- **GitHub Actions 실행:** https://github.com/Tarang1215/Odify/actions/runs/39383245694
- **APK 다운로드:** https://github.com/Tarang1215/Odify/actions/runs/39383245694/artifacts/12246409217
- **아티팩트:** `odify-debug-arm64` (ZIP; GitHub 표시 크기 약 636 MB)
- **소스 커밋:** `6bb1ce10509b46ff85a0d1b6640e1704819f057`
- **Flutter 분석 / 추천 테스트 / Gradle :app:assembleDebug / 아티팩트 업로드:** 통과
- **실기기 동작:** 아직 검증 전
- **아티팩트 만료 예정:** 2026-11-03 (GitHub 14일 보관)

## 설치

1. GitHub 로그인 후 위 아티팩트 링크에서 ZIP을 다운로드하고 압축 해제한다.
2. APK가 여러 개면 **`arm64-v8a`** 포함 파일을 Galaxy S25 Ultra에서 우선 선택한다. 해당 파일이 없다면 `universal` 파일을 선택한다. 디버그 APK이므로 Play Store 앱은 아니다.
3. 휴대전화에 APK를 복사해 열고, Android의 해당 파일 출처에 대한 **알 수 없는 앱 설치** 승인을 진행한다. 설치 완료 후에는 해당 출처의 허용 설정을 다시 끄는 것을 권장한다.
4. 설치 시에는 기존 Samsung Music 앱을 제거할 필요가 없다. Android 패키지는 `com.odify.music`으로 독립되어 있다.

## 기능별 확인 순서

- [ ] 앱 첫 실행, 홈 화면 표시, 시스템 다크 모드에서 UI 확인
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

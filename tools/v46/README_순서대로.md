# Odify V4.6 — Toshiba D: 기반 PC 검증·외장하드·Drive 업로드 준비

**2026-10-24 프로젝트 통합:** 아래 내용은 원본 도구 사용법입니다. 실제 수행 결과,
자동 재개·오매칭 판정·검증 복사·선택형 Drive 전송은
[`../../docs/V46_PROGRESS.md`](../../docs/V46_PROGRESS.md)를 먼저 확인하세요.
새 `run_v46.ps1`, `odify_review.py`, `odify_library.py`가 추가되었습니다.

이미 `D:\Odify\Music`, `Incoming`, `Metadata`, `Logs`, `Backups` 폴더를 만드셨다면 **다시 만들거나 포맷할 필요는 없습니다.** 이 도구는 그 폴더를 그대로 사용하고 `DriveUpload` 폴더만 추가할 수 있습니다.

## 지금 할 일 (첫 번째)

1. 압축을 **통째로** 풀고 이 폴더의 `03_check_D_drive.cmd`를 실행하세요. `D:\Odify\Logs\odify_storage_inventory.csv`가 생성됩니다. 외장하드 용량·음악 파일을 확인할 뿐, 음악 파일을 수정하지 않습니다.
2. `01_probe_12.cmd` 실행: KR 4/JP 4/US 4의 실제 Odify 수집 YouTube URL을 PC에서 `yt-dlp`로 검사합니다. **로그인 없이, 오디오 다운로드 없이** 제목·업로더·길이·오디오 포맷을 확인합니다. 결과는 `D:\Odify\Logs\odify_probe_12_results.csv`.
3. `02_probe_10_pairs.cmd` 실행: V4.4에서 논란이 된 5곡의 변경 전후 후보 10개를 검사합니다. 결과는 `D:\Odify\Logs\odify_probe_pairs_results.csv`.
4. `08_probe_summary.cmd` 실행: 결과 요약. **위 두 CSV를 이 대화에 업로드**하면 검색 URL의 오매칭 및 PC에서의 추출 가능성을 근거로 다음 매칭 기준을 개선할 수 있습니다.

이전 V4.5의 CSV를 이미 테스트하셨더라도 새 V4.6의 결과 저장 위치는 다릅니다. 이전 파일이 있으면 먼저 알려주셔도 됩니다. **V4.6은 매 URL마다 기록을 저장하고, 다시 실행하면 성공/실패를 포함한 기존 검사 결과를 기본적으로 재사용**합니다. 실패했던 URL만 다시 검사하려면 예: `powershell -NoProfile -ExecutionPolicy Bypass -File .\odify_pc_probe_v46.ps1 -InputCsv .\Reference\odify_pc_probe_12.csv -ResultCsv D:\Odify\Logs\odify_probe_12_results.csv -Limit 12 -RetryFailures`.

> `audio_formats_present`는 `yt-dlp`가 스트림 포맷을 확인했다는 뜻이고, **정확한 원곡·실제 다운로드·재생권한을 검증했다는 뜻이 아닙니다.** 자동으로 링크를 `verified`로 바꾸지 않습니다. 쿠키와 비밀번호를 요구하거나 업로드하지 않습니다.

## 기존 PC 음악을 외장하드로 가져오기

- `07_existing_music_preview.cmd`를 실행하면 기존 `YouTube_MP3` 폴더를 찾아 **복사 미리보기만** 보여 줍니다. 파일을 복사하거나 PC에서 삭제하지 않습니다.
- 미리보기 로그를 확인한 뒤, 외장하드 `D:\Odify\Music`에 **실제 복사**하고 싶다면, *먼저* 원본 폴더 경로를 확인하고 PowerShell에서 다음처럼 실행하세요.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\odify_archive_copy.ps1 -SourceDir "$env:USERPROFILE\Desktop\YouTube_MP3" -ArchiveDir "D:\Odify\Music" -Execute
```

실행할 때 경로가 다른 PC는 `-SourceDir`만 실제 경로로 바꾸세요. 파일을 상대 폴더 구조 그대로 보관하며 **SHA-256으로 원본/복사본 일치 확인**, 동일 이름의 다른 파일 충돌 시 **덮어쓰기 금지**, 원본 **삭제 금지**입니다. 처음부터 다운받을 파일은 `D:\Odify\Incoming`에 두고 메타데이터를 확인한 뒤 `Music`으로 옮기는 방식이 좋습니다.

## Drive 동기화 대상 선정 (나중에 사용)

1. `04_make_drive_selection.cmd` 실행 → `D:\Odify\Logs\drive_selection.csv` 생성. **기본값은 모든 곡 `selected=NO`**.
2. Drive에 둘 곡의 행에서 `selected`를 `YES`로 바꿔 저장합니다. (엑셀 편집 후 CSV UTF-8로 저장 권장)
3. `05_preview_drive_stage.cmd` 실행 → 어떤 파일이 준비되는지 확인(복사 없음).
4. `06_execute_drive_stage.cmd` 실행 → `YES`를 직접 입력한 경우에만 최대 200곡을 `D:\Odify\DriveUpload`로 SHA-256 검증 복사합니다. 기존 파일 충돌 시 덮어쓰지 않습니다.
5. **`DriveUpload`는 외장하드에 있는 로컬 폴더입니다. Google Drive로 자동 업로드되지 않습니다.** Google Drive 웹/데스크톱 앱에서 원하는 파일을 직접 업로드한 뒤 Drive 상에서 업로드 완료와 재생 가능 여부를 확인하세요.

전체 4만 곡을 Drive에 올리는 방식보다는, **외장하드 전체 원본 + Drive에 즐겨 듣는 곡의 별도 사본**을 권합니다. Google Drive의 저장 공간 사용량·계정 정책 및 파일 접근 권한도 고려해야 합니다.

## 현재 개발 단계

- Piped/Invidious 수집 URL과 `yt-dlp` 추출은 서로 다릅니다. 사용자의 Windows PC에서 AKMU 한 곡 다운로드·재생은 성공했지만, **이번 12+10개는 사용자가 위 검증을 직접 실행한 뒤 결과가 확인**됩니다.
- Odify 41,416곡 카탈로그 / 1,000곡 수집 후보 자체를 변경하지 않습니다. 별도의 `Reference/review_risk_1000.csv`에 위험 후보를 보관합니다.
- Google MASTER의 기존 검증 링크, Drive 데이터, Android APK는 **아무것도 수정하지 않습니다.** PC 검사 결과는 단순한 관찰값이며 수동 원곡 검증과 구별됩니다.
- Toshiba exFAT 볼륨에서는 unplug 전 Windows의 **안전하게 제거**를 사용하세요. 단일 외장하드만 보유한 자료는 백업이 아닙니다.

## 파일별 기능

`01/02` PC 링크 검증 / `03` 외장하드 상태 / `04` Drive 대상 목록 / `05` Drive 준비 미리보기 / `06` Drive 스테이징 복사 / `07` 기존 파일 복사 미리보기 / `08` PC 테스트 요약. 모든 `cmd`는 Windows 11에서 더블클릭용입니다. PowerShell 스크립트는 하위 폴더의 `Reference` CSV를 필요로 하므로 ZIP 내부의 폴더 구조를 보존하세요.

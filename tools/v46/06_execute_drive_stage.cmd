@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo This copies selected songs to D:\Odify\DriveUpload. It does NOT upload to Google Drive.
set /p "ACCEPT=Type YES to confirm disk copy: "
if not "%ACCEPT%"=="YES" (
 echo Cancelled.
 pause
 exit /b 0
)
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0odify_drive_stage.ps1" -Root "D:\Odify" -Execute
if errorlevel 1 echo Staging failed; check logs, nothing will be deleted.
pause

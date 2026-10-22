@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0설치_준비.ps1"
set "ODIFY_EXIT=%ERRORLEVEL%"
if not "%ODIFY_EXIT%"=="0" echo Setup did not finish. Read the message above.
pause
exit /b %ODIFY_EXIT%

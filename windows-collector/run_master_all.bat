@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"
echo Odify MASTER - all missing tracks, resumable
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run_collector.ps1"
set "ODIFY_EXIT=%ERRORLEVEL%"
if not "%ODIFY_EXIT%"=="0" echo Check Music\Odify_Library\MASTER\master_status.csv for pending items.
pause
exit /b %ODIFY_EXIT%

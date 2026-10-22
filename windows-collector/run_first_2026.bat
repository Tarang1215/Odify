@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run_collector.ps1"
set "ODIFY_EXIT=%ERRORLEVEL%"
if not "%ODIFY_EXIT%"=="0" echo Some tracks need review. See the CSV and JSON report.
pause
exit /b %ODIFY_EXIT%

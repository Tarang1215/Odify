@echo off
chcp 65001 >nul
cd /d "%~dp0"
if not exist "D:\Odify\Logs" (
  echo Toshiba D:\Odify\Logs not found. Connect drive D: and retry.
  pause
  exit /b 1
)
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0odify_pc_probe_v46.ps1" -InputCsv "%~dp0Reference\odify_false_match_pairs.csv" -ResultCsv "D:\Odify\Logs\odify_probe_pairs_results.csv" -Limit 10
if errorlevel 1 echo Error: check yt-dlp, CSV and D:.
echo Results: D:\Odify\Logs\odify_probe_pairs_results.csv
pause

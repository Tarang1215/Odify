@echo off
chcp 65001 >nul
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0odify_drive_selection.ps1" -Root "D:\Odify"
if errorlevel 1 echo Drive selection generation failed. Run 03 first.
echo Edit D:\Odify\Logs\drive_selection.csv and set selected=YES for favorites.
pause

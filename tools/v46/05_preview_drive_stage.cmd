@echo off
chcp 65001 >nul
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0odify_drive_stage.ps1" -Root "D:\Odify"
if errorlevel 1 echo Preview failed. Run 04 first and edit the selection CSV.
pause

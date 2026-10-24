@echo off
chcp 65001 >nul
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0odify_storage_check.ps1" -Root "D:\Odify"
if errorlevel 1 echo External drive may be disconnected or changed letter.
pause

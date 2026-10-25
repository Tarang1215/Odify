@echo off
chcp 65001 >nul
cd /d "%~dp0"
set "SOURCE=%USERPROFILE%\Desktop\YouTube_MP3"
if not exist "%SOURCE%" set "SOURCE=%USERPROFILE%\OneDrive\Desktop\YouTube_MP3"
if not exist "%SOURCE%" (
 echo Default music directory was not found.
 set /p "SOURCE=Enter full path to existing local music directory: "
)
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0odify_archive_copy.ps1" -SourceDir "%SOURCE%" -ArchiveDir "D:\Odify\Music"
if errorlevel 1 echo Preview failed. Ensure source and Toshiba D: are connected.
echo Dry run only. To copy, run the command with -Execute after reviewing the report.
pause

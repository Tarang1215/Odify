param([string]$OAuthClientJson, [switch]$CheckOnly)
$ErrorActionPreference = 'Stop'
$wingetLinks = Join-Path $env:LOCALAPPDATA 'Microsoft/WinGet/Links'
$env:PATH = "$wingetLinks;$env:PATH"

function Require-Program([string]$Binary, [string]$Package) {
    if (Get-Command $Binary -ErrorAction SilentlyContinue) { return }
    if ($CheckOnly) { throw "$Binary is missing." }
    & winget install --id $Package --exact --silent --disable-interactivity
    if ($LASTEXITCODE -ne 0) { throw "$Package installation failed or needs an agreement. Complete its installer once, then rerun." }
    $env:PATH = "$wingetLinks;" + [Environment]::GetEnvironmentVariable('Path', 'User') + ';' + $env:PATH
    if (-not (Get-Command $Binary -ErrorAction SilentlyContinue)) { throw "$Binary is not available after installation." }
}

Require-Program 'py' 'Python.Python.3.12'
Require-Program 'ffmpeg' 'Gyan.FFmpeg'
Require-Program 'deno' 'DenoLand.Deno'
Require-Program 'rclone' 'Rclone.Rclone'
& py -c "import sys; assert sys.version_info >= (3,11); import yt_dlp, mutagen, PIL"
if ($LASTEXITCODE -ne 0) {
    if ($CheckOnly) { throw 'Python 3.11+ and yt-dlp/mutagen/Pillow are required.' }
    & py -m pip install 'yt-dlp[default]' mutagen pillow
    if ($LASTEXITCODE -ne 0) { throw 'Python package installation failed.' }
}
$pythonScripts = (& py -c "import sysconfig; print(sysconfig.get_path('scripts'))") -join ''
if ($LASTEXITCODE -ne 0) { throw 'Could not locate Python command launchers.' }
$env:PATH = "$pythonScripts;$env:PATH"
if (-not (Get-Command yt-dlp -ErrorAction SilentlyContinue)) { throw 'yt-dlp launcher is missing from the Python installation.' }
& (Join-Path $PSScriptRoot 'setup_drive.ps1') -OAuthClientJson $OAuthClientJson -CheckOnly:$CheckOnly
if (-not $?) { throw 'Drive setup failed.' }
Write-Host 'Odify is ready. Double-click run_first_2026.bat.' -ForegroundColor Green

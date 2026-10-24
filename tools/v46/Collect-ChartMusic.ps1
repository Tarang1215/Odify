param(
    [string]$Repository = 'C:\Users\ushig\OneDrive\문서\ChatGPT\Odify',
    [string]$Root = 'D:\Odify\Charts\20261024',
    [switch]$CheckOnly
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$pythonPath=Join-Path $env:LOCALAPPDATA 'Python\pythoncore-3.14-64\python.exe'
$configPath=Join-Path $env:LOCALAPPDATA 'Odify\Collector\rclone.conf'
$rclonePath=(Get-Command rclone -ErrorAction Stop).Source
$null=Get-Command ffmpeg -ErrorAction Stop
$workerPath=Join-Path $Repository 'tools\v46\odify_charts.py'
$publisherPath=Join-Path $Repository 'tools\v46\publish_chart_index.py'
$catalogPath=Join-Path $Repository '.local\v46\device_catalog_after_update.sqlite3'
foreach($file in @($pythonPath,$configPath,$workerPath,$publisherPath,$catalogPath,(Join-Path $Root 'chart_status.json'))) {
    if(!(Test-Path -LiteralPath $file -PathType Leaf)) {throw "Required file missing: $file"}
}
& $pythonPath -c 'import yt_dlp,mutagen'
if($LASTEXITCODE -ne 0) {throw 'Python dependencies are missing: yt-dlp / mutagen'}
$state=Get-Content -LiteralPath (Join-Path $Root 'chart_status.json') -Raw -Encoding UTF8 | ConvertFrom-Json
Write-Host 'Odify: resume chart collection; originals and MASTER remain protected.'
$state.counts | Format-List
if($CheckOnly) {Write-Host 'Preflight passed. No downloads, uploads or source requests were made.';return}
$arguments=@($workerPath,'--root',$Root,'--catalog',$catalogPath,'--config',$configPath,
    '--folder-id','1JMjClvv990es3mZns6RZHC-Qul3PiH03','--rclone',$rclonePath,'--retry-held')
try {
    & $pythonPath @arguments
    if($LASTEXITCODE -ne 0) {throw "Collection failed (exit $LASTEXITCODE); completed transfers remain resumable."}
} finally {
    # Publish verified/reused recordings even when the source asks to pause.
    & $pythonPath $publisherPath --root $Root --config $configPath `
        --folder-id '1JMjClvv990es3mZns6RZHC-Qul3PiH03' --rclone $rclonePath
}
$latest=Get-Content -LiteralPath (Join-Path $Root 'chart_status.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$latest.counts | Format-List
Write-Host $latest.detail
Write-Host ('Reports: '+(Join-Path $Root 'chart_status.csv'))

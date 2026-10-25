param([switch]$ResolveReports, [int]$WatchSeconds = 0)
$ErrorActionPreference = 'Stop'
$collectorConfig = Join-Path $env:LOCALAPPDATA 'Odify\Collector\rclone.conf'
$rclonePath = Join-Path $env:LOCALAPPDATA 'Microsoft\WinGet\Links\rclone.exe'
$pythonPath = Join-Path $env:LOCALAPPDATA 'Python\pythoncore-3.14-64\python.exe'
foreach ($required in @($collectorConfig, $rclonePath, $pythonPath)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Odify collector setup is missing: $required" }
}
$workerArgs = @('-u', (Join-Path $PSScriptRoot 'audio_requests.py'), '--config', $collectorConfig,
    '--rclone', $rclonePath, '--process-links', '--watch-seconds', [string]$WatchSeconds)
if ($ResolveReports) { $workerArgs += '--resolve-reports' }
& $pythonPath @workerArgs
if ($LASTEXITCODE -ne 0) { throw "Audio request worker exited with code $LASTEXITCODE" }

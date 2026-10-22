param(
    [switch]$DryRun,
    [ValidateSet('master', '2026')][string]$Mode = 'master',
    [ValidateRange(0, 100000)][int]$Limit = 0
)
$ErrorActionPreference = 'Stop'
$env:PYTHONUTF8 = '1'
$installer = @(Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.ps1' -File | Where-Object Name -notin @('setup_drive.ps1', 'run_collector.ps1'))
if ($installer.Count -ne 1) { throw 'Expected exactly one dependency installer.' }
& $installer[0].FullName -CheckOnly
if (-not $?) { throw 'Setup check failed.' }
if ($Mode -eq '2026') {
    & $env:ODIFY_PYTHON (Join-Path $PSScriptRoot 'run_2026.py') --dry-run
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    if ($DryRun) { exit 0 }
    & $env:ODIFY_PYTHON (Join-Path $PSScriptRoot 'run_2026.py') --limit 152
    exit $LASTEXITCODE
}
$options = @('--limit', $Limit)
if ($DryRun) { $options += '--plan' }
& $env:ODIFY_PYTHON (Join-Path $PSScriptRoot 'run_master.py') @options
exit $LASTEXITCODE

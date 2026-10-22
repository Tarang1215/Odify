param([switch]$DryRun)
$ErrorActionPreference = 'Stop'
$env:PYTHONUTF8 = '1'
$installer = @(Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.ps1' -File | Where-Object Name -notin @('setup_drive.ps1', 'run_collector.ps1'))
if ($installer.Count -ne 1) { throw 'Expected exactly one dependency installer.' }
& $installer[0].FullName -CheckOnly
if (-not $?) { throw 'Setup check failed.' }
& py (Join-Path $PSScriptRoot 'run_2026.py') --dry-run
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
if ($DryRun) { exit 0 }
& py (Join-Path $PSScriptRoot 'run_2026.py') --limit 152
exit $LASTEXITCODE

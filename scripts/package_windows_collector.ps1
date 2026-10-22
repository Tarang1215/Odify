param([string]$Destination = (Join-Path $PSScriptRoot '../dist/Odify_2026_AutoSetup.zip'))
$ErrorActionPreference = 'Stop'
$source = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../windows-collector'))
$files = @('2026_tracks.csv', 'run_2026.py', 'music_catalog.py', 'test_run_2026.py', 'test_worker_integration.py',
    'drive_source.py', 'run_master.py', 'test_master.py', 'setup_drive.ps1', 'run_collector.ps1',
    'install_and_connect.cmd', 'run_first_2026.bat', 'run_master_all.bat', 'README.md', 'README_ko.txt')
$installer = @(Get-ChildItem -LiteralPath $source -Filter '*.ps1' -File | Where-Object Name -notin @('setup_drive.ps1', 'run_collector.ps1'))
if ($installer.Count -ne 1) { throw 'Expected exactly one dependency installer.' }
$files += $installer[0].Name
$paths = @($files | ForEach-Object {
    $path = Join-Path $source $_
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing package file: $_" }
    $path
})
$destinationPath = [IO.Path]::GetFullPath($Destination)
New-Item -ItemType Directory -Path (Split-Path $destinationPath -Parent) -Force | Out-Null
Compress-Archive -LiteralPath $paths -DestinationPath $destinationPath -Force
Write-Host "Created: $destinationPath"

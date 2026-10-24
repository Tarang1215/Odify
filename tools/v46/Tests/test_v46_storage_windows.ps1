$ErrorActionPreference='Stop'
$src=Split-Path -Parent $PSScriptRoot
$base=Join-Path $env:TEMP ('odify_v46_test_'+[Guid]::NewGuid().ToString('N'))
try {
  New-Item -ItemType Directory -Path $base -Force | Out-Null
  foreach ($name in @('odify_pc_probe_v46.ps1','odify_storage_check.ps1','odify_drive_selection.ps1',
     'odify_drive_stage.ps1','odify_archive_copy.ps1','odify_probe_summary.ps1')) {
    $null=$null;$tokens=$null;$errors=$null
    [System.Management.Automation.Language.Parser]::ParseFile((Join-Path $src $name),[ref]$tokens,[ref]$errors) | Out-Null
    if (@($errors).Count -gt 0) {throw "PowerShell parse failure for $name : $errors"}
  }
  & (Join-Path $src 'odify_storage_check.ps1') -Root $base
  $music=Join-Path $base 'Music'
  $f=Join-Path $music 'artist_track.m4a'
  [IO.File]::WriteAllBytes($f,[byte[]](1,4,9,16,25))
  & (Join-Path $src 'odify_drive_selection.ps1') -Root $base
  $choose=Join-Path $base 'Logs\drive_selection.csv'
  $rows=@(Import-Csv -LiteralPath $choose -Encoding UTF8)
  if ($rows.Count -ne 1 -or $rows[0].selected -ne 'NO') {throw 'new selection must default NO'}
  $rows[0].selected='YES';$rows | Export-Csv -LiteralPath $choose -Encoding UTF8 -NoTypeInformation
  & (Join-Path $src 'odify_drive_selection.ps1') -Root $base
  $rows=@(Import-Csv -LiteralPath $choose -Encoding UTF8)
  if ($rows[0].selected -ne 'YES') {throw 'selected YES lost on refresh'}
  & (Join-Path $src 'odify_drive_stage.ps1') -Root $base
  $target=Join-Path $base 'DriveUpload\artist_track.m4a'
  if (Test-Path -LiteralPath $target) {throw 'dryrun wrote media'}
  & (Join-Path $src 'odify_drive_stage.ps1') -Root $base -Execute
  if (-not (Test-Path -LiteralPath $target)) {throw 'no staged file'}
  if ((Get-FileHash -LiteralPath $f -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash) {throw 'hash mismatch'}
  [IO.File]::WriteAllBytes($f,[byte[]](4,3,2,1))
  & (Join-Path $src 'odify_drive_stage.ps1') -Root $base -Execute
  if ((Get-FileHash -LiteralPath $f -Algorithm SHA256).Hash -eq (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash) {throw 'conflicting target was overwritten'}
  Write-Output 'ODIFY_V46_TEST_SUCCESS: dryrun, preserve selection, hash-copy and refuse overwrite'
} finally {
  $resolvedBase=[IO.Path]::GetFullPath($base)
  $expectedTemp=[IO.Path]::GetFullPath($env:TEMP).TrimEnd('\')+'\'
  if (-not $resolvedBase.StartsWith($expectedTemp,[StringComparison]::OrdinalIgnoreCase) -or
      [IO.Path]::GetFileName($resolvedBase) -notmatch '^odify_v46_test_[a-f0-9]{32}$') {
    throw 'Refusing cleanup outside the generated test directory'
  }
  Remove-Item -LiteralPath $resolvedBase -Recurse -Force -ErrorAction SilentlyContinue
}

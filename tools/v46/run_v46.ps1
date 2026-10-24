# Resume cached metadata probes, then emit a NEW identity review. Never edits MASTER.
param(
  [string]$Root='D:\Odify',
  [string]$Python,
  [string]$Catalog,
  [switch]$RetryFailures
)
$ErrorActionPreference='Stop'
if (-not $Python) {
  $installedPython=Join-Path $env:LOCALAPPDATA 'Python\pythoncore-3.14-64\python.exe'
  $Python=if (Test-Path -LiteralPath $installedPython -PathType Leaf) {$installedPython} else {'python'}
}
if (-not (Test-Path -LiteralPath $Root -PathType Container)) {throw 'Connect the external archive first.'}
$env:PYTHONUTF8='1'
$logs=Join-Path $Root 'Logs'
foreach ($set in @(
  @('odify_pc_probe_12.csv','odify_probe_12_results.csv',12),
  @('odify_false_match_pairs.csv','odify_probe_pairs_results.csv',10)
)) {
  & (Join-Path $PSScriptRoot 'odify_pc_probe_v46.ps1') -InputCsv (Join-Path $PSScriptRoot ('Reference\'+$set[0])) -ResultCsv (Join-Path $logs $set[1]) -Limit $set[2] -RetryFailures:$RetryFailures
}
$stamp=(Get-Date -Format 'yyyyMMdd_HHmmss')+'_'+[guid]::NewGuid().ToString('N').Substring(0,8)
$results=@((Join-Path $logs 'odify_probe_12_results.csv'),(Join-Path $logs 'odify_probe_pairs_results.csv'))
& $Python (Join-Path $PSScriptRoot 'odify_review.py') --results @results --out (Join-Path $logs "link_identity_$stamp.csv")
if ($LASTEXITCODE -ne 0) {throw 'Identity review failed.'}
if ($Catalog) {
  & $Python (Join-Path $PSScriptRoot 'odify_probe_overlay.py') --catalog $Catalog --pc-results @results --out (Join-Path $Root "Metadata\probe_review_$stamp.sqlite3")
  if ($LASTEXITCODE -ne 0) {throw 'Review overlay failed.'}
}
Write-Host 'Completed. Reports require recording review; MASTER and playback approvals are unchanged.'

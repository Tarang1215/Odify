param([string]$Root='D:\Odify')
$ErrorActionPreference='Stop'
$logs=Join-Path $Root 'Logs'
$all=New-Object System.Collections.Generic.List[object]
foreach ($file in @('odify_probe_12_results.csv','odify_probe_pairs_results.csv')) {
  $path=Join-Path $logs $file
  if (-not (Test-Path -LiteralPath $path)) { Write-Host "Not run yet: $file";continue }
  foreach ($r in @(Import-Csv -LiteralPath $path -Encoding UTF8)) {$all.Add($r)}
}
if ($all.Count -eq 0) {Write-Host 'No PC probe results yet. Run 01 and 02 first.';exit 0}
Write-Host "Total tested links: $($all.Count)"
$all | Group-Object extractor_status | Select-Object Name,Count | Format-Table -AutoSize
Write-Host 'NEEDS MANUAL REVIEW: extractable format does not guarantee the requested song, performer, or version.'
$all | Where-Object { $_.extractor_status -ne 'audio_formats_present' -or $_.variant -ne 'positive_baseline' } |
 Select-Object artist,track,variant,extractor_status,manual_same_recording | Format-Table -AutoSize
Write-Host 'Upload the two results CSV files for analysis. Do NOT upload cookies, Google login data, or passwords.'

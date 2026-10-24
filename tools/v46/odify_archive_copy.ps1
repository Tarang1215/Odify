# Verified-copy local audio to external archive; NEVER deletes source files.
# Defaults to DRY RUN. Use -Execute only after verifying source and destination.
param(
  [Parameter(Mandatory=$true)][string]$SourceDir,
  [Parameter(Mandatory=$true)][string]$ArchiveDir,
  [switch]$Execute
)
$ErrorActionPreference='Stop'
$src=[IO.Path]::GetFullPath($SourceDir).TrimEnd('\','/')
$dst=[IO.Path]::GetFullPath($ArchiveDir).TrimEnd('\','/')
if (-not (Test-Path -LiteralPath $src -PathType Container)) { throw "Source not found: $src" }
if ($dst.Equals($src,[StringComparison]::OrdinalIgnoreCase) -or $dst.StartsWith($src+'\',[StringComparison]::OrdinalIgnoreCase) -or $src.StartsWith($dst+'\',[StringComparison]::OrdinalIgnoreCase)) {
  throw 'Source and archive folder must not contain one another.'
}
$valid=@('.m4a','.mp3','.flac','.wav','.opus','.ogg','.aac')
$items=@(Get-ChildItem -LiteralPath $src -File -Recurse | Where-Object { $valid -contains $_.Extension.ToLowerInvariant() })
$report = New-Object System.Collections.Generic.List[object]
$checked=0
foreach ($f in $items) {
  $checked++
  $relative=$f.FullName.Substring($src.Length).TrimStart('\','/')
  $target=Join-Path $dst $relative
  $result='WOULD_COPY'; $reason=''; $sourceHash=''; $destHash=''
  try {
    if (Test-Path -LiteralPath $target) {
      $sourceHash=(Get-FileHash -LiteralPath $f.FullName -Algorithm SHA256).Hash
      $destHash=(Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash
      $result=if ($sourceHash -eq $destHash) { 'EXISTS_SAME_HASH' } else { 'CONFLICT_DIFFERENT_HASH' }
    } elseif ($Execute) {
      $folder=Split-Path -Parent $target
      New-Item -ItemType Directory -Path $folder -Force | Out-Null
      $partial=$target+'.odify-partial'
      if (Test-Path -LiteralPath $partial) { throw "Partial file exists: $partial" }
      Copy-Item -LiteralPath $f.FullName -Destination $partial -ErrorAction Stop
      $sourceHash=(Get-FileHash -LiteralPath $f.FullName -Algorithm SHA256).Hash
      $destHash=(Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash
      if ($sourceHash -ne $destHash) { throw 'SHA256 mismatch; do not delete original' }
      Move-Item -LiteralPath $partial -Destination $target -ErrorAction Stop
      $result='COPIED_VERIFIED'
    }
  } catch {
    $result='ERROR';$reason=$_.Exception.Message
  }
  $report.Add([pscustomobject]@{ source=$f.FullName; archive=$target; bytes=$f.Length;status=$result;
    source_sha256=$sourceHash;archive_sha256=$destHash;error=$reason })
  Write-Host ("[{0}/{1}] {2} : {3}" -f $checked,$items.Count,$result,$relative)
}
$log=Join-Path $src ('odify_archive_report_'+(Get-Date -Format 'yyyyMMdd_HHmmss')+'.csv')
$report | Export-Csv -LiteralPath $log -Encoding UTF8 -NoTypeInformation
Write-Host "Finished. Run mode: $(if($Execute){'verified COPY'}else{'DRY RUN'}). Files: $($items.Count). Report: $log"
Write-Host 'No source files were deleted. For Drive upload, verify sync completion in Google Drive before cleanup.'

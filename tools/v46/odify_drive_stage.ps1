# Review-only Google Drive staging using exact SHA256 copies. Never uploads / deletes.
param([string]$Root='D:\Odify',[switch]$Execute,[ValidateRange(1,10000)][int]$Limit=200)
$ErrorActionPreference='Stop'
$music=Join-Path $Root 'Music';$stage=Join-Path $Root 'DriveUpload';$logs=Join-Path $Root 'Logs'
$manifest=Join-Path $logs 'drive_selection.csv'
if (-not (Test-Path -LiteralPath $music -PathType Container) -or -not (Test-Path -LiteralPath $manifest -PathType Leaf)) {
 throw 'Run odify_drive_selection.ps1 first, then mark selected=YES for chosen songs.'
}
$rows=@(Import-Csv -LiteralPath $manifest -Encoding UTF8 | Where-Object selected -eq 'YES' | Select-Object -First $Limit)
$result=New-Object System.Collections.Generic.List[object]
foreach ($r in $rows) {
  $rel=[string]$r.relative_path
  if ([IO.Path]::IsPathRooted($rel) -or $rel -match '(^|[\\/])\.\.([\\/]|$)' -or [string]::IsNullOrWhiteSpace($rel)) {
    throw 'Refuse absolute or directory-traversal path from CSV.'
  }
  $src=Join-Path $music $rel;$dest=Join-Path $stage $rel
  $srcFull=[IO.Path]::GetFullPath($src);$destFull=[IO.Path]::GetFullPath($dest)
  if (-not $srcFull.StartsWith(([IO.Path]::GetFullPath($music).TrimEnd('\')+'\'),[StringComparison]::OrdinalIgnoreCase)) {throw 'Invalid source path'}
  if (-not $destFull.StartsWith(([IO.Path]::GetFullPath($stage).TrimEnd('\')+'\'),[StringComparison]::OrdinalIgnoreCase)) {throw 'Invalid target path'}
  $status='WOULD_STAGE';$sha='';$error=''
  try {
    if (-not (Test-Path -LiteralPath $src -PathType Leaf)) {throw 'Selected music file not found'}
    $sha=(Get-FileHash -LiteralPath $src -Algorithm SHA256).Hash
    if (Test-Path -LiteralPath $dest -PathType Leaf) {
      $other=(Get-FileHash -LiteralPath $dest -Algorithm SHA256).Hash
      $status=if ($sha -eq $other) {'ALREADY_STAGED_IDENTICAL'} else {'CONFLICT_SKIP'}
    } elseif ($Execute) {
      New-Item -ItemType Directory -Force -Path (Split-Path -Parent $dest) | Out-Null
      $partial=$dest+'.partial'
      if (Test-Path -LiteralPath $partial) {throw 'Partial file exists; remove after inspection'}
      Copy-Item -LiteralPath $src -Destination $partial -ErrorAction Stop
      if ((Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash -ne $sha) {throw 'Hash mismatch in staged copy'}
      Move-Item -LiteralPath $partial -Destination $dest -ErrorAction Stop
      $status='STAGED_VERIFIED'
    }
  } catch { $status='ERROR';$error=$_.Exception.Message }
  $result.Add([pscustomobject]@{relative_path=$rel;bytes=$r.file_size_bytes;
     status=$status;sha256=$sha;error=$error})
  Write-Host ("$status : $rel")
}
$file=Join-Path $logs ('drive_stage_'+(Get-Date -Format 'yyyyMMdd_HHmmss')+'.csv')
if ($result.Count -gt 0) {$result | Export-Csv -LiteralPath $file -NoTypeInformation -Encoding UTF8}
else {'relative_path,bytes,status,sha256,error' | Set-Content -LiteralPath $file -Encoding UTF8 }
Write-Host ('Processed selected files: '+$result.Count+'; execute mode: '+[bool]$Execute)
Write-Host ('Results: '+$file)
Write-Host 'DriveUpload is LOCAL on Toshiba. Upload to Drive yourself after reviewing. No source files deleted.'

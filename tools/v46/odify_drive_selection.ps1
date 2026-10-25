# Create/update selection manifest from D:\Odify\Music. Default all new files to NO.
param([string]$Root='D:\Odify')
$ErrorActionPreference='Stop'
$music=Join-Path $Root 'Music';$logs=Join-Path $Root 'Logs'
if (-not (Test-Path -LiteralPath $music -PathType Container)) {throw "Music directory missing: $music"}
New-Item -ItemType Directory -Path $logs -Force | Out-Null
$manifest=Join-Path $logs 'drive_selection.csv'
$old=@{}
if (Test-Path -LiteralPath $manifest) {
  foreach ($row in @(Import-Csv -LiteralPath $manifest -Encoding UTF8)) {
    if ($row.relative_path) {$old[[string]$row.relative_path]=$row}
  }
}
$exts=@('.mp3','.m4a','.flac','.opus','.aac','.ogg','.wav')
$selected=New-Object System.Collections.Generic.List[object]
foreach ($file in (Get-ChildItem -LiteralPath $music -Recurse -File)) {
  if ($exts -notcontains $file.Extension.ToLowerInvariant()) {continue}
  $relative=$file.FullName.Substring($music.Length).TrimStart('\')
  $was=$old[$relative]
  $choose=if ($was -and $was.selected -eq 'YES') {'YES'} else {'NO'}
  $selected.Add([pscustomobject]@{relative_path=$relative;file_size_bytes=$file.Length;
    selected=$choose;drive_upload_status='NOT_UPLOADED';notes=if ($was) {$was.notes} else {''}})
}
$tmp=$manifest+'.tmp'
if ($selected.Count -gt 0) {$selected | Export-Csv -LiteralPath $tmp -Encoding UTF8 -NoTypeInformation}
else {'relative_path,file_size_bytes,selected,drive_upload_status,notes' | Set-Content -LiteralPath $tmp -Encoding UTF8}
Move-Item -LiteralPath $tmp -Destination $manifest -Force
Write-Host ('Drive selection: '+$manifest)
Write-Host ('Songs: '+$selected.Count+'; YES: '+@($selected | Where-Object selected -eq 'YES').Count)
Write-Host 'Edit selected=YES in CSV for favorites. Does not upload to Drive or modify music.'

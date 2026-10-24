# Odify external drive audit. Read-only, does not delete or move music.
param([string]$Root = 'D:\Odify')
$ErrorActionPreference='Stop'
if (-not (Test-Path -LiteralPath $Root -PathType Container)) {
  throw "Odify external drive not mounted: $Root. Please connect Toshiba D: first."
}
$folders=@('Music','Incoming','Metadata','Logs','Backups','DriveUpload')
foreach ($folder in $folders) { New-Item -ItemType Directory -Force -Path (Join-Path $Root $folder) | Out-Null }
$drive=Get-PSDrive -Name ([IO.Path]::GetPathRoot($Root).TrimEnd(':','\')) -ErrorAction Stop
$valid=@('.m4a','.mp3','.flac','.wav','.opus','.ogg','.aac')
$inventory=New-Object System.Collections.Generic.List[object]
foreach ($sub in @('Music','Incoming')) {
  $base=Join-Path $Root $sub
  foreach ($f in (Get-ChildItem -LiteralPath $base -Recurse -File)) {
    if ($valid -notcontains $f.Extension.ToLowerInvariant()) {continue}
    $inventory.Add([pscustomobject]@{
      category=$sub;relative_path=$f.FullName.Substring($Root.Length).TrimStart('\');
      size_bytes=$f.Length;last_write_utc=$f.LastWriteTimeUtc.ToString('o');
      ext=$f.Extension.ToLowerInvariant();sha256='NOT_HASHED';upload_selected='NO'
    })
  }
}
$log=Join-Path $Root 'Logs'
$invfile=Join-Path $log 'odify_storage_inventory.csv'
# Do not overwrite a selection list; inventory is a separate, replaceable snapshot.
if ($inventory.Count -gt 0) {$inventory | Export-Csv -LiteralPath $invfile -Encoding UTF8 -NoTypeInformation}
else { 'category,relative_path,size_bytes,last_write_utc,ext,sha256,upload_selected' | Set-Content -LiteralPath $invfile -Encoding UTF8 }
Write-Host ('Odify drive: ' + $Root)
Write-Host ('Available space: '+ [math]::Round($drive.Free/1GB,1) +' GiB')
Write-Host ('Audio files: '+$inventory.Count)
Write-Host ('Inventory: '+$invfile)
Write-Host 'No existing music files altered.'

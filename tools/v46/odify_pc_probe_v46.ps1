# Odify V4.6: Windows-only, checkpointed yt-dlp metadata probe. NO media downloads.
# Never supplies login credentials/cookies; no master-sheet writes.
param(
  [Parameter(Mandatory=$true)][string]$InputCsv,
  [Parameter(Mandatory=$true)][string]$ResultCsv,
  [ValidateRange(1,1000)][int]$Limit = 12,
  [ValidateRange(0,30)][int]$DelaySeconds = 2,
  [switch]$RetryFailures
)
$ErrorActionPreference = 'Stop'
$yt = Get-Command yt-dlp -ErrorAction SilentlyContinue
if (-not $yt) { throw 'yt-dlp not found. Install with: winget install --id yt-dlp.yt-dlp -e' }
if (-not (Test-Path -LiteralPath $InputCsv -PathType Leaf)) { throw "Missing input CSV: $InputCsv" }
$dir = Split-Path -Parent ([IO.Path]::GetFullPath($ResultCsv))
if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
$inputRows = @(Import-Csv -LiteralPath $InputCsv -Encoding UTF8 | Select-Object -First $Limit)
if ($inputRows.Count -eq 0) { throw 'Input CSV has no entries.' }
$existing = [System.Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
if (Test-Path -LiteralPath $ResultCsv) {
  foreach ($entry in @(Import-Csv -LiteralPath $ResultCsv -Encoding UTF8)) {
    if ($entry.probe_key) { $existing[[string]$entry.probe_key] = $entry }
  }
}
function KeyFor($r) {
  $parts = @([string]$r.region,[string]$r.artist,[string]$r.title,[string]$r.video_id,[string]$r.variant,[string]$r.source)
  return ($parts -join '|')
}
function Save-Checkpoint {
  $tmp = $ResultCsv + '.partial'
  @($existing.Values | Sort-Object -Property probe_key) | Export-Csv -LiteralPath $tmp -NoTypeInformation -Encoding UTF8
  Move-Item -LiteralPath $tmp -Destination $ResultCsv -Force
}
$completed=0; $skipped=0; $failed=0; $requests=0
foreach ($row in $inputRows) {
  $key = KeyFor $row
  $old = $existing[$key]
  if ($null -ne $old) {
    $oldStatus = [string]$old.extractor_status
    if (-not $RetryFailures -or $oldStatus -eq 'audio_formats_present') {
      $skipped++
      Write-Host ('SKIP: existing result - ' + $row.artist + ' / ' + $row.title)
      continue
    }
  }
  $url = [string]$row.url
  if ($url -cnotmatch '^https://www\.youtube\.com/watch\?v=([A-Za-z0-9_-]{11})$') {
    throw "Untrusted YouTube URL (no request made): $url"
  }
  $expectedId = [regex]::Match($url,'^https://www\.youtube\.com/watch\?v=([A-Za-z0-9_-]{11})$').Groups[1].Value
  if ([string]$row.video_id -cne $expectedId) {
    throw "CSV video_id and URL disagree: $expectedId / $($row.video_id)"
  }
  $stdout = Join-Path $dir ('odify_{0}_{1}.out.tmp' -f $PID, $expectedId)
  $stderr = Join-Path $dir ('odify_{0}_{1}.err.tmp' -f $PID, $expectedId)
  $time=[System.Diagnostics.Stopwatch]::StartNew()
  $status='extractor_failed';$errorText='';$extractedId='';$extractedTitle='';$extractedChannel=''
  $duration='';$preferred='';$audioCount=0;$difference='';$formatIds=''
  Write-Host ('[{0}/{1}] {2} — {3}' -f ($requests + $skipped + 1),$inputRows.Count,$row.artist,$row.title)
  try {
    $args = @('--skip-download','--no-playlist','--no-progress','--no-warnings',
      '--dump-single-json','--socket-timeout','12','--retries','0','--extractor-retries','0', $url)
    $proc=Start-Process -FilePath $yt.Source -ArgumentList $args -PassThru -WindowStyle Hidden `
      -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    if (-not $proc.WaitForExit(90000)) {
      $proc.Kill()
      $proc.WaitForExit()
      throw 'Metadata probe exceeded 90 seconds.'
    }
    if ($proc.ExitCode -eq 0) {
      $json=Get-Content -LiteralPath $stdout -Encoding UTF8 -Raw | ConvertFrom-Json
      $extractedId=[string]$json.id
      $extractedTitle=[string]$json.title
      $extractedChannel=[string]$json.channel
      $duration=[string]$json.duration
      $audio = @($json.formats | Where-Object { $_.acodec -and $_.acodec -ne 'none' -and $_.vcodec -eq 'none' })
      $audioCount=$audio.Count
      $formatIds=($audio | ForEach-Object { [string]$_.format_id }) -join ';'
      $good = $audio | Sort-Object -Property @{Expression={if ($null -ne $_.abr) {[double]$_.abr} else {0}};Descending=$true} | Select-Object -First 1
      if ($good) { $preferred=([string]$good.format_id+'/'+[string]$good.ext) }
      if ($duration -match '^\d+(\.\d+)?$' -and [string]$row.expected_seconds -match '^\d+(\.\d+)?$') {
        $difference=[math]::Round([math]::Abs([double]$duration - [double]$row.expected_seconds),1)
      }
      $status=if ($extractedId -cne $expectedId) {'video_id_mismatch'} elseif ($audioCount -gt 0) {'audio_formats_present'} else {'no_audio_only_formats'}
    } else {
      $errorText=([string](Get-Content -LiteralPath $stderr -Encoding UTF8 -Raw -ErrorAction SilentlyContinue)).Trim()
      if ($errorText.Length -gt 500) { $errorText=$errorText.Substring($errorText.Length-500) }
    }
  } catch {
    $status='probe_exception';$errorText=[string]$_.Exception.Message
  } finally {
    $time.Stop()
    Remove-Item -LiteralPath $stdout,$stderr -ErrorAction SilentlyContinue -Force
  }
  $manual = if ($old -and $old.manual_same_recording) { [string]$old.manual_same_recording } else { 'PENDING' }
  $notes = if ($old -and $old.review_notes) { [string]$old.review_notes } else { '' }
  $entry=[pscustomobject]@{
    probe_key=$key;region=$row.region;artist=$row.artist;track=$row.title;
    video_id=$expectedId;url=$url;variant=$row.variant;candidate_source=$row.source;
    candidate_channel=$row.channel;candidate_video_title=$row.video_title;
    expected_seconds=$row.expected_seconds;extracted_id=$extractedId;
    extracted_title=$extractedTitle;extracted_channel=$extractedChannel;
    extracted_seconds=$duration;length_difference_seconds=$difference;
    audio_formats=$audioCount;audio_format_ids=$formatIds;preferred_format=$preferred;
    extractor_status=$status;elapsed_ms=$time.ElapsedMilliseconds;
    manual_same_recording=$manual;actual_download_verified=if ($old -and $old.actual_download_verified) {$old.actual_download_verified} else {'NOT_TESTED'};
    review_notes=$notes;error=$errorText;verified_at=(Get-Date).ToString('o')
  }
  $existing[$key]=$entry
  Save-Checkpoint
  $requests++
  if ($status -eq 'audio_formats_present') {$completed++} else {$failed++}
  Write-Host (' -> {0}, audio formats: {1}; saved.' -f $status,$audioCount)
  if ($DelaySeconds -gt 0 -and ($requests + $skipped) -lt $inputRows.Count) { Start-Sleep -Seconds $DelaySeconds }
}
Write-Host ('COMPLETE: new={0}, ok={1}, failed={2}, previously saved={3}' -f $requests,$completed,$failed,$skipped)
Write-Host ('Results: '+$ResultCsv)
Write-Host 'No media downloaded. Audio extraction and source identity are separate tests.'

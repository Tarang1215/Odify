param(
    [string]$Root='D:\Odify\Albums',
    [ValidateSet('Window','Status','Pause','Resume')][string]$Action='Window'
)
$ErrorActionPreference='Stop'
$config=Get-Content -LiteralPath (Join-Path $Root 'manager_config.json') -Raw -Encoding UTF8 | ConvertFrom-Json
function Read-JsonFile([string]$Path) {
    if(Test-Path -LiteralPath $Path) {try {return Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json} catch {return $null}}
    return $null
}
function Write-Control([bool]$Paused) {
    $data=@{paused=$Paused;updated_at=[DateTimeOffset]::UtcNow.ToString('o')}
    $temp=Join-Path $Root 'control.ui.tmp'
    [IO.File]::WriteAllText($temp,($data | ConvertTo-Json),[Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temp -Destination (Join-Path $Root 'control.json') -Force
}
function Get-WorkerProcess {
    $state=Read-JsonFile (Join-Path $Root 'manager_status.json')
    $launch=Read-JsonFile (Join-Path $Root 'manager_launch.json')
    foreach($workerId in @($state.worker_pid,$launch.worker_pid,$config.legacy_pid)) {
        if(!$workerId){continue}
        $process=Get-CimInstance Win32_Process -Filter "ProcessId=$workerId" -ErrorAction SilentlyContinue
        if($process -and $process.CommandLine -match 'album_music\.py') {return $process}
    }
    return $null
}
function Resume-Collection {
    Write-Control $false
    if(Get-WorkerProcess){return}
    $log=Join-Path $Root ('manager_'+(Get-Date -Format 'yyyyMMdd_HHmmss'))
    $worker=Join-Path $config.workspace 'tools\v46\album_music.py'
    $argsLine='-u "'+$worker+'" --resume --workers 4 --root "'+$Root+'"'
    $process=Start-Process -FilePath $config.python -ArgumentList $argsLine -WorkingDirectory $config.workspace -WindowStyle Hidden -PassThru -RedirectStandardOutput ($log+'.stdout.log') -RedirectStandardError ($log+'.stderr.log')
    @{worker_pid=$process.Id;started_at=[DateTimeOffset]::UtcNow.ToString('o')} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $Root 'manager_launch.json') -Encoding UTF8
}
function Get-ProgressState {
    $data=Read-JsonFile (Join-Path $Root 'manager_status.json')
    $full=Read-JsonFile (Join-Path $Root 'chart_status.json')
    if(!$data -or $data.updated_at -lt $full.updated_at){$data=$full}
    $counts=$data.counts
    $ready=[int]$counts.DOWNLOADED_VERIFIED+[int]$counts.REUSED_HISTORY+[int]$counts.REUSED_LOCAL
    $control=Read-JsonFile (Join-Path $Root 'control.json')
    $worker=Get-WorkerProcess
    $label=if(!$worker){'멈춤 · 재개 가능'} elseif($control.paused -and $data.paused){'일시정지'} elseif($control.paused){'일시정지 요청 · 현재 파일을 마치고 멈춤'} else {'다운로드 진행 중'}
    [pscustomobject]@{state=$label;running=($null -ne $worker);paused=[bool]$control.paused;ready=$ready;total=11513;new=[int]$counts.DOWNLOADED_VERIFIED;reused=([int]$counts.REUSED_HISTORY+[int]$counts.REUSED_LOCAL);downloading=[int]$counts.DOWNLOADING;queued=([int]$counts.QUEUED+[int]$counts.LINK_SELECTED);metadata=[int]$counts.NEED_METADATA;held=[int]$counts.HELD_REVIEW;updated_at=$data.updated_at;drive_upload=$false}
}
if($Action -eq 'Pause'){Write-Control $true;Get-ProgressState | ConvertTo-Json;exit}
if($Action -eq 'Resume'){Resume-Collection;Get-ProgressState | ConvertTo-Json;exit}
if($Action -eq 'Status'){Get-ProgressState | ConvertTo-Json;exit}
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
$form=[Windows.Forms.Form]::new()
$form.Text='Odify · 음악 다운로드 관리'
$form.ClientSize=[Drawing.Size]::new(660,440)
$form.StartPosition='CenterScreen'
$form.BackColor=[Drawing.Color]::FromArgb(19,21,26)
$form.ForeColor=[Drawing.Color]::White
$form.Font=[Drawing.Font]::new('맑은 고딕',11)
$title=[Windows.Forms.Label]::new();$title.Text='앨범 수록곡 보관 · 2024–2026';$title.SetBounds(28,24,610,36);$title.Font=[Drawing.Font]::new('맑은 고딕',17,[Drawing.FontStyle]::Bold)
$stateLabel=[Windows.Forms.Label]::new();$stateLabel.SetBounds(28,77,610,30)
$bar=[Windows.Forms.ProgressBar]::new();$bar.SetBounds(28,117,600,18);$bar.Maximum=11513
$details=[Windows.Forms.Label]::new();$details.SetBounds(28,155,610,150)
$note=[Windows.Forms.Label]::new();$note.Text="월별 차트: 2,558 / 2,759곡 준비`r`n저장 위치: $Root`r`nDrive 업로드 보류 · 창을 닫아도 다운로드는 계속됩니다.";$note.SetBounds(28,305,610,80);$note.ForeColor=[Drawing.Color]::LightGray
$pause=[Windows.Forms.Button]::new();$pause.Text='일시정지';$pause.SetBounds(28,391,170,34)
$resume=[Windows.Forms.Button]::new();$resume.Text='재개';$resume.SetBounds(214,391,170,34)
$folder=[Windows.Forms.Button]::new();$folder.Text='저장 폴더 열기';$folder.SetBounds(400,391,228,34)
foreach($button in @($pause,$resume,$folder)){$button.FlatStyle='Flat';$button.BackColor=[Drawing.Color]::FromArgb(44,48,57)}
$pause.Add_Click({Write-Control $true})
$resume.Add_Click({try {Resume-Collection} catch {[Windows.Forms.MessageBox]::Show($_.Exception.Message,'재개 실패')}})
$folder.Add_Click({Start-Process explorer.exe -ArgumentList ('"'+$Root+'"')})
$form.Controls.AddRange(@($title,$stateLabel,$bar,$details,$note,$pause,$resume,$folder))
$timer=[Windows.Forms.Timer]::new();$timer.Interval=2500
$refresh={try {$s=Get-ProgressState;$stateLabel.Text=$s.state;$bar.Value=[Math]::Min(11513,$s.ready)
    $details.Text="준비 완료  $($s.ready.ToString('N0')) / $($s.total.ToString('N0'))곡`r`n신규 다운로드 $($s.new.ToString('N0'))  · 기존 파일 재사용 $($s.reused.ToString('N0'))`r`n현재 전송 $($s.downloading)  · 대기 $($s.queued.ToString('N0'))`r`n메타데이터 필요 $($s.metadata.ToString('N0'))  · 확인 필요 $($s.held.ToString('N0'))"
} catch {$stateLabel.Text='진행 기록을 갱신 중입니다.'}}
$timer.Add_Tick($refresh);$form.Add_Shown($refresh);$timer.Start()
$form.Add_FormClosed({$timer.Stop();$timer.Dispose()})
[Windows.Forms.Application]::Run($form)

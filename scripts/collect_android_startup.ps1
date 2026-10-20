# Captures a small, focused startup crash trace from the user's own Android device.
# Requirements: Windows 11, Android SDK Platform Tools (adb), USB debugging.
# Run from PowerShell: powershell -ExecutionPolicy Bypass -File scripts/collect_android_startup.ps1
# The output remains on your PC; review it before sharing.
param(
    [ValidateSet("com.odify.music", "com.odify.music.debug")]
    [string]$Package = "com.odify.music",
    [ValidateRange(5, 60)]
    [int]$WaitSeconds = 12
)
$ErrorActionPreference = "Stop"
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw "adb를 찾지 못했습니다. Android SDK Platform Tools를 설치하고 PATH에 adb를 추가하세요."
}
$devices = (& adb devices) -join "`n"
if ($devices -notmatch "(?m)^[A-Za-z0-9._:-]+\s+device\s*$") {
    throw "USB 디버깅으로 연결된 Android 기기가 없습니다. 휴대폰에서 USB 디버깅 허용을 확인하세요."
}
Write-Host "Odify startup diagnostics: $Package"
& adb logcat -c
& adb shell am force-stop $Package
& adb shell monkey -p $Package -c android.intent.category.LAUNCHER 1 | Out-Null
Start-Sleep -Seconds $WaitSeconds
$log = (& adb logcat -d -v time 2>&1) -join "`n"
$rows = $log -split "\r?\n"
# Capture context surrounding relevant crash markers; do not upload system logs
# indiscriminately, as other apps may log personal details.
$marks = 'FATAL EXCEPTION|AndroidRuntime|Fatal signal|SIGSEGV|SIGABRT|UnsatisfiedLinkError|NoClassDefFoundError|ClassNotFoundException|FlutterJNI|FlutterEngine|libflutter\.so|com\.odify\.music|com\.zarz\.mornye|AppCrashReporter'
$keep = New-Object 'System.Collections.Generic.HashSet[int]'
for ($i=0; $i -lt $rows.Length; $i++) {
    if ($rows[$i] -match $marks) {
        $lo = [Math]::Max(0, $i - 5)
        $hi = [Math]::Min($rows.Length - 1, $i + 12)
        for ($j=$lo; $j -le $hi; $j++) { [void]$keep.Add($j) }
    }
}
$result = @("Odify startup log capture", "Package: $Package", "Wait: $WaitSeconds seconds", "-----") +
    @(($keep | Sort-Object | Select-Object -Last 1600) | ForEach-Object { $rows[$_] })
$report = $result -join "`r`n"
# Redact likely email addresses and bearer tokens before saving.
$report = [regex]::Replace($report, '(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b', '[email]')
$report = [regex]::Replace($report, '(?i)(Bearer\s+)[A-Za-z0-9._~+/-]{12,}', '$1[redacted]')
$desktop = [Environment]::GetFolderPath("Desktop")
if ([string]::IsNullOrWhiteSpace($desktop)) { $desktop = (Get-Location).Path }
$file = Join-Path $desktop ("Odify-startup-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + ".txt")
Set-Content -LiteralPath $file -Value $report -Encoding utf8
Write-Host "저장됨: $file"
Write-Host "개인정보가 있는지 확인한 후 오류 관련 부분만 공유해 주세요."

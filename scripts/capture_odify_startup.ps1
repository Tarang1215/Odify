# Windows 11 / Android SDK Platform Tools required.
# Captures Android's CRASH BUFFER only; never uploads logs or reads MP3s.
# Connect Galaxy via USB debugging and accept the device authorization prompt.
param(
    [ValidateSet("release","debug")]
    [string] $Variant = "release"
)

$ErrorActionPreference = "Stop"
$packageName = if ($Variant -eq "debug") {
    "com.odify.music.debug"
} else {
    "com.odify.music"
}

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw "adb (Android Platform Tools) is not on PATH."
}
$devices = @(adb devices | Select-String "\tdevice$")
if ($devices.Count -ne 1) {
    throw "Exactly one authorized Android device must be connected. adb devices output: $(adb devices)"
}

Write-Host "Clearing previous crash messages (not music or app data) ..."
adb logcat -b crash -c
adb shell am force-stop $packageName
Write-Host "Launching $packageName ..."
adb shell monkey -p $packageName -c android.intent.category.LAUNCHER 1
Start-Sleep -Seconds 8

$pids = adb shell pidof $packageName
$logPath = Join-Path (Get-Location) "odify-startup-crash.log"
adb logcat -b crash -d -v time | Out-File -FilePath $logPath -Encoding utf8
if ($LASTEXITCODE -ne 0) { throw "Unable to retrieve crash log." }
Write-Host "Odify PID after 8 seconds: $pids"
Write-Host "Captured crash buffer: $logPath"
Write-Host "Review and REDACT any personal paths, identifiers or account data before sharing."
Write-Host "If the crash buffer is empty, Android may have killed the process without a recorded Java/native exception."

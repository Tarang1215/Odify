$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot '../windows-collector/setup_drive.ps1') -CheckOnly
$rclone = (Get-Command rclone).Source
$testDirectory = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../.local/connection-test'))
New-Item -ItemType Directory -Path $testDirectory -Force | Out-Null
$marker = '.odify-setup-check-' + [Guid]::NewGuid().ToString('N') + '.txt'
$source = Join-Path $testDirectory $marker
$readback = Join-Path $testDirectory ('readback-' + $marker)
$remote = 'mydrive:Odify_Auto_Upload/' + $marker
Set-Content -LiteralPath $source -Value ('Odify connection check ' + [Guid]::NewGuid().ToString('N')) -Encoding ascii
$uploaded = $false
try {
    & $rclone copyto $source $remote --retries 1
    if ($LASTEXITCODE -ne 0) { throw 'Test upload failed.' }
    $uploaded = $true
    & $rclone copyto $remote $readback --retries 1
    if ($LASTEXITCODE -ne 0) { throw 'Test download failed.' }
    if ((Get-FileHash -LiteralPath $source).Hash -ne (Get-FileHash -LiteralPath $readback).Hash) {
        throw 'Uploaded and downloaded checksums do not match.'
    }
    Write-Host 'LIVE PASS: upload, download and SHA-256 comparison.'
} finally {
    if ($uploaded) {
        & $rclone deletefile $remote --retries 1
        if ($LASTEXITCODE -ne 0) { throw "Test succeeded but cleanup is needed for $remote" }
    }
    if (Test-Path -LiteralPath $source) { Remove-Item -LiteralPath $source }
    if (Test-Path -LiteralPath $readback) { Remove-Item -LiteralPath $readback }
}
Write-Host 'Test marker removed; existing music was not modified.'

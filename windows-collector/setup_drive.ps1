param(
    [string]$OAuthClientJson,
    [string]$StateDirectory = (Join-Path $env:LOCALAPPDATA 'Odify/Collector'),
    [switch]$Reconnect,
    [switch]$NoBrowser,
    [switch]$CheckOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$wingetLinks = Join-Path $env:LOCALAPPDATA 'Microsoft/WinGet/Links'
$env:PATH = "$wingetLinks;$env:PATH"
$rclone = Get-Command rclone -ErrorAction SilentlyContinue
if (-not $rclone) { throw 'rclone is missing. Run install_and_connect.cmd first.' }
$configPath = Join-Path $StateDirectory 'rclone.conf'
$env:RCLONE_CONFIG = $configPath
$script:hasSavedToken = $false

function Protect-PrivateDirectory([string]$Path) {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    & icacls.exe $Path /inheritance:r /grant:r "*${identity}:(OI)(CI)F" '*S-1-5-18:(OI)(CI)F' 1>$null
    if ($LASTEXITCODE -ne 0) { throw 'Could not restrict access to the private credential folder.' }
}

function Test-DriveConnection {
    if (-not (Test-Path -LiteralPath $configPath)) { return $false }
    $script:hasSavedToken = [bool](Select-String -LiteralPath $configPath -Pattern '^token\s*=\s*\{' -Quiet)
    $remotes = @(& $rclone.Source listremotes --config $configPath 2>$null)
    if ($LASTEXITCODE -ne 0 -or $remotes -notcontains 'mydrive:') { return $false }
    try {
        & $rclone.Source lsf 'mydrive:' --config $configPath --max-depth 1 --dirs-only --retries 1 --contimeout 15s --timeout 30s 1>$null 2>$null
        return $LASTEXITCODE -eq 0
    } catch { return $false }
}

function Initialize-UploadFolder {
    & $rclone.Source mkdir 'mydrive:Odify_Auto_Upload' --config $configPath --retries 1
    if ($LASTEXITCODE -ne 0) { throw 'Could not prepare the Odify_Auto_Upload destination folder.' }
}

if (-not $Reconnect -and (Test-DriveConnection)) {
    if (-not $CheckOnly) { Initialize-UploadFolder }
    Write-Host 'Google Drive connection is ready. Existing authorization reused.' -ForegroundColor Green
    return
}
if ($CheckOnly) { throw 'Google Drive is not connected. Run install_and_connect.cmd once.' }
if (-not $Reconnect -and $script:hasSavedToken) {
    throw 'Saved authorization exists but Drive is unreachable. Check the network first. If access was revoked, run setup_drive.ps1 -Reconnect.'
}
New-Item -ItemType Directory -Path $StateDirectory -Force | Out-Null
Protect-PrivateDirectory $StateDirectory
$savedClient = Join-Path $StateDirectory 'oauth-client.json'
if (-not $OAuthClientJson -and (Test-Path -LiteralPath $savedClient)) { $OAuthClientJson = $savedClient }
if (-not $OAuthClientJson) {
    $downloads = Join-Path $env:USERPROFILE 'Downloads'
    $candidates = @(Get-ChildItem -LiteralPath $downloads -Filter 'client_secret*.json' -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending)
    foreach ($candidate in $candidates) {
        try {
            $candidateData = Get-Content -LiteralPath $candidate.FullName -Raw | ConvertFrom-Json
            if ($candidateData.installed.project_id -eq 'odify-512404') {
                $OAuthClientJson = $candidate.FullName
                break
            }
        } catch { }
    }
}
if (-not $OAuthClientJson) {
    throw 'Odify Desktop OAuth JSON is needed once. Download it from Google Cloud and rerun, or pass -OAuthClientJson <path>.'
}
$client = Get-Content -LiteralPath $OAuthClientJson -Raw | ConvertFrom-Json
if (-not $client.PSObject.Properties['installed']) { throw 'Use a Desktop app OAuth JSON, not Web/Android/service-account credentials.' }
$installed = $client.installed
if ($installed.project_id -ne 'odify-512404' -or $installed.client_id -notmatch '^[A-Za-z0-9-]+\.apps\.googleusercontent\.com$' -or -not $installed.client_secret) {
    throw 'Invalid Odify OAuth client JSON.'
}
if ([IO.Path]::GetFullPath($OAuthClientJson) -ne [IO.Path]::GetFullPath($savedClient)) {
    Copy-Item -LiteralPath $OAuthClientJson -Destination $savedClient
}
if (Test-Path -LiteralPath $configPath) {
    Copy-Item -LiteralPath $configPath -Destination ($configPath + '.backup-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
}
Write-Host 'Approve Google access in the browser once. Do not send passwords or tokens in chat.' -ForegroundColor Cyan
$authOptions = @()
if ($NoBrowser) { $authOptions += @('config_auth_no_browser', 'true') }
& $rclone.Source config create mydrive drive client_id $installed.client_id client_secret $installed.client_secret scope drive config_is_local true config_team_drive false --config $configPath --no-output --no-obscure @authOptions
if ($LASTEXITCODE -ne 0) { throw 'Google authorization did not complete. Existing settings have a private backup.' }
if (-not (Test-DriveConnection)) { throw 'Authorization finished, but the read-only Drive connection test failed.' }
Initialize-UploadFolder
Write-Host 'Google Drive connected. Future runs reuse the authorization automatically.' -ForegroundColor Green

param(
    [string]$ApiBaseUrl = "http://127.0.0.1:8000",
    [switch]$Install
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$adbPath = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path -LiteralPath $adbPath)) {
    throw "Android SDK platform-tools adb.exe was not found."
}
if ($ApiBaseUrl -notmatch '^https?://[^/]+(?::\d+)?$') {
    throw "ApiBaseUrl must be a root URL such as http://127.0.0.1:8000."
}

try {
    $health = Invoke-RestMethod -Uri "$ApiBaseUrl/health" -Method Get -TimeoutSec 5
} catch {
    throw "Field API is not reachable at $ApiBaseUrl/health. Start the PostgreSQL field server first."
}
if ($health.persistence -ne "postgresql") {
    throw "The connected API is not PostgreSQL-backed. persistence=$($health.persistence)"
}

$deviceLines = & $adbPath devices
$devices = @($deviceLines | Where-Object { $_ -match '^[^\s]+\s+device$' } | ForEach-Object { ($_ -split '\s+')[0] })
if ($devices.Count -ne 1) {
    throw "Connect exactly one authorized Android device. Current authorized device count: $($devices.Count)"
}
$serial = $devices[0]

$uri = [Uri]$ApiBaseUrl
if ($uri.Host -in @("127.0.0.1", "localhost")) {
    & $adbPath -s $serial reverse "tcp:$($uri.Port)" "tcp:$($uri.Port)"
    if ($LASTEXITCODE -ne 0) { throw "adb reverse failed." }
    Write-Output "ADB reverse configured: phone localhost:$($uri.Port) -> PC localhost:$($uri.Port)"
} else {
    Write-Output "LAN API mode: phone and PC must be on the same network and the Windows firewall must allow TCP $($uri.Port)."
}

Push-Location $projectRoot
try {
    & .\gradlew.bat -p android :app:assembleDebug "-PPINSET_AI_API_BASE_URL=$ApiBaseUrl" --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "Debug APK build failed." }
    $apk = Join-Path $projectRoot "android\app\build\outputs\apk\debug\app-debug.apk"
    if (-not (Test-Path -LiteralPath $apk)) { throw "Debug APK was not generated." }
    Write-Output "APK=$apk"
    if ($Install) {
        & $adbPath -s $serial install -r $apk
        if ($LASTEXITCODE -ne 0) { throw "APK installation failed." }
        Write-Output "INSTALL=PASS"
    } else {
        Write-Output "INSTALL=SKIPPED (run again with -Install to install on the phone)"
    }
    Write-Output "FIELD_E2E_PREFLIGHT=PASS"
    Write-Output "NEXT=Open the app, save one photographed defect, then verify its sync status is completed."
} finally {
    Pop-Location
}


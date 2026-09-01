param([string]$HostAddress = "0.0.0.0", [int]$Port = 8000)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$python = Join-Path $projectRoot ".venv\Scripts\python.exe"

if (-not $env:LOCAL_APARTMENT_DB_HOST) { $env:LOCAL_APARTMENT_DB_HOST = "127.0.0.1" }
if (-not $env:LOCAL_APARTMENT_DB_PORT) { $env:LOCAL_APARTMENT_DB_PORT = "5432" }
if (-not $env:LOCAL_APARTMENT_DB_NAME) { $env:LOCAL_APARTMENT_DB_NAME = "apartment_defect_local" }
if (-not $env:LOCAL_APARTMENT_DB_USER) { $env:LOCAL_APARTMENT_DB_USER = "postgres" }
if (-not $env:LOCAL_APARTMENT_DB_SSLMODE) { $env:LOCAL_APARTMENT_DB_SSLMODE = "disable" }
if ([string]::IsNullOrWhiteSpace($env:LOCAL_APARTMENT_DB_PASSWORD)) {
    $securePassword = Read-Host "Local PostgreSQL password" -AsSecureString
    $passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
    try {
        $env:LOCAL_APARTMENT_DB_PASSWORD =
            [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
    }
}
if ([string]::IsNullOrWhiteSpace($env:LOCAL_APARTMENT_DB_PASSWORD)) {
    throw "Local PostgreSQL password was empty."
}

Set-Location -LiteralPath $projectRoot
& $python (Join-Path $PSScriptRoot "check_local_field_db.py")
if ($LASTEXITCODE -ne 0) {
    throw "Local PostgreSQL readiness check failed. Verify the password and database service before starting the field API."
}
if ([string]::IsNullOrWhiteSpace($env:FIELD_MEDIA_ROOT)) {
    Write-Output "Field media root: $(Join-Path $projectRoot 'workspace\field-media')"
} else {
    Write-Output "Field media root: $env:FIELD_MEDIA_ROOT"
}
& $python -m uvicorn vision_ai.field_postgres_entrypoint:app `
    --app-dir python --host $HostAddress --port $Port

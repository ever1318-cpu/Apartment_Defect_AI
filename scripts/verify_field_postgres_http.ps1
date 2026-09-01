$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$python = Join-Path $projectRoot ".venv\Scripts\python.exe"

$env:LOCAL_APARTMENT_DB_HOST = "127.0.0.1"
$env:LOCAL_APARTMENT_DB_PORT = "5432"
$env:LOCAL_APARTMENT_DB_NAME = "apartment_defect_local"
$env:LOCAL_APARTMENT_DB_USER = "postgres"
$env:LOCAL_APARTMENT_DB_SSLMODE = "disable"

if ([string]::IsNullOrWhiteSpace($env:LOCAL_APARTMENT_DB_PASSWORD)) {
    $securePassword = Read-Host "Local PostgreSQL password" -AsSecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
    try {
        $env:LOCAL_APARTMENT_DB_PASSWORD =
            [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

try {
    Set-Location -LiteralPath $projectRoot
    & $python scripts\verify_field_postgres_http.py
    if ($LASTEXITCODE -ne 0) { throw "Local PostgreSQL HTTP verification failed." }
} finally {
    Remove-Item Env:LOCAL_APARTMENT_DB_PASSWORD -ErrorAction SilentlyContinue
}

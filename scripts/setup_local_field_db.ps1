param(
    [string]$PsqlPath = "C:\Program Files\PostgreSQL\18\bin\psql.exe",
    [string]$CreatedbPath = "C:\Program Files\PostgreSQL\18\bin\createdb.exe"
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$migration = Join-Path $projectRoot "database\migrations\001_apartment_ai_core.sql"
$mediaMigration = Join-Path $projectRoot "database\migrations\002_allow_duplicate_media_hashes.sql"
$commonAreaMigration = Join-Path $projectRoot "database\migrations\003_common_area_inspections.sql"
$taxonomyMigration = Join-Path $projectRoot "database\migrations\004_defect_taxonomy_master.sql"
$amendmentMigration = Join-Path $projectRoot "database\migrations\005_session_amendment_history.sql"
$captureMetadataMigration = Join-Path $projectRoot "database\migrations\006_capture_distance_metadata.sql"
$gapMeasurementMigration = Join-Path $projectRoot "database\migrations\007_gap_measurement_metadata.sql"
$seed = Join-Path $projectRoot "database\seeds\001_ulsan_down_local.sql"

$hostName = if ($env:LOCAL_APARTMENT_DB_HOST) { $env:LOCAL_APARTMENT_DB_HOST } else { "127.0.0.1" }
$port = if ($env:LOCAL_APARTMENT_DB_PORT) { $env:LOCAL_APARTMENT_DB_PORT } else { "5432" }
$dbName = if ($env:LOCAL_APARTMENT_DB_NAME) { $env:LOCAL_APARTMENT_DB_NAME } else { "apartment_defect_local" }
$userName = if ($env:LOCAL_APARTMENT_DB_USER) { $env:LOCAL_APARTMENT_DB_USER } else { "postgres" }

if ($hostName -notin @("127.0.0.1", "localhost", "::1")) {
    throw "Safety stop: local setup only accepts localhost addresses."
}
if (-not (Test-Path -LiteralPath $PsqlPath)) { throw "psql not found: $PsqlPath" }
if (-not (Test-Path -LiteralPath $CreatedbPath)) { throw "createdb not found: $CreatedbPath" }

$localPassword = $env:LOCAL_APARTMENT_DB_PASSWORD
if ([string]::IsNullOrWhiteSpace($localPassword)) {
    $securePassword = Read-Host "Local PostgreSQL password" -AsSecureString
    $passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
    try {
        $localPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
    }
}
if ([string]::IsNullOrWhiteSpace($localPassword)) {
    throw "Local PostgreSQL password was empty."
}

$env:PGPASSWORD = $localPassword
$previousClientEncoding = $env:PGCLIENTENCODING
$env:PGCLIENTENCODING = "UTF8"
try {
    $existsOutput = & $PsqlPath -h $hostName -p $port -U $userName -d postgres -w -tAc `
        "SELECT 1 FROM pg_database WHERE datname = '$dbName'"
    if ($LASTEXITCODE -ne 0) { throw "Local PostgreSQL authentication failed." }
    if ($null -eq $existsOutput) {
        $exists = ""
    } else {
        $firstExistsLine = $existsOutput | Select-Object -First 1
        $exists = if ($null -eq $firstExistsLine) {
            ""
        } else {
            $firstExistsLine.ToString().Trim()
        }
    }
    if ($exists -ne "1") {
        & $CreatedbPath -h $hostName -p $port -U $userName -w $dbName
        if ($LASTEXITCODE -ne 0) { throw "Failed to create local database." }
    }
    & $PsqlPath -h $hostName -p $port -U $userName -d $dbName -w -v ON_ERROR_STOP=1 -f $migration
    if ($LASTEXITCODE -ne 0) { throw "Migration failed." }
    & $PsqlPath -h $hostName -p $port -U $userName -d $dbName -w -v ON_ERROR_STOP=1 -f $mediaMigration
    if ($LASTEXITCODE -ne 0) { throw "Media migration failed." }
    & $PsqlPath -h $hostName -p $port -U $userName -d $dbName -w -v ON_ERROR_STOP=1 -f $seed
    if ($LASTEXITCODE -ne 0) { throw "Seed failed." }
    & $PsqlPath -h $hostName -p $port -U $userName -d $dbName -w -v ON_ERROR_STOP=1 -f $commonAreaMigration
    if ($LASTEXITCODE -ne 0) { throw "Common-area migration failed." }
    & $PsqlPath -h $hostName -p $port -U $userName -d $dbName -w -v ON_ERROR_STOP=1 -f $taxonomyMigration
    if ($LASTEXITCODE -ne 0) { throw "Taxonomy migration failed." }
    & $PsqlPath -h $hostName -p $port -U $userName -d $dbName -w -v ON_ERROR_STOP=1 -f $amendmentMigration
    if ($LASTEXITCODE -ne 0) { throw "Amendment-history migration failed." }
    & $PsqlPath -h $hostName -p $port -U $userName -d $dbName -w -v ON_ERROR_STOP=1 -f $captureMetadataMigration
    if ($LASTEXITCODE -ne 0) { throw "Capture-distance metadata migration failed." }
    & $PsqlPath -h $hostName -p $port -U $userName -d $dbName -w -v ON_ERROR_STOP=1 -f $gapMeasurementMigration
    if ($LASTEXITCODE -ne 0) { throw "Gap-measurement metadata migration failed." }
    & $PsqlPath -h $hostName -p $port -U $userName -d $dbName -w -tAc `
        "SELECT 'buildings=' || count(*) FROM apartment_ai.buildings UNION ALL SELECT 'households=' || count(*) FROM apartment_ai.households;"
} finally {
    Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
    if ($null -eq $previousClientEncoding) {
        Remove-Item Env:PGCLIENTENCODING -ErrorAction SilentlyContinue
    } else {
        $env:PGCLIENTENCODING = $previousClientEncoding
    }
    $localPassword = $null
    $securePassword = $null
}

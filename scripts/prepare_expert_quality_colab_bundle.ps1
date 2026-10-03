[CmdletBinding()]
param(
    [string]$DatasetVersion = ("expert-quality-" + (Get-Date -Format "yyyyMMdd")),
    [int]$Limit = 20000,
    [string]$OutputRoot = "workspace\colab-expert-quality",
    [string]$BundleOutput = "workspace\colab-input\expert-quality-convnext-colab.zip",
    [switch]$PromptDatabaseConfig
)

$ErrorActionPreference = "Stop"

function Ensure-ReadOnlyDatabaseEnvironment {
    param([switch]$ForcePrompt)

    $required = @("APARTMENT_DB_HOST", "APARTMENT_DB_NAME", "APARTMENT_DB_USER", "APARTMENT_DB_PASSWORD")
    $missing = @($required | Where-Object {
        [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($_, "Process"))
    })
    if ($missing.Count -eq 0 -and -not $ForcePrompt) { return }

    Write-Host "PostgreSQL connection values will be used only for this run and are not written to a file."
    foreach ($name in @("APARTMENT_DB_HOST", "APARTMENT_DB_NAME", "APARTMENT_DB_USER")) {
        if ($ForcePrompt -or [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name, "Process"))) {
            $value = Read-Host $name
            if ([string]::IsNullOrWhiteSpace($value)) { throw "$name is required." }
            [Environment]::SetEnvironmentVariable($name, $value, "Process")
        }
    }

    if ($ForcePrompt -or [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable("APARTMENT_DB_PASSWORD", "Process"))) {
        $securePassword = Read-Host "APARTMENT_DB_PASSWORD" -AsSecureString
        $password = [Net.NetworkCredential]::new("", $securePassword).Password
        if ([string]::IsNullOrWhiteSpace($password)) { throw "APARTMENT_DB_PASSWORD is required." }
        [Environment]::SetEnvironmentVariable("APARTMENT_DB_PASSWORD", $password, "Process")
    }

    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable("APARTMENT_DB_PORT", "Process"))) {
        [Environment]::SetEnvironmentVariable("APARTMENT_DB_PORT", "5432", "Process")
    }
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable("APARTMENT_DB_SSLMODE", "Process"))) {
        [Environment]::SetEnvironmentVariable("APARTMENT_DB_SSLMODE", "require", "Process")
    }
}

$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $projectRoot
$cli = Join-Path $projectRoot ".venv\Scripts\apartment-data.exe"
if (-not (Test-Path -LiteralPath $cli)) {
    throw "apartment-data CLI was not found. Install the project environment first: .\.venv\Scripts\python.exe -m pip install -e '.[database,pytorch]'"
}
if ($Limit -lt 0) { throw "Limit must be zero (all rows) or a positive number." }

Ensure-ReadOnlyDatabaseEnvironment -ForcePrompt:$PromptDatabaseConfig

$dataset = Join-Path $OutputRoot "db-$DatasetVersion"
$training = Join-Path $OutputRoot "training-$DatasetVersion"
$bundle = $BundleOutput
foreach ($target in @($dataset, $training, $bundle)) {
    if (Test-Path -LiteralPath $target) {
        throw "Refusing to overwrite existing output: $target"
    }
}

Write-Host "[1/4] Checking PostgreSQL with a read-only connection..."
& $cli vision-db-test
if ($LASTEXITCODE -ne 0) { throw "PostgreSQL read-only connection test failed." }

Write-Host "[2/4] Extracting the curated expert-quality candidate dataset (SELECT only)..."
$dbArgs = @("vision-db-build-defect-dataset", $dataset, "--version", $DatasetVersion, "--seed", "42")
if ($Limit -gt 0) { $dbArgs += @("--limit", "$Limit") }
& $cli @dbArgs
if ($LASTEXITCODE -ne 0) { throw "Read-only PostgreSQL dataset extraction failed." }

Write-Host "[3/4] Building leakage-safe five-task training inputs..."
& $cli vision-build-training-dataset `
    (Join-Path $dataset "records.jsonl") `
    (Join-Path $dataset "annotations.jsonl") `
    $training `
    --dataset-version $DatasetVersion `
    --tasks classification `
    --classification-task area `
    --classification-task part `
    --classification-task part_detail `
    --classification-task work_kind `
    --classification-task cause
if ($LASTEXITCODE -ne 0) { throw "Training dataset build failed." }

Write-Host "[4/4] Creating a credential-free Google Colab bundle..."
$bundleParent = Split-Path -Parent $bundle
New-Item -ItemType Directory -Force -Path $bundleParent | Out-Null
& $cli vision-colab-export $dataset (Join-Path $training "training_spec.json") $bundle
if ($LASTEXITCODE -ne 0) { throw "Colab bundle export failed." }

Write-Host ""
Write-Host "COLAB_BUNDLE_READY"
Write-Host "DATASET_VERSION=$DatasetVersion"
Write-Host "ROWS_LIMIT=$Limit (0 means all eligible rows)"
Write-Host "BUNDLE=$((Resolve-Path -LiteralPath $bundle).Path)"
Write-Host "CHECKSUM=$((Resolve-Path -LiteralPath ($bundle + '.sha256')).Path)"
Write-Host "NEXT=Copy the ZIP and .sha256 file to Google Drive: MyDrive/Apartment_Defect_AI/colab-input/"
<#+
.SYNOPSIS
Copies the current minimal PostgreSQL field-inspection handoff to another PC.

.DESCRIPTION
Copies only source, migration, verification, contract, and latest embedded
documentation required to continue the backend work. It intentionally excludes
virtual environments, caches, build output, datasets, images, credentials,
local.properties, and .env files.
#>
[CmdletBinding()]
param(
    [string]$Destination = "Z:\\WooMi_Haja_AI"
)

$ErrorActionPreference = "Stop"

$sourceRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$destinationRoot = [System.IO.Path]::GetFullPath($Destination)

if ($destinationRoot.TrimEnd('\\') -eq $destinationRoot.Substring(0, 3).TrimEnd('\\')) {
    throw "Refusing to copy to a drive root. Specify a subfolder such as Z:\\WooMi_Haja_AI."
}

New-Item -ItemType Directory -Path $destinationRoot -Force | Out-Null

$requiredFiles = @(
    "README.md",
    "CURRENT_STATUS.md",
    "NEXT_TASK.md",
    "OPERATIONS.md",
    "pyproject.toml",
    "Dockerfile",
    "database\\migrations\\001_apartment_ai_core.sql",
    "contracts\\v2\\openapi.yaml",
    "python\\data_engineering\\__init__.py",
    "python\\data_engineering\\database\\__init__.py",
    "python\\data_engineering\\database\\config.py",
    "python\\data_engineering\\database\\connection.py",
    "python\\vision_ai\\__init__.py",
    "python\\vision_ai\\field_inspection.py",
    "python\\vision_ai\\field_app.py",
    "python\\vision_ai\\inspection_v2.py",
    "python\\vision_ai\\inspection_dev_app.py",
    "python\\vision_ai\\postgres_inspection_store.py",
    "python\\vision_ai\\field_postgres_entrypoint.py",
    "python\\tests\\test_field_inspection_api.py",
    "python\\tests\\test_inspection_http_e2e.py",
    "python\\tests\\test_postgres_inspection_store.py",
    "scripts\\run_field_postgres_server.ps1",
    "scripts\\setup_local_field_db.ps1",
    "scripts\\verify_field_postgres_http.ps1",
    "scripts\\verify_field_postgres_http.py",
    "scripts\\reset_local_postgres_password.ps1",
    "scripts\\reset_local_postgres_password.py",
    "scripts\\build_static_guide_index.py",
    "scripts\\export_minimal_claude_handoff.ps1"
)

# The guide is a self-contained static HTML file: it embeds the detailed reports
# and UI screenshots, so one document is sufficient for the handoff.
$guide = Get-ChildItem -LiteralPath (Join-Path $sourceRoot "docs") -File -Filter "*Index1.8.html" |
    Select-Object -First 1
if ($null -eq $guide) {
    throw "Latest embedded development guide (*Index1.8.html) was not found."
}
$requiredFiles += $guide.FullName.Substring($sourceRoot.Length).TrimStart('\\')

$copied = [System.Collections.Generic.List[string]]::new()
foreach ($relativePath in $requiredFiles) {
    $sourcePath = Join-Path $sourceRoot $relativePath
    if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
        throw "Required source file is missing: $relativePath"
    }

    $targetPath = Join-Path $destinationRoot $relativePath
    New-Item -ItemType Directory -Path (Split-Path -Parent $targetPath) -Force | Out-Null
    Copy-Item -LiteralPath $sourcePath -Destination $targetPath -Force
    $copied.Add($relativePath)
}

$manifest = @(
    "WooMi Haja AI minimal backend handoff",
    "Exported: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss K')",
    "Source: $sourceRoot",
    "",
    "Included: PostgreSQL backend source, migration, API contract, verification scripts, tests, and latest self-contained development guide.",
    "Excluded: .venv, .python-runtime, __pycache__, build artifacts, datasets, images, .env files, local.properties, passwords, DSNs, and Android project files.",
    "",
    "On the destination PC:",
    "1. Install Python 3.11+ and PostgreSQL 18 (or compatible).",
    "2. Create a virtual environment, then run: python -m pip install -e '.[database,serving,test]'",
    "3. Run scripts\\setup_local_field_db.ps1, then scripts\\run_field_postgres_server.ps1.",
    "4. Open the single Index1.8.html file in the docs folder. It is self-contained and opens offline.",
    "",
    "Files copied:"
) + $copied

[System.IO.File]::WriteAllLines((Join-Path $destinationRoot "HANDOFF_MANIFEST.txt"), $manifest, [System.Text.UTF8Encoding]::new($false))

Write-Output "HANDOFF_EXPORT=PASS"
Write-Output "DESTINATION=$destinationRoot"
Write-Output "FILES_COPIED=$($copied.Count)"
Write-Output "MANIFEST=$(Join-Path $destinationRoot 'HANDOFF_MANIFEST.txt')"

param(
    [string]$HostAddress = "0.0.0.0",
    [int]$Port = 8000
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$python = Join-Path $projectRoot ".venv\Scripts\python.exe"

if (-not (Test-Path -LiteralPath $python)) {
    throw "Project virtual environment not found: $python"
}

Set-Location -LiteralPath $projectRoot
& $python -m uvicorn vision_ai.inspection_dev_app:app `
    --app-dir python `
    --host $HostAddress `
    --port $Port

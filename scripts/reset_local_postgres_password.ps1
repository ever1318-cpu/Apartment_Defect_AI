$ErrorActionPreference = "Stop"

$serviceName = "postgresql-x64-18"
$hbaPath = "C:\Program Files\PostgreSQL\18\data\pg_hba.conf"
$projectRoot = Split-Path -Parent $PSScriptRoot
$python = Join-Path $projectRoot ".venv\Scripts\python.exe"
$backupPath = "$hbaPath.codex-password-reset.bak"

$principal = New-Object Security.Principal.WindowsPrincipal(
    [Security.Principal.WindowsIdentity]::GetCurrent()
)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "Run PowerShell as Administrator."
}
if (-not (Test-Path -LiteralPath $hbaPath)) { throw "pg_hba.conf not found." }
if (-not (Test-Path -LiteralPath $python)) { throw "Project Python not found." }
if (Test-Path -LiteralPath $backupPath) {
    throw "A previous reset backup already exists: $backupPath"
}

$original = [IO.File]::ReadAllText($hbaPath)
$temporaryRules = @"
# Temporary local password-reset rules; removed automatically.
host    postgres        postgres        127.0.0.1/32            trust
host    postgres        postgres        ::1/128                 trust

"@

Copy-Item -LiteralPath $hbaPath -Destination $backupPath
try {
    [IO.File]::WriteAllText(
        $hbaPath,
        $temporaryRules + $original,
        (New-Object Text.UTF8Encoding($false))
    )
    Restart-Service -Name $serviceName -Force
    & $python (Join-Path $PSScriptRoot "reset_local_postgres_password.py")
    if ($LASTEXITCODE -ne 0) { throw "Password reset helper failed." }
} finally {
    if (Test-Path -LiteralPath $backupPath) {
        Copy-Item -LiteralPath $backupPath -Destination $hbaPath -Force
        Remove-Item -LiteralPath $backupPath -Force
        Restart-Service -Name $serviceName -Force
    }
}

Write-Output "pg_hba.conf restored and PostgreSQL restarted with SCRAM authentication."

<#+
.SYNOPSIS
Exports Android field app and PostgreSQL backend source for a new developer.
#>
[CmdletBinding()]
param([string]$Destination = 'Z:\WooMi_Haja_AI\DeveloperHandoff')

$ErrorActionPreference = 'Stop'
$backendRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$projectsRoot = Split-Path -Parent $backendRoot
$androidRoot = Join-Path $projectsRoot 'PinSet-App'
$destinationRoot = [IO.Path]::GetFullPath($Destination)
if (-not (Test-Path -LiteralPath $androidRoot -PathType Container)) { throw "Android project was not found: $androidRoot" }
if ($destinationRoot.StartsWith($backendRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Destination must not be inside source.' }

function Copy-RequiredDirectory([string]$Root,[string]$Relative,[string]$TargetRoot) {
  $source=Join-Path $Root $Relative; if(-not (Test-Path -LiteralPath $source -PathType Container)){throw "Missing directory: $source"}
  $target=Join-Path $TargetRoot $Relative; New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force|Out-Null; Copy-Item -LiteralPath $source -Destination $target -Recurse -Force
}
function Copy-RequiredFile([string]$Root,[string]$Relative,[string]$TargetRoot) {
  $source=Join-Path $Root $Relative; if(-not (Test-Path -LiteralPath $source -PathType Leaf)){throw "Missing file: $source"}
  $target=Join-Path $TargetRoot $Relative; New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force|Out-Null; Copy-Item -LiteralPath $source -Destination $target -Force
}

New-Item -ItemType Directory -Path $destinationRoot -Force|Out-Null
$backendTarget=Join-Path $destinationRoot 'Apartment_Defect_AI'; $androidTarget=Join-Path $destinationRoot 'PinSet-App'
New-Item -ItemType Directory -Path $backendTarget,$androidTarget -Force|Out-Null
@('api','contracts','database','python','scripts','docs')|ForEach-Object{Copy-RequiredDirectory $backendRoot $_ $backendTarget}
@('README.md','CURRENT_STATUS.md','NEXT_TASK.md','OPERATIONS.md','pyproject.toml','Dockerfile','CHANGELOG.md')|ForEach-Object{Copy-RequiredFile $backendRoot $_ $backendTarget}
@('android','gradle','scripts')|ForEach-Object{Copy-RequiredDirectory $androidRoot $_ $androidTarget}
@('gradlew','gradlew.bat','settings.gradle.kts','PLAN.md')|ForEach-Object{Copy-RequiredFile $androidRoot $_ $androidTarget}

$readme=@"
# AI 하자점검 개발 인수인계

포함: Android Kotlin/Compose 앱, 카메라·오프라인 저장·동기화·평면도 자산, FastAPI, PostgreSQL 마이그레이션, API 계약, 테스트, 최신 문서.
제외: 암호, .env, local.properties, .venv, .gradle, build, Android SDK, 로컬 DB, workspace/field-media, 사용자 사진, 학습 데이터와 모델.

새 PC:
1. JDK 17, Android SDK(API 35 이상), Android platform-tools, Python 3.11+, PostgreSQL 18 설치.
2. PinSet-App/android/local.properties 생성: sdk.dir=C:\\Users\\<사용자>\\AppData\\Local\\Android\\Sdk
3. Apartment_Defect_AI에서 python -m venv .venv
4. .\\.venv\\Scripts\\python.exe -m pip install -e ".[database,serving,test]"
5. PostgreSQL 비밀번호는 새 PC에서 별도 설정. 저장소에 기록 금지.
6. .\\scripts\\setup_local_field_db.ps1
7. .\\scripts\\run_field_postgres_server.ps1 -Port 8001
8. PinSet-App에서 .\\scripts\\build_field_e2e_debug.ps1 -ApiBaseUrl http://127.0.0.1:8001 -Install
"@
[IO.File]::WriteAllText((Join-Path $destinationRoot 'HANDOFF_README.md'),$readme,[Text.UTF8Encoding]::new($false))
Write-Output "HANDOFF_EXPORT=PASS"
Write-Output "DESTINATION=$destinationRoot"
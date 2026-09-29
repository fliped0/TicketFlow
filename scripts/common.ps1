$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot
function Invoke-TicketFlowMaven {
    param([string[]]$Goals)
    $maven = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if (-not $maven) { throw 'Maven 3.9.12 is required. Add Maven bin to PATH, or use backend/mvnw.cmd.' }
    $version = & $maven.Source --version 2>&1 | Out-String
    if ($version -notmatch 'Apache Maven 3\.9\.12') { throw 'This build is pinned to Maven 3.9.12; use backend/mvnw.cmd if needed.' }
    & $maven.Source -B -ntp -gs "$PSScriptRoot/maven-settings.xml" -s "$PSScriptRoot/maven-settings.xml" "-Dmaven.repo.local=$project/.tools/m2" -f "$project/backend/pom.xml" @Goals
    if ($LASTEXITCODE -ne 0) { throw "Maven failed: $LASTEXITCODE" }
}
function Import-TicketFlowConfig {
    param([ValidateSet('dev','test')] [string]$Mode)
    $path = Join-Path $project "config/local/$Mode.json"
    if (-not (Test-Path -LiteralPath $path)) { throw "Missing local configuration: $path. Run scripts/setup-local.ps1 first." }
    $config = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
    if ($config.database -ne "ticketflow_$Mode") { throw 'Unexpected database; refusing to proceed.' }
    if ($config.username -ne "tf_$Mode") { throw 'Use the dedicated TicketFlow database account.' }
    $env:TF_DB_HOST = '127.0.0.1'
    $env:TF_DB_PORT = '3306'
    $env:TF_DB_NAME = $config.database
    $env:TF_DB_USER = $config.username
    $env:TF_DB_PASSWORD = $config.password
    $env:TF_JWT_PRIVATE_KEY = Join-Path $project 'config/local/jwt-private.pem'
    $env:TF_JWT_PUBLIC_KEY = Join-Path $project 'config/local/jwt-public.pem'
}

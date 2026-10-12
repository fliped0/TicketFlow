# Explicit Windows-only local demo bootstrap; the separate data script requires an existing admin.
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/common.ps1"
if (-not $IsWindows) { throw 'This helper uses Windows user-bound encrypted credentials.' }
Import-TicketFlowConfig -Mode dev
$credentialPath = Join-Path $project 'config/local/frontend-demo-admin.xml'
if (-not (Test-Path -LiteralPath $credentialPath)) {
    $username = 'frontend_admin_' + [Guid]::NewGuid().ToString('N').Substring(0,12)
    $bytes = [Security.Cryptography.RandomNumberGenerator]::GetBytes(24)
    $plain = [Convert]::ToBase64String($bytes)
    $secure = ConvertTo-SecureString $plain -AsPlainText -Force
    $credential = [System.Management.Automation.PSCredential]::new($username,$secure)
    $credential | Export-Clixml -LiteralPath $credentialPath
    $plain = $null
} else {
    $credential = Import-Clixml -LiteralPath $credentialPath
    if ($credential -isnot [System.Management.Automation.PSCredential]) { throw 'Invalid local demo credential file.' }
}
$env:TF_ADMIN_USERNAME = $credential.UserName
$env:TF_ADMIN_PASSWORD = $credential.GetNetworkCredential().Password
$env:TF_REDIS_ENABLED = 'false'
$env:TF_ASYNC_ENABLED = 'false'
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java.exe).Source }
try {
    & $java -jar "$project/backend/target/ticketflow-0.1.0-SNAPSHOT.jar" --server.address=127.0.0.1 --server.port=8080
    if ($LASTEXITCODE -ne 0) { throw "Demo backend exited: $LASTEXITCODE" }
} finally {
    @('TF_DB_PASSWORD','TF_ADMIN_USERNAME','TF_ADMIN_PASSWORD','TF_REDIS_ENABLED','TF_ASYNC_ENABLED') | ForEach-Object { Remove-Item "Env:$_" -ErrorAction SilentlyContinue }
    $credential = $null
}

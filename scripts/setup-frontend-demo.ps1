param(
    [string]$ApiOrigin = 'http://127.0.0.1:8080',
    [System.Management.Automation.PSCredential]$Credential,
    [switch]$NewBatch
)
$ErrorActionPreference = 'Stop'
if (-not $Credential) {
    $username = Read-Host 'Existing local administrator username'
    $password = Read-Host 'Administrator password' -AsSecureString
    $Credential = [System.Management.Automation.PSCredential]::new($username, $password)
}
$payload = @{username=$Credential.UserName;password=$Credential.GetNetworkCredential().Password} | ConvertTo-Json -Compress
$arguments = @("$PSScriptRoot/frontend-demo.py", '--base', $ApiOrigin)
if ($NewBatch) { $arguments += '--new-batch' }
$previousEncoding = $env:PYTHONIOENCODING
try {
    $env:PYTHONIOENCODING = 'utf-8'
    $payload | & python @arguments
    if ($LASTEXITCODE -ne 0) { throw 'Demo preparation failed; inspect the stage message. Existing data was retained.' }
} finally {
    $payload = $null; $Credential = $null
    if ($null -eq $previousEncoding) { Remove-Item Env:PYTHONIOENCODING -ErrorAction SilentlyContinue }
    else { $env:PYTHONIOENCODING = $previousEncoding }
}

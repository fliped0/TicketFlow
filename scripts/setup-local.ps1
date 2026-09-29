# Requires PowerShell 7.4+ and a MySQL administrator. Only creates TicketFlow resources.
param([string]$MysqlCommand = 'mysql.exe', [string]$AdminUser = 'root')
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot
$local = Join-Path $project 'config/local'
if (Test-Path -LiteralPath "$local/dev.json") { throw 'Local configuration already exists; refusing to overwrite credentials.' }
if (Test-Path -LiteralPath "$local/test.json") { throw 'Local configuration already exists; refusing to overwrite credentials.' }
New-Item -ItemType Directory -Path $local -Force | Out-Null
$sql = @()
foreach ($mode in @('dev','test')) {
    $database = "ticketflow_$mode"
    $user = "tf_$mode"
    $password = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    @{database=$database;username=$user;password=$password} | ConvertTo-Json |
        Set-Content -LiteralPath "$local/$mode.json" -Encoding utf8
    $sql += "CREATE DATABASE $database CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
    $sql += "CREATE USER '$user'@'localhost' IDENTIFIED BY '$password';"
    $sql += "GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON $database.* TO '$user'@'localhost';"
}
$sqlFile = Join-Path $local 'bootstrap.sql'
[IO.File]::WriteAllText($sqlFile, ($sql -join [Environment]::NewLine), [Text.UTF8Encoding]::new($false))
$rsa = [Security.Cryptography.RSA]::Create(2048)
try {
    [IO.File]::WriteAllText("$local/jwt-private.pem", $rsa.ExportPkcs8PrivateKeyPem())
    [IO.File]::WriteAllText("$local/jwt-public.pem", $rsa.ExportSubjectPublicKeyInfoPem())
} finally { $rsa.Dispose() }
$sqlSource = $sqlFile.Replace('\','/')
Write-Host 'Enter the MySQL administrator password at the following prompt. It is not saved.'
& $MysqlCommand --no-defaults --host=127.0.0.1 --port=3306 "--user=$AdminUser" --password --execute="source $sqlSource"
if ($LASTEXITCODE -ne 0) { throw 'Database provisioning failed. DDL may be partially applied; inspect TicketFlow resources before retrying. Saved random credentials are preserved.' }
Remove-Item -LiteralPath $sqlFile
Write-Host 'Created isolated development/test databases and local configuration.'

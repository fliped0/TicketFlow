param(
    [ValidateSet('dev','test')] [string]$Mode = 'dev',
    [long]$TierId = 0,
    [string]$MysqlPath = 'mysql.exe'
)
. "$PSScriptRoot/common.ps1"
if ($TierId -lt 0) { throw 'TierId must be positive or zero for all tiers.' }
$previousMysqlPassword = $env:MYSQL_PWD
try {
    Import-TicketFlowConfig -Mode $Mode
    $env:MYSQL_PWD = $env:TF_DB_PASSWORD
    $scope = if ($TierId -eq 0) { 'NULL' } else { $TierId.ToString([Globalization.CultureInfo]::InvariantCulture) }
    $sql = "SET @tf_reconcile_tier=$scope;`n" + (Get-Content -LiteralPath "$PSScriptRoot/reconcile.sql" -Raw)
    $sql | & $MysqlPath --host=127.0.0.1 --port=3306 "--user=$env:TF_DB_USER" "--database=$env:TF_DB_NAME" --default-character-set=utf8mb4 --batch --raw
    if ($LASTEXITCODE -ne 0) { throw "Reconciliation query failed: $LASTEXITCODE" }
} finally {
    $env:MYSQL_PWD = $previousMysqlPassword
    Remove-Item Env:TF_DB_PASSWORD -ErrorAction SilentlyContinue
}

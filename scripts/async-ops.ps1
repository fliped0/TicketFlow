param(
    [Parameter(Mandatory)] [ValidateSet('audit','rebuild','alerts','replay')] [string]$Operation,
    [long]$SessionId = 0,
    [string]$EventId = ''
)
. "$PSScriptRoot/common.ps1"
if ($Operation -in @('audit','rebuild') -and $SessionId -le 0) { throw 'A positive SessionId is required.' }
if ($Operation -eq 'replay' -and $EventId -notmatch '^[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}$') { throw 'A UUID EventId is required.' }
try {
    Import-TicketFlowConfig -Mode dev
    Import-TicketFlowRedisConfig -Mode dev
    Import-TicketFlowAsyncConfig
    $java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java.exe -ErrorAction Stop).Source }
    & $java -jar "$project/backend/target/ticketflow-0.1.0-SNAPSHOT.jar" --server.address=127.0.0.1 --server.port=0 --ticketflow.async.jobs-enabled=false --ticketflow.expiry.enabled=false "--ticketflow.async.operation=$Operation" "--ticketflow.async.operation-session=$SessionId" "--ticketflow.async.operation-event=$EventId"
    if ($LASTEXITCODE -ne 0) { throw "Async operation failed: $LASTEXITCODE" }
} finally {
    Remove-Item Env:TF_DB_PASSWORD -ErrorAction SilentlyContinue
    Remove-TicketFlowRedisConfig
    Remove-TicketFlowAsyncConfig
}

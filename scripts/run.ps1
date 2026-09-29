. "$PSScriptRoot/common.ps1"
try {
    Import-TicketFlowConfig -Mode dev
    $java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java.exe -ErrorAction Stop).Source }
    & $java -jar "$project/backend/target/ticketflow-0.1.0-SNAPSHOT.jar"
    if ($LASTEXITCODE -ne 0) { throw "Application exited: $LASTEXITCODE" }
} finally { Remove-Item Env:TF_DB_PASSWORD -ErrorAction SilentlyContinue }

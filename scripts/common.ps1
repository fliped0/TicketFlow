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
function Import-TicketFlowRedisConfig {
    param([ValidateSet('dev','test')] [string]$Mode)
    & "$PSScriptRoot/start-tunnel.ps1"
    $sshKey = Join-Path $env:USERPROFILE '.ssh/ticketflow_ecs'
    $server = if($env:TF_ECS_HOST){$env:TF_ECS_HOST}else{'118.178.253.75'}
    $privateConfig = & ssh -i $sshKey -o BatchMode=yes -o StrictHostKeyChecking=yes -o HostKeyAlias=118.178.253.75 -o ConnectTimeout=8 "root@$server" 'cat /opt/ticketflow/.env'
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read middleware credentials over authorized SSH.' }
    $redisPassword = $privateConfig | Where-Object { $_ -match '^REDIS_PASSWORD=[a-f0-9]{48}$' } | Select-Object -First 1
    if (-not $redisPassword) { throw 'Missing server Redis configuration.' }
    $env:TF_REDIS_PASSWORD = $redisPassword.Substring('REDIS_PASSWORD='.Length)
    $env:TF_REDIS_PORT = '16379'
    $env:TF_REDIS_NAMESPACE = "tf:${Mode}:v1"
    $probe = [System.Net.Sockets.TcpClient]::new()
    try {
        if (-not $probe.ConnectAsync('127.0.0.1',16379).Wait(3000)) { throw 'Redis tunnel preflight timed out.' }
        $probe.ReceiveTimeout = 3000
        $probe.SendTimeout = 3000
        $stream = $probe.GetStream()
        $packet = [System.Text.Encoding]::ASCII.GetBytes("AUTH $env:TF_REDIS_PASSWORD`r`nPING`r`n")
        $stream.Write($packet,0,$packet.Length)
        $reader = [System.IO.StreamReader]::new($stream)
        try {
            if ($reader.ReadLine() -ne '+OK' -or $reader.ReadLine() -ne '+PONG') { throw 'Redis authentication preflight failed.' }
        } finally { $reader.Dispose() }
    } finally { $probe.Dispose() }
    if ($Mode -eq 'dev') { $env:TF_REDIS_ENABLED = 'true' }
    else { $env:TF_REDIS_IT = 'true' }
}
function Remove-TicketFlowRedisConfig {
    @('TF_REDIS_PASSWORD','TF_REDIS_PORT','TF_REDIS_NAMESPACE','TF_REDIS_ENABLED','TF_REDIS_IT') |
        ForEach-Object { Remove-Item "Env:$_" -ErrorAction SilentlyContinue }
}
function Import-TicketFlowRabbitConfig {
    & "$PSScriptRoot/start-tunnel.ps1"
    $sshKey = Join-Path $env:USERPROFILE '.ssh/ticketflow_ecs'
    $server = if($env:TF_ECS_HOST){$env:TF_ECS_HOST}else{'118.178.253.75'}
    $privateConfig = & ssh -i $sshKey -o BatchMode=yes -o StrictHostKeyChecking=yes -o HostKeyAlias=118.178.253.75 -o ConnectTimeout=8 "root@$server" 'cat /opt/ticketflow/.env'
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read RabbitMQ configuration over authorized SSH.' }
    $rabbitPassword = $privateConfig | Where-Object { $_ -match '^RABBITMQ_PASSWORD=[a-f0-9]{48}$' } | Select-Object -First 1
    if (-not $rabbitPassword) { throw 'Missing RabbitMQ server configuration.' }
    $env:TF_RABBITMQ_PASSWORD = $rabbitPassword.Substring('RABBITMQ_PASSWORD='.Length)
    $env:TF_RABBITMQ_IT = 'true'
    $basic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("tf_admin:$env:TF_RABBITMQ_PASSWORD"))
    $overview = Invoke-RestMethod -Uri 'http://127.0.0.1:15672/api/overview' -Headers @{ Authorization = "Basic $basic" } -TimeoutSec 5
    if ($overview.rabbitmq_version -ne '4.3.6') { throw 'Unexpected RabbitMQ version; review environment before running the lab.' }
}
function Remove-TicketFlowRabbitConfig {
    Remove-Item Env:TF_RABBITMQ_PASSWORD,Env:TF_RABBITMQ_IT -ErrorAction SilentlyContinue
}
function Import-TicketFlowAsyncConfig {
    & "$PSScriptRoot/start-tunnel.ps1"
    $sshKey=Join-Path $env:USERPROFILE '.ssh/ticketflow_ecs'
    $server=if($env:TF_ECS_HOST){$env:TF_ECS_HOST}else{'118.178.253.75'}
    $privateConfig=& ssh -i $sshKey -o BatchMode=yes -o StrictHostKeyChecking=yes -o HostKeyAlias=118.178.253.75 -o ConnectTimeout=8 "root@$server" 'cat /opt/ticketflow/.env'
    if($LASTEXITCODE -ne 0){throw 'Cannot read scoped application configuration.'}
    $line=$privateConfig | Where-Object {$_ -match '^RABBITMQ_APP_PASSWORD=[a-f0-9]{48}$'} | Select-Object -First 1
    if(-not $line){throw 'Run scripts/setup-async.ps1 to prepare the scoped tf_app account.'}
    $env:TF_RABBITMQ_PASSWORD=$line.Substring('RABBITMQ_APP_PASSWORD='.Length)
    $env:TF_RABBITMQ_USER='tf_app';$env:TF_RABBITMQ_VHOST='/ticketflow-dev';$env:TF_RABBITMQ_PREFIX='tf.dev.async'
    $env:TF_ASYNC_ENABLED='true'
}
function Remove-TicketFlowAsyncConfig {
    @('TF_RABBITMQ_PASSWORD','TF_RABBITMQ_USER','TF_RABBITMQ_VHOST','TF_RABBITMQ_PREFIX','TF_ASYNC_ENABLED') |
        ForEach-Object {Remove-Item "Env:$_" -ErrorAction SilentlyContinue}
}

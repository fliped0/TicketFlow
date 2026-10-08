. "$PSScriptRoot/common.ps1"
try {
    Import-TicketFlowRabbitConfig
    $server = if($env:TF_ECS_HOST){$env:TF_ECS_HOST}else{'118.178.253.75'}
    $sshKey = Join-Path $env:USERPROFILE '.ssh/ticketflow_ecs'
    # Generate once on the authorized development host; capture, never print credentials.
    $privateConfig = & ssh -i $sshKey -o BatchMode=yes -o StrictHostKeyChecking=yes -o HostKeyAlias=118.178.253.75 -o ConnectTimeout=8 "root@$server" 'cd /opt/ticketflow && if ! grep -q "^RABBITMQ_APP_PASSWORD=" .env; then printf "RABBITMQ_APP_PASSWORD=%s\n" "$(openssl rand -hex 24)" >> .env; fi; chmod 600 .env; cat .env'
    if($LASTEXITCODE -ne 0){throw 'Cannot prepare scoped application credentials.'}
    $line=$privateConfig | Where-Object {$_ -match '^RABBITMQ_APP_PASSWORD=[a-f0-9]{48}$'} | Select-Object -First 1
    if(-not $line){throw 'Missing scoped application password.'}
    $appPassword=$line.Substring('RABBITMQ_APP_PASSWORD='.Length)
    $basic=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("tf_admin:$env:TF_RABBITMQ_PASSWORD"))
    $headers=@{Authorization="Basic $basic"}
    function Set-AsyncResource([string]$Path,[hashtable]$Body) {
        Invoke-RestMethod -Method Put -Uri "http://127.0.0.1:15672/api/$Path" -Headers $headers -ContentType 'application/json' -Body ($Body | ConvertTo-Json -Depth 5 -Compress) -TimeoutSec 8 | Out-Null
    }
    Set-AsyncResource 'vhosts/%2Fticketflow-dev' @{}
    Set-AsyncResource 'users/tf_app' @{password=$appPassword;tags=''}
    $pattern='^tf\.dev\.async\..*'
    Set-AsyncResource 'permissions/%2Fticketflow-dev/tf_app' @{configure=$pattern;write=$pattern;read=$pattern}
    Set-AsyncResource 'policies/%2Fticketflow-dev/async-dlx' @{pattern=$pattern;priority=1;'apply-to'='queues';definition=@{'dead-letter-strategy'='at-least-once';overflow='reject-publish'}}
    Write-Output 'Prepared tf_app without management tags, restricted to /ticketflow-dev and tf.dev.async.*.'
} finally {Remove-TicketFlowRabbitConfig}

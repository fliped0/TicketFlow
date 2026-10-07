param([switch]$UnitOnly,[switch]$WithRedis,[switch]$WithRabbitMQ,[switch]$WithAsync,[switch]$Clean)
. "$PSScriptRoot/common.ps1"
if($WithAsync) { $WithRedis=$true; $WithRabbitMQ=$true }
$tunnelWatch=$null
try {
    if ($UnitOnly) { Invoke-TicketFlowMaven -Goals @('test') }
    else {
        Import-TicketFlowConfig -Mode test
        if ($WithRedis) { Import-TicketFlowRedisConfig -Mode test }
        if ($WithRabbitMQ) { Import-TicketFlowRabbitConfig }
        if ($WithAsync) { $env:TF_ASYNC_IT='true' }
        if($WithRedis -or $WithRabbitMQ) {
            # Transport supervision lasts only for this verification run. It does not
            # retry assertions, buy/start ECS, or replace partially occupied ports.
            $tunnelWatch=Start-Job -ArgumentList "$PSScriptRoot/start-tunnel.ps1",$PID -ScriptBlock {
                param($script,$ownerPid)
                $ports=@(16379,15673,15672)
                while(Get-Process -Id $ownerPid -ErrorAction SilentlyContinue) {
                    $present=@(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object {$_.LocalPort -in $ports} | Select-Object -ExpandProperty LocalPort -Unique)
                    if($present.Count -eq 0) {
                        Add-Content -LiteralPath (Join-Path (Split-Path (Split-Path $script)) '.tools/tunnel-reconnect.log') -Value ([DateTime]::UtcNow.ToString('o')+' all tunnel listeners lost; reconnecting')
                        try { & $script | Out-Null }
                        catch {
                            Add-Content -LiteralPath (Join-Path (Split-Path (Split-Path $script)) '.tools/tunnel-reconnect.log') -Value ([DateTime]::UtcNow.ToString('o')+' reconnect failed; next supervision cycle will retry')
                            Start-Sleep -Seconds 5
                        }
                    } elseif($present.Count -ne 3) {throw 'Partial tunnel port occupancy; refusing automatic replacement.'}
                    Start-Sleep -Seconds 1
                }
            }
        }
        $goals = if ($Clean) { @('clean','verify') } else { @('verify') }
        Invoke-TicketFlowMaven -Goals $goals
    }
} finally {
    if($tunnelWatch) {
        $watchFailed=$tunnelWatch.State -eq 'Failed'
        Stop-Job $tunnelWatch -ErrorAction SilentlyContinue
        Remove-Job $tunnelWatch -Force -ErrorAction SilentlyContinue
        if($watchFailed){Write-Warning 'Tunnel supervision failed; inspect connectivity before interpreting the run.'}
    }
    Remove-Item Env:TF_DB_PASSWORD -ErrorAction SilentlyContinue
    if ($WithRedis) { Remove-TicketFlowRedisConfig }
    if ($WithRabbitMQ) { Remove-TicketFlowRabbitConfig }
    Remove-Item Env:TF_ASYNC_IT -ErrorAction SilentlyContinue
}

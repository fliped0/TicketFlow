param([switch]$UnitOnly,[switch]$WithRedis,[switch]$Clean)
. "$PSScriptRoot/common.ps1"
try {
    if ($UnitOnly) { Invoke-TicketFlowMaven -Goals @('test') }
    else {
        Import-TicketFlowConfig -Mode test
        if ($WithRedis) { Import-TicketFlowRedisConfig -Mode test }
        $goals = if ($Clean) { @('clean','verify') } else { @('verify') }
        Invoke-TicketFlowMaven -Goals $goals
    }
} finally {
    Remove-Item Env:TF_DB_PASSWORD -ErrorAction SilentlyContinue
    if ($WithRedis) { Remove-TicketFlowRedisConfig }
}

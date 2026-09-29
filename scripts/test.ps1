param([switch]$UnitOnly)
. "$PSScriptRoot/common.ps1"
try {
    if ($UnitOnly) { Invoke-TicketFlowMaven -Goals @('test') }
    else {
        Import-TicketFlowConfig -Mode test
        Invoke-TicketFlowMaven -Goals @('verify')
    }
} finally { Remove-Item Env:TF_DB_PASSWORD -ErrorAction SilentlyContinue }

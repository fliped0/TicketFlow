param([int]$RetryBaseMillis = 400,
    [ValidateSet('All','boundedBackoffThenSuccessfulRetry')] [string]$TestCase = 'All')
. "$PSScriptRoot/common.ps1"
if ($RetryBaseMillis -lt 100 -or $RetryBaseMillis -gt 2000) { throw 'RetryBaseMillis must be 100..2000.' }
try {
    Import-TicketFlowConfig -Mode test
    Import-TicketFlowRabbitConfig
    $env:TF_MQ_LAB_RETRY_MS = [string]$RetryBaseMillis
    $selection = if ($TestCase -eq 'All') { 'RabbitMqLabIT' } else { "RabbitMqLabIT#$TestCase" }
    Invoke-TicketFlowMaven -Goals @('verify',"-Dit.test=$selection")
} finally {
    Remove-Item Env:TF_DB_PASSWORD,Env:TF_MQ_LAB_RETRY_MS -ErrorAction SilentlyContinue
    Remove-TicketFlowRabbitConfig
}

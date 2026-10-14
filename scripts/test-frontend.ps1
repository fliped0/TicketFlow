param([switch]$Live,[switch]$Async)
. "$PSScriptRoot/common.ps1"
Push-Location (Join-Path $project 'frontend')
try {
    & python "$PSScriptRoot/test_frontend_demo.py"
    if ($LASTEXITCODE -ne 0) { throw 'Demo recovery safety tests failed.' }
    & npm.cmd run build
    if ($LASTEXITCODE -ne 0) { throw 'Frontend typecheck/build failed.' }
    & npm.cmd test
    if ($LASTEXITCODE -ne 0) { throw 'Frontend unit/component tests failed.' }
    & npm.cmd run test:e2e
    if ($LASTEXITCODE -ne 0) { throw 'Mock browser tests failed.' }
    if ($Live -or $Async) {
        Import-TicketFlowConfig -Mode test
        if ($Async) {
            Import-TicketFlowRedisConfig -Mode test
            Import-TicketFlowRabbitConfig
            $env:TF_FRONTEND_ASYNC = 'true'
        }
        & node tests/live-runner.mjs
        if ($LASTEXITCODE -ne 0) { throw 'Real Java/MySQL browser integration failed; see .tools/frontend-live.' }
    }
} finally {
    if ($Async) { Remove-TicketFlowRedisConfig; Remove-TicketFlowRabbitConfig; Remove-Item Env:TF_FRONTEND_ASYNC -ErrorAction SilentlyContinue }
    Remove-Item Env:TF_DB_PASSWORD -ErrorAction SilentlyContinue
    Pop-Location
}

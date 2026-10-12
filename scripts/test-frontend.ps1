param([switch]$Live)
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
    if ($Live) {
        Import-TicketFlowConfig -Mode test
        & node tests/live-runner.mjs
        if ($LASTEXITCODE -ne 0) { throw 'Real Java/MySQL browser integration failed; see .tools/frontend-live.' }
    }
} finally {
    Remove-Item Env:TF_DB_PASSWORD -ErrorAction SilentlyContinue
    Pop-Location
}

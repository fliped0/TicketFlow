param([switch]$Live,[switch]$Gateway,[switch]$Confirmations,[switch]$Evaluation,[switch]$Faults)
$ErrorActionPreference = 'Stop'
if ($Gateway -and -not $Live) { throw '-Gateway requires -Live; it sends real model requests.' }
if (($Confirmations -or $Evaluation -or $Faults) -and -not $Live) { throw 'Live checks require -Live.' }
if ($Evaluation -and -not $Gateway) { throw '-Evaluation requires -Gateway.' }
$agentProject = Split-Path $PSScriptRoot
$agentPython = Join-Path $agentProject 'agent/.venv/Scripts/python.exe'
if (-not (Test-Path -LiteralPath $agentPython)) { throw 'Install locked dependencies with uv sync --project agent --locked first.' }
Push-Location $agentProject
try {
    & "$agentProject/agent/.venv/Scripts/ruff.exe" check agent
    if ($LASTEXITCODE -ne 0) { throw 'Agent lint failed.' }
    & $agentPython -m pytest agent/tests -q --junitxml=.tools/agent-results.xml
    if ($LASTEXITCODE -ne 0) { throw 'Agent tests failed.' }
    if ($Live) {
        . "$PSScriptRoot/common.ps1"
        Import-TicketFlowConfig -Mode test
        try {
            $agentLiveArgs = @('agent/tests/live_smoke.py')
            if ($Gateway) { $agentLiveArgs += '--gateway' }
            if ($Confirmations) { $agentLiveArgs += '--confirmations' }
            if ($Evaluation) { $agentLiveArgs += '--evaluation' }
            if ($Faults) { $agentLiveArgs += '--faults' }
            & $agentPython @agentLiveArgs
            if ($LASTEXITCODE -ne 0) { throw 'Agent live integration failed.' }
        } finally {
            Remove-Item Env:TF_DB_PASSWORD -ErrorAction SilentlyContinue
        }
    }
} finally { Pop-Location }

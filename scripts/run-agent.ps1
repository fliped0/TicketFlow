param([ValidateSet('disabled','demo','gateway')] [string]$ModelMode = 'disabled', [switch]$Client,[switch]$Chat)
$ErrorActionPreference = 'Stop'
$agentRoot = Join-Path (Split-Path $PSScriptRoot) 'agent'
$agentPython = Join-Path $agentRoot '.venv/Scripts/python.exe'
if (-not (Test-Path -LiteralPath $agentPython)) { throw 'Install locked dependencies with uv sync --project agent --locked first.' }
$previousMode = $env:TF_AGENT_MODEL_MODE
Push-Location $agentRoot
try {
    if ($Client) {
        if ($Chat) { & $agentPython demo.py --chat }
        else { & $agentPython demo.py }
    }
    else {
        $env:TF_AGENT_MODEL_MODE = $ModelMode
        & $agentPython -m uvicorn ticketflow_agent.app:create_app --factory --host 127.0.0.1 --port 8090 --no-access-log
    }
    if ($LASTEXITCODE -ne 0) { throw "Agent exited: $LASTEXITCODE" }
} finally {
    Pop-Location
    $env:TF_AGENT_MODEL_MODE = $previousMode
}

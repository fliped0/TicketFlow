param([switch]$AllowHttp,[switch]$ShareOrderData,[switch]$Replace)
$ErrorActionPreference = 'Stop'
$agentRoot = Join-Path (Split-Path $PSScriptRoot) 'agent'
$agentPython = Join-Path $agentRoot '.venv/Scripts/python.exe'
if (-not (Test-Path -LiteralPath $agentPython)) { throw 'Install locked Agent dependencies first.' }
$agentArguments = @("$agentRoot/setup_gateway.py")
if ($AllowHttp) { $agentArguments += '--allow-http' }
if ($ShareOrderData) { $agentArguments += '--share-order-data' }
if ($Replace) { $agentArguments += '--replace' }
& $agentPython @agentArguments
if ($LASTEXITCODE -ne 0) { throw 'Local gateway configuration did not complete.' }

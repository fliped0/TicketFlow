param([string]$Server = $(if($env:TF_ECS_HOST){$env:TF_ECS_HOST}else{'118.178.253.75'}))
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot
$ports = @(16379,15673,15672)
$listeners = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -in $ports })
$present = @($listeners.LocalPort | Select-Object -Unique)
if ($present.Count -eq 3) { Write-Output 'TicketFlow tunnel ports already listening; reusing existing tunnel.'; return }
if ($present.Count -gt 0) { throw 'Some TicketFlow tunnel ports are occupied; refusing to replace an existing connection.' }
$sshKey = Join-Path $env:USERPROFILE '.ssh/ticketflow_ecs'
if (-not (Test-Path -LiteralPath $sshKey)) { throw 'Missing TicketFlow ECS SSH key.' }
$ssh = (Get-Command ssh.exe -ErrorAction Stop).Source
$toolDir = Join-Path $project '.tools'
New-Item -ItemType Directory -Force -Path $toolDir | Out-Null
$arguments = @('-i',('"' + $sshKey + '"'),'-N','-o','BatchMode=yes','-o','StrictHostKeyChecking=yes',
    '-o','HostKeyAlias=118.178.253.75',
    '-o','ConnectTimeout=8','-o','ExitOnForwardFailure=yes','-o','ServerAliveInterval=30','-o','ServerAliveCountMax=3',
    '-L','127.0.0.1:16379:127.0.0.1:6379','-L','127.0.0.1:15673:127.0.0.1:5672',
    '-L','127.0.0.1:15672:127.0.0.1:15672',("root@$Server"))
$process = Start-Process -FilePath $ssh -ArgumentList $arguments -WindowStyle Hidden -PassThru `
    -RedirectStandardError (Join-Path $toolDir 'tunnel.stderr.log') -RedirectStandardOutput (Join-Path $toolDir 'tunnel.stdout.log')
$deadline = [DateTime]::UtcNow.AddSeconds(12)
while ([DateTime]::UtcNow -lt $deadline) {
    if ($process.HasExited) { throw 'SSH tunnel exited; inspect .tools/tunnel.stderr.log (no credentials are logged).' }
    $ready = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -in $ports } | Select-Object -ExpandProperty LocalPort -Unique)
    if ($ready.Count -eq 3) {
        $process.Id | Set-Content -LiteralPath (Join-Path $toolDir 'tunnel.pid')
        Write-Output "TicketFlow SSH tunnel started (PID $($process.Id)); ports 16379/15673/15672."
        return
    }
    Start-Sleep -Milliseconds 200
}
if (-not $process.HasExited) { Stop-Process -Id $process.Id }
throw 'SSH tunnel did not establish all ports within 12 seconds.'

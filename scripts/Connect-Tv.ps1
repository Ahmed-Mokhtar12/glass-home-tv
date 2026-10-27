# Connect to the TV over the network and wait until it is authorized.
# Run:  powershell -ExecutionPolicy Bypass -File scripts\Connect-Tv.ps1 [-Ip <TV-IP>]
param([string]$Ip = $(if ($env:TV_IP) { $env:TV_IP } else { throw 'Set your TV address first, for example:  $env:TV_IP = "192.168.1.50"' }), [int]$WaitSeconds = 90)

$env:TV_IP = $Ip
Import-Module (Join-Path $PSScriptRoot 'TvTools.psm1') -Force -DisableNameChecking

$c = New-Object Net.Sockets.TcpClient
$open = $c.BeginConnect($Ip, 5555, $null, $null).AsyncWaitHandle.WaitOne(2000) -and $c.Connected
$c.Close()
if (-not $open) {
    Write-Host "Port 5555 on $Ip is closed. On the TV: Settings > Device Preferences > Developer options > USB debugging = On." -ForegroundColor Red
    Write-Host "If it is already on, switch it off and on again, or restart the TV, then rerun this." -ForegroundColor Red
    exit 1
}

Invoke-Tv connect "${Ip}:5555"
$deadline = (Get-Date).AddSeconds($WaitSeconds)
do {
    $state = (Invoke-Tv devices) | Where-Object { $_ -like "${Ip}:5555*" }
    if ($state -match '\bdevice$') { Write-Host "Connected: $state" -ForegroundColor Green; exit 0 }
    if ($state -match 'unauthorized') { Write-Host 'On the TV: tick "Always allow from this computer", then OK.' -ForegroundColor Yellow }
    elseif (-not $state) { Invoke-Tv connect "${Ip}:5555" | Out-Null }
    Start-Sleep -Seconds 3
} while ((Get-Date) -lt $deadline)
Write-Host "Not authorized after $WaitSeconds s. Rerun once you've approved the prompt on the TV." -ForegroundColor Red
exit 1

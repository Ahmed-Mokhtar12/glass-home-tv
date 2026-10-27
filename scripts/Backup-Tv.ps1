# Read-only snapshot of the TV before any change, plus an undo.ps1 for it.
# Run:  powershell -ExecutionPolicy Bypass -File scripts\Backup-Tv.ps1
Import-Module (Join-Path $PSScriptRoot 'TvTools.psm1') -Force -DisableNameChecking

$Keys = 'window_animation_scale', 'transition_animation_scale', 'animator_duration_scale',
        'private_dns_mode', 'private_dns_specifier'

$dir = Join-Path $Root ('backup\' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force $dir | Out-Null

function Save([string]$Name, [string]$Cmd) {
    $out = Invoke-Tv shell $Cmd
    if ($LASTEXITCODE -ne 0) {
        # package and settings lists are what undo depends on; the rest is reference only
        if ($Name -match '^(packages|settings)-') { throw "adb shell $Cmd failed: $out" }
        Write-Warning "adb shell $Cmd failed: $out"
    }
    Set-Content -Path (Join-Path $dir $Name) -Value $out -Encoding UTF8
}

Use-TvActivity 'Backup' {
    Save 'getprop.txt'                  'getprop'
    Save 'packages-all.txt'             'pm list packages -f -U'
    Save 'packages-disabled-before.txt' 'pm list packages -d'
    Save 'packages-system.txt'          'pm list packages -s'
    Save 'packages-3rdparty.txt'        'pm list packages -3'
    Save 'settings-global.txt'          'settings list global'
    Save 'settings-secure.txt'          'settings list secure'
    Save 'settings-system.txt'          'settings list system'
    # Inputs for the never-touch set
    Save 'home-resolver.txt'            'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'
    Save 'ime.txt'                      'ime list -a -s'
    Save 'accessibility.txt'            'settings get secure enabled_accessibility_services'
    Save 'tv-inputs.txt'                'dumpsys tv_input'
    Save 'launcher-apps.txt'            'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER'

    $lines = foreach ($k in $Keys) { "global`t$k`t" + ((Invoke-Tv shell settings get global $k) -join '').Trim() }
    Set-Content -Path (Join-Path $dir 'settings-before.txt') -Value $lines -Encoding UTF8
}

# Packages this project disables or installs are appended to these as it goes
New-Item -ItemType File -Force (Join-Path $dir 'disabled-by-us.txt'), (Join-Path $dir 'installed-by-us.txt') | Out-Null

$undo = @'
# Undo everything recorded in this backup folder. Preview first:  .\undo.ps1 -DryRun
param([switch]$DryRun)
$here = $PSScriptRoot
$adb  = Join-Path (Split-Path (Split-Path $here -Parent) -Parent) 'tools\platform-tools\adb.exe'
$ip   = if ($env:TV_IP) { $env:TV_IP } else { throw 'Set your TV address first, for example:  $env:TV_IP = "192.168.1.50"' }
$cmds = @()
# 1. Settings back to their original values ("null" means the key did not exist)
foreach ($row in Get-Content (Join-Path $here 'settings-before.txt')) {
    $ns, $key, $val = $row -split "`t", 3
    if ($val -eq 'null' -or -not $val) { $cmds += "settings delete $ns $key" } else { $cmds += "settings put $ns $key $val" }
}
# 2. Re-enable only the packages this project disabled (newest first)
$dis = @(Get-Content (Join-Path $here 'disabled-by-us.txt') | Where-Object { $_ })
[array]::Reverse($dis)
foreach ($p in $dis) { $cmds += "pm enable $p" }
# 3. Remove apps this project installed
foreach ($p in Get-Content (Join-Path $here 'installed-by-us.txt') | Where-Object { $_ }) { $cmds += "pm uninstall $p" }

foreach ($c in $cmds) {
    if ($DryRun) { Write-Host "adb -s ${ip}:5555 shell $c" } else { Write-Host "> $c"; & $adb -s "${ip}:5555" shell $c }
}
'@
Set-Content -Path (Join-Path $dir 'undo.ps1') -Value $undo -Encoding UTF8

Write-Host "Backup saved to $dir" -ForegroundColor Green
Get-Content (Join-Path $dir 'settings-before.txt')
$dir

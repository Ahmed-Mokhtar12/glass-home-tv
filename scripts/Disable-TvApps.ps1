# Disable (never uninstall) approved packages one at a time. After each one,
# press HOME and check the launcher still comes up; stop at the first problem.
# Every package disabled here is recorded in <backup>\disabled-by-us.txt for undo.ps1.
param(
    [Parameter(Mandatory)][string]$BackupDir,
    [Parameter(Mandatory)][string[]]$Packages,
    # Don't press HOME (someone is watching); only check the launcher is still the HOME app
    [switch]$Quiet
)
Import-Module (Join-Path $PSScriptRoot 'TvTools.psm1') -Force -DisableNameChecking
$record = Join-Path $BackupDir 'disabled-by-us.txt'

Use-TvActivity 'Disable approved apps' {
    foreach ($p in $Packages) {
        $listed = Invoke-Tv shell "pm list packages $p" | Where-Object { $_ -eq "package:$p" }
        if (-not $listed) { Write-Host "skip $p (not installed)" -ForegroundColor DarkYellow; continue }
        $already = Invoke-Tv shell "pm list packages -d $p" | Where-Object { $_ -eq "package:$p" }
        if ($already) { Write-Host "skip $p (already disabled)" -ForegroundColor DarkYellow; continue }

        $out = (Invoke-Tv shell "pm disable-user --user 0 $p") -join ' '
        if ($out -notmatch 'disabled-user') { Write-Host "FAILED $p : $out" -ForegroundColor Red; break }
        Add-Content -Path $record -Value $p -Encoding UTF8

        if ($Quiet) {
            $homeApp = (Invoke-Tv shell 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME') -join ' '
            if ($homeApp -notmatch 'com\.google\.android\.tvlauncher/') { Write-Host "STOP after $p : HOME no longer resolves to the launcher ($homeApp)" -ForegroundColor Red; break }
            Write-Host "disabled $p - launcher still default" -ForegroundColor Green
            continue
        }
        Send-TvKey HOME -DelayMs 2500
        $focus = Get-TvFocus
        $shot = Save-TvScreenshot ("after-" + $p)
        if ($focus -notmatch 'com\.google\.android\.tvlauncher|com\.ahmedfamily\.glasshome') {
            Write-Host "STOP after $p : home screen not in front ($focus). Screenshot: $shot" -ForegroundColor Red
            break
        }
        Write-Host "disabled $p - home OK" -ForegroundColor Green
    }
}

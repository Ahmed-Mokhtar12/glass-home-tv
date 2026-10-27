# Live monitor: prints every TV command with its timestamp, and streams the
# TV's logcat only while a command is running (logs\busy.flag present).
# Run:  powershell -ExecutionPolicy Bypass -File scripts\Monitor.ps1
param([string]$Serial = $(if ($env:TV_IP) { "$($env:TV_IP):5555" } else { throw 'Set your TV address first, for example:  $env:TV_IP = "192.168.1.50"' }))

$Root   = Split-Path $PSScriptRoot -Parent
$Adb    = Join-Path $Root 'tools\platform-tools\adb.exe'
$CmdLog = Join-Path $Root 'logs\commands.log'
$Busy   = Join-Path $Root 'logs\busy.flag'
# app starts plus anything warning-level or worse (adb quotes each argument for the TV's shell itself)
$Filter = 'ActivityTaskManager:I ActivityManager:I PackageManager:I *:W'

New-Item -ItemType Directory -Force (Split-Path $CmdLog) | Out-Null
if (-not (Test-Path $CmdLog)) { New-Item -ItemType File $CmdLog | Out-Null }
$Host.UI.RawUI.WindowTitle = 'TV monitor'
Write-Host "TV monitor for $Serial - commands below; logcat shows only while a command runs. Ctrl+C stops." -ForegroundColor Yellow

$fs = [IO.File]::Open($CmdLog, 'Open', 'Read', 'ReadWrite, Delete')
[void]$fs.Seek(0, 'End')
$reader = New-Object IO.StreamReader($fs)
$proc = $null
$task = $null

function Start-Logcat {
    # Start from slightly before the command began, in the TV's own clock
    $since = 0
    try {
        $start = [double](Get-Content $Busy -TotalCount 1 -ErrorAction Stop) / 1000
        $tvNow = [double]((& $Adb -s $Serial shell date +%s 2>$null) | Select-Object -First 1)
        $pcNow = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() / 1000
        if ($tvNow -gt 0) { $since = $start + ($tvNow - $pcNow) - 1 }
    } catch { }
    $t = if ($since -gt 0) { $since.ToString('0.000', [Globalization.CultureInfo]::InvariantCulture) } else { '1' }
    $psi = New-Object Diagnostics.ProcessStartInfo $Adb
    $psi.Arguments = "-s $Serial logcat -v time -T $t $Filter"
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.CreateNoWindow = $true
    try { [Diagnostics.Process]::Start($psi) } catch { $null }
}

function Write-LogcatLine([string]$l) {
    $color = if ($l -match '^\S+ \S+ [EF]/') { 'Red' } elseif ($l -match '^\S+ \S+ W/') { 'DarkYellow' } else { 'DarkGray' }
    Write-Host "    | $l" -ForegroundColor $color
}

function Stop-Logcat {
    if (-not $script:proc) { return }
    while ($script:task -and $script:task.Wait(300)) {
        $l = $script:task.Result
        if ($null -eq $l) { break }
        Write-LogcatLine $l
        $script:task = $script:proc.StandardOutput.ReadLineAsync()
    }
    try { $script:proc.Kill() } catch { }
    $script:proc = $null
    $script:task = $null
}

try {
    while ($true) {
        while ($null -ne ($line = $reader.ReadLine())) {
            $color = switch -Regex ($line) {
                '\] > '     { 'Cyan'; break }
                '\] < ok'   { 'Green'; break }
                '\] < exit' { 'Red'; break }
                '\] =='     { 'Magenta'; break }
                default     { 'Gray' }
            }
            Write-Host $line -ForegroundColor $color
        }
        $busyNow = Test-Path $Busy
        if ($busyNow -and -not $proc) {
            $proc = Start-Logcat
            if ($proc) { $task = $proc.StandardOutput.ReadLineAsync() }
        }
        if ($proc) {
            while ($task -and $task.IsCompleted) {
                $l = $task.Result
                if ($null -eq $l) { $task = $null; break }
                Write-LogcatLine $l
                $task = $proc.StandardOutput.ReadLineAsync()
            }
            if (-not $busyNow) { Stop-Logcat }
        }
        Start-Sleep -Milliseconds 100
    }
} finally {
    Stop-Logcat
    $reader.Dispose()
}

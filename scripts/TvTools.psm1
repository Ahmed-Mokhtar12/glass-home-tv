# Helpers for driving the Sony BRAVIA over ADB.
# Every adb call goes through Invoke-Tv, which logs it to logs\commands.log and
# raises logs\busy.flag while it runs, so Monitor.ps1 can show the command and
# stream logcat only for its duration.

$Root   = Split-Path $PSScriptRoot -Parent
$Adb    = Join-Path $Root 'tools\platform-tools\adb.exe'
$LogDir = Join-Path $Root 'logs'
$CmdLog = Join-Path $LogDir 'commands.log'
$Busy   = Join-Path $LogDir 'busy.flag'
$Shots  = Join-Path $LogDir 'shots'
$TvIp   = if ($env:TV_IP) { $env:TV_IP } else { throw 'Set your TV address first, for example:  $env:TV_IP = "192.168.1.50"' }
$Serial = "${TvIp}:5555"

New-Item -ItemType Directory -Force $LogDir, $Shots | Out-Null

# adb subcommands that talk to the adb server rather than one device
$HostCommands = 'connect', 'disconnect', 'devices', 'version', 'start-server', 'kill-server', 'pair'

function Get-Stamp { (Get-Date).ToString('yyyy-MM-dd HH:mm:ss.fff') }

function Write-CmdLog([string]$Line) {
    # OneDrive or the monitor can hold the file for a moment, so retry briefly
    for ($i = 0; $i -lt 10; $i++) {
        try { [IO.File]::AppendAllText($CmdLog, "$Line`r`n"); return } catch { Start-Sleep -Milliseconds 25 }
    }
}

function Enter-TvBusy([string]$What) {
    # Returns $true when this caller owns the flag (outermost command).
    if (Test-Path $Busy) {
        $age = (Get-Date) - (Get-Item $Busy).LastWriteTime
        if ($age.TotalMinutes -lt 10) { return $false }   # stale flags from a crashed run are taken over
    }
    $ms = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    [IO.File]::WriteAllText($Busy, "$ms`n$What")
    $true
}

function Exit-TvBusy([bool]$Owner) {
    if (-not $Owner) { return }
    Start-Sleep -Milliseconds 400   # let the monitor catch the last logcat lines
    Remove-Item $Busy -ErrorAction SilentlyContinue
}

function Invoke-Tv {
    <#
    .SYNOPSIS
    Run adb against the TV and log it for the monitor. Usage: Invoke-Tv shell 'pm list packages -d'
    Returns adb's output lines; $LASTEXITCODE holds adb's exit code.
    #>
    $adbArgs = @($args | ForEach-Object { [string]$_ })
    $full = @(if ($adbArgs.Count -and $HostCommands -contains $adbArgs[0]) { $adbArgs } else { @('-s', $Serial) + $adbArgs })
    $text = 'adb ' + ($adbArgs -join ' ')
    $owner = Enter-TvBusy $text
    Write-CmdLog "[$(Get-Stamp)] > $text"
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $code = 0
    try {
        $out = & $Adb @full 2>&1 | ForEach-Object { "$_" }
        $code = $LASTEXITCODE
    } finally {
        $mark = if ($code -eq 0) { 'ok' } else { "exit $code" }
        Write-CmdLog ("[{0}] < {1} {2:N2}s" -f (Get-Stamp), $mark, $sw.Elapsed.TotalSeconds)
        Exit-TvBusy $owner
    }
    $global:LASTEXITCODE = $code
    $out
}

function Assert-TvConnected {
    <# .SYNOPSIS Reconnect if the TV dropped off (standby, restart, PC reboot); $true when ready #>
    for ($i = 0; $i -lt 3; $i++) {
        $state = (Invoke-Tv devices) | Where-Object { $_ -like "$Serial*" }
        if ($state -match '\sdevice$') { return $true }
        Invoke-Tv connect $Serial | Out-Null
        Start-Sleep -Seconds 2
    }
    $false
}

function Use-TvActivity {
    <#
    .SYNOPSIS
    Run a script block as one logged activity so logcat stays on across all its adb calls.
    #>
    param([string]$Name, [scriptblock]$Body)
    $owner = Enter-TvBusy $Name
    Write-CmdLog "[$(Get-Stamp)] == $Name"
    try { & $Body } finally {
        Write-CmdLog "[$(Get-Stamp)] == end $Name"
        Exit-TvBusy $owner
    }
}

function Send-TvKey {
    <# .SYNOPSIS Press a remote key, e.g. Send-TvKey DPAD_DOWN -Repeat 2 #>
    param([string]$Key, [int]$Repeat = 1, [int]$DelayMs = 450)
    $code = if ($Key -match '^\d+$' -or $Key -like 'KEYCODE_*') { $Key } else { 'KEYCODE_' + $Key.ToUpper() }
    for ($i = 0; $i -lt $Repeat; $i++) {
        Invoke-Tv shell input keyevent $code | Out-Null
        Start-Sleep -Milliseconds $DelayMs
    }
}

function Send-TvText([string]$Text) {
    # `input text` needs %s for spaces; keep to characters that survive the device shell
    $safe = ($Text -replace "[^A-Za-z0-9 ._,-]", '').Trim() -replace ' ', '%s'
    Invoke-Tv shell "input text '$safe'" | Out-Null
}

function Save-TvScreenshot([string]$Name = 'screen') {
    # screencap goes to the TV first; PowerShell 5.1 redirection would corrupt the PNG
    $file = Join-Path $Shots ('{0}-{1}.png' -f (Get-Date -Format 'yyyyMMdd-HHmmss'), $Name)
    Invoke-Tv shell screencap -p /sdcard/tvctl.png | Out-Null
    Invoke-Tv pull /sdcard/tvctl.png $file | Out-Null
    $file
}

function Get-TvUi {
    <# .SYNOPSIS uiautomator dump of the current screen as [xml], or $null if the app hides its tree #>
    for ($i = 0; $i -lt 3; $i++) {
        $res = Invoke-Tv shell uiautomator dump /sdcard/tvctl-ui.xml
        if ($res -match 'dumped to') {
            $raw = (Invoke-Tv shell cat /sdcard/tvctl-ui.xml) -join "`n"
            try { return [xml]$raw } catch { }
        }
        Start-Sleep -Milliseconds 600
    }
    $null
}

function Get-TvUiText {
    <# .SYNOPSIS Visible text and content descriptions on screen #>
    $ui = Get-TvUi
    if (-not $ui) { return @() }
    $ui.SelectNodes('//node') | ForEach-Object { $_.GetAttribute('text'); $_.GetAttribute('content-desc') } |
        Where-Object { $_ } | Select-Object -Unique
}

function Get-TvFocus {
    <# .SYNOPSIS Package/activity currently in front #>
    $line = Invoke-Tv shell "dumpsys window | grep -E 'mCurrentFocus'" | Select-Object -First 1
    if ($line) { $line.Trim() } else { '' }
}

function Get-TvPlayback {
    <# .SYNOPSIS Media sessions with their playback state (3 = playing) and title #>
    $dump = Invoke-Tv shell dumpsys media_session
    $sessions = @(); $cur = $null
    foreach ($l in $dump) {
        if ($l -match '^\s{4}(\S.*) (\S+)/(\S+) \(userId=') { $cur = [pscustomobject]@{ Package = $Matches[2]; State = $null; Title = $null }; $sessions += $cur }
        elseif ($cur -and $l -match 'package=(\S+)') { $cur.Package = $Matches[1] }
        elseif ($cur -and $l -match 'state=PlaybackState \{state=(\d+)') { $cur.State = [int]$Matches[1] }
        elseif ($cur -and $l -match 'metadata:.*description=(.*)$') { $cur.Title = $Matches[1].Trim() }
    }
    $sessions
}

# --- Screen reading for apps that hide their UI tree (YouTube draws its own UI) ---
# Uses the Windows built-in OCR engine on a TV screenshot.
$script:OcrReady = $false
function Initialize-Ocr {
    if ($script:OcrReady) { return }
    Add-Type -AssemblyName System.Runtime.WindowsRuntime
    $script:AsTask = [System.WindowsRuntimeSystemExtensions].GetMethods() |
        Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' } |
        Select-Object -First 1
    [void][Windows.Storage.StorageFile, Windows.Storage, ContentType = WindowsRuntime]
    [void][Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType = WindowsRuntime]
    [void][Windows.Graphics.Imaging.BitmapDecoder, Windows.Graphics, ContentType = WindowsRuntime]
    [void][Windows.Globalization.Language, Windows.Globalization, ContentType = WindowsRuntime]
    $script:OcrReady = $true
}

function Wait-WinRt($Op, [type]$ResultType) {
    $task = $script:AsTask.MakeGenericMethod($ResultType).Invoke($null, @($Op))
    [void]$task.Wait(-1)
    $task.Result
}

function Read-ImageText([string]$Path) {
    <# .SYNOPSIS OCR lines of an image: Text plus X, Y (centre, in screen pixels) #>
    Initialize-Ocr
    $file    = Wait-WinRt ([Windows.Storage.StorageFile]::GetFileFromPathAsync($Path)) ([Windows.Storage.StorageFile])
    $stream  = Wait-WinRt ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
    $decoder = Wait-WinRt ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
    $bitmap  = Wait-WinRt ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
    $engine  = [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage([Windows.Globalization.Language]::new('en-US'))
    if (-not $engine) { $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages() }
    $result  = Wait-WinRt ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])
    $stream.Dispose()
    foreach ($line in $result.Lines) {
        $r = $line.Words | ForEach-Object { $_.BoundingRect }
        $x1 = ($r | Measure-Object X -Minimum).Minimum; $y1 = ($r | Measure-Object Y -Minimum).Minimum
        $x2 = ($r | ForEach-Object { $_.X + $_.Width } | Measure-Object -Maximum).Maximum
        $y2 = ($r | ForEach-Object { $_.Y + $_.Height } | Measure-Object -Maximum).Maximum
        [pscustomobject]@{ Text = $line.Text; X = [int](($x1 + $x2) / 2); Y = [int](($y1 + $y2) / 2) }
    }
}

function Read-TvScreen([string]$Name = 'screen') {
    <# .SYNOPSIS Screenshot the TV and return its OCR lines (Shot holds the PNG path) #>
    $shot = Save-TvScreenshot $Name
    $lines = @(Read-ImageText $shot)
    # a playing video screenshots as black (DRM), so keep one empty line to carry the path
    if (-not $lines.Count) { $lines = @([pscustomobject]@{ Text = ''; X = 0; Y = 0 }) }
    $lines | Add-Member -NotePropertyName Shot -NotePropertyValue $shot -PassThru
}

Set-Alias tv Invoke-Tv
Export-ModuleMember -Function * -Alias tv -Variable Root, Adb, Serial, TvIp, Shots, LogDir

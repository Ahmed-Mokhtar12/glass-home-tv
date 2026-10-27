# Open YouTube on the TV and play the first search result for a title, using
# real remote key presses so every step is visible on the TV. The screen is
# read (OCR of a screenshot) before each OK; playback is confirmed from the
# TV's media session, because screenshots of a playing video are blank (DRM).
#
# Run:  powershell -ExecutionPolicy Bypass -File scripts\Play-YouTube.ps1 "Costa Rica in 4K 60fps HDR"
#       add -Account "Name" to pick a specific account on the "Who's watching?" screen
param(
    [Parameter(Mandatory, Position = 0)][string]$Query,
    [Alias('Profile')][string]$Account
)
Import-Module (Join-Path $PSScriptRoot 'TvTools.psm1') -Force -DisableNameChecking
$Yt = 'com.google.android.youtube.tv'

function Step([string]$Text) { Write-Host "-> $Text" -ForegroundColor Cyan }
function Fail([string]$Text, $Screen) {
    Write-Host "STOPPED: $Text" -ForegroundColor Red
    if ($Screen) { Write-Host "Screenshot: $($Screen[0].Shot)" -ForegroundColor Red }
    exit 1
}
function Has($Screen, [string]$Pattern) { [bool]($Screen | Where-Object { $_.Text -match $Pattern }) }

function Get-Profiles($Screen) {
    # profile names sit in one row under the avatars; take the fullest text row, left to right
    $cand = @($Screen | Where-Object { $_.Y -gt 300 -and $_.Text -and $_.Text -notmatch "^@|Who.?s watching|Add account|guest|Sign in|Manage|Use YouTube|Kids" })
    if (-not $cand.Count) { return @() }
    $row = $cand | Group-Object { [int]($_.Y / 40) } | Sort-Object Count -Descending | Select-Object -First 1
    @($row.Group | Sort-Object X)
}

function Get-Highlighted($Screen) {
    # the highlighted tile shows the account's @handle right under its name
    $handle = $Screen | Where-Object { $_.Text -match '^@' } | Select-Object -First 1
    if ($handle) { Get-Profiles $Screen | Sort-Object { [math]::Abs($_.X - $handle.X) } | Select-Object -First 1 }
}

$typed = ($Query -replace "[^A-Za-z0-9 ._,-]", '').Trim()
if (-not $typed) { Fail 'The title must contain English letters or digits (the TV keyboard input cannot type other scripts).' }
if (-not (Assert-TvConnected)) { Fail "Cannot reach the TV at $Serial. Is it on, with ADB debugging on? Try scripts\Connect-Tv.ps1." }

Use-TvActivity "Play YouTube: $Query" {
    Step 'Wake the TV and go Home'
    Send-TvKey WAKEUP -DelayMs 800
    Send-TvKey HOME -DelayMs 1500

    Step 'Open YouTube fresh'
    $launch = (Invoke-Tv shell "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER $Yt") | Select-Object -Last 1
    if ($launch -notmatch "^$([regex]::Escape($Yt))/") { Fail "YouTube is not installed or has no launcher entry ($launch)" }
    Invoke-Tv shell "am force-stop $Yt" | Out-Null
    Invoke-Tv shell "am start -n $launch" | Out-Null

    # Wait for YouTube's home (or the account picker) to be drawn
    $screen = $null
    for ($i = 0; $i -lt 12; $i++) {
        Start-Sleep -Seconds 2
        if ((Get-TvFocus) -notmatch [regex]::Escape($Yt)) { continue }
        $screen = Read-TvScreen 'yt-start'
        if ((Has $screen "Who.?s watching") -or (Has $screen '^(Recommended|Search|Home|Shorts)$')) { break }
    }
    if (-not $screen) { Fail 'YouTube did not come to the front.' }

    if (Has $screen "Who.?s watching") {
        Step '"Who''s watching?" screen'
        $profiles = Get-Profiles $screen
        if (-not $profiles.Count) { Fail 'Only sign-in or guest choices are shown. Sign in on the TV yourself, then rerun.' $screen }
        Write-Host "   profiles: $(($profiles | ForEach-Object Text) -join ', ')"
        if ($Account) {
            $idx = [array]::FindIndex($profiles, [Predicate[object]] { param($n) $n.Text -like "*$Account*" })
            if ($idx -lt 0) { Fail "No profile named '$Account' on screen." $screen }
            Send-TvKey DPAD_LEFT -Repeat 6 -DelayMs 300
            if ($idx -gt 0) { Send-TvKey DPAD_RIGHT -Repeat $idx -DelayMs 600 }
            $screen = Read-TvScreen 'yt-profile-pick'
        }
        $pick = Get-Highlighted $screen
        if ($Account -and $pick -and $pick.Text -notlike "*$Account*") { Fail "'$($pick.Text)' is highlighted, not '$Account'." $screen }
        Step "Choose profile '$(if ($pick) { $pick.Text } else { 'the highlighted one' })'"
        Send-TvKey DPAD_CENTER -DelayMs 4000
        $screen = Read-TvScreen 'yt-after-profile'
    }

    Step 'Open Search from the side menu'
    Send-TvKey DPAD_LEFT -DelayMs 1200
    Send-TvKey DPAD_UP -DelayMs 1200
    $screen = Read-TvScreen 'yt-search-menu'
    if (-not (Has $screen '^(SPACE|CLEAR|SEARCH)$')) {
        # menu may have opened on another item; walk to the top entry
        Send-TvKey DPAD_UP -Repeat 3 -DelayMs 600
        $screen = Read-TvScreen 'yt-search-menu'
        if (-not (Has $screen '^(SPACE|CLEAR|SEARCH)$')) { Fail 'Could not find YouTube Search.' $screen }
    }
    Send-TvKey DPAD_CENTER -DelayMs 1500

    Step "Type '$typed'"
    Send-TvText $typed
    Start-Sleep -Seconds 3
    $screen = Read-TvScreen 'yt-typed'
    $head = $typed.Substring(0, [Math]::Min(12, $typed.Length))
    if (-not (Has $screen ('^' + [regex]::Escape($head)))) { Fail "The search box does not show '$typed'." $screen }

    Step 'Move down to the first result'
    $inResults = $false
    for ($i = 0; $i -lt 8; $i++) {
        Send-TvKey DPAD_DOWN -DelayMs 1500
        $screen = Read-TvScreen 'yt-results'
        if (-not (Has $screen '^(SPACE|CLEAR|SEARCH)$')) { $inResults = $true; break }
    }
    if (-not $inResults -or -not (Has $screen '^Search results for')) { Fail 'Search results did not take focus.' $screen }
    $title = ($screen | Where-Object { $_.X -lt 700 -and $_.Y -gt 580 -and $_.Y -lt 700 } | ForEach-Object Text) -join ' '
    Step "Play: $title"
    Send-TvKey DPAD_CENTER -DelayMs 3000

    Step 'Confirm playback'
    # the title arrives a few seconds after playback starts (later still after an ad)
    $playing = $false
    for ($i = 0; $i -lt 15; $i++) {
        $session = Get-TvPlayback | Where-Object { $_.Package -eq $Yt } | Select-Object -First 1
        if ($session -and $session.State -eq 3) {
            $playing = $true
            if ($session.Title -and $session.Title -notmatch '^null') {
                Write-Host "PLAYING: $($session.Title)" -ForegroundColor Green
                return
            }
        }
        Start-Sleep -Seconds 2
    }
    if ($playing) { Write-Host 'PLAYING (YouTube has not reported the title yet - possibly an ad first)' -ForegroundColor Green; return }
    Fail 'YouTube did not report playback.' (Read-TvScreen 'yt-not-playing')
}

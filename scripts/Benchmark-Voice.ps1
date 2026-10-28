# Model comparison for Voice control, in dry-run mode: the TV asks the AI, checks the plan, and presses nothing.
# Each model (or Jev allowed-models list) gets the same requests on the same screens. The saved settings are not changed.
#   powershell -ExecutionPolicy Bypass -File .\scripts\Benchmark-Voice.ps1 -Token <code> [-Runs 1]
# It costs real OpenRouter credit (about $0.001 per request with the cheap models); the total is printed at the end.
param(
    [Parameter(Mandatory)][string]$Token,
    [string]$Ip = $(if ($env:TV_IP) { $env:TV_IP } else { throw 'Set your TV address first, for example:  $env:TV_IP = "192.168.1.50"' }),
    [int]$Runs = 1,
    [double]$MaxCost = 1.0
)
$ErrorActionPreference = 'Continue'
Import-Module (Join-Path $PSScriptRoot 'TvTools.psm1') -Force
$yt = 'com.google.android.youtube.tv'

# name -> @{ model = direct model, or pool = Jev allowed-models list }
$configs = [ordered]@{
    'jev (haiku, gemini-3.8-flash)'   = @{ pool = 'anthropic/claude-haiku-5.5, google/gemini-3.8-flash' }
    'jev (+ ~typesafe/jev-latest)'    = @{ pool = 'anthropic/claude-haiku-5.5, google/gemini-3.8-flash, ~typesafe/jev-latest' }
    'claude-haiku-5.5'                = @{ model = 'anthropic/claude-haiku-5.5' }
    'gemini-2.5-flash-lite'           = @{ model = 'google/gemini-2.5-flash-lite' }
    'mistral-small-3.2-24b'           = @{ model = 'mistralai/mistral-small-3.2-24b-instruct' }
    'qwen3.5-flash'                   = @{ model = 'qwen/qwen3.5-flash-02-23' }
}

# screen, words, expected first step as "action" or "action:arg" (regex); '' = nothing should run
$cases = @(
    @('glass', 'open YouTube', '^open_app:youtube$'),
    @('glass', 'put on some relaxing piano music', '^youtube_play$'),
    @('glass', 'play Amr Diab songs', '^youtube_play$'),
    @('glass', 'search YouTube for cooking videos', '^youtube_search$'),
    @('glass', 'show me videos about the pyramids', '^(youtube_search|youtube_play)$'),
    @('glass', 'I want to watch football highlights', '^youtube_play$'),
    @('glass', 'open Lionz', '^open_app:lionz$'),
    @('glass', 'show my favourite channels on Lionz', '^lionz_live_favourites$'),
    @('glass', 'continue my series on Lionz', '^lionz_continue$'),
    @('glass', 'search Lionz for Bab Al Hara', '^lionz_search$'),
    @('glass', 'find Quran Surah Al Kahf on YouTube', '^(youtube_search|youtube_play)$'),
    @('glass', 'open my YouTube subscriptions', '^youtube_tab:subscriptions$'),
    @('glass', 'go to YouTube shorts', '^youtube_tab:shorts$'),
    @('glass', 'buy YouTube premium', '^$'),
    @('glass', 'open the settings', '^$'),
    @('glass', "don't turn off the TV", '^$'),
    @('glass', 'what time is it', '^$'),
    @('glass', 'all done for tonight, cheers', '^$'),
    @('results', "what's on the screen?", '^$'),
    @('results', 'play the second one', '^key:(right|ok)$'),
    @('results', 'move to the next one', '^key:right$'),
    @('results', 'go down a row', '^key:down$'),
    @('results', 'play this one', '^key:ok$'),
    @('results', 'YouTube home', '^youtube_home$|^youtube_tab:home$'),
    @('results', 'go back to the YouTube home page', '^youtube_home$|^youtube_tab:home$'),
    @('results', 'show the music section', '^youtube_tab:music$'),
    @('results', 'open my library', '^(youtube_tab:library|youtube_library)$'),
    @('results', 'search for chocolate cake instead', '^(youtube_search|youtube_play)$'),
    @('results', 'no, I meant pasta recipes', '^(youtube_search|youtube_play)$'),
    @('results', 'which video is highlighted?', '^$'),
    @('player', 'next video', '^key:next$'),
    @('player', 'previous video', '^key:previous$'),
    @('player', 'skip forward two minutes', '^(seek|key:(right|forward))'),
    @('player', 'start it again from the beginning', '^(key:previous|seek)'),
    @('player', 'what am I watching?', '^$'),
    @('player', 'stop this and go to YouTube home', '^(youtube_home|youtube_tab:home|key:(stop|pause|back))'),
    @('player', 'play something by Ed Sheeran instead', '^youtube_play$'),
    @('player', 'turn it up a bit and skip ahead 30 seconds', '^(key:volume_up|seek)'),
    @('player', 'open Lionz live TV', '^lionz_live$'),
    @('player', 'thanks, that will do', '^$')
)

function View([string]$url) { Invoke-Tv shell "am start -a android.intent.action.VIEW -d '$url' -p $yt -f 0x10000000" | Out-Null }
function Set-Screen([string]$s) {
    switch ($s) {
        'glass'   { Send-TvKey HOME; Start-Sleep 4 }
        'results' { View 'https://www.youtube.com/results?search_query=cooking+videos'; Start-Sleep 7 }
        'player'  {   # a real video (the TV's own "play" skips sponsored results); 25 s lets any pre-roll ad finish
            $b = [Text.Encoding]::UTF8.GetBytes('play Ed Sheeran Perfect')
            Invoke-WebRequest -UseBasicParsing -Uri "http://${Ip}:8765/talk" -Method Post -Body $b -Headers @{ 'X-Token' = $Token } -ContentType 'text/plain; charset=utf-8' -TimeoutSec 30 | Out-Null
            Start-Sleep 30
        }
    }
}
function Ask([string]$words, $cfg) {
    $h = @{ 'X-Token' = $Token; 'X-Dry-Run' = '1' }
    if ($cfg.model) { $h['X-Test-Model'] = $cfg.model }
    if ($cfg.pool) { $h['X-Test-Pool'] = $cfg.pool }
    $b = [Text.Encoding]::UTF8.GetBytes($words)
    try {
        $r = Invoke-WebRequest -UseBasicParsing -Uri "http://${Ip}:8765/talk" -Method Post -Body $b -Headers $h -ContentType 'text/plain; charset=utf-8' -TimeoutSec 30
        [Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray()) | ConvertFrom-Json
    } catch { [pscustomobject]@{ status = 'error'; say = $_.Exception.Message } }
}

$rows = @(); $spent = 0.0
foreach ($screen in 'glass', 'results', 'player') {
    Set-Screen $screen
    foreach ($run in 1..$Runs) {
        foreach ($name in $configs.Keys) {
            foreach ($c in ($cases | Where-Object { $_[0] -eq $screen })) {
                if ($spent -ge $MaxCost) { Write-Host "Stopped: cost cap reached"; break }
                $r = Ask $c[1] $configs[$name]
                $first = if ($r.actions -and $r.actions.Count -gt 0) { $r.actions[0].action + $(if ($r.actions[0].arg) { ':' + $r.actions[0].arg } else { '' }) } else { '' }
                # a pattern without ":" checks only the action; one with ":" checks action and arg
                $cmp = if ($c[2] -match ':') { $first } else { ($first -split ':')[0] }
                $ok = $r.status -eq 'dry' -and $cmp -match $c[2]
                $cost = if ($r.cost) { [double]$r.cost } else { 0 }
                $spent += $cost
                $rows += [pscustomobject]@{ Config = $name; Screen = $screen; Words = $c[1]; Got = $first; Ok = $ok; Ms = [int]$r.ms;
                    Fast = [bool]$r.fast; Picture = [bool]$r.picture; Model = $r.model; Cost = $cost; Say = $r.say }
            }
        }
    }
}
$rows | Export-Csv (Join-Path $LogDir ('benchmark-{0}.csv' -f (Get-Date -Format 'yyyyMMdd-HHmm'))) -NoTypeInformation -Encoding UTF8

"`n{0,-32} {1,7} {2,7} {3,7} {4,9} {5,9}" -f 'config', 'right', 'p50 ms', 'p95 ms', 'cost $', 'pic p50'
foreach ($name in $configs.Keys) {
    $r = @($rows | Where-Object { $_.Config -eq $name -and -not $_.Fast })
    if (-not $r.Count) { continue }
    $ms = @($r | ForEach-Object Ms | Sort-Object); $pm = @($r | Where-Object Picture | ForEach-Object Ms | Sort-Object)
    $p = { param($a, $q) if ($a.Count) { $a[[math]::Min($a.Count - 1, [math]::Floor($a.Count * $q))] } else { 0 } }
    "{0,-32} {1,3}/{2,-3} {3,7} {4,7} {5,9:n4} {6,9}" -f $name, @($r | Where-Object Ok).Count, $r.Count, (& $p $ms 0.5), (& $p $ms 0.95),
        (($r | Measure-Object Cost -Sum).Sum), (& $p $pm 0.5)
}
"`nTotal spent: {0:n4} USD" -f $spent
"Wrong answers:"
$rows | Where-Object { -not $_.Ok -and -not $_.Fast } | ForEach-Object { '  {0,-30} {1,-8} {2,-42} got [{3}]' -f $_.Config, $_.Screen, $_.Words, $_.Got }

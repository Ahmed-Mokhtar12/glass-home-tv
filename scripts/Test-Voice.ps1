# Phrase test for Voice control: sends sample requests to the TV's /talk in dry-run mode, so nothing on the
# TV is pressed, and checks what it would do. Run it before every Glass Home install.
#   powershell -ExecutionPolicy Bypass -File .\scripts\Test-Voice.ps1 -Token <code from the Voice control screen>
# -Live sends the AI cases to OpenRouter for real (needs the AI key saved on the TV); otherwise those cases
# use a canned AI answer and only test how the TV handles it.
param(
    [Parameter(Mandatory)][string]$Token,
    [string]$Ip = $(if ($env:TV_IP) { $env:TV_IP } else { throw 'Set your TV address first, for example:  $env:TV_IP = "192.168.1.50"' }),
    [switch]$Live
)
$ErrorActionPreference = 'Stop'

function Talk([string]$words, [string]$fake) {
    $body = if ($fake -and -not $Live) { @{ words = $words; fake = $fake } | ConvertTo-Json -Compress } else { $words }
    [byte[]]$bytes = [Text.Encoding]::UTF8.GetBytes($body)
    $r = Invoke-WebRequest -UseBasicParsing -Uri "http://${Ip}:8765/talk" -Method Post -Body $bytes `
        -Headers @{ 'X-Token' = $Token; 'X-Dry-Run' = '1' } -ContentType 'text/plain; charset=utf-8' -TimeoutSec 20
    [Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray()) | ConvertFrom-Json
}

# words, expected first action ('' = none), expected end, canned AI answer (for AI cases)
$cases = @(
    @('Volume up', 'key', $false, $null),
    @('turn it down a bit', 'key', $false, $null),
    @('Mute', 'key', $false, $null),
    @('pause', 'key', $false, $null),
    @('go back', 'key', $false, $null),
    @('go home', 'key', $false, $null),
    @('Turn off the TV', 'power_off', $true, $null),
    @('turn on the TV', 'power_on', $false, $null),
    @('Thank you', '', $true, $null),
    @('Thanks so much, that''s all', '', $true, $null),
    @('bye', '', $true, $null),
    @('OK that''s it for now', '', $true, $null),
    @('thanks, turn it down', 'key', $false, $null),
    @('Play Goodbye by Mariah Carey', 'youtube_play', $false, '{"actions":[{"action":"youtube_play","arg":"Goodbye Mariah Carey","count":0}],"say":"Playing Goodbye by Mariah Carey."}'),
    @('Open YouTube', 'open_app', $false, '{"actions":[{"action":"open_app","arg":"youtube","count":0}],"say":"Opening YouTube."}'),
    @('Search YouTube for cooking videos', 'youtube_search', $false, '{"actions":[{"action":"youtube_search","arg":"cooking videos","count":0}],"say":"Searching YouTube for cooking videos."}'),
    @('Show my favourite channels on Lionz', 'lionz_live_favourites', $false, '{"actions":[{"action":"lionz_live_favourites","arg":"","count":0}],"say":"Opening your favourite channels."}'),
    @('open YouTube and search football then play the first one', 'youtube_play', $false, '{"actions":[{"action":"open_app","arg":"youtube","count":0},{"action":"youtube_play","arg":"football","count":0}],"say":"Playing football on YouTube."}'),
    @('Open settings', '', $false, '{"actions":[{"action":"open_settings","arg":"","count":0}],"say":"Opening settings."}'),
    @('Buy the premium plan', '', $false, '{"actions":[],"say":"Sorry, I can''t buy anything."}'),
    @('Play the second video', 'key', $false, '{"actions":[{"action":"key","arg":"right","count":1},{"action":"key","arg":"ok","count":1}],"say":"Playing the second video."}'),
    @('skip ahead 30 seconds', 'seek', $false, $null),
    @('go back 10 seconds', 'seek', $false, $null),
    @('forward two minutes', 'seek', $false, $null),
    @('unmute', 'key', $false, $null),
    @('louder please', 'key', $false, $null),
    @('cancel', '', $false, $null),
    @('', '', $false, $null),
    @('something weird the AI answers badly', '', $false, 'Sure thing!'),
    @('put the TV on mute', 'key', $false, $null),
    @('don''t turn off the TV', '', $false, '{"actions":[],"say":"OK, I''ll leave it on."}'),
    @('turn down the brightness', '', $false, '{"actions":[],"say":"Sorry, I can''t change the brightness."}'),
    @('thank you and goodbye', '', $true, $null),
    @('thanks, have a nice evening', '', $true, $null),
    @('All done, goodbye', '', $true, '{"actions":[],"say":"Goodbye!"}'),
    @('could you turn off the TV please', 'power_off', $true, $null),
    @('Search Lionz for Loki', 'lionz_search', $false, '{"actions":[{"action":"lionz_search","arg":"Loki","count":0}],"say":"Searching Lionz for Loki."}'),
    @('Search YouTube for Amr Diab songs', 'youtube_search', $false, '{"actions":[{"action":"youtube_search","arg":"اغاني عمرو دياب","count":0}],"say":"Searching YouTube for Amr Diab songs."}', 'arabic'),
    @('Play cooking videos in Arabic', 'youtube_play', $false, '{"actions":[{"action":"youtube_play","arg":"وصفات طبخ","count":0}],"say":"Playing Arabic cooking videos."}', 'arabic'),
    @('Search YouTube for relaxing piano music', 'youtube_search', $false, '{"actions":[{"action":"youtube_search","arg":"relaxing piano music","count":0}],"say":"Searching YouTube for relaxing piano music."}', 'latin')
)

$pass = 0; $fail = 0
foreach ($c in $cases) {
    $words, $want, $wantEnd, $fake, $script = $c
    try {
        $r = Talk $words $fake
        $got = if ($r.actions -and $r.actions.Count -gt 0) { $r.actions[0].action } else { '' }
        # seek/pause need a video in front; when nothing plays they go to the AI, so accept either
        $ok = ($got -eq $want -or ($want -eq 'seek' -and -not $r.fast)) -and ([bool]$r.end -eq $wantEnd)
        # 'arabic' / 'latin': the search text must (not) be written in Arabic letters
        $arg = if ($r.actions -and $r.actions.Count -gt 0) { [string]$r.actions[-1].arg } else { '' }
        if ($script -eq 'arabic') { $ok = $ok -and ($arg -match '[؀-ۿ]') }
        if ($script -eq 'latin') { $ok = $ok -and ($arg -notmatch '[؀-ۿ]') }
        if ($script) { $got = "$got [$arg]" }
    } catch { $r = [pscustomobject]@{ say = "ERROR $($_.Exception.Message)" }; $got = '?'; $ok = $false }
    if ($ok) { $pass++ } else { $fail++ }
    $mark = if ($ok) { 'PASS' } else { 'FAIL' }
    '{0}  {1,-48} -> {2,-22} end={3,-5} "{4}"' -f $mark, "'$words'", $got, $r.end, $r.say
}
"`n$pass passed, $fail failed"
if ($fail) { exit 1 }

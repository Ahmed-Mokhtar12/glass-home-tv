# PC tests for the Glass Home logic that needs no Android (FastPath, Turns, ScreenPolicy, TurnLog, ModelPool, YouTubeMenu).
# Nothing touches the TV. Run it before every build:
#   powershell -ExecutionPolicy Bypass -File .\scripts\Test-Logic.ps1
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$jdk  = Join-Path $root 'tools\jdk\bin'
$src  = Join-Path $root 'glasshome\src\com\ahmedfamily\glasshome'
$out  = Join-Path $env:TEMP "glasshome-logic-tests"
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force $out | Out-Null

$files = @('FastPath', 'Turns', 'ScreenPolicy', 'TurnLog', 'ModelPool', 'YouTubeMenu') | ForEach-Object { Join-Path $src "$_.java" }
$files += Join-Path $root 'glasshome\tests\LogicTests.java'
& "$jdk\javac.exe" -encoding UTF-8 -d $out @files
if ($LASTEXITCODE -ne 0) { Write-Host 'Compile failed' -ForegroundColor Red; exit 1 }
& "$jdk\java.exe" -cp $out com.ahmedfamily.glasshome.LogicTests
exit $LASTEXITCODE

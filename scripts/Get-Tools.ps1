# Download the PC tools this project needs, from Google and Microsoft only, into tools\.
# By downloading the Android files you accept Google's Android SDK license:
#   https://developer.android.com/studio/terms
# Run:  powershell -ExecutionPolicy Bypass -File scripts\Get-Tools.ps1
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$Root  = Split-Path $PSScriptRoot -Parent
$tools = Join-Path $Root 'tools'
$dl    = Join-Path $Root 'downloads'
New-Item -ItemType Directory -Force $tools, $dl | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem

# name, URL, the zip's top folder, where it goes under tools\
$items = @(
    @('platform-tools', 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip', 'platform-tools', 'platform-tools'),
    @('build-tools 34', 'https://dl.google.com/android/repository/build-tools_r34-windows.zip', 'android-14', 'android-sdk\build-tools\34.0.0'),
    @('platform 34', 'https://dl.google.com/android/repository/platform-34-ext7_r03.zip', 'android-34', 'android-sdk\platforms\android-34'),
    @('JDK 17', 'https://aka.ms/download-jdk/microsoft-jdk-17-windows-x64.zip', 'jdk-*', 'jdk')
)
foreach ($i in $items) {
    $name, $url, $top, $dest = $i
    $target = Join-Path $tools $dest
    if (Test-Path $target) { Write-Host "have $name" -ForegroundColor DarkGray; continue }
    $zip = Join-Path $dl (Split-Path $url -Leaf)
    if (-not (Test-Path $zip)) {
        Write-Host "download $name ..."
        Invoke-WebRequest $url -OutFile "$zip.part" -UseBasicParsing
        Move-Item "$zip.part" $zip
    }
    Write-Host ("  SHA-256 {0}" -f (Get-FileHash $zip -Algorithm SHA256).Hash) -ForegroundColor DarkGray
    $tmp = Join-Path $dl ('x-' + [IO.Path]::GetFileNameWithoutExtension($zip))
    if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
    [IO.Compression.ZipFile]::ExtractToDirectory($zip, $tmp)
    $inner = Get-ChildItem $tmp -Directory -Filter $top | Select-Object -First 1
    if (-not $inner) { throw "$name : folder '$top' not found inside $zip" }
    New-Item -ItemType Directory -Force (Split-Path $target) | Out-Null
    Move-Item $inner.FullName $target
    Remove-Item $tmp -Recurse -Force
}

$checks = 'platform-tools\adb.exe', 'jdk\bin\javac.exe', 'android-sdk\build-tools\34.0.0\aapt2.exe',
          'android-sdk\build-tools\34.0.0\d8.bat', 'android-sdk\platforms\android-34\android.jar'
$missing = $checks | Where-Object { -not (Test-Path (Join-Path $tools $_)) }
if ($missing) { Write-Host "Missing: $($missing -join ', ')" -ForegroundColor Red; exit 1 }
Write-Host 'All tools are ready in tools\' -ForegroundColor Green

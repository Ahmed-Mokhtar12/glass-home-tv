# Build Glass Home without Gradle: javac -> d8 -> aapt2 -> zipalign -> apksigner.
# Run:  powershell -ExecutionPolicy Bypass -File glasshome\build.ps1
$ErrorActionPreference = 'Stop'
$here  = $PSScriptRoot
$root  = Split-Path $here -Parent
$jdk   = Join-Path $root 'tools\jdk\bin'
$bt    = Join-Path $root 'tools\android-sdk\build-tools\34.0.0'
$jar   = Join-Path $root 'tools\android-sdk\platforms\android-34\android.jar'
$out   = Join-Path $here 'out'
$env:JAVA_HOME = Split-Path $jdk -Parent
$env:PATH = "$jdk;$env:PATH"

function Run([string]$Exe, [string[]]$Arguments) {
    & $Exe @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$(Split-Path $Exe -Leaf) failed ($LASTEXITCODE)" }
}

if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force "$out\gen", "$out\classes", "$out\dex" | Out-Null

# Signing key for this app only (created once, kept next to the project)
$ks = Join-Path $here 'keystore\glasshome.jks'
$pwFile = Join-Path $here 'keystore\password.txt'
if (-not (Test-Path $ks)) {
    New-Item -ItemType Directory -Force (Split-Path $ks) | Out-Null
    $pw = -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 24 | ForEach-Object { [char]$_ })
    Set-Content $pwFile $pw -NoNewline
    Run "$jdk\keytool.exe" @('-genkeypair', '-keystore', $ks, '-alias', 'glasshome', '-keyalg', 'RSA', '-keysize', '2048',
        '-validity', '10000', '-storepass', $pw, '-keypass', $pw, '-dname', 'CN=Glass Home')
}
$pw = Get-Content $pwFile -Raw

# Resources and manifest
Run "$bt\aapt2.exe" @('compile', '--dir', "$here\res", '-o', "$out\res.zip")
Run "$bt\aapt2.exe" @('link', '-o', "$out\base.apk", '-I', $jar, '--manifest', "$here\AndroidManifest.xml",
    '--min-sdk-version', '29', '--target-sdk-version', '29', '--version-code', '17', '--version-name', '2.6',
    '--java', "$out\gen", "$out\res.zip")

# Java -> classes -> dex
$sources = @(Get-ChildItem "$here\src", "$out\gen" -Recurse -Filter *.java | ForEach-Object FullName)
Run "$jdk\javac.exe" (@('-source', '8', '-target', '8', '-bootclasspath', $jar, '-Xlint:-options', '-encoding', 'UTF-8', '-d', "$out\classes") + $sources)
# one jar instead of a list of class files, which grew past cmd's command-line limit
Run "$jdk\jar.exe" @('--create', '--file', "$out\classes.jar", '-C', "$out\classes", '.')
Run "$bt\d8.bat" @('--release', '--min-api', '29', '--lib', $jar, '--output', "$out\dex", "$out\classes.jar")

# Add classes.dex to the APK, align, sign
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open("$out\base.apk", 'Update')
[void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$out\dex\classes.dex", 'classes.dex')
$zip.Dispose()
Run "$bt\zipalign.exe" @('-f', '-p', '4', "$out\base.apk", "$out\aligned.apk")
Run "$bt\apksigner.bat" @('sign', '--ks', $ks, '--ks-key-alias', 'glasshome', '--ks-pass', "pass:$pw", '--out', "$out\GlassHome.apk", "$out\aligned.apk")
Run "$bt\apksigner.bat" @('verify', "$out\GlassHome.apk")
Write-Host "Built $out\GlassHome.apk ($([math]::Round((Get-Item "$out\GlassHome.apk").Length / 1KB)) KB)" -ForegroundColor Green

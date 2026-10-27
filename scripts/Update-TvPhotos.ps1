# Prepare family photos for Glass Home and copy them to the TV, replacing the previous set.
# Each photo is fitted to the 1920x1080 screen; tall or square photos get a soft blurred fill
# at the sides instead of being cropped. Working copies stay in %LOCALAPPDATA% (not OneDrive)
# and are deleted afterwards. On the TV, Glass Home moves them into its private storage.
#
# Run:  powershell -ExecutionPolicy Bypass -File scripts\Update-TvPhotos.ps1 [-Source "C:\TV-Family-Photos"]
param([string]$Source)
Import-Module (Join-Path $PSScriptRoot 'TvTools.psm1') -Force -DisableNameChecking
Add-Type -AssemblyName System.Drawing

if (-not $Source) {
    $Source = @('C:\TV-Family-Photos', (Join-Path $Root 'Family pictures')) |
        Where-Object { Test-Path $_ } |
        Where-Object { Get-ChildItem $_ -Recurse -File -Include *.jpg, *.jpeg, *.png, *.bmp -ErrorAction SilentlyContinue } |
        Select-Object -First 1
}
if (-not $Source) { Write-Host 'No photos found. Copy them to C:\TV-Family-Photos and run this again.' -ForegroundColor Red; exit 1 }

$Pkg    = 'com.ahmedfamily.glasshome'
$Remote = "/sdcard/Android/data/$Pkg/files/incoming"
$W = 1920; $H = 1080
$stage = Join-Path $env:LOCALAPPDATA 'GlassHome\photos'
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Force $stage | Out-Null

$jpeg = [Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object MimeType -eq 'image/jpeg'
$quality = New-Object Drawing.Imaging.EncoderParameters 1
$quality.Param[0] = New-Object Drawing.Imaging.EncoderParameter ([Drawing.Imaging.Encoder]::Quality), 90L

function Draw-Cover($g, $img, $w, $h) {
    $s = [Math]::Max($w / $img.Width, $h / $img.Height)
    $dw = $img.Width * $s; $dh = $img.Height * $s
    $g.DrawImage($img, [float](($w - $dw) / 2), [float](($h - $dh) / 2), [float]$dw, [float]$dh)
}

$seen = @{}
$n = 0
$files = Get-ChildItem $Source -Recurse -File -Include *.jpg, *.jpeg, *.png, *.bmp | Sort-Object Name
foreach ($f in $files) {
    $hash = (Get-FileHash $f.FullName -Algorithm SHA256).Hash
    if ($seen.ContainsKey($hash)) { continue }     # skip exact duplicates ("- Copy" files)
    $seen[$hash] = $true
    $bytes = [IO.File]::ReadAllBytes($f.FullName)
    $ms = New-Object IO.MemoryStream (, $bytes)
    try { $img = [Drawing.Image]::FromStream($ms) } catch { Write-Host "skip $($f.Name) (not a readable image)" -ForegroundColor DarkYellow; continue }
    # turn the picture upright if the camera stored its rotation separately
    if ($img.PropertyIdList -contains 0x0112) {
        switch ([int]$img.GetPropertyItem(0x0112).Value[0]) {
            3 { $img.RotateFlip('Rotate180FlipNone') }
            6 { $img.RotateFlip('Rotate90FlipNone') }
            8 { $img.RotateFlip('Rotate270FlipNone') }
        }
    }
    $out = New-Object Drawing.Bitmap $W, $H
    $g = [Drawing.Graphics]::FromImage($out)
    $g.InterpolationMode = 'HighQualityBicubic'; $g.SmoothingMode = 'HighQuality'; $g.PixelOffsetMode = 'HighQuality'
    $ratio = $img.Width / $img.Height
    if ([Math]::Abs($ratio - $W / $H) / ($W / $H) -lt 0.12) {
        Draw-Cover $g $img $W $H                     # already wide: fill the screen
    } else {
        # soft fill: the same photo shrunk to a few pixels and stretched back up is a strong blur
        $tiny = New-Object Drawing.Bitmap 48, 27
        $tg = [Drawing.Graphics]::FromImage($tiny); $tg.InterpolationMode = 'HighQualityBilinear'
        Draw-Cover $tg $img 48 27; $tg.Dispose()
        $attr = New-Object Drawing.Imaging.ImageAttributes; $attr.SetWrapMode('TileFlipXY')
        $g.DrawImage($tiny, (New-Object Drawing.Rectangle 0, 0, $W, $H), 0, 0, 48, 27, 'Pixel', $attr)
        $g.FillRectangle((New-Object Drawing.SolidBrush ([Drawing.Color]::FromArgb(70, 0, 0, 0))), 0, 0, $W, $H)
        $s = [Math]::Min($W / $img.Width, $H / $img.Height)
        $dw = $img.Width * $s; $dh = $img.Height * $s
        $g.DrawImage($img, [float](($W - $dw) / 2), [float](($H - $dh) / 2), [float]$dw, [float]$dh)
        $tiny.Dispose()
    }
    $n++
    $name = 'photo-{0:D3}.jpg' -f $n
    $out.Save((Join-Path $stage $name), $jpeg, $quality)
    $g.Dispose(); $out.Dispose(); $img.Dispose(); $ms.Dispose()
}
if ($n -eq 0) { Write-Host "No usable photos in $Source" -ForegroundColor Red; exit 1 }
Write-Host "Prepared $n photos from $Source ($($files.Count - $n) duplicates or unreadable skipped)" -ForegroundColor Green

if (-not (Assert-TvConnected)) { Write-Host "Cannot reach the TV at $Serial." -ForegroundColor Red; exit 1 }
Use-TvActivity "Copy $n family photos to Glass Home" {
    Invoke-Tv shell "rm -rf $Remote" | Out-Null
    Invoke-Tv push $stage $Remote | Select-Object -Last 1
    # the manifest goes last: Glass Home only imports a complete set
    $manifest = Join-Path $env:LOCALAPPDATA 'GlassHome\manifest.txt'
    [IO.File]::WriteAllText($manifest, ((Get-ChildItem $stage -Filter *.jpg | ForEach-Object Name) -join "`n") + "`n")
    Invoke-Tv push $manifest "$Remote/manifest.txt" | Out-Null
    Remove-Item $manifest
    Send-TvKey HOME -DelayMs 7000       # Glass Home imports the set and shows a new photo
    $left = (Invoke-Tv shell "ls $Remote 2>/dev/null | wc -l") -join ''
    if ([int]$left -eq 0) { Write-Host 'Glass Home has taken the new photos.' -ForegroundColor Green }
    else { Write-Host "Photos are on the TV; Glass Home will take them the next time it opens ($left files waiting)." -ForegroundColor Yellow }
}
Remove-Item $stage -Recurse -Force

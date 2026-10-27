# Draw the app icon and TV banner (glass drop on a blue-teal gradient) into res\drawable.
Add-Type -AssemblyName System.Drawing
$dir = Join-Path $PSScriptRoot 'res\drawable'
New-Item -ItemType Directory -Force $dir | Out-Null

function New-Art([int]$W, [int]$H, [string]$Path, [string]$Text) {
    $bmp = New-Object Drawing.Bitmap $W, $H
    $g = [Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'; $g.TextRenderingHint = 'AntiAliasGridFit'
    $rect = New-Object Drawing.Rectangle 0, 0, $W, $H
    $bg = New-Object Drawing.Drawing2D.LinearGradientBrush $rect, ([Drawing.Color]::FromArgb(255, 20, 60, 110)), ([Drawing.Color]::FromArgb(255, 40, 170, 170)), 45
    $g.FillRectangle($bg, $rect)
    # glass drop: translucent circle with a bright rim and highlight
    $d = [int]([Math]::Min($W, $H) * 0.62)
    $x = if ($Text) { [int]($H * 0.19) } else { [int](($W - $d) / 2) }
    $y = [int](($H - $d) / 2)
    $g.FillEllipse((New-Object Drawing.SolidBrush ([Drawing.Color]::FromArgb(70, 255, 255, 255))), $x, $y, $d, $d)
    $g.DrawEllipse((New-Object Drawing.Pen ([Drawing.Color]::FromArgb(200, 255, 255, 255)), ([float]($d * 0.04))), $x, $y, $d, $d)
    $g.FillEllipse((New-Object Drawing.SolidBrush ([Drawing.Color]::FromArgb(170, 255, 255, 255))), [int]($x + $d * 0.2), [int]($y + $d * 0.16), [int]($d * 0.3), [int]($d * 0.18))
    if ($Text) {
        $font = New-Object Drawing.Font 'Segoe UI Semibold', ([float]($H * 0.2)), ([Drawing.FontStyle]::Regular), ([Drawing.GraphicsUnit]::Pixel)
        $g.DrawString($Text, $font, [Drawing.Brushes]::White, [float]($x + $d + $H * 0.12), [float](($H - $font.Height) / 2))
    }
    $bmp.Save($Path, [Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose(); $bmp.Dispose()
}

New-Art 192 192 (Join-Path $dir 'icon.png') $null
New-Art 320 180 (Join-Path $dir 'banner.png') 'Glass Home'

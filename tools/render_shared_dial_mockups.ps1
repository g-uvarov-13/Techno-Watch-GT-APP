param(
    [string]$Workspace = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
)

Add-Type -AssemblyName System.Drawing

$manifestPath = Join-Path $Workspace 'work/shared-dial-base/candidates-v4/build-manifest.json'
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$output = Join-Path $Workspace 'outputs/shared-dial-base-mockups-v4'
New-Item -ItemType Directory -Path $output -Force | Out-Null
$backgroundPath = Join-Path $Workspace 'work/qc176-diallab3000-offline/background-clean-opaque-466.png'
$values = @{
    TIME_M_D = '09/28'; TIME_W = 'MON'; HOUR = '08'; MIN = '58'
    TIME_S = '16'; AM_PM = 'AM'; STEPS = '978'; KCAL = '72'
    HEART = '71'; BATTERY = '71%'; DISTAN = '0.68'
}

foreach ($variant in $manifest.variants) {
    $name = if ($variant.identity.name -eq 'base3000') { 'base' } else { 'dial3000' }
    $image = if ($name -eq 'base') {
        New-Object System.Drawing.Bitmap 466, 466
    } else {
        New-Object System.Drawing.Bitmap $backgroundPath
    }
    try {
        $graphics = [System.Drawing.Graphics]::FromImage($image)
        try {
            $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
            $graphics.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
            if ($name -eq 'base') { $graphics.Clear([System.Drawing.Color]::Black) }
            foreach ($property in $variant.layout.PSObject.Properties) {
                $id = $property.Name
                $position = $property.Value
                $rectangle = [System.Drawing.RectangleF]::new(
                    [single]$position[0], [single]$position[1],
                    [single]$position[2], [single]$position[3]
                )
                $size = [single]$variant.fontPixels.$id
                do {
                    $font = [System.Drawing.Font]::new('Arial', $size, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
                    $measured = $graphics.MeasureString($values[$id], $font)
                    if (($measured.Width -le $rectangle.Width - 2 -and $measured.Height -le $rectangle.Height + 4) -or $size -le 10) { break }
                    $font.Dispose()
                    $size -= 1
                } while ($size -ge 10)
                $argb = $variant.argb
                $color = [System.Drawing.Color]::FromArgb(
                    [Convert]::ToInt32($argb.Substring(1, 2), 16),
                    [Convert]::ToInt32($argb.Substring(3, 2), 16),
                    [Convert]::ToInt32($argb.Substring(5, 2), 16),
                    [Convert]::ToInt32($argb.Substring(7, 2), 16)
                )
                $brush = [System.Drawing.SolidBrush]::new($color)
                $format = [System.Drawing.StringFormat]::new()
                try {
                    $format.Alignment = [System.Drawing.StringAlignment]::Center
                    $format.LineAlignment = [System.Drawing.StringAlignment]::Center
                    $format.FormatFlags = [System.Drawing.StringFormatFlags]::NoWrap
                    $graphics.DrawString($values[$id], $font, $brush, $rectangle, $format)
                } finally {
                    $format.Dispose(); $brush.Dispose(); $font.Dispose()
                }
            }
        } finally { $graphics.Dispose() }
        $destination = Join-Path $output ($name + '.png')
        $image.Save($destination, [System.Drawing.Imaging.ImageFormat]::Png)
        Write-Output $destination

        $coordinates = [System.Drawing.Bitmap]::new($image)
        try {
            $overlay = [System.Drawing.Graphics]::FromImage($coordinates)
            try {
                $pen = [System.Drawing.Pen]::new([System.Drawing.Color]::FromArgb(200, 255, 170, 40), 1)
                $labelBrush = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(255, 255, 205, 110))
                $labelFont = [System.Drawing.Font]::new('Arial', 10, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
                try {
                    foreach ($property in $variant.layout.PSObject.Properties) {
                        $id = $property.Name
                        $rect = $property.Value
                        $overlay.DrawRectangle($pen, [int]$rect[0], [int]$rect[1], [int]$rect[2], [int]$rect[3])
                        $overlay.DrawString($id, $labelFont, $labelBrush, [single]$rect[0], [single]([Math]::Max(0, $rect[1] - 12)))
                    }
                } finally { $labelFont.Dispose(); $labelBrush.Dispose(); $pen.Dispose() }
            } finally { $overlay.Dispose() }
            $coordinatesPath = Join-Path $output ($name + '-coordinates.png')
            $coordinates.Save($coordinatesPath, [System.Drawing.Imaging.ImageFormat]::Png)
            Write-Output $coordinatesPath
        } finally { $coordinates.Dispose() }
    } finally { $image.Dispose() }
}

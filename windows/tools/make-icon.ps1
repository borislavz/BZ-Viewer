# Generates windows\app.ico (purple circle with a white eye, like the phone app icon).
# Run once; the resulting app.ico is committed.
Add-Type -AssemblyName System.Drawing

function New-EyeBitmap([int]$s) {
    $bmp = New-Object System.Drawing.Bitmap $s, $s
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'
    $g.Clear([System.Drawing.Color]::Transparent)
    $purple = [System.Drawing.Color]::FromArgb(79, 55, 139)
    $pb = New-Object System.Drawing.SolidBrush $purple
    $wb = [System.Drawing.Brushes]::White
    $g.FillEllipse($pb, 0, 0, $s - 1, $s - 1)
    $g.FillEllipse($wb, [single](0.14 * $s), [single](0.32 * $s), [single](0.72 * $s), [single](0.36 * $s))
    $g.FillEllipse($pb, [single](0.5 * $s - 0.14 * $s), [single](0.5 * $s - 0.14 * $s), [single](0.28 * $s), [single](0.28 * $s))
    $g.FillEllipse($wb, [single](0.5 * $s - 0.05 * $s), [single](0.5 * $s - 0.05 * $s), [single](0.10 * $s), [single](0.10 * $s))
    $g.Dispose()
    return $bmp
}

$sizes = 16, 32, 48, 256
$pngs = @()
foreach ($s in $sizes) {
    $bmp = New-EyeBitmap $s
    $ms = New-Object System.IO.MemoryStream
    $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    $pngs += , $ms.ToArray()
    $bmp.Dispose()
}

$out = New-Object System.IO.MemoryStream
$w = New-Object System.IO.BinaryWriter $out
$w.Write([uint16]0); $w.Write([uint16]1); $w.Write([uint16]$sizes.Count)
$offset = 6 + 16 * $sizes.Count
for ($i = 0; $i -lt $sizes.Count; $i++) {
    $s = $sizes[$i]
    $dim = if ($s -ge 256) { 0 } else { $s }
    $w.Write([byte]$dim); $w.Write([byte]$dim); $w.Write([byte]0); $w.Write([byte]0)
    $w.Write([uint16]1); $w.Write([uint16]32)
    $w.Write([uint32]$pngs[$i].Length); $w.Write([uint32]$offset)
    $offset += $pngs[$i].Length
}
foreach ($p in $pngs) { $w.Write($p) }
$w.Flush()
[System.IO.File]::WriteAllBytes((Join-Path (Split-Path $PSScriptRoot -Parent) 'app.ico'), $out.ToArray())
Write-Host 'app.ico written'

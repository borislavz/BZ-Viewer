# Builds dist\BZ-View-Viewer.exe with the C# compiler that is part of Windows (.NET Framework 4).
# No SDK, Visual Studio or Java needed.
$ErrorActionPreference = 'Stop'
$csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path $csc)) { throw "C# compiler not found: $csc" }

$root = $PSScriptRoot
$dist = Join-Path $root 'dist'
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$exe = Join-Path $dist 'BZ-View-Viewer.exe'

$sources = Get-ChildItem (Join-Path $root 'src') -Filter *.cs | ForEach-Object { $_.FullName }
$arguments = @(
    '/nologo', '/target:winexe', '/optimize+', '/platform:anycpu', '/warn:4',
    "/out:$exe",
    "/win32icon:$(Join-Path $root 'app.ico')",
    "/win32manifest:$(Join-Path $root 'app.manifest')",
    '/reference:System.dll', '/reference:System.Core.dll',
    '/reference:System.Drawing.dll', '/reference:System.Windows.Forms.dll'
) + $sources

& $csc @arguments
if ($LASTEXITCODE -ne 0) { throw "Build failed (exit code $LASTEXITCODE)" }
Write-Host "Built $exe ($([math]::Round((Get-Item $exe).Length / 1KB)) KB)"

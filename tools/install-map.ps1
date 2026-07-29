# Install a downloaded Minecraft world (.zip) into an instance's saves/.
#
# Map sites sit behind bot protection and ad-gates, so the download itself has to be done in a
# browser. Everything after that is handled here.
#
#   .\install-map.ps1 -Zip "$env:USERPROFILE\Downloads\hohenzollern.zip" -Name "Hohenzollern"
param(
    [Parameter(Mandatory = $true)][string]$Zip,
    [string]$Name = "",
    [string]$Instance = "C:\cc\isocraft-real"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $Zip)) { throw "no such file: $Zip" }

$mc = Get-Process | Where-Object { $_.MainWindowTitle -like "Minecraft*" } | Select-Object -First 1
if ($mc) { throw "close Minecraft first (pid $($mc.Id)) - it locks save files" }

$staging = Join-Path $env:TEMP "isocraft-map-staging"
if (Test-Path $staging) { Remove-Item $staging -Recurse -Force }
New-Item -ItemType Directory -Force -Path $staging | Out-Null

Write-Host "extracting $([math]::Round((Get-Item $Zip).Length / 1MB, 1)) MB..."
Expand-Archive -Path $Zip -DestinationPath $staging -Force

# A world is whatever directory contains level.dat - the zip may nest it arbitrarily deep.
$levelDat = Get-ChildItem $staging -Recurse -Filter "level.dat" -File | Select-Object -First 1
if (-not $levelDat) {
    Write-Host "no level.dat found. Archive contents:"
    Get-ChildItem $staging -Recurse -Directory | Select-Object -First 20 | ForEach-Object { "  " + $_.FullName.Replace($staging, "") }
    throw "that zip does not appear to contain a Minecraft world"
}

$worldDir = $levelDat.Directory
if (-not $Name) { $Name = $worldDir.Name }

$dest = Join-Path $Instance "saves\$Name"
if (Test-Path $dest) {
    $backup = "$dest.backup-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
    Write-Host "existing world moved aside -> $backup"
    Move-Item $dest $backup
}

New-Item -ItemType Directory -Force -Path (Join-Path $Instance "saves") | Out-Null
Copy-Item $worldDir.FullName $dest -Recurse -Force
# a session.lock from the packager confuses the client
Remove-Item (Join-Path $dest "session.lock") -Force -ErrorAction SilentlyContinue

$size = [math]::Round(((Get-ChildItem $dest -Recurse -File | Measure-Object Length -Sum).Sum / 1MB), 1)
$regions = (Get-ChildItem (Join-Path $dest "region") -Filter *.mca -ErrorAction SilentlyContinue | Measure-Object).Count
Write-Host ""
Write-Host "installed '$Name' -> $dest"
Write-Host "  size:    $size MB"
Write-Host "  regions: $regions .mca files"
Write-Host ""
Write-Host "launch it with:"
Write-Host "  .\play.ps1 -World `"$Name`""
Write-Host ""
Write-Host "NOTE: a map built for an older Minecraft version is upgraded on first load, which can"
Write-Host "take a while and occasionally mangles modded or edge-case blocks. The original zip is"
Write-Host "untouched, so re-running this script always restores a clean copy."

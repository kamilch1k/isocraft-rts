# Generates a Minecraft world headlessly with the vanilla server jar, then copies it into an
# instance's saves/. No GUI, no world-creation screen.
#
# The server generates spawn chunks BEFORE it prints "Done", and the queued "stop" on stdin is
# only executed once it starts accepting console commands - so the world is complete and saved.
param(
    [string]$WorldName = "IsoWorld",
    [string]$Seed      = "",
    [string]$Instance  = "C:\cc\isocraft-real",
    [string]$McVersion = "1.21.11",
    # Flat ocean base for the RTS map - the mod raises islands out of it on first load.
    # Layers stack from the world floor (-64): bedrock, 110 stone, 15 water.
    # Sea floor lands at y=46 and the surface at y=61, close enough to normal sea level
    # that vanilla structures and mob spawning behave.
    [switch]$Ocean
)

$ErrorActionPreference = "Stop"
$work = "C:\cc\worldgen"
$java = "C:\cc\jdk21\bin\java.exe"

New-Item -ItemType Directory -Force -Path $work | Out-Null

$jar = Join-Path $work "server-$McVersion.jar"
if (-not (Test-Path $jar)) {
    $man = Invoke-RestMethod "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"
    $v = $man.versions | Where-Object { $_.id -eq $McVersion } | Select-Object -First 1
    if (-not $v) { throw "no such Minecraft version: $McVersion" }
    $det = Invoke-RestMethod $v.url
    Write-Host "downloading server jar ($([math]::Round($det.downloads.server.size/1MB)) MB)..."
    Invoke-WebRequest -Uri $det.downloads.server.url -OutFile $jar
}

# Mojang's EULA. Writing this asserts you accept it - you own the game, which we verified.
Set-Content (Join-Path $work "eula.txt") "eula=true" -Encoding ascii

$levelType = "minecraft:normal"
$generatorSettings = ""
if ($Ocean) {
    $levelType = "minecraft:flat"
    $layers = '{"block":"minecraft:bedrock","height":1},{"block":"minecraft:stone","height":110},{"block":"minecraft:water","height":15}'
    $generatorSettings = '{"layers":[' + $layers + '],"biome":"minecraft:plains"}'
}

@"
level-name=$WorldName
level-seed=$Seed
level-type=$levelType
generator-settings=$generatorSettings
online-mode=false
view-distance=10
spawn-protection=0
sync-chunk-writes=true
max-players=1
motd=isocraft worldgen
"@ | Set-Content (Join-Path $work "server.properties") -Encoding ascii

# remove any previous run of this world so generation is clean
$genPath = Join-Path $work $WorldName
if (Test-Path $genPath) { Remove-Item $genPath -Recurse -Force }

Write-Host "generating '$WorldName'..."
# ponytail: wait for "Done", then kill - we do NOT try to deliver a "stop" command.
# Piping it prepends a UTF-8 BOM ("<BOM>stop" -> unknown command); redirecting stdin from a file
# makes the console handler error on EOF. Killing is safe because server.properties sets
# sync-chunk-writes=true, so chunks are already flushed to disk as they generate.
$out = Join-Path $work "gen-out.log"
Remove-Item $out -Force -ErrorAction SilentlyContinue

$proc = Start-Process -FilePath $java `
    -ArgumentList @("-Xmx1500M", "-jar", $jar, "--nogui") `
    -WorkingDirectory $work -NoNewWindow -PassThru `
    -RedirectStandardOutput $out `
    -RedirectStandardError  (Join-Path $work "gen-err.log")

$done = $false
foreach ($i in 1..120) {
    Start-Sleep -Seconds 2
    if ((Test-Path $out) -and (Select-String -Path $out -Pattern 'Done \(' -Quiet)) { $done = $true; break }
    if ($proc.HasExited) { break }
}
Start-Sleep -Seconds 3               # let the final chunk writes land
if (-not $proc.HasExited) { $proc.Kill(); $proc.WaitForExit(30000) | Out-Null }
if (-not $done) { throw "server never reported Done - see $out" }
Write-Host "world generated, server stopped"

if (-not (Test-Path (Join-Path $genPath "level.dat"))) {
    throw "generation failed - no level.dat at $genPath"
}

$dest = Join-Path $Instance "saves\$WorldName"
New-Item -ItemType Directory -Force -Path (Join-Path $Instance "saves") | Out-Null
if (Test-Path $dest) { Remove-Item $dest -Recurse -Force }
Copy-Item $genPath $dest -Recurse -Force
# session.lock from the server run would confuse the client
Remove-Item (Join-Path $dest "session.lock") -Force -ErrorAction SilentlyContinue

$mb = [math]::Round(((Get-ChildItem $dest -Recurse -File | Measure-Object Length -Sum).Sum / 1MB), 1)
Write-Host "world '$WorldName' ready at $dest ($mb MB)"

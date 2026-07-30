# Start (or stop) the Create workshop server.
#
#   .\create-server.ps1              # start and wait until it is accepting commands
#   .\create-server.ps1 -Stop        # stop it via RCON, so the world saves cleanly
#
# The server is the build environment: it is driven entirely through RCON by tools/rcon.py, which is
# the only way to issue commands from a terminal that also returns the server's answer. Reading
# answers is the point - /data get block reads Create's own block entities, so a factory can be
# verified by number rather than by eye.
param(
    [switch]$Stop,
    [string]$Dir = "C:\cc\create-server",
    [string]$NeoForge = "21.1.244",
    [string]$Heap = "4G",
    [int]$TimeoutSeconds = 300
)

$ErrorActionPreference = "Stop"
$java = "C:\cc\jdk21\bin\java.exe"

function Get-ServerProcess {
    Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" |
        Where-Object { $_.CommandLine -like "*neoforge*win_args*" } |
        ForEach-Object { Get-Process -Id $_.ProcessId -ErrorAction SilentlyContinue }
}

if ($Stop) {
    $running = Get-ServerProcess
    if (-not $running) { "server is not running"; exit 0 }
    # Through RCON, not Stop-Process: the world must flush to disk before the client opens it.
    python "$PSScriptRoot\rcon.py" "save-all" "stop" 2>&1 | Out-Null
    foreach ($i in 1..30) {
        Start-Sleep -Seconds 2
        if (-not (Get-ServerProcess)) { "server stopped"; exit 0 }
    }
    "server did not stop in time - still running"
    exit 1
}

if (Get-ServerProcess) { "server already running"; exit 0 }

$args = @("-Xmx$Heap", "@libraries/net/neoforged/neoforge/$NeoForge/win_args.txt", "nogui")
$out = Join-Path $Dir "server-out.log"
Remove-Item $out, (Join-Path $Dir "server-err.log") -Force -ErrorAction SilentlyContinue

Start-Process -FilePath $java -ArgumentList $args -WorkingDirectory $Dir `
    -WindowStyle Hidden -RedirectStandardOutput $out `
    -RedirectStandardError (Join-Path $Dir "server-err.log")

"starting server, waiting for it to accept commands..."
$log = Join-Path $Dir "logs\latest.log"
foreach ($i in 1..($TimeoutSeconds / 2)) {
    Start-Sleep -Seconds 2
    if ((Test-Path $log) -and (Select-String -Path $log -Pattern 'Done \(' -Quiet)) {
        $line = (Select-String -Path $log -Pattern 'Done \(' | Select-Object -Last 1).Line
        "ready: $($line -replace '.*\]: ','')"
        exit 0
    }
}
"TIMED OUT - see $out and $log"
exit 1

# 60fps H.264 capture of the game window.
#
#   .\record60.ps1 -Name fight -Seconds 12
#
# Captures the game window's OWN device context by title. It is incapable of seeing anything else
# on the display - which is the entire reason it is written this way.
#
# ddagrab (GPU Desktop Duplication) is faster and would manage 60fps at full resolution, but it
# captures whatever the display is SHOWING. An attempt to make that safe by switching to the game's
# virtual desktop first failed: Switch-Desktop does nothing from a non-interactive shell, the
# display never changed, and the recording captured the user's own screen instead of the game. It
# was deleted. Do not reintroduce display capture here.
#
# The cost of gdigrab is bandwidth: GDI copies through system memory, so the frame rate depends on
# the window's pixel count. Roughly 3.2 megapixels manages only ~29fps, so the window has to be
# sized for the frame rate wanted. 1280x720 physical holds 60fps comfortably.
param(
    [string]$Name = "fight",
    [int]$Seconds = 12,
    [string]$Out = "C:\cc\isocraft-rts\media",
    [int]$Fps = 60,
    [int]$ScaleHeight = 720
)

$ErrorActionPreference = "Stop"
$ffmpeg = "C:\cc\tools\ffmpeg\ffmpeg.exe"
if (-not (Test-Path $ffmpeg)) { throw "no ffmpeg at $ffmpeg" }

$mc = Get-Process | Where-Object { $_.MainWindowTitle -like "Minecraft*" } | Select-Object -First 1
if (-not $mc) { throw "the game is not running" }
$title = $mc.MainWindowTitle

New-Item -ItemType Directory -Force -Path $Out | Out-Null
$file = Join-Path $Out "$Name.mp4"

"capturing window '$title' for $Seconds s, target $Fps fps"
& $ffmpeg -hide_banner -loglevel error -y `
    -f gdigrab -framerate $Fps -draw_mouse 0 -i "title=$title" `
    -t $Seconds `
    -vf "scale=-2:$($ScaleHeight):flags=lanczos,format=yuv420p" `
    -c:v h264_nvenc -preset p5 -cq 21 -r $Fps `
    $file

# ponytail: no ffprobe pass. "ffmpeg -i" with no output file always exits non-zero, which this
# tool reports as a failure even though the recording succeeded.
$mb = [math]::Round((Get-Item $file).Length / 1MB, 1)
"wrote $file ($mb MB)"

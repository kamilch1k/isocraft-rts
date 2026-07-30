# Launch the Create client and connect it straight to the workshop server.
#
#   .\create-play.ps1
#
# Connecting to the running server (-s) rather than opening a singleplayer copy is deliberate: the
# server is where the factory is built from the terminal over RCON, so this way the client is looking
# at the live world - anything built while it is open simply appears. It also sidesteps the world
# selection screen, which cannot be clicked from a terminal.
param(
    [string]$Dir = "C:\cc\create-real",
    # A locally installed version id, not portablemc's "neoforge:1.21.1" resolver: that resolver
    # crashes on this version with KeyError: 'ROOT' while running the installer's processors. The
    # official NeoForge installer (--installClient) writes this version into versions/ correctly,
    # and portablemc launches an already-installed id without any post-processing.
    [string]$Version = "neoforge-21.1.244",
    [string]$User = "Falkner",
    [string]$Uuid = "b6f0a9f3-1f6c-3f2e-9c1a-2d4e5f60718a",
    [string]$Server = "127.0.0.1",
    [int]$Port = 25565,
    [int]$Width = 819,
    [int]$Height = 461,
    [int]$DesktopIndex = 1
)

$ErrorActionPreference = "Stop"

$log = Join-Path $env:TEMP "create-launch.log"
# Through create_launch.py, not the portablemc CLI: the CLI's -s/--server emits Minecraft's
# pre-1.20 arguments, which the game ignores, so the client just sat on the main menu.
$launchPy = Join-Path $PSScriptRoot "create_launch.py"
$args = @($launchPy, "--dir", $Dir, "--version", $Version, "--user", $User,
          "--server", "$($Server):$Port", "--size", "${Width}x${Height}")
Start-Process -FilePath "python" -ArgumentList $args -WindowStyle Hidden `
              -RedirectStandardOutput $log -RedirectStandardError "$log.err"
"launching $Version -> $($Server):$Port   log: $log"

$p = $null
foreach ($i in 1..150) {
    $p = Get-Process | Where-Object { $_.MainWindowTitle -like "Minecraft*" } | Select-Object -First 1
    if ($p) { break }
    Start-Sleep -Seconds 2
}
if (-not $p) { "TIMED OUT waiting for the game window - see $log"; exit 1 }
"window up: pid=$($p.Id) '$($p.MainWindowTitle)'"

$h = $p.MainWindowHandle

# The desktop move goes FIRST and needs no P/Invoke. Add-Type intermittently fails in this
# environment with a BadImageFormatException, and when the ordering was the other way round that
# failure aborted the script before the window had been moved - leaving the game on top of whatever
# the user was doing. Keeping the game off desktop 1 matters more than its size or border.
Import-Module "C:\cc\tools\VirtualDesktop\VirtualDesktop.psd1" -WarningAction SilentlyContinue
if ((Get-DesktopCount) -le $DesktopIndex) { New-Desktop | Out-Null }
Move-Window -Desktop (Get-Desktop -Index $DesktopIndex) -Hwnd $h | Out-Null
"moved to desktop $($DesktopIndex + 1)"

try {
    Add-Type -AssemblyName System.Windows.Forms
    Add-Type @"
using System;
using System.Runtime.InteropServices;
public class CreateWin {
  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr a, int X, int Y, int cx, int cy, uint f);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int n);
  [DllImport("user32.dll", SetLastError=true)] public static extern int GetWindowLong(IntPtr h, int idx);
  [DllImport("user32.dll", SetLastError=true)] public static extern int SetWindowLong(IntPtr h, int idx, int val);
}
"@
    $wa = [System.Windows.Forms.Screen]::PrimaryScreen.WorkingArea
    [void][CreateWin]::ShowWindow($h, 9)
    $style = [CreateWin]::GetWindowLong($h, -16)
    $new = $style -band -bnot (0x00C00000 -bor 0x00040000 -bor 0x00020000 -bor 0x00010000 -bor 0x00080000)
    [void][CreateWin]::SetWindowLong($h, -16, $new)
    [void][CreateWin]::SetWindowPos($h, [IntPtr]::Zero,
        ($wa.X + $wa.Width - $Width - 12), ($wa.Y + $wa.Height - $Height - 12),
        $Width, $Height, (0x0014 -bor 0x0020))
    "placed ${Width}x${Height} borderless bottom-right"
} catch {
    "window styling skipped: $($_.Exception.GetType().Name) (game is still on desktop $($DesktopIndex + 1))"
}

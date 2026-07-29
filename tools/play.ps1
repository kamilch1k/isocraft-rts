# Launch an instance, park it small in the bottom-right corner, and move it to Desktop 2.
# All terminal - no computer-use, no launcher GUI.
#
#   .\play.ps1                       # Isocraft (1.21.11)
#   .\play.ps1 -Instance old         # the 26.1.2 / OrthoCamera instance
#   .\play.ps1 -World "New World"
param(
    [ValidateSet("iso", "old")] [string]$Instance = "iso",
    [string]$World = "IsoWorld",
    [int]$DesktopIndex = 1,          # 0 = Desktop 1, 1 = Desktop 2
    [int]$Width = 854,
    [int]$Height = 480,
    [switch]$Bordered                # default is borderless
)

$ErrorActionPreference = "Stop"

if ($Instance -eq "iso") { $dir = "C:\cc\isocraft-real"; $mc = "1.21.11" }
else                     { $dir = "C:\cc\isocraft";      $mc = "26.1.2"  }

# resolve launch.py next to this script, so moving the repo doesn't break it
$launchPy = Join-Path $PSScriptRoot "launch.py"
if (-not (Test-Path $launchPy)) { throw "launch.py not found next to play.ps1 at $launchPy" }
$launchArgs = @($launchPy, "--dir", $dir, "--mc", $mc, "--size", "${Width}x${Height}")
if ($World) { $launchArgs += @("--world", $World) } else { $launchArgs += "--menu" }

$log = Join-Path $env:TEMP "isocraft-launch-$Instance.log"
Start-Process -FilePath "python" -ArgumentList $launchArgs -WindowStyle Hidden `
              -RedirectStandardOutput $log -RedirectStandardError "$log.err"
"launching $Instance ($mc)  ->  $log"

# wait for the game window
$p = $null
foreach ($i in 1..100) {
    $p = Get-Process | Where-Object { $_.MainWindowTitle -like "Minecraft*" } | Select-Object -First 1
    if ($p) { break }
    Start-Sleep -Milliseconds 2000
}
if (-not $p) { "TIMED OUT waiting for the game window - see $log"; exit 1 }
"window up: pid=$($p.Id) '$($p.MainWindowTitle)'"

Add-Type -AssemblyName System.Windows.Forms
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class IsoWin {
  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr a, int X, int Y, int cx, int cy, uint f);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int n);
  [DllImport("user32.dll", SetLastError=true)] public static extern int GetWindowLong(IntPtr h, int idx);
  [DllImport("user32.dll", SetLastError=true)] public static extern int SetWindowLong(IntPtr h, int idx, int val);
}
"@

$h  = $p.MainWindowHandle
$wa = [System.Windows.Forms.Screen]::PrimaryScreen.WorkingArea
$m  = 12
[void][IsoWin]::ShowWindow($h, 9)                                   # SW_RESTORE

if (-not $Bordered) {
    # Strip the title bar and resize frame. GWL_STYLE = -16.
    $GWL_STYLE = -16
    $WS_CAPTION = 0x00C00000; $WS_THICKFRAME = 0x00040000
    $WS_MINIMIZEBOX = 0x00020000; $WS_MAXIMIZEBOX = 0x00010000; $WS_SYSMENU = 0x00080000
    $style = [IsoWin]::GetWindowLong($h, $GWL_STYLE)
    $new = $style -band -bnot ($WS_CAPTION -bor $WS_THICKFRAME -bor $WS_MINIMIZEBOX -bor $WS_MAXIMIZEBOX -bor $WS_SYSMENU)
    [void][IsoWin]::SetWindowLong($h, $GWL_STYLE, $new)
    "borderless: title bar and frame removed"
}

# SWP_FRAMECHANGED (0x0020) makes the style change take effect.
[void][IsoWin]::SetWindowPos($h, [IntPtr]::Zero,
    ($wa.X + $wa.Width - $Width - $m), ($wa.Y + $wa.Height - $Height - $m),
    $Width, $Height, (0x0014 -bor 0x0020))                          # NOZORDER|NOACTIVATE|FRAMECHANGED
"placed $($Width)x$($Height) bottom-right"

Import-Module "C:\cc\tools\VirtualDesktop\VirtualDesktop.psd1" -WarningAction SilentlyContinue
if ((Get-DesktopCount) -le $DesktopIndex) {
    "only $(Get-DesktopCount) desktop(s) exist - creating one"
    New-Desktop | Out-Null
}
Move-Window -Desktop (Get-Desktop -Index $DesktopIndex) -Hwnd $h | Out-Null
"moved to desktop index $DesktopIndex (Desktop $($DesktopIndex + 1))"

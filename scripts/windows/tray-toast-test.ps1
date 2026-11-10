# Posts the tray agent's test toasts from inside the installed Control package: one now, one scheduled 30 s out
# (desktop-windows-port.md §3.9 T2). Toasts belong to the package's identity, so they can only be tested in it.
#
#   powershell -ExecutionPolicy Bypass -File scripts/windows/tray-toast-test.ps1   # the installed package's agent
#   ... -Agent <path to a freshly built tray-agent.exe>                               # a fresh build, same identity
#
# Clicking either toast should bring Control to the front, through the activator in Control's manifest.
param([string]$Agent)

$ErrorActionPreference = 'Stop'
$package = Get-AppxPackage Tenshu.Control
if (-not $package) { throw 'Tenshu.Control is not installed — side-load it first (desktop-windows-port.md §5).' }
if (-not $Agent) { $Agent = Join-Path $package.InstallLocation 'tray-agent\tray-agent.exe' }

$result = Join-Path $env:TEMP "tenshu-toast-test-$PID.txt"
Invoke-CommandInDesktopPackage -PackageFamilyName $package.PackageFamilyName -AppId Control -Command $Agent `
    -Args "--test-notifications $result"
for ($i = 0; $i -lt 20 -and -not (Test-Path $result); $i++) { Start-Sleep -Milliseconds 500 }
if (-not (Test-Path $result)) { throw 'the agent wrote no result — did it start inside the package?' }
$text = Get-Content $result -Raw
Remove-Item $result
if ($text.Trim() -ne 'ok') { throw "toast self-test failed:`n$text" }
'Posted one toast now and scheduled one for 30 s from now.'

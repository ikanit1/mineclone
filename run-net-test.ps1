# Two real game processes, one room, a real socket between them.
#
# The unit tests drive whole sessions, but never through Game: they do not
# build a world from a seed, do not apply a block edit to a live chunk and do
# not draw anyone. This script starts the game twice - one hosting, one
# joining - and each side checks that the other's block arrived.
#
# Usage: .\run-net-test.ps1            - over a local TCP socket
#        .\run-net-test.ps1 -Photon    - over Photon Cloud, the real thing
#
# Photon costs two of the free concurrent slots and needs the internet, so it
# is opt-in; the socket run is the one that gates every change.
# Exits non-zero if either side failed.

param([switch]$Photon)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$out = Join-Path $root 'out'
$libs = Join-Path $root 'libs'
if (-not (Test-Path (Join-Path $out 'com\mineclone\Main.class'))) {
    throw "out/ is empty - compile first (see CLAUDE.md or run .\run.ps1)"
}

# A free port, so a leftover process or another checkout cannot collide.
$probe = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
$probe.Start()
$port = $probe.LocalEndpoint.Port
$probe.Stop()

$work = Join-Path $root 'out-test\net'
if (Test-Path $work) { Remove-Item -Recurse -Force $work }
New-Item -ItemType Directory -Force -Path $work | Out-Null

$cp = "$out;$libs\*"
$via = if ($Photon) { 'photon' } else { 'lan' }
Write-Host "net test: via $via, port $port"

function Start-Side {
    param([string]$mode, [string]$name)
    $dir = Join-Path $work $name
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $args = @(
        "-Dmineclone.autopilot=$dir\shots",
        "-Dmineclone.savesDir=$dir\saves",
        "-Dmineclone.autopilot.net=$mode",
        "-Dmineclone.autopilot.netPort=$port",
        "-Dmineclone.autopilot.netVia=$via",
        '-cp', $cp, 'com.mineclone.Main'
    )
    $proc = Start-Process -FilePath 'java' -ArgumentList $args -PassThru -NoNewWindow `
        -RedirectStandardOutput "$dir\out.log" -RedirectStandardError "$dir\err.log"
    # Touching Handle caches it; without that, Windows PowerShell hands back an
    # empty ExitCode once the process is gone.
    $null = $proc.Handle
    return $proc
}

$host_ = Start-Side 'host' 'host'
# The host has to create a world and open the room before anyone can knock.
# Photon needs longer: the room only exists after a round trip through the
# name server, the master and a game server.
Start-Sleep -Seconds $(if ($Photon) { 30 } else { 15 })
$guest = Start-Side 'join' 'guest'

foreach ($proc in @($host_, $guest)) {
    if (-not $proc.WaitForExit(180000)) {
        Write-Host "net test: killing a side that would not stop"
        try { $proc.Kill() } catch { }
    }
    # The no-argument overload flushes the exit code: after the timed one
    # PowerShell can still report it as empty.
    $proc.WaitForExit()
}

foreach ($side in @('host', 'guest')) {
    Write-Host ""
    Write-Host "---- $side ----"
    $log = Join-Path $work "$side\out.log"
    if (Test-Path $log) { Get-Content $log | Select-String -Pattern 'autopilot' }
    $err = Join-Path $work "$side\err.log"
    if ((Test-Path $err) -and (Get-Item $err).Length -gt 0) {
        Get-Content $err | Select-String -Pattern 'autopilot|Exception|Error' | Select-Object -First 10
    }
}

$code = 0
if ($host_.ExitCode -ne 0) { Write-Host "net test: host failed ($($host_.ExitCode))"; $code = 1 }
if ($guest.ExitCode -ne 0) { Write-Host "net test: guest failed ($($guest.ExitCode))"; $code = 1 }
if ($code -eq 0) { Write-Host ""; Write-Host "net test: both sides passed" }
exit $code

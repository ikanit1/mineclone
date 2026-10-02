# Two real game processes, one room, a real socket between them.
#
# The unit tests drive whole sessions, but never through Game: they do not
# build a world from a seed, do not apply a block edit to a live chunk and do
# not draw anyone. This script starts the game twice - one hosting, one
# joining - and each side checks that the other's block arrived.
#
# Usage: .\run-net-test.ps1            - over a local TCP socket
#        .\run-net-test.ps1 -Photon    - over Photon Cloud, the real thing
#        .\run-net-test.ps1 -LivingWorld - enabled ecology and real guest refusal
#        .\run-net-test.ps1 -Farm       - farm actions and growth through the host
#        .\run-net-test.ps1 -Farm -LivingWorld - both scenarios together
#        .\run-net-test.ps1 -Lairs     - finite nests, normal combat and recolonization
#        .\run-net-test.ps1 -BurnScars - burned grove, seven days, finite animal return
#
# Photon costs two of the free concurrent slots and needs the internet, so it
# is opt-in; the socket run is the one that gates every change.
# Exits non-zero if either side failed.

param([switch]$Photon, [switch]$LivingWorld, [switch]$Farm, [switch]$Lairs, [switch]$BurnScars)

if ($Farm -and $Lairs) { throw 'Farm and Lairs are separate acceptance scenarios' }
if ($Lairs) { $LivingWorld = $true }
if ($BurnScars -and ($Farm -or $Lairs)) { throw 'BurnScars is a separate acceptance scenario' }
if ($BurnScars) { $LivingWorld = $true }

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

$scenarioOutput = if ($BurnScars) { 'burn-net' } else { 'net' }
$work = Join-Path (Join-Path $root 'out-test') $scenarioOutput
if ([IO.Path]::GetFullPath($work) -ne [IO.Path]::Combine([IO.Path]::GetFullPath($root), 'out-test', $scenarioOutput)) {
    throw "Unexpected test output path: $work"
}
if (Test-Path $work) { Remove-Item -Recurse -Force $work }
New-Item -ItemType Directory -Force -Path $work | Out-Null

$cp = "$out;$libs\*"
$via = if ($Photon) { 'photon' } else { 'lan' }
$room = ''
Write-Host "net test: via $via, port $port"

function Start-Side {
    param([string]$mode, [string]$name)
    $dir = Join-Path $work $name
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $args = @(
        "-Dmineclone.autopilot=$dir\shots",
        "-Dmineclone.savesDir=$dir\saves",
        "-Dmineclone.autopilot.net=$mode",
        "-Dmineclone.autopilot.netScenario=$(if ($BurnScars) { 'burn' } elseif ($Lairs) { 'lair' } elseif ($Farm) { 'farm' } else { '' })",
        "-Dmineclone.autopilot.netPort=$port",
        "-Dmineclone.autopilot.netVia=$via",
        "-Dmineclone.autopilot.netRoom=$room"
    )
    if ($LivingWorld) { $args += '-Dmineclone.autopilot.ecology=true' }
    $args += @('-cp', $cp, 'com.mineclone.Main')
    $proc = Start-Process -FilePath 'java' -ArgumentList $args -PassThru -WindowStyle Hidden `
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
if ($Photon) {
    # Hosting generates a fresh room code. Join the code the game actually
    # published, not the old autopilot-<port> placeholder.
    $deadline = [DateTime]::UtcNow.AddSeconds(90)
    while ([DateTime]::UtcNow -lt $deadline -and -not $host_.HasExited) {
        $log = Join-Path $work 'host\out.log'
        if (Test-Path $log) {
            $match = Select-String -LiteralPath $log -Pattern '^autopilot: room=(.+)$' | Select-Object -Last 1
            if ($match) { $room = $match.Matches[0].Groups[1].Value.Trim(); break }
        }
        Start-Sleep -Milliseconds 250
    }
    if (-not $room) {
        if (-not $host_.HasExited) { $host_.Kill(); $host_.WaitForExit() }
        throw 'Photon host did not publish its room code within 90 seconds'
    }
} else {
    Start-Sleep -Seconds 15
}
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
if ($LivingWorld) {
    $hostLog = Get-Content -LiteralPath (Join-Path $work 'host\out.log') -Raw
    $guestLog = Get-Content -LiteralPath (Join-Path $work 'guest\out.log') -Raw
    foreach ($marker in @('living world owner enabled ecology', 'living world rule survived room opening',
            'all checks passed')) {
        if ($hostLog -notmatch [regex]::Escape($marker)) {
            Write-Host "net test: missing host ecology proof: $marker"
            $code = 1
        }
    }
    $guestMarkers = @('living world guest command refused', 'all checks passed')
    if (-not $Farm -and -not $Lairs -and -not $BurnScars) { $guestMarkers += 'all mob species replicated with valid poses' }
    if ($BurnScars) {
        foreach ($marker in @('burn owner prepared actual fuel consumption callbacks=',
                'burn host saw actual scorched ground and finite original animal', 'burn owner actual seven-day command',
                'burn host day7 grass and scheduled young trees', 'burn host ordinary finite animal returned to recovered grove',
                'burn finite return proof complete')) {
            if ($hostLog -notmatch [regex]::Escape($marker)) { Write-Host "net test: missing burn owner proof: $marker"; $code = 1 }
        }
        $guestMarkers += @('burn guest saw actual scorched ground and original S_MOBS animal with ecology absent',
                'burn guest day7 grass and scheduled young trees mirrored',
                'burn guest same returned finite animal mirrored with ecology absent', 'burn finite return proof complete')
        $hostIds = [regex]::Match($hostLog, 'burn returned IDs=([0-9,]+)').Groups[1].Value
        $guestIds = [regex]::Match($guestLog, 'burn returned IDs=([0-9,]+)').Groups[1].Value
        if (-not $hostIds -or $hostIds -ne $guestIds) { Write-Host 'net test: returned animal IDs differ between authority and S_MOBS guest'; $code = 1 }
    }
    if ($Lairs) {
        $lairCommon = @('lair initial IDs=', 'lair dead pose observed id=', 'lair original wanderer died through ordinary combat and mirrored',
                'lair old territory stayed closed next night',
                'lair other cave colonized after actual ten-day command and heart mirrored')
        foreach ($marker in (@('lair host saw nest64 and four finite emitted IDs',
                'lair heart AIR propagated and original wanderers survived', 'lair non-attacker witnessed authoritative dead pose') + $lairCommon)) {
            if ($hostLog -notmatch [regex]::Escape($marker)) { Write-Host "net test: missing lair owner proof: $marker"; $code = 1 }
        }
        $guestMarkers += @('lair guest saw nest64 and four emitted S_MOBS IDs with ecology absent',
                'lair heart broken through ordinary input') + $lairCommon
        $hostIds = [regex]::Match($hostLog, 'lair initial IDs=([0-9,]+)').Groups[1].Value
        $guestIds = [regex]::Match($guestLog, 'lair initial IDs=([0-9,]+)').Groups[1].Value
        if (-not $hostIds -or $hostIds -ne $guestIds) { Write-Host 'net test: emitted lair IDs differ between host and actual S_MOBS guest'; $code = 1 }
    }
    if ($Farm) {
        foreach ($marker in @('raid host saw mature crop stage7', 'raid host released two finite natural rabbits',
                'raid host rabbit consumed stage7 to0', 'raid protected crop stayed stage7')) {
            if ($hostLog -notmatch [regex]::Escape($marker)) {
                Write-Host "net test: missing authoritative raid proof: $marker"
                $code = 1
            }
        }
        $guestMarkers += @('raid guest acknowledged mature crop stage7 with ecology absent',
                'raid guest received crop stage0 and protected stage7', 'raid protected crop stayed stage7')
    }
    foreach ($marker in $guestMarkers) {
        if ($guestLog -notmatch [regex]::Escape($marker)) {
            Write-Host "net test: missing guest ecology proof: $marker"
            $code = 1
        }
    }
    if ($code -eq 0) { Write-Host 'net test: Living World owner, guest authority and S_MOBS checks passed' }
    if ($code -eq 0 -and $Farm) { Write-Host 'net test: natural host rabbit raid, guest crop replication and protected field passed' }
    if ($code -eq 0 -and $Lairs) { Write-Host 'net test: finite lair IDs, ordinary guest heart/combat, later-night closure and other-cave colony passed' }
    if ($code -eq 0 -and $BurnScars) { Write-Host 'net test: real scorched terrain, day7 recovery and same finite returned animal passed' }
}
if ($code -eq 0) { Write-Host ""; Write-Host "net test: both sides passed" }
exit $code

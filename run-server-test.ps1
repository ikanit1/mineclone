# A dedicated server and two real game clients on real sockets.
#
# The unit tests drive sessions without a world and without Game; run-net-test
# drives two games hosting each other. Neither covers the case this feature was
# written for: a world that lives with nobody playing it. Here ServerMain holds
# the world, two clients walk in, and each one's block has to reach the other
# through the server.
#
# Usage: .\run-server-test.ps1
#        .\run-server-test.ps1 -LivingWorld
#        .\run-server-test.ps1 -Farm
#        .\run-server-test.ps1 -Farm -LivingWorld
#        .\run-server-test.ps1 -Lairs
#        .\run-server-test.ps1 -Stories
#        .\run-server-test.ps1 -BurnScars
# Exits non-zero if the server or either client failed.

param([switch]$LivingWorld, [switch]$Farm, [switch]$Lairs, [switch]$BurnScars, [switch]$Stories)

if ($Farm -and $Lairs) { throw 'Farm and Lairs are separate acceptance scenarios' }
if ($Lairs) { $LivingWorld = $true }
if ($BurnScars -and ($Farm -or $Lairs)) { throw 'BurnScars is a separate acceptance scenario' }
if ($BurnScars) { $LivingWorld = $true }
if ($Stories -and ($Farm -or $Lairs -or $BurnScars)) { throw 'Stories is a separate consolidated acceptance scenario' }
if ($Stories) { $LivingWorld = $true }

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$out = Join-Path $root 'out'
$libs = Join-Path $root 'libs'
if (-not (Test-Path (Join-Path $out 'com\mineclone\server\ServerMain.class'))) {
    throw "out/ has no server - compile first (see CLAUDE.md or run .\run.ps1)"
}

# A free port, so a leftover process or another checkout cannot collide.
$probe = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
$probe.Start()
$port = $probe.LocalEndpoint.Port
$probe.Stop()

$work = Join-Path $root $(if ($Stories) { 'out-test\stories-server' } elseif ($BurnScars) { 'out-test\burn-server' } else { 'out-test\server' })
$work = [IO.Path]::GetFullPath($work)
$testRoot = [IO.Path]::GetFullPath((Join-Path $root 'out-test')) + [IO.Path]::DirectorySeparatorChar
if (-not $work.StartsWith($testRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to clean server test directory outside out-test: $work"
}
if (Test-Path $work) {
    if ($Stories) {
        $archive = [IO.Path]::GetFullPath($work + '-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmssfff'))
        if ([IO.Path]::GetDirectoryName($archive) -ne [IO.Path]::GetFullPath((Join-Path $root 'out-test'))) { throw 'Unexpected story archive path' }
        Move-Item -LiteralPath $work -Destination $archive
    } else { Remove-Item -Recurse -Force -LiteralPath $work }
}
New-Item -ItemType Directory -Force -Path $work | Out-Null
$cp = "$out;$libs\*"
Write-Host "server test: port $port"

# No cloud room and no port forwarding: this run must not depend on the
# internet, and it must not spend one of the hundred free Photon slots.
$conf = Join-Path $work 'server.properties'
# Forward slashes: .properties treats a backslash as an escape. ServerConfig
# doubles them on read for the sake of anyone pasting a Windows path by hand,
# but a generated file has no excuse to lean on that.
$savesDir = $work -replace '\\', '/'
@"
port=$port
direct=true
upnp=false
photon=false
saves-dir=$savesDir/saves
world=test
world-name=TestServer
seed=$(if ($BurnScars -or $Stories) { 20261006 } else { 20260922 })
mode=creative
difficulty=normal
autosave-seconds=15
view-distance=4
"@ | Set-Content -LiteralPath $conf -Encoding ascii

# Only the combined scenario needs saved, finite rabbits. A source-mode tool
# prepares a new temporary server world through its normal save path; the real
# server reopens it and its ordinary rabbit brains wait behind a closed gate.
if ($Farm -and $LivingWorld) {
    $fixtureTool = Join-Path $root 'tools\RaidSaveAcceptance.java'
    & java -cp $cp $fixtureTool prepare $conf
    if ($LASTEXITCODE -ne 0) { throw 'server test: could not prepare finite natural raid fixture' }
}
if ($Lairs) {
    $lairTool = Join-Path $root 'tools\LairSaveAcceptance.java'
    & java -cp $cp $lairTool prepare $conf
    if ($LASTEXITCODE -ne 0) { throw 'server test: could not prepare the finite two-cave lair fixture' }
}
if ($BurnScars) {
    $burnTool = Join-Path $root 'tools\BurnScarsSaveAcceptance.java'
    & java -cp $cp $burnTool prepare $conf
    if ($LASTEXITCODE -ne 0) { throw 'server test: could not prepare the production-fire burned grove fixture' }
}

if ($Stories) {
    $storyTools = Join-Path $root 'out-test\story-tools'
    New-Item -ItemType Directory -Force -Path $storyTools | Out-Null
    & javac -cp $cp -d $storyTools (Join-Path $root 'tools\LivingWorldSaveAcceptance.java')
    if ($LASTEXITCODE -ne 0) { throw 'server test: consolidated fixture tools did not compile' }
    $storyCp = "$storyTools;$cp"
    & java -cp $storyCp LivingWorldSaveAcceptance prepare $conf
    if ($LASTEXITCODE -ne 0) { throw 'server test: combined valley preparation failed' }
}

# The server is started through .NET rather than Start-Process because we need
# its stdin: stopping it with /stop is the path a person uses, and it is the
# only one that exercises the clean shutdown - Kill() skips shutdown hooks, so
# whatever it failed to write would never be missed.
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = 'java'
$psi.Arguments = "-cp `"$cp`" com.mineclone.server.ServerMain `"$conf`""
$psi.WorkingDirectory = $root
$psi.UseShellExecute = $false
$psi.CreateNoWindow = $true
$psi.RedirectStandardInput = $true
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$server = [System.Diagnostics.Process]::Start($psi)
# Read both streams asynchronously from the start: a server that fills its
# pipe buffer while nobody drains it simply stops.
$serverOutTask = $server.StandardOutput.ReadToEndAsync()
$serverErrTask = $server.StandardError.ReadToEndAsync()

function Start-Guest {
    param([int]$slot)
    $dir = Join-Path $work "guest$slot"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $a = @(
        "-Dmineclone.autopilot=$dir\shots",
        "-Dmineclone.savesDir=$dir\saves",
        '-Dmineclone.autopilot.net=server',
        "-Dmineclone.autopilot.netScenario=$(if ($Stories) { 'stories' } elseif ($BurnScars) { 'burn' } elseif ($Lairs) { 'lair' } elseif ($Farm) { 'farm' } else { '' })",
        "-Dmineclone.autopilot.netPort=$port",
        "-Dmineclone.autopilot.netSlot=$slot"
    )
    if ($LivingWorld) { $a += '-Dmineclone.autopilot.ecology=true' }
    $a += @('-cp', $cp, 'com.mineclone.Main')
    $proc = Start-Process -FilePath 'java' -ArgumentList $a -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput "$dir\out.log" -RedirectStandardError "$dir\err.log"
    # Touching Handle caches it; without that, Windows PowerShell hands back an
    # empty ExitCode once the process is gone.
    $null = $proc.Handle
    return $proc
}

# The server has to generate its spawn chunks before it can answer a knock.
Start-Sleep -Seconds 10
if ($LivingWorld) {
    $server.StandardInput.WriteLine('/ecology on')
    $server.StandardInput.WriteLine('/ecology info')
    $server.StandardInput.Flush()
}
if ($Lairs) {
    $server.StandardInput.WriteLine('/time night')
    $server.StandardInput.Flush()
}
$guests = @((Start-Guest 0), (Start-Guest 1))

$farmAdvanced = $false
if ($Farm) {
    $deadline = [DateTime]::UtcNow.AddMinutes(3)
    while ([DateTime]::UtcNow -lt $deadline) {
        $a = Get-Content (Join-Path $work 'guest0\out.log') -Raw -ErrorAction SilentlyContinue
        $b = Get-Content (Join-Path $work 'guest1\out.log') -Raw -ErrorAction SilentlyContinue
        if ($a -match 'farm server READY' -and $b -match 'farm guest READY') {
            $server.StandardInput.WriteLine('/time add 48')
            $server.StandardInput.Flush()
            $farmAdvanced = $true
            break
        }
        if ($guests[0].HasExited -or $guests[1].HasExited) { break }
        Start-Sleep -Milliseconds 250
    }
}

$storiesAdvanced = $false
if ($Stories) {
    $deadline = [DateTime]::UtcNow.AddMinutes(5)
    while ([DateTime]::UtcNow -lt $deadline) {
        $a = Get-Content (Join-Path $work 'guest0\out.log') -Raw -ErrorAction SilentlyContinue
        $b = Get-Content (Join-Path $work 'guest1\out.log') -Raw -ErrorAction SilentlyContinue
        if ($a -match 'stories READY seven days' -and $b -match 'stories READY seven days') {
            $server.StandardInput.WriteLine('/ecology advance 7')
            $server.StandardInput.WriteLine('/time noon')
            $server.StandardInput.WriteLine('/ecology info')
            $server.StandardInput.Flush()
            $storiesAdvanced = $true
            break
        }
        if ($guests[0].HasExited -or $guests[1].HasExited) { break }
        Start-Sleep -Milliseconds 250
    }
}

$lairNightAdvanced = $false
$lairColonizationAdvanced = $false
$burnAdvanced = $false
if ($BurnScars) {
    $deadline = [DateTime]::UtcNow.AddMinutes(3)
    while ([DateTime]::UtcNow -lt $deadline) {
        $a = Get-Content (Join-Path $work 'guest0\out.log') -Raw -ErrorAction SilentlyContinue
        $b = Get-Content (Join-Path $work 'guest1\out.log') -Raw -ErrorAction SilentlyContinue
        if ($a -match 'burn READY seven days' -and $b -match 'burn READY seven days') {
            $server.StandardInput.WriteLine('/ecology advance 7')
            $server.StandardInput.WriteLine('/time noon')
            $server.StandardInput.WriteLine('/ecology info')
            $server.StandardInput.WriteLine('/save')
            $server.StandardInput.Flush()
            $burnAdvanced = $true
            break
        }
        if ($guests[0].HasExited -or $guests[1].HasExited) { break }
        Start-Sleep -Milliseconds 250
    }
}
if ($Lairs) {
    $deadline = [DateTime]::UtcNow.AddMinutes(3)
    while ([DateTime]::UtcNow -lt $deadline) {
        $a = Get-Content (Join-Path $work 'guest0\out.log') -Raw -ErrorAction SilentlyContinue
        $b = Get-Content (Join-Path $work 'guest1\out.log') -Raw -ErrorAction SilentlyContinue
        if (-not $lairNightAdvanced -and $a -match 'lair READY next night' -and $b -match 'lair READY next night') {
            $server.StandardInput.WriteLine('/time add 24')
            $server.StandardInput.Flush()
            $lairNightAdvanced = $true
        }
        if ($lairNightAdvanced -and $a -match 'lair READY colonization' -and $b -match 'lair READY colonization') {
            $server.StandardInput.WriteLine('/ecology advance 10')
            $server.StandardInput.WriteLine('/ecology info')
            $server.StandardInput.WriteLine('/save')
            $server.StandardInput.Flush()
            $lairColonizationAdvanced = $true
            break
        }
        if ($guests[0].HasExited -or $guests[1].HasExited) { break }
        Start-Sleep -Milliseconds 250
    }
}

foreach ($proc in $guests) {
    if (-not $proc.WaitForExit($(if ($Stories) { 480000 } else { 180000 }))) {
        Write-Host 'server test: killing a client that would not stop'
        try { $proc.Kill() } catch { }
        $null = $proc.WaitForExit(10000)
    }
    # The no-argument overload flushes the exit code: after the timed one
    # PowerShell can still report it as empty.
    $proc.WaitForExit()
}
Write-Host 'server test: both clients finished'

# Capture the actual meal before the later ten-day clock command can regrow it.
# Read the atomically published snapshots until /save's queue is durable; no
# mutation or fabricated stage0 is performed by the verification tool.
$raidSaved = $false
if ($Farm -and $LivingWorld -and -not $server.HasExited) {
    $server.StandardInput.WriteLine('/save')
    $server.StandardInput.Flush()
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    do {
        $verificationErrorPolicy = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            $raidProof = & java -cp $cp $fixtureTool verify $conf 2>&1
            $raidExit = $LASTEXITCODE
        } finally { $ErrorActionPreference = $verificationErrorPolicy }
        $raidProof | Set-Content -LiteralPath (Join-Path $work 'raid-save.log') -Encoding utf8
        if ($raidExit -eq 0) { $raidSaved = $true; Write-Host $raidProof; break }
        Start-Sleep -Milliseconds 300
    } while ([DateTime]::UtcNow -lt $deadline -and -not $server.HasExited)
}

$lairSaved = $false
$burnSaved = $false
$returnedBurnIds = ''
if ($BurnScars -and -not $server.HasExited) {
    $returnedBurnIds = [regex]::Match((Get-Content (Join-Path $work 'guest0\out.log') -Raw), 'burn returned IDs=([0-9,]+)').Groups[1].Value
    $server.StandardInput.WriteLine('/save')
    $server.StandardInput.Flush()
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    if ($returnedBurnIds) {
        do {
            $verificationErrorPolicy = $ErrorActionPreference
            try {
                $ErrorActionPreference = 'Continue'
                $burnProof = & java -cp $cp $burnTool verify $conf $returnedBurnIds 2>&1
                $burnExit = $LASTEXITCODE
            } finally { $ErrorActionPreference = $verificationErrorPolicy }
            $burnProof | Set-Content -LiteralPath (Join-Path $work 'burn-save.log') -Encoding utf8
            if ($burnExit -eq 0) { $burnSaved = $true; Write-Host $burnProof; break }
            Start-Sleep -Milliseconds 300
        } while ([DateTime]::UtcNow -lt $deadline -and -not $server.HasExited)
    }
}
if ($Lairs -and -not $server.HasExited) {
    $initialLairIds = [regex]::Match((Get-Content (Join-Path $work 'guest0\out.log') -Raw), 'lair initial IDs=([0-9,]+)').Groups[1].Value
    $server.StandardInput.WriteLine('/save')
    $server.StandardInput.Flush()
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    if ($initialLairIds) {
        do {
            $verificationErrorPolicy = $ErrorActionPreference
            try {
                $ErrorActionPreference = 'Continue'
                $lairProof = & java -cp $cp $lairTool verify $conf $initialLairIds 2>&1
                $lairExit = $LASTEXITCODE
            } finally { $ErrorActionPreference = $verificationErrorPolicy }
            $lairProof | Set-Content -LiteralPath (Join-Path $work 'lair-save.log') -Encoding utf8
            if ($lairExit -eq 0) { $lairSaved = $true; Write-Host $lairProof; break }
            Start-Sleep -Milliseconds 300
        } while ([DateTime]::UtcNow -lt $deadline -and -not $server.HasExited)
    }
}

$storiesSaved = $false
$storiesReturnedIds = ''
if ($Stories -and -not $server.HasExited) {
    $storiesReturnedIds = [regex]::Match((Get-Content (Join-Path $work 'guest0\out.log') -Raw), 'stories finite returned IDs=([0-9,]+)').Groups[1].Value
    $server.StandardInput.WriteLine('/save')
    $server.StandardInput.Flush()
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    if ($storiesReturnedIds) {
        do {
            $verificationErrorPolicy = $ErrorActionPreference
            try {
                $ErrorActionPreference = 'Continue'
                $storyLiveProof = & java -cp $storyCp LivingWorldSaveAcceptance verify $conf $storiesReturnedIds 2>&1
                $storyLiveExit = $LASTEXITCODE
            } finally { $ErrorActionPreference = $verificationErrorPolicy }
            $storyLiveProof | Set-Content -LiteralPath (Join-Path $work 'stories-save-live.log') -Encoding utf8
            if ($storyLiveExit -eq 0) { $storiesSaved = $true; Write-Host $storyLiveProof; break }
            Start-Sleep -Milliseconds 300
        } while ([DateTime]::UtcNow -lt $deadline -and -not $server.HasExited)
    }
}

# Clients are done: ask the server to save and stop the way a person would.
Write-Host 'server test: asking the server to stop'
$stoppedCleanly = $true
try {
    if ($LivingWorld) {
        if (-not $Lairs -and -not $BurnScars -and -not $Stories) { $server.StandardInput.WriteLine('/ecology advance 10') }
        $server.StandardInput.WriteLine('/ecology info')
        $server.StandardInput.WriteLine('/save')
    }
    $server.StandardInput.WriteLine('/stop')
    $server.StandardInput.Flush()
    $server.StandardInput.Close()
} catch {
    Write-Host "server test: could not write /stop ($($_.Exception.Message))"
    $stoppedCleanly = $false
}
if (-not $server.WaitForExit(25000)) {
    Write-Host 'server test: the server ignored /stop, killing it'
    $stoppedCleanly = $false
    try { $server.Kill() } catch { }
    # Every wait is bounded: a hung shutdown must fail the run, not hang it.
    $null = $server.WaitForExit(15000)
}
# The tasks complete when the pipes close, which happens as the process dies.
# Bounded, for the same reason.
if (-not $serverOutTask.Wait(10000)) { Write-Host 'server test: stdout never closed' }
if (-not $serverErrTask.Wait(5000)) { Write-Host 'server test: stderr never closed' }
$serverOut = if ($serverOutTask.IsCompleted) { $serverOutTask.Result } else { '' }
$serverErr = if ($serverErrTask.IsCompleted) { $serverErrTask.Result } else { '' }

# Verify the final published shutdown files as well as the live /save result.
# An earlier successful snapshot must not hide a later destructive save.
$lairSavedAfterStop = $false
$burnSavedAfterStop = $false
if ($BurnScars -and $stoppedCleanly -and $server.HasExited -and $returnedBurnIds) {
    $verificationErrorPolicy = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $burnFinalProof = & java -cp $cp $burnTool verify $conf $returnedBurnIds 2>&1
        $burnSavedAfterStop = $LASTEXITCODE -eq 0
    } finally { $ErrorActionPreference = $verificationErrorPolicy }
    $burnFinalProof | Set-Content -LiteralPath (Join-Path $work 'burn-save-after-stop.log') -Encoding utf8
    if ($burnSavedAfterStop) { Write-Host $burnFinalProof }
}
if ($Lairs -and $stoppedCleanly -and $server.HasExited -and $initialLairIds) {
    $verificationErrorPolicy = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $lairFinalProof = & java -cp $cp $lairTool verify $conf $initialLairIds 2>&1
        $lairSavedAfterStop = $LASTEXITCODE -eq 0
    } finally { $ErrorActionPreference = $verificationErrorPolicy }
    $lairFinalProof | Set-Content -LiteralPath (Join-Path $work 'lair-save-after-stop.log') -Encoding utf8
    if ($lairSavedAfterStop) { Write-Host $lairFinalProof }
}

$storiesSavedAfterStop = $false
if ($Stories -and $stoppedCleanly -and $server.HasExited) {
    $storyIds = [regex]::Match((Get-Content (Join-Path $work 'guest0\out.log') -Raw), 'stories finite returned IDs=([0-9,]+)').Groups[1].Value
    if ($storyIds) {
        $verificationErrorPolicy = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            $storyProof = & java -cp $storyCp LivingWorldSaveAcceptance verify $conf $storyIds 2>&1
            $storiesSavedAfterStop = $LASTEXITCODE -eq 0
        } finally { $ErrorActionPreference = $verificationErrorPolicy }
        $storyProof | Set-Content -LiteralPath (Join-Path $work 'stories-save-after-stop.log') -Encoding utf8
        Write-Host $storyProof
    }
}

Write-Host ''
Write-Host '---- server ----'
if ($serverOut) { Write-Host $serverOut.TrimEnd() }
if ($serverErr) { Write-Host ($serverErr -split "`n" | Select-Object -First 10) }

$code = 0
if ($Stories -and (-not $storiesAdvanced -or -not $storiesSaved -or -not $storiesSavedAfterStop)) { Write-Host 'server test: consolidated live advance, live save or post-stop world proof missing'; $code = 1 }
if ($BurnScars -and (-not $burnAdvanced -or -not $burnSaved -or -not $burnSavedAfterStop)) {
    Write-Host 'server test: missing actual day7 recovery or intact live/post-stop scar, tree and finite body proof'
    $code = 1
}
if ($Lairs -and (-not $lairNightAdvanced -or -not $lairColonizationAdvanced -or -not $lairSaved -or -not $lairSavedAfterStop)) {
    Write-Host 'server test: missing actual lair night/colonization or intact saved death/IDs proof (see lair-save.log)'
    $code = 1
}
if ($Farm -and $LivingWorld -and -not $raidSaved) {
    Write-Host 'server test: the actual rabbit meal was not saved as crop0 with protected crop7 (see raid-save.log)'
    $code = 1
}
if (-not $stoppedCleanly) {
    Write-Host 'server test: the server did not stop on /stop'
    $code = 1
}
# The clean shutdown has to say so: "stopped" is the last line it prints, and
# it prints it only after the world is on disk.
if ($serverOut -notmatch 'stopped') {
    Write-Host 'server test: the server never reported a clean shutdown'
    $code = 1
}
if ($LivingWorld) {
    $expectedDay = if ($Stories) { 7 } elseif ($BurnScars) { 7 } elseif ($Lairs) { 11 } elseif ($farmAdvanced) { 12 } else { 10 }
    $advancedDays = if ($BurnScars -or $Stories) { 7 } else { 10 }
    foreach ($pattern in @('\[server\] ecology on', "\[server\] ecology advanced $advancedDays day\(s\)",
            "\[server\] Ecology ON \| region .+ \| snapshot day $expectedDay\b",
            '\[server\] Species: total / capacity \| bodies \| model 3d sum \[oldest->latest\]',
            '\[server\] saved [1-9][0-9]* chunks')) {
        if ($serverOut -notmatch $pattern) {
            Write-Host "server test: missing authoritative ecology proof: $pattern"
            $code = 1
        }
    }
}

# Redirected files are flushed when the handle closes; give Windows a moment
# before reading them back.
Start-Sleep -Milliseconds 700
foreach ($slot in 0, 1) {
    Write-Host ''
    Write-Host "---- guest $slot ----"
    $log = Join-Path $work "guest$slot\out.log"
    if (Test-Path $log) { Get-Content $log | Select-String -Pattern 'autopilot' }
    $err = Join-Path $work "guest$slot\err.log"
    if ((Test-Path $err) -and (Get-Item $err).Length -gt 0) {
        Get-Content $err | Select-String -Pattern 'autopilot|Exception|Error' | Select-Object -First 10
    }
    if ($guests[$slot].ExitCode -ne 0) {
        Write-Host "server test: guest $slot failed ($($guests[$slot].ExitCode))"
        $code = 1
    }
    if ($LivingWorld) {
        $guestLog = if (Test-Path -LiteralPath $log) { Get-Content -LiteralPath $log -Raw } else { '' }
        $markers = @('living world guest command refused', 'all checks passed')
        if ($Stories) {
            $markers += @('stories actual trail stage5', 'stories same valley scorched and fields protected',
                    'stories real scheduled silk replicated', 'stories ordinary raid mirrored protected crop7',
                    'stories day7 natural trees and faded trail', 'stories gate opened through actual replicated right input',
                    'stories finite returned IDs=', 'stories combined network PASS')
            $referenceIds = [regex]::Match((Get-Content (Join-Path $work 'guest0\out.log') -Raw), 'stories finite returned IDs=([0-9,]+)').Groups[1].Value
            $peerIds = [regex]::Match($guestLog, 'stories finite returned IDs=([0-9,]+)').Groups[1].Value
            if (-not $referenceIds -or $referenceIds -ne $peerIds) { Write-Host "server test: guest $slot observed a different combined finite body"; $code = 1 }
        }
        if ($BurnScars) {
            $markers += @('burn guest saw actual scorched ground and original S_MOBS animal with ecology absent',
                    'burn guest day7 grass and scheduled young trees mirrored',
                    'burn guest same returned finite animal mirrored with ecology absent', 'burn finite return proof complete')
            $referenceIds = [regex]::Match((Get-Content (Join-Path $work 'guest0\out.log') -Raw), 'burn returned IDs=([0-9,]+)').Groups[1].Value
            $peerIds = [regex]::Match($guestLog, 'burn returned IDs=([0-9,]+)').Groups[1].Value
            if (-not $referenceIds -or $referenceIds -ne $peerIds) { Write-Host "server test: guest $slot saw a different returned animal ID"; $code = 1 }
        }
        if ($Farm) {
            $markers += @('raid guest acknowledged mature crop stage7 with ecology absent',
                    'raid guest received crop stage0 and protected stage7', 'raid protected crop stayed stage7')
            if ($slot -eq 0) { $markers += 'raid release gate opened' }
        }
        if ($Lairs) {
            $markers += @('lair guest saw nest64 and four emitted S_MOBS IDs with ecology absent',
                    'lair dead pose observed id=',
                    'lair original wanderer died through ordinary combat and mirrored',
                    'lair old territory stayed closed next night',
                    'lair other cave colonized after actual ten-day command and heart mirrored')
            if ($slot -eq 0) { $markers += 'lair heart broken through ordinary input' }
            else { $markers += @('lair heart AIR propagated and original wanderers survived', 'lair non-attacker witnessed authoritative dead pose') }
            $otherLog = Get-Content -LiteralPath (Join-Path $work 'guest0\out.log') -Raw
            $referenceIds = [regex]::Match($otherLog, 'lair initial IDs=([0-9,]+)').Groups[1].Value
            $peerIds = [regex]::Match($guestLog, 'lair initial IDs=([0-9,]+)').Groups[1].Value
            if (-not $referenceIds -or $referenceIds -ne $peerIds) { Write-Host "server test: guest $slot received different emitted spider IDs"; $code = 1 }
        }
        foreach ($marker in $markers) {
            if ($guestLog -notmatch [regex]::Escape($marker)) {
                Write-Host "server test: guest $slot lacks ecology proof: $marker"
                $code = 1
            }
        }
    }
}

# The world has to be on disk: a server that forgets what was built is worse
# than no server at all.
$chunks = Join-Path $work 'saves\test\chunks'
if (-not (Test-Path $chunks) -or ((Get-ChildItem $chunks -File).Count -eq 0)) {
    Write-Host 'server test: the server saved no chunks'
    $code = 1
} else {
    Write-Host ''
    Write-Host "server test: $((Get-ChildItem $chunks -File).Count) chunks on disk"
}

if ($code -eq 0) {
    if ($LivingWorld) { Write-Host "server test: Living World commands, $advancedDays-day advance and guest authority passed" }
    if ($Farm -and $LivingWorld) { Write-Host 'server test: natural server raid, both guest mirrors, protected crop and saved meal passed' }
    if ($Lairs) { Write-Host 'server test: NORMAL lair bodies, guest input/combat, later night, colony and intact saved identities passed' }
    if ($BurnScars) { Write-Host 'server test: NORMAL burned grove, both day7 mirrors, finite animal return and intact shutdown save passed' }
    Write-Host ''; Write-Host 'server test: the server and both clients passed'
}
exit $code

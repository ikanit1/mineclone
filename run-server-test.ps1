# A dedicated server and two real game clients on real sockets.
#
# The unit tests drive sessions without a world and without Game; run-net-test
# drives two games hosting each other. Neither covers the case this feature was
# written for: a world that lives with nobody playing it. Here ServerMain holds
# the world, two clients walk in, and each one's block has to reach the other
# through the server.
#
# Usage: .\run-server-test.ps1
# Exits non-zero if the server or either client failed.

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

$work = Join-Path $root 'out-test\server'
$work = [IO.Path]::GetFullPath($work)
$testRoot = [IO.Path]::GetFullPath((Join-Path $root 'out-test')) + [IO.Path]::DirectorySeparatorChar
if (-not $work.StartsWith($testRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to clean server test directory outside out-test: $work"
}
if (Test-Path $work) { Remove-Item -Recurse -Force -LiteralPath $work }
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
seed=20260922
mode=creative
autosave-seconds=15
view-distance=4
"@ | Set-Content -LiteralPath $conf -Encoding ascii

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
        "-Dmineclone.autopilot.netPort=$port",
        "-Dmineclone.autopilot.netSlot=$slot",
        '-cp', $cp, 'com.mineclone.Main'
    )
    $proc = Start-Process -FilePath 'java' -ArgumentList $a -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput "$dir\out.log" -RedirectStandardError "$dir\err.log"
    # Touching Handle caches it; without that, Windows PowerShell hands back an
    # empty ExitCode once the process is gone.
    $null = $proc.Handle
    return $proc
}

# The server has to generate its spawn chunks before it can answer a knock.
Start-Sleep -Seconds 10
$guests = @((Start-Guest 0), (Start-Guest 1))

foreach ($proc in $guests) {
    if (-not $proc.WaitForExit(180000)) {
        Write-Host 'server test: killing a client that would not stop'
        try { $proc.Kill() } catch { }
        $null = $proc.WaitForExit(10000)
    }
    # The no-argument overload flushes the exit code: after the timed one
    # PowerShell can still report it as empty.
    $proc.WaitForExit()
}
Write-Host 'server test: both clients finished'

# Clients are done: ask the server to save and stop the way a person would.
Write-Host 'server test: asking the server to stop'
$stoppedCleanly = $true
try {
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

Write-Host ''
Write-Host '---- server ----'
if ($serverOut) { Write-Host $serverOut.TrimEnd() }
if ($serverErr) { Write-Host ($serverErr -split "`n" | Select-Object -First 10) }

$code = 0
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

if ($code -eq 0) { Write-Host ''; Write-Host 'server test: the server and both clients passed' }
exit $code

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot | Split-Path
$work = Join-Path $root ('out-test\server-package-' + [guid]::NewGuid().ToString('N'))
$archive = Get-ChildItem -LiteralPath (Join-Path $root 'build\distributions') -Filter '*-server.zip' | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
Expand-Archive -LiteralPath $archive.FullName -DestinationPath $work
$serverDir = Join-Path $work 'Mineclone-server'
$probe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$probe.Start()
$port = $probe.LocalEndpoint.Port
$probe.Stop()
$config = Join-Path $serverDir 'server.properties'
$settings = [IO.File]::ReadAllText($config).Replace('port=25565', "port=$port").Replace('view-distance=6', 'view-distance=2')
[IO.File]::WriteAllText($config, $settings, [Text.UTF8Encoding]::new($false))
$psi = [Diagnostics.ProcessStartInfo]::new()
$psi.FileName = 'cmd.exe'
$psi.Arguments = '/c start-server.bat'
$psi.WorkingDirectory = $serverDir
$psi.UseShellExecute = $false
$psi.CreateNoWindow = $true
$psi.RedirectStandardInput = $true
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$proc = [Diagnostics.Process]::Start($psi)
$stdout = $proc.StandardOutput.ReadToEndAsync()
$stderr = $proc.StandardError.ReadToEndAsync()
try {
    $ready = $false
    $deadline = [DateTime]::UtcNow.AddSeconds(35)
    while ([DateTime]::UtcNow -lt $deadline -and -not $proc.HasExited) {
        $socket = [Net.Sockets.TcpClient]::new()
        try { $socket.Connect('127.0.0.1', $port); $ready = $true; break }
        catch { Start-Sleep -Milliseconds 100 }
        finally { $socket.Dispose() }
    }
    if (-not $ready) { throw 'Extracted server did not listen on its TCP port' }
    $proc.StandardInput.WriteLine('/stop')
    $proc.StandardInput.Flush()
    if (-not $proc.WaitForExit(30000)) { throw 'Extracted server did not stop on /stop' }
    $output = $stdout.GetAwaiter().GetResult()
    $errors = $stderr.GetAwaiter().GetResult()
    [IO.File]::WriteAllText((Join-Path $work 'stdout.log'), $output)
    [IO.File]::WriteAllText((Join-Path $work 'stderr.log'), $errors)
    if ($proc.ExitCode -ne 0 -or $output -notmatch 'stopped') { throw "Server exit $($proc.ExitCode): $output $errors" }
    if (-not (Test-Path (Join-Path $serverDir 'saves\server\level.dat'))) { throw 'Server did not save level.dat' }
    if (-not (Test-Path (Join-Path $serverDir 'assets\data\mineclone\items'))) { throw 'Server item data not included' }
    Write-Host 'SERVER PACKAGE PASS: extracted launcher -> TCP ready -> /stop -> exit 0 -> level.dat'
    Write-Host "Evidence: $work"
    Write-Host $output
} finally {
    if (-not $proc.HasExited) { & taskkill.exe /PID $proc.Id /T /F | Out-Null; $proc.WaitForExit() }
    $proc.Dispose()
}

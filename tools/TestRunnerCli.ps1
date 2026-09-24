# Run after run-tests.ps1. Exercise filters, listing, environment precedence and a real failure exit.
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
$work = Join-Path $root 'out-test\runner-cli'
New-Item -ItemType Directory -Force -Path $work | Out-Null
$cp = "$(Join-Path $root 'out-test');$(Join-Path $root 'libs')\*"
function Invoke-Runner {
    param([string]$Name, [string[]]$Arguments, [int]$Expected = 0, [string]$Classpath = $cp)
    $report = Join-Path $work "$Name-report.txt"
    $output = @(& java "-Dmineclone.testReport=$report" -cp $Classpath com.mineclone.TestMain @Arguments 2>&1)
    $code = $LASTEXITCODE
    $text = $output -join "`n"
    [IO.File]::WriteAllText((Join-Path $work "$Name.log"), $text)
    if ($code -ne $Expected) { throw "$Name expected exit $Expected, got ${code}: $text" }
    return $text
}
$oldOnly = $env:MINECLONE_TEST_ONLY
Push-Location $root
try {
    $env:MINECLONE_TEST_ONLY = $null
    $listed = Invoke-Runner 'list-save' @('--only', 'save', '--list')
    if ($listed -notmatch 'save / CoreTests/save /' -or $listed -notmatch 'none executed' -or $listed -match '\[PASS\]|\[FAIL\]') {
        throw '--list executed tests or omitted save checks'
    }
    $filtered = Invoke-Runner 'run-save' @('--only', 'save')
    if ($filtered -notmatch '\[PASS\] \[save\]' -or $filtered -match '\[(PASS|FAIL)\] \[(?!save\])' -or $filtered -notmatch '0 failed') {
        throw '--only save leaked another category'
    }
    $skipped = Invoke-Runner 'skip-net' @('--skip=net', '--list')
    if ($skipped -match '(?m)^net /' -or $skipped -notmatch '(?m)^core /') { throw '--skip net did not filter correctly' }
    $env:MINECLONE_TEST_ONLY = 'save'
    $environment = Invoke-Runner 'environment' @('--list')
    if ($environment -ne $listed) { throw 'MINECLONE_TEST_ONLY did not select the same tests' }
    $explicit = Invoke-Runner 'explicit-only' @('--only', 'core', '--list')
    if ($explicit -match '(?m)^save /' -or $explicit -notmatch '(?m)^core /') { throw 'Explicit --only did not override environment' }
    $env:MINECLONE_TEST_ONLY = $null
    $null = Invoke-Runner 'unknown-category' @('--only', 'typo') 2
    $null = Invoke-Runner 'empty-selection' @('--only', 'save', '--skip', 'save') 2
    $null = Invoke-Runner 'missing-value' @('--only') 2
    $null = Invoke-Runner 'unknown-argument' @('--typo') 2
    $null = Invoke-Runner 'empty-category' @('--only', 'save,') 2

    # A classpath-local replacement proves assertion failures propagate out of TestMain.
    # It never modifies tracked sources or the compiled classes used by the main suite.
    $fixture = Join-Path $work 'CoreTests.java'
    $source = @'
package com.mineclone;
final class CoreTests {
    static void runAll(TestMain.Runner runner) {
        runner.run("intentional runner failure", () -> { throw new AssertionError("runner sentinel"); });
    }
    static void runSaveTests(TestMain.Runner runner) {}
}
'@
    [IO.File]::WriteAllText($fixture, $source, [Text.UTF8Encoding]::new($false))
    & javac -encoding UTF-8 --release 17 -cp $cp -d $work $fixture
    if ($LASTEXITCODE -ne 0) { throw 'Could not compile runner failure fixture' }
    $failure = Invoke-Runner 'failed-check' @('--only', 'core') 1 "$work;$cp"
    if ($failure -notmatch '1 failed' -or $failure -notmatch 'runner sentinel') { throw 'Failure was not reported' }
    Write-Host 'RUNNER CLI PASS: list, only, skip, environment, precedence, invalid filters, assertion exit 1'
} finally {
    $env:MINECLONE_TEST_ONLY = $oldOnly
    Pop-Location
}
exit 0

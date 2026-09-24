#requires -Version 5
# Headless core tests; -Only save,net; -Skip net; -List lists without executing.
param([string[]]$Only, [string[]]$Skip, [switch]$List)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$out = Join-Path $root 'out-test'
. (Join-Path $root 'tools\build-common.ps1')
$properties = Get-MinecloneBuildProperties $root
$cp = Get-MinecloneClasspath $root $properties
$generated = Write-MinecloneBuildInfo $root $out $properties
$sources = @()
$sources += Get-ChildItem -Path (Join-Path $root 'src\main\java') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$sources += Get-ChildItem -Path (Join-Path $root 'src\test\java') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$sources += $generated
$srcList = Join-Path $out 'sources.txt'
[IO.File]::WriteAllLines($srcList, [string[]]($sources | ForEach-Object { '"' + $_.Replace('\', '/') + '"' }), [Text.UTF8Encoding]::new($false))
Write-Host 'Compiling main + test sources...'
& javac -encoding UTF-8 --release 17 -d $out -cp $cp "@$srcList"
if ($LASTEXITCODE -ne 0) { throw "compile failed ($LASTEXITCODE)" }
$testArgs = @()
if ($Only) { $testArgs += '--only'; $testArgs += $Only -join ',' }
if ($Skip) { $testArgs += '--skip'; $testArgs += $Skip -join ',' }
if ($List) { $testArgs += '--list' }
Push-Location $root
try {
    Write-Host 'Running tests...'
    & java -cp "$out$([IO.Path]::PathSeparator)$cp" com.mineclone.TestMain @testArgs
    $code = $LASTEXITCODE
} finally { Pop-Location }
exit $code

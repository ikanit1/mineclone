#requires -Version 5
# Download dependencies, generate BuildInfo, compile, and optionally launch.
param(
    [switch]$CompileOnly,
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$GameArgs
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$out = Join-Path $root 'out'
. (Join-Path $root 'tools\build-common.ps1')
$properties = Get-MinecloneBuildProperties $root
$cp = Get-MinecloneClasspath $root $properties
$generated = Write-MinecloneBuildInfo $root $out $properties
$sources = @(Get-ChildItem -Path (Join-Path $root 'src\main\java') -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$sources += $generated
$srcList = Join-Path $out 'sources.txt'
[IO.File]::WriteAllLines($srcList, [string[]]($sources | ForEach-Object { '"' + $_.Replace('\', '/') + '"' }), [Text.UTF8Encoding]::new($false))
Write-Host 'Compiling...'
& javac -encoding UTF-8 --release 17 -d $out -cp $cp "@$srcList"
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit $LASTEXITCODE" }
if ($CompileOnly) { Write-Host 'Compilation complete.'; exit 0 }
Push-Location $root
try {
    Write-Host 'Launching...'
    & java -cp "$out$([IO.Path]::PathSeparator)$cp" com.mineclone.Main @GameArgs
    $code = $LASTEXITCODE
} finally { Pop-Location }
exit $code

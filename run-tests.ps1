# Compile main + test sources and run the zero-dependency test runner.
# Mirrors the compile recipe in CLAUDE.md but adds src/test/java and runs TestMain.
$ErrorActionPreference = 'Stop'

$libs = Join-Path $PWD 'libs'
$out  = Join-Path $PWD 'out-test'
if (-not (Test-Path $libs)) { throw "libs/ missing - run .\run.ps1 once to download jars first." }
if (-not (Test-Path $out))  { New-Item -ItemType Directory $out | Out-Null }

$lwjglVersion = '3.3.6'
$jars = @(
    'lwjgl', 'lwjgl-glfw', 'lwjgl-opengl', 'lwjgl-stb', 'lwjgl-openal' |
        ForEach-Object {
            Join-Path $libs "$($_)-$lwjglVersion.jar"
            Join-Path $libs "$($_)-$lwjglVersion-natives-windows.jar"
        }
    Join-Path $libs 'joml-1.10.5.jar'
    Join-Path $libs 'jlayer-1.0.1.4.jar'
) -join ';'
$sources = @()
$sources += Get-ChildItem -Path 'src\main\java' -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$sources += Get-ChildItem -Path 'src\test\java' -Recurse -Filter *.java | ForEach-Object { $_.FullName }

$srcList = Join-Path $out 'sources.txt'
[IO.File]::WriteAllLines($srcList, [string[]]$sources, [System.Text.UTF8Encoding]::new($false))

Write-Host "Compiling main + test sources..."
javac -encoding UTF-8 -d $out -cp $jars "@$srcList"
if ($LASTEXITCODE -ne 0) { throw "compile failed ($LASTEXITCODE)" }

Write-Host "Running tests..."
java -cp "$out;$jars" com.mineclone.TestMain
exit $LASTEXITCODE

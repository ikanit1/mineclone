#requires -Version 5
# Downloads LWJGL + JOML jars (first run), compiles, and runs the game.
# Usage: .\run.ps1

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$libs = Join-Path $root 'libs'
$out  = Join-Path $root 'out'
New-Item -ItemType Directory -Force -Path $libs | Out-Null
New-Item -ItemType Directory -Force -Path $out  | Out-Null

$LWJGL  = '3.3.3'
$JOML   = '1.10.5'
$REPO   = 'https://repo1.maven.org/maven2'

# Module names. Each LWJGL module has a base jar and a natives-windows jar.
$lwjglModules = @('lwjgl', 'lwjgl-glfw', 'lwjgl-opengl', 'lwjgl-stb', 'lwjgl-openal')

function Get-Jar {
    param([string]$group, [string]$artifact, [string]$version, [string]$classifier = '')
    $gpath = $group.Replace('.', '/')
    $name  = if ($classifier) { "$artifact-$version-$classifier.jar" } else { "$artifact-$version.jar" }
    $url   = "$REPO/$gpath/$artifact/$version/$name"
    $dest  = Join-Path $libs $name
    if (-not (Test-Path $dest)) {
        Write-Host "Downloading $name..."
        try {
            Invoke-WebRequest -Uri $url -OutFile $dest -UseBasicParsing
        } catch {
            throw "Failed to download $url : $($_.Exception.Message)"
        }
    }
    return $dest
}

$jars = @()
foreach ($m in $lwjglModules) {
    $jars += Get-Jar 'org.lwjgl' $m $LWJGL
    $jars += Get-Jar 'org.lwjgl' $m $LWJGL 'natives-windows'
}
$jars += Get-Jar 'org.joml' 'joml' $JOML

# Build classpath
$sep = ';'
$cp = ($jars -join $sep)

# Find sources
$sources = Get-ChildItem -Path (Join-Path $root 'src\main\java') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
if (-not $sources) { throw "No .java sources found." }

# Compile to out/ (UTF-8 without BOM — javac chokes on BOM in @argfile)
$srcListFile = Join-Path $out 'sources.txt'
[IO.File]::WriteAllLines($srcListFile, [string[]]$sources, [System.Text.UTF8Encoding]::new($false))

Write-Host "Compiling..."
& javac -encoding UTF-8 -d $out -cp $cp "@$srcListFile"
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit $LASTEXITCODE" }

# Run
$runCp = "$out$sep$cp"
Write-Host "Launching..."
& java -cp $runCp com.mineclone.Main @args
exit $LASTEXITCODE

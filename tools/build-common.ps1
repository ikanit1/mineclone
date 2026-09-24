# Shared dependency versions and BuildInfo generation for both PowerShell entry points.
function Get-MinecloneBuildProperties {
    param([string]$Root)
    $properties = @{}
    foreach ($line in [IO.File]::ReadAllLines((Join-Path $Root 'gradle.properties'))) {
        if ($line -match '^([^#=]+)=(.*)$') { $properties[$matches[1].Trim()] = $matches[2].Trim() }
    }
    return $properties
}

function Get-MinecloneClasspath {
    param([string]$Root, [hashtable]$Properties)
    $libs = Join-Path $Root 'libs'
    New-Item -ItemType Directory -Force -Path $libs | Out-Null
    $artifacts = @()
    foreach ($module in @('lwjgl', 'lwjgl-glfw', 'lwjgl-opengl', 'lwjgl-stb', 'lwjgl-openal')) {
        $artifacts += ,@('org.lwjgl', $module, $Properties.lwjglVersion, '')
        $artifacts += ,@('org.lwjgl', $module, $Properties.lwjglVersion, 'natives-windows')
    }
    $artifacts += ,@('org.joml', 'joml', $Properties.jomlVersion, '')
    $artifacts += ,@('com.googlecode.soundlibs', 'jlayer', $Properties.jlayerVersion, '')
    $jars = foreach ($artifact in $artifacts) {
        $group, $name, $version, $classifier = $artifact
        $file = if ($classifier) { "$name-$version-$classifier.jar" } else { "$name-$version.jar" }
        $dest = Join-Path $libs $file
        if (-not (Test-Path -LiteralPath $dest)) {
            $url = "https://repo.maven.apache.org/maven2/$($group.Replace('.', '/'))/$name/$version/$file"
            $temporary = "$dest.download"
            Write-Host "Downloading $file..."
            try {
                Invoke-WebRequest -Uri $url -OutFile $temporary -UseBasicParsing
                Move-Item -LiteralPath $temporary -Destination $dest -Force
            } finally {
                if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
            }
        }
        $dest
    }
    return $jars -join [IO.Path]::PathSeparator
}

function Write-MinecloneBuildInfo {
    param([string]$Root, [string]$Output, [hashtable]$Properties)
    $sha = 'unknown'
    if (Get-Command git -ErrorAction SilentlyContinue) {
        $candidate = & git -C $Root rev-parse --short=12 HEAD 2>$null
        if ($LASTEXITCODE -eq 0) { $sha = ($candidate | Select-Object -First 1).Trim() }
    }
    $version = $Properties.version
    if ($version -notmatch '^[0-9A-Za-z.+_-]+$') { throw 'Invalid version in gradle.properties' }
    $path = Join-Path $Output 'generated\com\mineclone\core\BuildInfo.java'
    New-Item -ItemType Directory -Force -Path (Split-Path $path) | Out-Null
    $template = [IO.File]::ReadAllText((Join-Path $Root 'src\main\java\com\mineclone\core\BuildInfo.java.template'))
    [IO.File]::WriteAllText($path, $template.Replace('@VERSION@', $version).Replace('@GIT_SHA@', $sha), [Text.UTF8Encoding]::new($false))
    Write-Host "Building Mineclone $version ($sha)"
    return $path
}

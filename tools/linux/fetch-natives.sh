#!/usr/bin/env bash
# The LWJGL natives for Linux x64. libs/ ships the Windows ones only, so a
# window cannot open in a Linux container without these. Downloads them once
# from Maven Central into out-test/linux-natives (or the directory given).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DEST=${1:-$ROOT/out-test/linux-natives}
VERSION=$(grep '^lwjglVersion=' "$ROOT/gradle.properties" | cut -d= -f2 | tr -d '[:space:]')
mkdir -p "$DEST"
for module in lwjgl lwjgl-glfw lwjgl-opengl lwjgl-openal lwjgl-stb; do
    jar="$module-$VERSION-natives-linux.jar"
    [ -s "$DEST/$jar" ] && continue
    url="https://repo1.maven.org/maven2/org/lwjgl/$module/$VERSION/$jar"
    for attempt in 1 2 3 4 5; do
        if curl -fsSL -o "$DEST/$jar.part" "$url"; then
            mv "$DEST/$jar.part" "$DEST/$jar"
            break
        fi
        [ "$attempt" -eq 5 ] && { echo "could not download $url" >&2; exit 1; }
        sleep $((attempt * 2))    # Maven Central rate-limits bursts (HTTP 429)
    done
done
echo "LWJGL $VERSION natives for Linux in $DEST"

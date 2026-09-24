# Shared by the Linux ports of the real-game checks: classpath, a free port and
# a virtual display. Source it; it sets ROOT, CLASSES, GAME_CP, SERVER_CP, PORT.
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
CLASSES=${CLASSES:-$ROOT/out-test}
NATIVES=${NATIVES:-$ROOT/out-test/linux-natives}
if [ ! -f "$CLASSES/com/mineclone/Main.class" ]; then
    echo "no compiled game in $CLASSES (compile first, or set CLASSES)" >&2
    exit 2
fi
if ! ls "$NATIVES"/*-natives-linux.jar >/dev/null 2>&1; then
    echo "no Linux natives in $NATIVES: run tools/linux/fetch-natives.sh" >&2
    exit 2
fi
command -v xvfb-run >/dev/null || { echo "xvfb-run (Xvfb) is required" >&2; exit 2; }
LIBS=$(ls "$ROOT"/libs/*.jar | grep -v natives-windows | tr '\n' ':')
SERVER_CP="$CLASSES:$LIBS"
GAME_CP="$CLASSES:$LIBS$(ls "$NATIVES"/*.jar | tr '\n' ':')"
# A free port, so a leftover process or another checkout cannot collide.
PORT=$(python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])' 2>/dev/null \
    || echo $((20000 + RANDOM % 30000)))
# GLFW wants a private runtime directory even under Xvfb.
export XDG_RUNTIME_DIR=${XDG_RUNTIME_DIR:-$ROOT/out-test/xdg}
mkdir -p "$XDG_RUNTIME_DIR" && chmod 700 "$XDG_RUNTIME_DIR"

# One game process on its own virtual screen: game <dir> <extra -D flags...>
game() {
    local dir=$1; shift
    mkdir -p "$dir"
    timeout "${GAME_TIMEOUT:-1800}" xvfb-run -a -s "-screen 0 1280x720x24" java -Xmx2g \
        "-Dmineclone.autopilot=$dir/shots" "-Dmineclone.savesDir=$dir/saves" "$@" \
        -cp "$GAME_CP" com.mineclone.Main > "$dir/out.log" 2> "$dir/err.log"
}

report() {
    local name=$1 dir=$2
    echo
    echo "---- $name ----"
    grep -a 'autopilot' "$dir/out.log" | grep -v '^autopilot: /' || true
    grep -a 'autopilot\|Exception\|Error' "$dir/err.log" | grep -v 'ALSA' | head -10 || true
}

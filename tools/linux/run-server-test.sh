#!/usr/bin/env bash
# Linux port of run-server-test.ps1: a dedicated server and two real games
# under Xvfb. Each client places a block that must reach the other through the
# server; then the server stops on /stop and its world must be on disk.
set -u
source "$(dirname "$0")/common.sh"
WORK=$ROOT/out-test/server
rm -rf "$WORK" && mkdir -p "$WORK"
cd "$ROOT"
echo "server test: port $PORT"
cat > "$WORK/server.properties" <<PROPERTIES
port=$PORT
direct=true
upnp=false
photon=false
saves-dir=$WORK/saves
world=test
world-name=TestServer
seed=20260922
mode=creative
autosave-seconds=15
view-distance=4
PROPERTIES
mkfifo "$WORK/console"
java -cp "$SERVER_CP" com.mineclone.server.ServerMain "$WORK/server.properties" \
    < "$WORK/console" > "$WORK/server.out" 2> "$WORK/server.err" &
SERVER=$!
exec 3> "$WORK/console"          # keeps the console open until /stop
sleep 10
NET=(-Dmineclone.autopilot.net=server "-Dmineclone.autopilot.netPort=$PORT")
game "$WORK/guest0" "${NET[@]}" -Dmineclone.autopilot.netSlot=0 &
G0=$!
sleep 3
game "$WORK/guest1" "${NET[@]}" -Dmineclone.autopilot.netSlot=1 &
G1=$!
wait "$G0"; C0=$?
wait "$G1"; C1=$?
echo "server test: both clients finished"
echo '/stop' >&3
exec 3>&-
stopped=0
for _ in $(seq 1 40); do kill -0 "$SERVER" 2>/dev/null || { stopped=1; break; }; sleep 1; done
[ "$stopped" -eq 1 ] || { echo "server test: the server ignored /stop, killing it"; kill "$SERVER"; }
wait "$SERVER"
echo; echo '---- server ----'; grep -v '^\[server\] saved' "$WORK/server.out"
report "guest 0" "$WORK/guest0"
report "guest 1" "$WORK/guest1"
code=0
[ "$stopped" -eq 1 ] || code=1
grep -q 'stopped' "$WORK/server.out" || { echo "server test: no clean shutdown"; code=1; }
[ "$C0" -ne 0 ] && { echo "server test: guest 0 failed ($C0)"; code=1; }
[ "$C1" -ne 0 ] && { echo "server test: guest 1 failed ($C1)"; code=1; }
chunks=$(ls "$WORK/saves/test/chunks" 2>/dev/null | grep -c '^c\.' || true)
[ "$chunks" -gt 0 ] || { echo "server test: the server saved no chunks"; code=1; }
echo; echo "server test: $chunks chunks on disk"
[ "$code" -eq 0 ] && echo "server test: the server and both clients passed"
exit "$code"

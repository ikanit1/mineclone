#!/usr/bin/env bash
# Linux port of run-net-test.ps1 (LAN): the game twice under Xvfb, one hosting
# and one joining over a real socket; each side checks the other's block and
# equipment arrived. Software OpenGL is slow, so the guest starts only once the
# host says its room is open. Exits non-zero if either side failed.
set -u
source "$(dirname "$0")/common.sh"
WORK=$ROOT/out-test/net
rm -rf "$WORK" && mkdir -p "$WORK/host" "$WORK/guest"
cd "$ROOT"
echo "net test: via lan, port $PORT"
NET="-Dmineclone.autopilot.netPort=$PORT"
game "$WORK/host" -Dmineclone.autopilot.net=host "$NET" -Dmineclone.autopilot.netVia=lan &
HOST=$!
for _ in $(seq 1 600); do
    grep -q 'autopilot: ok - hosting the room' "$WORK/host/out.log" 2>/dev/null && break
    kill -0 "$HOST" 2>/dev/null || break
    sleep 1
done
game "$WORK/guest" -Dmineclone.autopilot.net=join "$NET" -Dmineclone.autopilot.netVia=lan &
GUEST=$!
wait "$HOST"; HOST_CODE=$?
wait "$GUEST"; GUEST_CODE=$?
report host "$WORK/host"
report guest "$WORK/guest"
code=0
[ "$HOST_CODE" -ne 0 ] && { echo "net test: host failed ($HOST_CODE)"; code=1; }
[ "$GUEST_CODE" -ne 0 ] && { echo "net test: guest failed ($GUEST_CODE)"; code=1; }
[ "$code" -eq 0 ] && { echo; echo "net test: both sides passed"; }
exit "$code"

# FND-01 baseline, 24 September 2026

Source commit: `91a2e5b`, network protocol v7. This checkpoint preserves the
existing working tree before implementation of the 1.1 roadmap. Shared Game,
shader and test-runner changes were committed together to preserve a compiling
baseline; splitting their partially overlapping edits would fabricate untested
intermediate states.

Verified on the development machine:

- `run-tests.ps1`: **455 passed, 0 failed**.
- `run-net-test.ps1`: **both sides passed** using actual LAN sockets and two
  rendered clients; guest and host blocks, equipment and mob snapshots checked.
- `run-server-test.ps1`: **the server and both clients passed**; clean `/stop`,
  **13 chunks on disk**.
- Menu/game autopilot: **all checks passed**, including reopening worlds,
  inventory drag conservation and creative window navigation.

Logs: `out-test/roadmap-baseline.log`, `roadmap-baseline-net.log`,
`roadmap-baseline-server.log`, `roadmap-baseline-menu.log` (local artifacts).

This checkpoint still has the P0 defects listed in the roadmap. In particular,
a separate production-server loopback regression reproduced loss of 16 diamonds
when a guest destroyed a chest. NET-05 and SAVE-01/02/03 are being implemented
after this baseline; their results are not claims about this checkpoint.

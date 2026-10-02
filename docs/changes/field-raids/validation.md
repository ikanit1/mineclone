# Field raids and weather hearing verification

Windows/JDK17, 2 October 2026. Candidate starts at `21dbe21`, including the
GitHub generation/survival batch at `c6c1774`. Original checkout and saves remain
untouched; every gameplay/save fixture is under ignored tool-owned output paths.

| Check | Result and boundary |
|---|---|
| Focused ecology/physics/sim/core | Initial 312 passed, 0 failed; later dense-field regression reproduced the failure, then all nine rabbit tests passed after the vertical-threat/bite correction |
| Full final `run-tests.ps1` | **872 passed, 0 failed, 0 skipped**, 67.090 s; includes all 23 new checks and unchanged disabled-rule trajectory |
| Runner CLI | PASS: filters/list/environment precedence and assertion failure exits 1 |
| Native `RabbitRaidGameSmoke` | Final PASS, 2,137 actual Game frames, six real framebuffer captures, actual input and commands |
| Compile / serverZip / extracted package | PASS; archive launcher reaches TCP, saves and stops with exit 0 |
| `run-net-test.ps1 -Farm -LivingWorld` | Final host and guest PASS: ordinary finite rabbit eats stage 7 -> 0, guest with no Ecology mirrors 0, protected crop remains 7 |
| `run-server-test.ps1 -Farm -LivingWorld` | Final NORMAL difficulty PASS: both guests mirror actual ordinary-rabbit stage 0, protected crop 7; disk snapshot verifies 0/7, ten-day advance and clean stop pass |

The native story kills four of five reserved wolves through player damage and
normal retirement, then uses the owner command to advance exactly seven days.
Rabbit population rises from 20 to 51. Two controlled hungry bodies are claimed
from that finite population; ordinary Game AI damages 1/16 open plants after
12.01 simulation seconds while leaving all 16 enclosed plants untouched.
The real gate then opens and the second rabbit enters and eats. The test freezes
crop growth so stage decreases can only be raids; farmland support remains intact.
Appetite in this controlled story is initialized hungry. The network acceptance
exercises ordinary randomized appetite separately, with no hunger override.

The real save/menu/reopen path preserves exact crop stages and all three surviving
animal IDs with three reservations, without duplicating bodies. F3+G uses actual
key input and preserves creative flight before any observer pose adjustment.

Independent review found dense inaccessible fields could recycle the nearest
rejected plants and starve a farther reachable crop. The new loaded-terrain
regression reproduced the defect for 60 simulation seconds; search now keeps
nearest-first preference and reserves a second attempt for rotating exploration,
within the existing cell/path budgets. The regression passes with both 16 and 64
inaccessible plants (the latter exceeds the rejection cache), and the reachable
crop is eaten through actual Mob update/physics while enclosed crops remain intact.

The first dedicated raid fixture failed because its rabbit entered exposed
irrigation water below the farmland lip. The corrected fixture keeps irrigation
within hydration radius under a boundary wall, so the requested reachable-crop
scenario does not become a swimming-exit test.

The next normal-difficulty run exposed horizontal-only wildlife sensing: hostiles
more than twenty blocks below the field kept elevated rabbits fleeing. A queued
read-only probe on the real server identified the threat while the same saved
bodies ate through the shared simulation in isolation. Living World sensing now
uses physical 3D distance for threats, prey and pack anger; the legacy OFF entry
point retains its old metric. The new actual Mob regression reproduced the old
failure and then confirmed eating above an active lower hostile while a nearby
same-level hostile still takes priority. Wolf prey bites use physical distance in
the enabled mode too: sensed prey four blocks below cannot be bitten through the
floor, while a nearby same-level bite still works. Final normal-difficulty server
and LAN reruns passed on this exact source, including the ordinary randomized
appetite, both guest mirrors and the authoritative saved 0/7 crop stages.

The weather check moves to an exposed, rainy, non-snow source below altitude 90
at night so the spider is awake. Actual held mouse input breaks a real stone and
passes through InteractionController, Game.blockBroken and WorldSession.noise.
At horizontal distance 12, the spider investigates in clear weather and does not
investigate in full thunder. Owner weather commands are checked against the real
Atmosphere state. Unit checks additionally cover roofs, dry biomes, snow, front
transitions, host/server natural-front parity and the unchanged ecology-OFF path.

Final native captures were visually inspected: [day 0](evidence/day-0.png),
[day 7](evidence/day-7.png), [open gate](evidence/open-gate.png),
[storm stone break](evidence/storm-stone-break.png).

## Real older-reader compatibility

`PopulationSaveCompatibility write -> rewrite -> verify` passes using the actual
pre-ecology `6f35d01` classes from `E:/mineclone/out` and current `out-test` classes.
Fresh fixtures: `out-test/field-raids-compatibility`.

The optional supported-clock-1 specimen retains unchanged player fields,
ecology/rules and the new `ecology_fields` section byte for byte, including an
opaque suffix. The current reader decodes the original negative-coordinate field
with 32 crops after that old-writer rewrite. Fields SHA256:
`4af6227b0281a38e57f55676c2e15cdf2bdc0d6df8b1be48cac3125125273894`.
Normal current clock-2 metadata and canonical chunk-8 animal records are refused
by the older reader and protected against bypass writes. Current reopening keeps
the same baby and opaque tail. This proves preservation/refusal, not whole-world
playability on an old build that lacks clock 2 and chunk reader 8.

## Boundaries

Only LW-06 and LW-09 are completed by this change. LW-11's full A–C stories,
Photon, hardware budgets and LW-12 remain open. Functional smoke logs contain
frame/update spikes and are not performance acceptance evidence.

Independent source review covered persistence, field bounds/eviction, OFF parity,
hearing, F3 input and physical crop routes. The dense-field finding above was fixed
and reviewed again; no other material defect was found in those boundaries.
F3's per-frame field diagnostics cost is unmeasured and belongs to LW-12.

The repository Agent Validator wrapper remains blocked before checks by its
retained metrics closure/retry state. Its underlying tests and runner-cli checks
ran directly. Claude review is disabled by repository configuration; independent
source review runs in this session.

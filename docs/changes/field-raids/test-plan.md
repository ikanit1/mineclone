# Validation plan

Run the current build's `run-tests.ps1 -Only ecology,sim,physics,combat,save` first,
then the full `run-tests.ps1` and runner CLI checks. The final frozen build passed
872 checks with no failures or skips, plus runner CLI checks; the recorded
outcomes and artifact paths are in [validation.md](validation.md).

| Layer | Evidence required |
|---|---|
| `RabbitRaidTests` | All three crops at stage 4–7, physical approach, no loot, scheduled regrowth and satiety; closed fence/gate, open narrow gate/gap, fear/wolf/FLEE/temptation, sleeping OFF/distraction, immature/read-only/loaded-only targets and reachable alternatives; real Mob approach to a farther crop beside 16- and 64-plant fenced clusters, including a cluster larger than the rejection cache |
| `FieldHabitatTests` | Sparse index across edits/restoration; negative chunk coordinates; opaque tails; last observations over absence/removal; future and corrupt data preservation; maximal bound and 256-crop saturation; final observation before eviction |
| `StoryARaidTests` | Exactly seven model days after four of five wolves are removed; actual shared session rabbit approach; untouched closed field and damage after the same gate opens |
| `WeatherHearingTests` | Actual mob response to a source at twelve blocks; roof/arid/snow exclusions; deterministic front fading; host/server parity, overrides and the legacy OFF path |
| Existing suites | Body reservations, death/birth accounting, save ownership, block ordinals, disabled simulation parity and network conservation remain valid |

The height regression exercises an actual crop approach above a hostile twenty
blocks below, same-level fear, height-aware wolf prey/pack sensing, a sensed prey
four blocks below that cannot be bitten, and a close same-level bite. Separate
legacy calls retain the horizontal threat, prey, anger and bite outcomes. Final
native, LAN and dedicated runs must use the frozen build containing these changes.
The dedicated crop scenario must pass on NORMAL difficulty; a peaceful diagnostic
run establishes the cause of underground fear but does not satisfy that gate.

Run `java -cp 'out;libs/*' tools/RabbitRaidGameSmoke.java` against current compiled
sources and native libraries. The optional positional argument selects an artifact
directory; the default is `out-test/rabbit-raid-game`. On Linux, use Xvfb and the
Linux natives/classpath separator. The tool uses disposable saves and the real
Game loop/render path. Inspect the captured fields and diagnostics, recorded wolf
deaths, seven-day advancement, closed/open gate phases, saved/reopened crop stages
and body identities. F3+G must show ecology without toggling creative flight.
Its weather phase uses actual input to break a stone source
and compares a spider's response in clear weather and thunder.

Run relevant real LAN and dedicated-server gates with matching protocol builds:
`run-net-test.ps1 -Farm -LivingWorld` and `run-server-test.ps1 -Farm -LivingWorld`.
Both peers must report success for a LAN claim. The combined Windows scenarios
require an ordinary finite rabbit's authoritative stage 7 -> 0 meal, that same
stage at every guest with Ecology absent, and an unchanged protected crop.
The dedicated tool also verifies the real disk snapshot before the later ten-day
command. Generic or Linux farm passes do not prove these additional raid steps.
Rainy hearing is checked natively and on the shared server/host path in unit tests;
the real network process scenarios do not issue rainy noise themselves.
Packaging uses `gradlew.bat test serverZip` and `tools/TestServerPackage.ps1`.

Distinguish headless rules, the native Game scenario, actual network scenarios and
hardware performance. A bounded scan or a passing smoke does not close LW-12.
The complete Living World A–C scenario set, nests, forest regrowth and the release
go/no-go gate remain outside this change. LW-06/LW-09 acceptance is complete:
the final native run produced six inspected captures over 2137 Game frames,
matching LAN host/guest and both NORMAL dedicated-server guests passed, the disk
snapshot retained eaten/protected crop stages 0/7, and the server ZIP was built
and launched after extraction. [Validation](validation.md) records each scope.

# Burn scars: validation, 2 October 2026

Base: `323796a8dba5c8efcf2c7ab5c7e7d7a1f27ebb90` (`codex/spider-lairs`),
including the earlier GitHub generation, farm, survival and Living World work.
LW-08 uses the existing opt-in rule. All writes used disposable fixtures in
this checkout's ignored `out-test/`; the primary checkout and user saves were preserved.

## Deterministic checks and package

The unchanged baseline passed **904/904**. Final PowerShell runner passed
**935 passed, 0 failed, 0 skipped**, **76.631 seconds**. Final Gradle
`test serverZip` ran the same **935/935**, **89.198 seconds**, in a successful
**1m 35s** build. The 31 added checks are BurnScarsTests (10), BurnGrowthTests
(10) and BurnRuntimeTests (11). Legacy OFF simulation/generation hashes passed
without changing expectations.

Checks include actual fire-to-ground registration and capacity, bounded records,
unknown suffixes, newer/corrupt protection and durable evidence, player fields,
loaded/writable crown boundaries, active trample paths and living bodies,
ordinary versus replayed terrain/flags/due ticks with actual scheduled trees,
same-day saved chunk arrival, real animal navigation and finite reservations,
natural sapling hand breaking/support collapse/explosion loot, Ctrl+pick,
item save round-trip and ordinary OFF player growth, and actual SaveManager/
ChunkLoader reopening with unique animal identities.

Production compilation, runner CLI filters/listing/precedence/failure exit and
the extracted server's TCP/save/stop/exit-0 path passed. After acceptance helper
changes, the final production sources and server archive were rebuilt; a focused
runner recompiled all tests and passed **308 checks, 0 failed, 627 skipped** in
**18.647 seconds**. Evidence: `burn-scars-all-final.log`,
`burn-scars-gradle-final.log`, `burn-scars-compile-final.log`,
`burn-scars-focused-final.log`, `burn-scars-runner-cli-final.log` and
`burn-scars-package-build-final.log` under `out-test/`. The final extracted
directory is `out-test/server-package-3fdae4c44628471fa14877738f1aa2e4`; its
`stdout.log` records the real launcher, world save and clean stop, with the
written `Mineclone-server/saves/server/level.dat` retained beside it.

## Defects reproduced and corrected

Daily/replay sapling scheduling initially differed by one tick (87,385 versus
87,384). `WorldClock.logicalDayTicks` now subtracts the exact calendar cycle
and fractional cursor while retaining the integer tick counter, including
counters above double's exact integer range. The same helper anchors nest silk.
Evidence: `burn-scars-isolated-before.log`, `burn-scars-isolated-after.log`.

Running the actual dispatcher between ordinary days exposed overlapping future
crowns: ordinary growth planted two sites, advancement planted three and left
one permanently blocked. Seven-cell Chebyshev root spacing reserves the future
crown before growth; the strict regression compares terrain, metadata, history
and pending ticks. Evidence: `burn-growth-replay-probe.log` and
`burn-scars-isolated-final.log`.

A wider escape test scarred all eight initial 12-block destinations. A 24-block
refuge was selected, but recomputing the nearest scar reversed the cow before it
left. Even retaining a goal only while within six blocks failed in clear gaps.
The host now keeps a still-valid refuge while regional age-0..2 history remains,
clearing it on arrival, invalidation or expiry/OFF. The same body completes 240
ordinary brain frames without teleportation, overlap, loss or changed identity.
Evidence: `burn-runtime-before-stable-escape.log`,
`burn-runtime-before-gap-escape.log`, `burn-runtime-focused.log`.

Review also reproduced missing loot for natural meta 4/5/6 and a Ctrl+picked
sapling remaining natural after player placement. Loot contexts and placement
use the low two species bits; raw saved item components remain preserved.
Actual interaction and item-save regressions failed before and pass after:
`burn-growth-flags-before.log`, `burn-growth-flags-after.log`.

Dense recovered histories can free a full slot even when spacing prevented some
sites from planting. Retirement requires age seven, loaded writable healed
grass and no unknown suffix; opaque, blocked and unhealed histories survive.
Natural planting and growth check the whole crown against participants/animals,
so recovery cannot grow a solid tree through a body.

## Real previous reader

`BurnScarsReaderCompatibility` created a fixture with current classes, rewrote
it with preserved actual `323796a` reader-9 classes where BurnScars is absent,
then verified it with current classes. It requires the old phase's receipt and
rewritten level title. Dates 3/5, cursor 7, exact cells and planted-once flags,
natural meta 4, COW ID 877 and unknown level/chunk/record/entity suffixes remained
intact. The outer ecology bytes remain exact through real disk rewriting.
Evidence: `out-test/burn-reader-compat.log` and
`out-test/burn-reader-compat-dc9d0347350b42f7ba82c00e25b38901`.
This proves optional-state preservation, rather than complete older-build gameplay.

## Actual native Game

The first complete real Game run passed 4,023 frames: actual RMB fire placement,
192 burned ground columns in seven records, habitat capacity **151→144→147**,
and **130** recovered grass cells plus **three** naturally scheduled trees by
day seven. Original COW ID **132** received a refuge and physically moved from
(17.5,21.5) to (3.406,25.15), alive. Actual atmosphere rain at 0.793 washed ash.
Ordinary materialization produced finite ID **153**, distinct from the initial
cast; both identities and planted state survived actual save/menu/reopening.
Evidence: `out-test/burn-game-fresh.log` and six captures in `burn-game-fresh/`.

The final strengthened run passed **4,417 real frames**, exit 0, with all final
production changes. It preloads the planned camera route, excludes every
initially active animal ID from the returning cast, and allows rendered chunks
to settle before capture. It registered the same **192 burned columns / seven
records**, capacity **141→138→141**, **131** recovered grass cells and **three**
new trees. Original COW **132** physically reached (3.715,25.32), alive; ordinary
materialization produced fresh finite **153**. Actual save/menu/reopen preserved
both identities, seven histories and unique active/pending IDs. Rain reached
0.7891699. The photographed new root is **(29,87,27)** on a recorded burned site.
Evidence: `out-test/burn-game-settled.log` and its isolated
`saves-4501789205956217590/world_2026-10-02_19-44-06` fixture.

All six final frames were visually inspected and retained in this change:

1. [Before the fire](evidence/burn-01-before.png).
2. [Actual fire after RMB ignition](evidence/burn-02-actual-fire.png).
3. [Day-zero burned ground and ash](evidence/burn-03-day-zero-scars.png).
4. [Naturally grown day-seven tree](evidence/burn-04-day-seven-young-forest.png).
5. [Recovered grove and finite animals](evidence/burn-05-recovered-animals.png).
6. [The saved grove after reopening](evidence/burn-06-saved-forest-reopen.png).

The day-zero and day-seven views wait 2.5 seconds for rendering. The held dirt
item remains visible; animal identity and reserve assertions come from the real
runtime and save checks, rather than identifying distant bodies from pixels.

Six authored mature root cells were genuinely consumed. Surviving wood/leaves
are retained as real obstacles; this does not claim every original crown was
completely burned. Recovery never manually writes successful grass/trees or
removes obstacles to meet the deadline. Fire and exposed-ash random callbacks
are explicitly accelerated; this is functional acceptance, not normal-time fire
rate or performance measurement. Subsequent growth/materialization uses ordinary
Game ticks and normal caps.

## Actual LAN and dedicated server

Final LAN owner and guest passed exit 0 with matching terrain: DIRT **192→47**,
grass **2,354**, **four** new scheduled tree roots. Original cast ID **170** was
observed before advancement; both peers saw the same healthy fresh finite ID
**180**, excluded from all initially active animal IDs. The guest's Ecology was
absent and its command was refused; it receives ordinary block/S_MOBS snapshots.
Evidence: `out-test/burn-net-empty-cast.log`, peer logs/captures in `burn-net/`.

The final **NORMAL dedicated server and two real clients** passed exit 0. Both
guests observed the same healthy fresh finite **48**, excluding the initial
global cast and original COW **14**. Guest ecology commands were refused and
neither guest owns Ecology. Habitat capacity changed **192→188→192**. Independent
reads of the actual saved world after both `/save` and `/stop` passed:

```text
BURN_SAVE_VERIFY PASS day=7 scars=10 sites=192 grass=145 grownNaturalTrees=4 capacity=192 originalCowID=14 observedFiniteIDs=[48] uniqueSavedIDs=true
```

Evidence: `out-test/burn-server-north.log`, `burn-server/burn-save.log`,
`burn-server/burn-save-after-stop.log`, peer logs, captures and fixture receipt.

Initial cast authoring occurs before Ecology ON, in fresh tool-owned saves.
Camera footprints are preloaded and empty-body terrain receipts saved before
reopening; the ordinary materializer is released when both cameras are away.
This avoids generated starter herds saturating the passive cap, without raising
caps or manually creating a successful returned body. Dedicated pre/post-stop
checks retain ordinary reservations and body caps. Dedicated observers move
north to (70,26), so the existing southern herd no longer fills the local
64-block cap around them; no already-created finite bodies are deleted.

Early failed attempts remain in `out-test/`. The network fixture initially
reused exhausted FIRE metadata; explicitly setting age zero now matches ordinary
ignition. Generated starter herds also filled normal caps before the story;
initial empty terrain receipts are now authored and saved before Ecology ON.
The retained south-camera dedicated attempt filled the local cap with an
ordinary herd; moving the observers north resolved it under unchanged caps.
These are fixture corrections, not successful runs or relaxed production rules.

## Validation boundaries

Agent Validator was attempted and failed before checks on the retained metrics
closure conflict (`changed console.1.log`) and retry limit. Logs were not reset.
Its configured runner and CLI checks passed directly; repository configuration
disables Claude CLI review. Independent source reviews in this session covered
host/save ordering, protected terrain, OFF behavior, record bounds, exact time,
canopy parity, loot flags and escape-goal invalidation.

Clear light, empty crown, loaded writable terrain and normal body caps constrain
the seven-day story. Protected/player-blocked cells remain intact. LW-10/11 full
story consolidation, LW-12 performance budgets, LW-13 owner verdict and rare
normal-radius update hitches remain open. No performance/Photon claim is made.

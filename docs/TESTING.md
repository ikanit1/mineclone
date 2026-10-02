# Tests and validation

Run from the repository root with Java 17 and PowerShell 5+:

```powershell
.\run-tests.ps1                    # compiles main + test sources, runs all
.\run-tests.ps1 -Only save,net      # comma-separated categories
.\run-tests.ps1 -Skip net
.\run-tests.ps1 -Only save -List    # names only; executes no check bodies
$env:MINECLONE_TEST_ONLY = 'save'   # used unless explicit -Only / --only is set
.\run-tests.ps1
Remove-Item Env:MINECLONE_TEST_ONLY
.\gradlew.bat test                 # same runner, not empty JUnit discovery
.\gradlew.bat mineTests -PtestOnly=save -PtestSkip=net
.\gradlew.bat mineTests -PtestList
```

`TestMain` registers checks before executing them. `CoreTests` contains the former
runner-local assertions; 455 original checks survived the move without loss.
New regression suites increase the total. Use `-List` for the current inventory,
not a stale count copied into a document. New suites implement
`void runAll(TestMain.Runner runner)`; register with `suite(category,name,suite)`.
`TestMain.Check` can throw Exception. `TestMain.run(category,name,check)` adds one check.

Core categories include `core`, `save`, `chunk`, `worldgen`, `optimization`, `audio`,
`weather`, `combat`, `physics`, `animation`, `creative`, `menu`, `inventory`, `net`
and `feature`. M0 also adds `data`, `clock`, `render` and `perf`. More categories
may be registered as the roadmap proceeds.
`ecology` covers strict species data, deterministic daily dynamics, bounded
codecs, corruption/future-layout preservation, finite materialization and
save/travel body ownership. It also registers `RabbitRaidTests` for physical
approach, regrowth, barriers, fear/temptation and sleeping rule changes;
`FieldHabitatTests` for the sparse index, bounded optional save data and eviction;
`StoryARaidTests` for seven-day predator release and actual session raids; and
`WeatherHearingTests` for source exposure, weather overrides and host/server
hearing parity. `sim` retains the disabled-rule parity fixture.
Unknown categories/arguments, missing values and an empty selected set exit 2;
an assertion/test or report-write failure exits 1. A valid all-passing run exits 0.
`--only` explicitly overrides `MINECLONE_TEST_ONLY`; `--skip` then removes categories.
Direct Java CLI accepts `--only save,net`, `--skip net`, `--list`, or `--only=save`.

A run writes `out-test/test-report.txt` with every result, failure stack traces,
suite timings, the ten slowest checks, selected/skipped counts and elapsed time.
`--list` does not replace the last execution report. Override the report path with
`-Dmineclone.testReport=...` for isolated diagnostics. Timings cover test execution;
PowerShell javac/Gradle startup are separate costs.

`tools/TestRunnerCli.ps1` verifies list/only/skip, environment precedence, bad-filter
exit 2 and an intentionally failing temporary classpath fixture returning exit 1.
The sentinel never changes tracked tests. Run it after `run-tests.ps1`.

## What requires the real engine

The default suite creates no GLFW window or OpenAL context. It still checks image
assets, decodes MP3 files, opens local sockets and creates temporary worlds. Run
from the repository root so asset/source fixture checks resolve correctly.
`net` here means deterministic protocol and local socket tests; it does not consume
Photon cloud slots. `OptimizationGlSmoke` is a separate main outside the default suite.

```powershell
.\run.ps1 -CompileOnly
.\run-net-test.ps1       # two graphical game processes over real LAN sockets
.\run-server-test.ps1    # headless server plus real clients
.\run-net-test.ps1 -LivingWorld    # host enables ecology; guest command is refused
.\run-server-test.ps1 -LivingWorld # console enables/advances ecology; two real guests
java -cp 'out;libs/*' tools/PopulationGameSmoke.java # actual Game/F3+G/death/save/reopen
.\gradlew.bat serverZip
.\tools\TestServerPackage.ps1
javac --release 17 -encoding UTF-8 -d out-test -cp 'out-test;libs/*' tools/ContainerBreakSmoke.java
java '-Dmineclone.checkThreadOwnership=true' -cp 'out-test;libs/*' com.mineclone.ContainerBreakSmoke
```

### In a Linux container

`libs/` carries Windows natives only, but a Linux container with Xvfb and Mesa
(software OpenGL, about ten frames a second) runs the same real-engine checks.
`tools/linux/fetch-natives.sh` downloads the LWJGL natives for Linux into
`out-test/linux-natives` once; compiled classes are expected in `out-test/`
(`CLASSES=` overrides it). Sound stays off without an audio device, so the
autopilot skips its music checks and says so.

```bash
tools/linux/fetch-natives.sh
tools/linux/run-net-test.sh       # the LAN pair of run-net-test.ps1
tools/linux/run-server-test.sh    # the server and two clients of run-server-test.ps1
# Same server gate with an old fixture, copied into the disposable test root:
WORLD_SOURCE="$PWD/src/test/resources/fixtures/saves/alpha-small" UPGRADE_WORLDGEN=true \
    tools/linux/run-server-test.sh
xvfb-run -a java -Dmineclone.autopilot=out-test/autopilot/shots \
    -Dmineclone.savesDir=out-test/autopilot/saves -cp "out-test:libs/*:out-test/linux-natives/*" com.mineclone.Main
```

Generator opening checks: `TestMain --only worldgen,net,save,menu`;
`tools/WorldGenOpeningSmoke.java` renders and clicks the new dialog (three
resolutions, failure recovery), and `tools/WorldGenGameSmoke.java` drives the
actual Game through decline, mouse upgrade, save and reopen. Run Java source
tools with the compiled game plus libraries/natives on the classpath and Xvfb
on Linux. They copy the fixture to ignored `out-test/worldgen-*` directories.

The autopilot's timeouts count game time, and the frame step is capped, so a
slow software renderer stretches a run instead of failing it: the menu autopilot
takes about 25 minutes, each network check about 15. Frame speed and the look of
a real GPU are not measured this way.

LAN evidence lives under `out-test/net`; package smoke extracts into a fresh
`out-test/server-package-*` directory, checks TCP readiness, sends `/stop`, verifies
exit 0 and saved `level.dat`, and retains stdout/stderr. No user world is touched.
Native animation, audio, menus and performance need their actual render/input path;
see [TOOLS](TOOLS.md), [rendering reviews](rendering/) and [architecture](architecture/).

`java -cp 'out;libs/*' tools/RabbitRaidGameSmoke.java` runs a disposable native
Game scenario with wolf deaths, seven days, open and fenced plots, opening a
gate, save/reopen, and actual stone-breaking input under clear/storm weather.
Its default artifacts go to `out-test/rabbit-raid-game`; a positional argument
selects another output directory. Run against the current compiled sources with
the usual GL/OpenAL natives; Linux needs Xvfb and the Linux classpath separator.
Headless checks do not establish native, LAN, dedicated-server or hardware
performance acceptance. [The field-raids test plan](changes/field-raids/test-plan.md)
defines those gates; [validation](changes/field-raids/validation.md) records results.

## Spider-lair checks

`LairTests`, `NestBlockTests`, `ColonizerTests` and `LairRuntimeTests` are registered
in the `ecology` category. The runtime checks use the actual shared WorldSession,
SaveManager and ChunkLoader, including deaths before saving, physical dawn returns,
parked identities, first-observation daily/advance parity and protected newer data.
`java -cp 'out;libs/*' tools/LairGameSmoke.java out-test/lair-game` exercises real
Game/input/render, three separate cleared nights, another dark cave and menu/reopen.
Windows `run-net-test.ps1 -Lairs` and `run-server-test.ps1 -Lairs` require matching
protocol-13 peers and check authoritative nest blocks, finite identities and combat.
The server scenario uses only a disposable `out-test` save. The optional lair scenario
is separate from `-Farm`; it does not replace that scenario's farming coverage.
[Spider-lairs validation](changes/spider-lairs/validation.md) records actual results
and separates functional acceptance from the open performance/Photon gates.

## Combined Living World stories (LW-10/11)

`EcologyDiagnosticsTests` pins read-only reports, stored model trends, bounded
nearest-location lookup, protected history and viewport overflow. `LivingWorldTests`
uses one valley with fields, caves, burn history and trails to test real predator
losses/raids, colony clearing beside forest recovery, common saves, thirty-day
absence with arriving chunks and ordinary-day/owner-advance parity.
Both suites are registered under `ecology` in the ordinary runner.

`java -cp 'out;libs/*' tools/EcologyHudPreview.java` renders the actual ecology
panel at three sizes from a restored diagnostic fixture and verifies that reads
leave saved state unchanged. It is a GL layout preview; real Game input and
network stories are separate gates. See the
[combined validation plan](changes/living-world-stories/test-plan.md) and
[recorded evidence](changes/living-world-stories/validation.md).

`LivingWorldGameSmoke` exercises actual Game input and combined saves, including
a departure that really unloads the valley and return after thirty days. The
optional `run-net-test.ps1 -Stories` and `run-server-test.ps1 -Stories` require
matching current classes and both peers' success markers. They prove ordinary
nest/silk/trail/crop replication alongside finite forest recovery. Dedicated
acceptance also reads the actual combined save before and after `/stop`.
Commands and the distinction from complete native nest chronology are in [TOOLS](TOOLS.md).

## Continuous integration


[CI](https://github.com/ikanit1/mineclone/actions/workflows/ci.yml) builds and runs
headless tests plus runner CLI checks for pushes/PRs targeting master/develop-1.1.
It uses Temurin 17 on windows-latest, caches exact library versions and always
uploads the report. The job timeout is six minutes.

The nightly/manual workflow installs checksum-pinned Mesa software OpenGL and a
null OpenAL backend, then runs real LAN processes. Its mandatory save audit checks
the six-world SAVE-08 corpus in `src/test/resources/fixtures/saves`; a missing or
incomplete corpus fails the job. No Photon job is scheduled.
Local passing commands do not establish a green remote Actions run; verify the
PR checks separately before marking remote CI acceptance complete.

## 30-task survival/farm batch (2 October 2026)

`ArmorTests`, `BuildingBlockTests`, `FarmingTests`, `VegetationTests`,
`SurvivalRulesTests` and `EntityLifecycleTests` extend the same TestMain runner.
Network inventory tests include host equipment gestures, farming, shared breeding,
rule updates, death conservation, effects/reconnect and combat intent abuse.
ContainerBreakNetworkTests uses actual TCP for explosion contents and guest events.

Linux compilation with a JDK and already fetched libs: `bash tools/linux/run-tests.sh`.
Set `JAVA_HOME` when the compiler is not on PATH, `CLASSES` to choose the output.
Farm native scenarios: Windows `run-net-test.ps1 -Farm` / `run-server-test.ps1 -Farm`;
Linux `NET_SCENARIO=farm CLASSES=<current build> bash tools/linux/run-net-test.sh`
and the corresponding server script. Both create disposable worlds; they also
exercise seven forms, real ladder input, and armor appearance on another player.

GL: RenderShaderPreview (17 frames), RenderHudPreview (42), RenderSurvivalPreview
(78 plus four depth comparisons). BenchUi checks one survival-layer draw call;
BenchArmor reports isolated CPU submission and identifies software vs hardware.
[Results and open hardware gates](BATCH_30_PROGRESS.md).

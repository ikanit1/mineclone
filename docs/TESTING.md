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
.\gradlew.bat serverZip
.\tools\TestServerPackage.ps1
javac --release 17 -encoding UTF-8 -d out-test -cp 'out-test;libs/*' tools/ContainerBreakSmoke.java
java '-Dmineclone.checkThreadOwnership=true' -cp 'out-test;libs/*' com.mineclone.ContainerBreakSmoke
```

LAN evidence lives under `out-test/net`; package smoke extracts into a fresh
`out-test/server-package-*` directory, checks TCP readiness, sends `/stop`, verifies
exit 0 and saved `level.dat`, and retains stdout/stderr. No user world is touched.
Native animation, audio, menus and performance need their actual render/input path;
see [TOOLS](TOOLS.md), [rendering reviews](rendering/) and [architecture](architecture/).

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

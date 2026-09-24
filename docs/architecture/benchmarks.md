# Scene benchmarks

`game.BenchDirector` drives the actual `Game` lifecycle, loader, simulation,
renderer and save manager through a narrow package-local adapter. It is enabled
only by `mineclone.bench=<scene>`. `tools/BenchScenes.java` starts independent JVMs
from an immutable source/dependency/asset snapshot and aggregates their reports.
See [run commands and provenance](../perf/README.md).

The director creates a disposable creative world, prepares the requested
workload, waits for generation/meshing/lighting queues to drain, captures the
scene image, then warms up before measurement. PNG encoding finishes before the
warmup clock begins. World creation and explicit post-measurement GC are outside
the measured interval. Flight continues from warmup into measurement without a
teleport back to its origin.

The eleven scenes cover flat terrain, forest, ocean, torch-lit cave, radius-16
flight, 100 mobs, 400 items, storm, a host with three real loopback guests,
generation while flying at 30 blocks/s, and recurring saves of 300 dirty chunks.
Only the flat scene changes generation; normal gameplay does not use that flag.
Population and mesh counts are checked in each report so an empty world cannot
produce a passing performance result.

`BenchMetrics` owns percentile/median calculations and JSON serialization.
Frame intervals include swapping and observation; CPU phase times measure work.
GPU queries arrive asynchronously and identify their source frame. Reports count
completed, observed, unobserved and dropped GPU samples explicitly. The latest
GPU sample must not be attributed to the current CPU frame. Chunk latency includes
queue wait, network statistics count application payload fanout, and allocation
statistics cover the main thread. Save capture, completion latency and gzip I/O
are separate measurements.

Unified JVM GC logs provide completed stop-the-world pause durations whose end
uptime falls inside the measured window. The raw log and its SHA-256 are retained;
concurrent collection phases and the post-measurement explicit GC are excluded.
Collector aggregate time remains a separate value. Launcher parsing and
aggregation checks run in CI alongside the headless game suites.

The runner retains all repetitions and uses numeric medians; absent metrics stay
null. Its five-percent repeatability gate is computed from the spread of mean
FPS, not from a selected fastest run. A short smoke validates execution only.
The manifest identifies the exact source snapshot, hardware and driver; a new
M0 snapshot is not claimed to be an unchanged historical release.

Regression coverage lives in `BenchMetricsTests` and `tools/BenchScenesTest.java`.
The native eleven-scene smoke and full repeated suite remain distinct gates.

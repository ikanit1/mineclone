# Reproducible scene measurements

Build current main/test classes, then run the suite from the repository root
with Java 17 and PowerShell:

```powershell
.\run-tests.ps1
java -cp 'out-test;libs/*' tools/BenchScenes.java
```

The default is three repetitions of all eleven scenes, each with 10 seconds of
warmup and 60 seconds of measurement, a 1920×1080 hidden native window, no vsync
or FPS cap, and a 4 GiB heap. Scenes run sequentially. World setup and loading
finish before warmup; their time is outside the measured window. The child
process has an additional 360-second setup/shutdown allowance and is terminated
if it exceeds that allowance plus the requested warmup and measurement.

The suite first copies source, generated build information, dependency jars
and assets into a new `out-test/bench/runs/<timestamp>/frozen` directory. It
compiles the copied source into private classes. Each child uses those classes
and assets, with disposable saves under the suite's `runtime/out-test/bench`
directory. Existing saves and options are not read. The suite refuses to reuse
an existing output directory. A concurrent source edit during snapshot creation
causes preparation to fail; later working-tree edits cannot change the running
suite.

`manifest.json` records the full Git SHA, dirty status and patch, source/class/
dependency/asset SHA-256 hashes, Java runtime, requested timing/settings, and
Windows CIM CPU, RAM and GPU driver information. Each scene also records the
actual OpenGL renderer/driver, dimensions, settings, seed and workload counts.
`assets/atlas.png` is a generated write-only debug dump and can be rewritten
inside the frozen asset copy when the game starts.

Every child leaves a JSON report, complete log, launch argument array and a
warmup screenshot. `summary.json` links raw reports with SHA-256 hashes and
contains medians of numeric metrics. Missing metrics remain null. A missing
report, child failure, timeout, workload validation failure or unstable repeat
fails the suite; valid reports remain available for diagnosis.

The repeatability gate uses `(maximum mean FPS − minimum mean FPS) / median mean
FPS ≤ 5%`. One repetition does not establish repeatability. `baseline_eligible`
requires all eleven scenes, at least three successful repetitions, full 10/60
second timing and the repeatability gate. An unstable result is preserved as a
failure, rather than silently selecting the fastest runs.

For a short smoke or preparation-only compile:

```powershell
java -cp 'out-test;libs/*' tools/BenchScenes.java --scene flat-idle --repeat 1 --warmup 1 --seconds 2
java -cp 'out-test;libs/*' tools/BenchScenes.java --prepare-only
```

Other options are `--scene` (comma-separated names), `--repeat`, `--warmup`,
`--seconds`, `--width`, `--height`, `--heap`, `--root` and `--output`. A short smoke
proves execution and JSON production, not normal-duration performance.
The [tool catalog](../TOOLS.md) also gives the compile/run command for
`BenchScenesTest`, which checks aggregation without launching any scenes.
For a main-only build, `.\run.ps1 -CompileOnly` produces `out`; substitute that
directory for `out-test` in the launcher classpath. Avoid other GL/CPU workloads
during the full suite.

Metric limits are explicit in the raw reports: GPU samples arrive asynchronously,
and completed/observed/unobserved counters expose samples skipped while the ring
catches up;
allocation rate covers the main thread. The launcher enables a per-child unified
GC log, preserves it with its SHA-256, and adds `gc_pauses_ms` distributions from
completed stop-the-world `Pause` events whose finish uptime falls inside the
Director's measured JVM uptime bounds. A 1 ms end tolerance accounts for log
decorator rounding. Each included event contributes its complete duration;
concurrent GC phases and the post-measurement explicit GC are excluded. The
separate `gc` object retains the JVM collector aggregate. Network counters describe application payload
fanout through three real in-process peers; save capture and completion latency
are measured separately from gzip I/O. The end-of-window explicit GC is outside
the measurement and reports whether the VM honored it.

No historical release baseline is inferred from a filename or version string.
The new benchmark measures the recorded current working-tree snapshot, which
includes M0 changes. A historical 1.0.1 comparison would require running an
equivalent harness against that authenticated code. Publish the actual suite
identity, full artifacts and this limitation with any baseline table.

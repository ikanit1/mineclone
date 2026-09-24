import java.util.*;

/** Headless checks for suite aggregation; no game process or GL context is started. */
public final class BenchScenesTest {
    public static void main(String[] args) {
        Object median = BenchScenes.medianTree(List.of(Map.of("frames", Map.of("fps", 80, "p99_ms", 20)),
                Map.of("frames", Map.of("fps", 100, "p99_ms", 40)), Map.of("frames", Map.of("fps", 90, "p99_ms", 30))));
        require(((Map<?, ?>)((Map<?, ?>)median).get("frames")).get("fps").equals(90.0), "numeric median");
        Object partial = BenchScenes.medianTree(List.of(Map.of("latency", 3), Map.of()));
        require(((Map<?, ?>)partial).containsKey("latency") && ((Map<?, ?>)partial).get("latency") == null, "missing metric stays null");
        require(BenchScenes.medianTree(List.of()) == null, "empty run set");
        require(BenchScenes.repeatability(List.of(100.0)).get("passed") == null, "one run is not repeatability evidence");
        require(Boolean.TRUE.equals(BenchScenes.repeatability(List.of(97.5, 100.0, 102.5)).get("passed")), "inclusive five percent gate");
        require(Boolean.FALSE.equals(BenchScenes.repeatability(List.of(97.0, 100.0, 103.0)).get("passed")), "unstable run fails");
        boolean rejected = false;
        try { BenchScenes.repeatability(List.of(0.0, 1.0)); } catch (IllegalArgumentException expected) { rejected = true; }
        require(rejected, "invalid FPS rejected");
        Map<String, Object> pauses = BenchScenes.gcPauses(List.of(
                "[0.999s][info][gc] GC(0) Pause Young (Normal) 1M->1M(100M) 999ms",
                "[1.001s][info][gc,start] GC(1) Pause Young (Normal)",
                "[1.010s][info][gc] GC(1) Pause Young (Normal) 10M->1M(100M) 2.000ms",
                "[1.020s][info][gc] GC(2) Concurrent Mark Cycle 900.0ms",
                "[1.050s][info][gc] GC(3) Pause Full (System.gc()) 10M->1M(100M) 0.003s",
                "[1.075s][info][gc    ] GC(4) Pause Remark 500us",
                "[1.080s][info][gc,phases] GC(4) Other: 700ms",
                "[2.001s][info][gc] GC(5) Pause Cleanup 500000ns",
                "[2.010s][info][gc] GC(6) Pause Full (System.gc()) 888ms"), 1000, 2000);
        require(((Number)pauses.get("samples")).intValue() == 4, "only completed in-window pauses including rounded end");
        require(pauses.get("total_ms").equals(6.0), "pause unit conversion and concurrent exclusion");
        require(pauses.get("max").equals(3.0), "pause maximum excludes post-measurement explicit GC");
        require(((Number)BenchScenes.gcPauses(List.of(), 0, 1).get("samples")).intValue() == 0, "empty GC window");
        rejected = false;
        try { BenchScenes.gcPauses(List.of(), 2, 1); } catch (IllegalArgumentException expected) { rejected = true; }
        require(rejected, "invalid GC window rejected");
        System.out.println("BENCH_SCENES_TEST PASS checks=12");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

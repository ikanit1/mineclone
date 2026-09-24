package com.mineclone;

import com.mineclone.data.Json;
import com.mineclone.game.BenchDirector;
import com.mineclone.game.BenchMetrics;
import com.mineclone.net.NetStats;
import com.mineclone.world.*;
import java.util.*;

/** Fixed analytical expectations: regressions cannot redefine their own benchmark baseline. */
public final class BenchMetricsTests {
    public static void runAll(TestMain.Runner r) {
        r.run("benchmark percentiles use nearest rank without mutating inputs", BenchMetricsTests::percentiles);
        r.run("benchmark FPS and slowest one percent use elapsed time", BenchMetricsTests::fps);
        r.run("benchmark JSON preserves nested values and rejects NaN", BenchMetricsTests::json);
        r.run("benchmark empty measurements differ from measured zero", BenchMetricsTests::empty);
        r.run("benchmark catalogue and biome selection are deterministic", BenchMetricsTests::scenes);
        r.run("benchmark flat terrain is explicit and isolated", BenchMetricsTests::flat);
        r.run("benchmark telemetry preserves concurrent samples and payload fanout", BenchMetricsTests::telemetry);
    }
    public static void main(String[] args) throws Exception {
        percentiles(); fps(); json(); empty(); scenes(); flat(); telemetry();
        System.out.println("BENCH_METRICS PASS: 7 groups");
    }
    private static void percentiles() {
        double[] values = {100, 1, 3, 2};
        near(BenchMetrics.percentile(values, 0), 1);
        near(BenchMetrics.percentile(values, .5), 2);
        near(BenchMetrics.percentile(values, .95), 100);
        near(BenchMetrics.percentile(values, 1), 100);
        check(Arrays.equals(values, new double[]{100, 1, 3, 2}), "input mutated");
        near(BenchMetrics.median(values), 2.5);
        near(BenchMetrics.median(new double[]{5, 1, 3}), 3);
        invalid(() -> BenchMetrics.percentile(values, -1));
        invalid(() -> BenchMetrics.percentile(values, Double.NaN));
        invalid(() -> BenchMetrics.percentile(new double[]{1, Double.NaN}, .5));
        invalid(() -> BenchMetrics.median(new double[0]));
    }
    private static void fps() {
        double[] values = new double[200]; Arrays.fill(values, 10); values[198] = 30; values[199] = 70;
        Map<String, Object> result = BenchMetrics.summarize(values);
        near(number(result, "mean_ms"), 10.4); near(number(result, "fps"), 200000.0 / 2080);
        near(number(result, "low_1pct_fps"), 20); near(number(result, "p99_ms"), 10);
        near(number(result, "worst_ms"), 70);
        invalid(() -> BenchMetrics.summarize(new double[]{0}));
        invalid(() -> BenchMetrics.summarize(new double[]{Double.POSITIVE_INFINITY}));
    }
    private static void json() {
        String text = "Русский \"quote\" \\ path\n\r\t\b\f" + (char) 1;
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("text", text); input.put("nothing", null); input.put("array", new Object[]{true, 2.5, List.of("x")});
        String encoded = BenchMetrics.json(input);
        Map<?, ?> result = (Map<?, ?>) Json.parse(encoded, "benchmark-test");
        check(text.equals(result.get("text")), "escape/unicode roundtrip");
        check(result.containsKey("nothing") && result.get("nothing") == null, "null changed");
        check(((List<?>) result.get("array")).size() == 3, "nested array changed");
        check(!encoded.contains("\n"), "raw newline not escaped");
        invalid(() -> BenchMetrics.json(Map.of("bad", Double.NaN)));
        invalid(() -> BenchMetrics.json(Map.of(1, "bad key")));
    }
    private static void empty() {
        var empty = BenchMetrics.summarize(new double[0]);
        check(empty.get("fps") == null && number(empty, "samples") == 0, "empty benchmark turned green zero");
        var zero = BenchMetrics.distribution(new double[]{0, 0, 0});
        near(number(zero, "mean"), 0); near(number(zero, "p99"), 0);
        check(BenchMetrics.distribution(new double[0]).get("p99") == null, "empty latency reported zero");
    }
    private static void scenes() {
        check(BenchDirector.SCENES.size() == 11 && new HashSet<>(BenchDirector.SCENES).size() == 11, "scene catalogue");
        check(BenchDirector.radius("forest-r10") == 10 && BenchDirector.radius("radius16-flight") == 16, "radius mismatch");
        invalid(() -> BenchDirector.requireScene("typo"));
        for (Biome biome : List.of(Biome.FOREST, Biome.OCEAN, Biome.PLAINS)) {
            int[] first = BenchDirector.findBiome(new BiomeProvider(20260924), biome);
            int[] second = BenchDirector.findBiome(new BiomeProvider(20260924), biome);
            check(Arrays.equals(first, second), "biome position nondeterministic");
            check(new BiomeProvider(20260924).biomeAt(first[0], first[1]) == biome, "wrong benchmark biome");
        }
    }
    private static void flat() {
        World flat = new World(42, GenProfile.FLAT), ordinary = new World(42, GenProfile.NORMAL);
        Chunk first = flat.getChunk(-2, 3), second = flat.getChunk(7, -4);
        for (Chunk chunk : List.of(first, second)) for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            check(chunk.get(x, 0, z) == BlockType.BEDROCK, "flat bedrock");
            check(chunk.get(x, 60, z) == BlockType.STONE, "flat stone");
            check(chunk.get(x, 63, z) == BlockType.DIRT, "flat dirt");
            check(chunk.get(x, 64, z) == BlockType.GRASS, "flat grass");
            check(chunk.get(x, 65, z) == BlockType.AIR, "flat vegetation");
        }
        boolean differs = false; Chunk normal = ordinary.getChunk(-2, 3);
        for (int y = 1; y < 128; y++) if (normal.get(0, y, 0) != first.get(0, y, 0)) differs = true;
        check(differs, "normal generation unexpectedly flat");
    }
    private static void telemetry() throws InterruptedException {
        ChunkPipelineMetrics metrics = new ChunkPipelineMetrics();
        Thread[] workers = new Thread[4];
        for (int n = 0; n < workers.length; n++) {
            workers[n] = new Thread(() -> { for (int i = 0; i < 1000; i++) { metrics.generation(2_000_000); metrics.mesh(3_000_000); } });
            workers[n].start();
        }
        for (Thread worker : workers) worker.join();
        var result = metrics.snapshot();
        check(result.generationMillis().length == 4000 && result.meshMillis().length == 4000, "lost telemetry samples");
        near(BenchMetrics.percentile(result.generationMillis(), .95), 2);
        near(BenchMetrics.percentile(result.meshMillis(), .95), 3);
        result.generationMillis()[0] = 999; near(metrics.snapshot().generationMillis()[0], 2);
        metrics.reset(); check(metrics.snapshot().meshMillis().length == 0, "reset retained old samples");
        NetStats net = new NetStats(); net.sent(100, 3); net.received(17);
        var traffic = net.snapshot(); check(traffic.sentBytes() == 300 && traffic.sentPackets() == 3 && traffic.receivedBytes() == 17, "payload fanout");
    }
    private static double number(Map<String, Object> values, String key) { return ((Number)values.get(key)).doubleValue(); }
    private static void near(double actual, double expected) { check(Math.abs(actual - expected) < 1e-8, actual + " != " + expected); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void invalid(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("invalid benchmark input accepted");
    }
}

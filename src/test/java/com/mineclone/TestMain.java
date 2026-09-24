package com.mineclone;

import com.mineclone.core.BuildInfo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Zero-dependency headless runner. Registration is separate from execution for --list. */
public final class TestMain {
    @FunctionalInterface public interface Check { void run() throws Exception; }
    @FunctionalInterface public interface Runner { void run(String name, Check check); }
    @FunctionalInterface public interface Suite { void runAll(Runner runner); }
    private record Test(String category, String suite, String name, Check check) {}
    private record Result(Test test, long nanos, Throwable failure) {}
    private static final List<Test> tests = new ArrayList<>();
    private static String registeringSuite;

    private TestMain() {}

    /** Register a categorized check; execution happens only after filters have been validated. */
    public static void run(String category, String name, Check check) {
        tests.add(new Test(category, registeringSuite == null ? category : registeringSuite, name, check));
    }

    private static void suite(String category, String name, Suite suite) {
        registeringSuite = name;
        try { suite.runAll((testName, check) -> run(category, testName, check)); }
        finally { registeringSuite = null; }
    }

    private static void register() {
        tests.clear();
        suite("creative", "CreativeModeTests", r -> CreativeModeTests.runAll((n, c) -> r.run(n, c::run)));
        suite("audio", "RainAudioTests", r -> RainAudioTests.runAll((n, c) -> r.run(n, c::run)));
        suite("audio", "RoomAcousticsTests", r -> RoomAcousticsTests.runAll((n, c) -> r.run(n, c::run)));
        suite("weather", "LightningTests", r -> LightningTests.runAll((n, c) -> r.run(n, c::run)));
        suite("weather", "StormTests", r -> StormTests.runAll((n, c) -> r.run(n, c::run)));
        suite("combat", "CombatTests", r -> CombatTests.runAll((n, c) -> r.run(n, c::run)));
        suite("combat", "ProjectileTests", r -> ProjectileTests.runAll((n, c) -> r.run(n, c::run)));
        suite("combat", "ExplosionTests", r -> ExplosionTests.runAll((n, c) -> r.run(n, c::run)));
        suite("combat", "DamageTests", DamageTests::runAll);
        suite("physics", "PlayerPhysicsTests", r -> com.mineclone.game.PlayerPhysicsTests.runAll((n, c) -> r.run(n, c::run)));
        suite("physics", "ShapeTests", ShapeTests::runAll);
        suite("physics", "CollisionParityTests", r -> com.mineclone.game.CollisionParityTests.runAll((n, c) -> r.run(n, c::run)));
        suite("physics", "RaycastShapeTests", RaycastShapeTests::runAll);
        suite("core", "BehaviorTests", BehaviorTests::runAll);
        suite("animation", "PlayerAnimationTests", r -> PlayerAnimationTests.runAll((n, c) -> r.run(n, c::run)));
        suite("animation", "PlayerHandednessTests", r -> PlayerHandednessTests.runAll((n, c) -> r.run(n, c::run)));
        suite("animation", "EquipmentTextureTests", r -> EquipmentTextureTests.runAll((n, c) -> r.run(n, c::run)));
        suite("animation", "PlayerMotionTests", r -> PlayerMotionTests.runAll((n, c) -> r.run(n, c::run)));
        suite("weather", "StormWeatherTests", r -> com.mineclone.game.StormWeatherTests.runAll((n, c) -> r.run(n, c::run)));
        suite("animation", "MobAnimationTests", r -> MobAnimationTests.runAll((n, c) -> r.run(n, c::run)));
        suite("optimization", "OptimizationTests", r -> r.run("optimization invariants", OptimizationTests::run));
        suite("worldgen", "WorldGenerationTests", r -> r.run("biome assets, sparse structures, seams and falling-block conservation", WorldGenerationTests::run));
        suite("worldgen", "WorldGenGoldenTests", WorldGenGoldenTests::runAll);
        suite("worldgen", "ChunkLedgerTests", ChunkLedgerTests::runAll);
        suite("core", "CoreTests", CoreTests::runAll);
        suite("core", "BlockOrdinalTests", BlockOrdinalTests::runAll);
        suite("data", "RecipeDataTests", RecipeDataTests::runAll);
        suite("data", "LootTableTests", LootTableTests::runAll);
        suite("data", "ReachabilityTests", ReachabilityTests::runAll);
        suite("save", "CoreTests/save", CoreTests::runSaveTests);
        suite("save", "SaveSafetyTests", SaveSafetyTests::runAll);
        suite("save", "DedicatedServerSaveTests", DedicatedServerSaveTests::runAll);
        suite("save", "WorldBackupTests", WorldBackupTests::runAll);
        suite("save", "LevelFormatTests", LevelFormatTests::runAll);
        suite("save", "SaveMigrationTests", SaveMigrationTests::runAll);
        suite("save", "ChunkSectionTests", ChunkSectionTests::runAll);
        suite("save", "QueuedChunkSaveTests", QueuedChunkSaveTests::runAll);
        suite("save", "PlayerRecordTests", PlayerRecordTests::runAll);
        suite("chunk", "ChunkPublicationTests", ChunkPublicationTests::runAll);
        suite("clock", "WorldClockTests", WorldClockTests::runAll);
        suite("sim", "ParticipantTests", ParticipantTests::runAll);
        suite("sim", "SessionParityTests", SessionParityTests::runAll);
        suite("sim", "ServerSimulationTests", ServerSimulationTests::runAll);
        suite("sim", "TargetSelectionTests", TargetSelectionTests::runAll);
        suite("sim", "WorldAroundEveryoneTests", WorldAroundEveryoneTests::runAll);
        suite("render", "GpuTimersRingTests", GpuTimersRingTests::runAll);
        suite("perf", "BenchMetricsTests", BenchMetricsTests::runAll);
        suite("feature", "FeatureTests", r -> FeatureTests.runAll((n, c) -> r.run(n, c::run)));
        suite("menu", "MenuTests", r -> MenuTests.runAll((n, c) -> r.run(n, c::run)));
        suite("audio", "MusicTests", r -> MusicTests.runAll((n, c) -> r.run(n, c::run)));
        suite("inventory", "InventoryTests", r -> InventoryTests.runAll((n, c) -> r.run(n, c::run)));
        suite("net", "NetworkTests", r -> NetworkTests.runAll((n, c) -> r.run(n, c::run)));
        suite("net", "ServerConfigTests", ServerConfigTests::runAll);
        suite("net", "NetStatsTests", NetStatsTests::runAll);
        suite("net", "ProtocolTests", ProtocolTests::runAll);
        suite("net", "InventoryNetworkTests", r -> InventoryNetworkTests.runAll((n, c) -> r.run(n, c::run)));
        suite("net", "ContainerBreakNetworkTests", r -> ContainerBreakNetworkTests.runAll((n, c) -> r.run(n, c::run)));
    }

    public static void main(String[] args) {
        int exitCode;
        try { exitCode = execute(args); }
        catch (IllegalArgumentException e) {
            String message = "TEST RUNNER ERROR: " + e.getMessage();
            System.err.println(message);
            System.err.println("Usage: TestMain [--only core,save] [--skip net] [--list]");
            writeReport(message + "\n");
            exitCode = 2;
        }
        if (exitCode != 0) System.exit(exitCode);
    }

    private static int execute(String[] args) {
        register();
        Set<String> only = new LinkedHashSet<>();
        Set<String> skip = new LinkedHashSet<>();
        boolean list = false;
        boolean explicitOnly = Arrays.stream(args).anyMatch(a -> a.equals("--only") || a.startsWith("--only="));
        if (!explicitOnly) addFilter(only, System.getenv("MINECLONE_TEST_ONLY"));
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--list")) { list = true; continue; }
            int equals = arg.indexOf('=');
            String flag = equals < 0 ? arg : arg.substring(0, equals);
            if (!flag.equals("--only") && !flag.equals("--skip"))
                throw new IllegalArgumentException("unknown argument " + arg);
            String value;
            if (equals >= 0) value = arg.substring(equals + 1);
            else {
                if (++i >= args.length || args[i].startsWith("--"))
                    throw new IllegalArgumentException("missing value for " + flag);
                value = args[i];
            }
            if (value.isBlank()) throw new IllegalArgumentException("empty value for " + flag);
            addFilter(flag.equals("--only") ? only : skip, value);
        }
        Set<String> categories = new LinkedHashSet<>();
        for (Test test : tests) categories.add(test.category());
        for (Set<String> filter : List.of(only, skip)) {
            for (String category : filter) {
                if (!categories.contains(category))
                    throw new IllegalArgumentException("unknown category '" + category + "'; available: " + String.join(",", categories));
            }
        }
        List<Test> selected = tests.stream()
                .filter(t -> (only.isEmpty() || only.contains(t.category())) && !skip.contains(t.category())).toList();
        if (selected.isEmpty()) throw new IllegalArgumentException("filters selected zero tests");
        if (list) {
            for (Test test : selected) System.out.println(test.category() + " / " + test.suite() + " / " + test.name());
            System.out.println("Listed " + selected.size() + " of " + tests.size() + " tests (none executed)");
            return 0;
        }
        List<Result> results = new ArrayList<>();
        StringBuilder report = new StringBuilder(BuildInfo.summary()).append('\n');
        long start = System.nanoTime();
        int failed = 0;
        for (Test test : selected) {
            long testStart = System.nanoTime();
            Throwable failure = null;
            try { test.check().run(); }
            catch (Throwable t) { failure = t; failed++; }
            Result result = new Result(test, System.nanoTime() - testStart, failure);
            results.add(result);
            line(report, String.format(Locale.ROOT, "[%s] [%s] %s (%.3f ms)%s",
                    failure == null ? "PASS" : "FAIL", test.category(), test.name(), result.nanos() / 1e6,
                    failure == null ? "" : " -> " + failure));
            if (failure != null) {
                java.io.StringWriter stack = new java.io.StringWriter();
                failure.printStackTrace(new java.io.PrintWriter(stack));
                report.append(stack);
            }
        }
        long elapsed = System.nanoTime() - start;
        line(report, "\nSuite timings:");
        Map<String, long[]> timings = new LinkedHashMap<>();
        for (Result result : results) {
            String key = result.test().category() + " / " + result.test().suite();
            long[] entry = timings.computeIfAbsent(key, k -> new long[2]);
            entry[0]++; entry[1] += result.nanos();
        }
        timings.forEach((suite, time) -> line(report, String.format(Locale.ROOT,
                "  %s: %d tests, %.3f ms", suite, time[0], time[1] / 1e6)));
        line(report, "Slowest 10 tests:");
        results.stream().sorted(Comparator.comparingLong(Result::nanos).reversed()).limit(10)
                .forEach(r -> line(report, String.format(Locale.ROOT, "  %.3f ms [%s] %s",
                        r.nanos() / 1e6, r.test().category(), r.test().name())));
        line(report, String.format(Locale.ROOT, "==== %d passed, %d failed, %d skipped; %.3f s ====",
                results.size() - failed, failed, tests.size() - selected.size(), elapsed / 1e9));
        return writeReport(report.toString()) && failed == 0 ? 0 : 1;
    }

    private static void addFilter(Set<String> target, String value) {
        if (value == null || value.isBlank()) return;
        for (String part : value.split(",", -1)) {
            if (part.isBlank()) throw new IllegalArgumentException("empty category in '" + value + "'");
            target.add(part.trim().toLowerCase(Locale.ROOT));
        }
    }

    private static void line(StringBuilder report, String text) {
        System.out.println(text);
        report.append(text).append('\n');
    }

    private static boolean writeReport(String report) {
        Path path = Path.of(System.getProperty("mineclone.testReport", "out-test/test-report.txt"));
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Files.writeString(path, report, StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            System.err.println("Cannot write test report " + path + ": " + e);
            return false;
        }
    }
}

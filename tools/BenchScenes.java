import com.mineclone.data.Json;
import com.mineclone.game.BenchDirector;
import com.mineclone.game.BenchMetrics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Frozen-source benchmark suite. Run from the repository root after building:
 * java -cp "out;libs/*" tools/BenchScenes.java
 * Defaults to all eleven scenes, three repetitions, 10 s warmup and 60 s measured.
 * --scene flat-idle --repeat 1 --warmup 1 --seconds 2 is a smoke, not a baseline.
 */
public final class BenchScenes {
    private static final List<String> METRICS = List.of("frames", "cpu_work_ms", "cpu_mobs_ms", "cpu_phases_ms",
            "gpu_phases_ms", "gpu_total_ms", "gpu_dropped_frames", "gpu_completed_samples", "gpu_observed_samples",
            "gpu_unobserved_samples", "queues", "generation_latency_ms", "mesh_latency_ms",
            "pipeline_samples_dropped", "main_thread_alloc_bytes_per_second", "gc", "gc_pauses_ms", "saves", "network",
            "observed", "heap_after_gc_bytes", "measured_seconds");
    private record Options(Path root, Path output, List<String> scenes, int repeats, double warmup,
                           double seconds, int width, int height, String heap, boolean prepareOnly) {}
    private record Run(String scene, int repeat, Path report, Path log, int exitCode, boolean timedOut,
                       Map<String, Object> data, String error) {}

    public static void main(String[] arguments) throws Exception {
        if (Arrays.asList(arguments).contains("--help")) {
            System.out.println("BenchScenes [--scene name[,name...]] [--repeat 3] [--warmup 10] [--seconds 60]"
                    + " [--width 1920] [--height 1080] [--heap 4g] [--output new-directory] [--root repo] [--prepare-only]");
            return;
        }
        Options options = options(arguments);
        Path suite = options.output();
        Files.createDirectories(suite.getParent());
        Files.createDirectory(suite); // Never reuse/overwrite an earlier run or its evidence.
        Map<String, Object> manifest = prepare(options);
        Path manifestPath = suite.resolve("manifest.json");
        writeJson(manifestPath, manifest);
        System.out.println("BENCH_SUITE_PREPARED " + manifestPath);
        if (options.prepareOnly()) return;
        List<Run> runs = new ArrayList<>();
        // Repetition-major order gives every scene time between repeats.
        for (int repeat = 1; repeat <= options.repeats(); repeat++) for (String scene : options.scenes()) {
            Run result = launch(options, scene, repeat, manifestPath);
            runs.add(result);
            System.out.println("BENCH_SUITE_RUN scene=" + scene + " repeat=" + repeat
                    + " exit=" + result.exitCode() + " error=" + result.error());
            writeSummary(options, manifestPath, runs);
        }
        Map<String, Object> report = writeSummary(options, manifestPath, runs);
        System.out.println("BENCH_SUITE_RESULT " + report.get("status") + " " + suite.resolve("summary.json"));
        if (!"PASS".equals(report.get("status"))) System.exit(1);
    }

    private static Options options(String[] args) {
        Path root = Path.of("").toAbsolutePath().normalize(), output = null;
        List<String> scenes = BenchDirector.SCENES;
        int repeats = 3, width = 1920, height = 1080;
        double warmup = 10, seconds = 60;
        String heap = "4g"; boolean prepareOnly = false;
        for (int i = 0; i < args.length; i++) {
            String flag = args[i];
            if (flag.equals("--prepare-only")) { prepareOnly = true; continue; }
            if (++i >= args.length) throw new IllegalArgumentException("Missing value for " + flag);
            String value = args[i];
            switch (flag) {
                case "--root" -> root = Path.of(value).toAbsolutePath().normalize();
                case "--output" -> output = Path.of(value).toAbsolutePath().normalize();
                case "--scene" -> {
                    scenes = new ArrayList<>(new LinkedHashSet<>(Arrays.asList(value.split(",", -1))));
                    scenes.forEach(BenchDirector::requireScene);
                }
                case "--repeat" -> repeats = Integer.parseInt(value);
                case "--width" -> width = Integer.parseInt(value);
                case "--height" -> height = Integer.parseInt(value);
                case "--warmup" -> warmup = Double.parseDouble(value);
                case "--seconds" -> seconds = Double.parseDouble(value);
                case "--heap" -> heap = value;
                default -> throw new IllegalArgumentException("Unknown option: " + flag);
            }
        }
        if (repeats < 1 || repeats > 100 || width < 320 || height < 200 || !Double.isFinite(warmup)
                || !Double.isFinite(seconds) || warmup <= 0 || seconds <= 0 || warmup > 3600 || seconds > 3600
                || !heap.matches("[1-9][0-9]*[mMgG]")) throw new IllegalArgumentException("Invalid benchmark options");
        if (output == null) output = root.resolve("out-test/bench/runs/" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
                .withZone(java.time.ZoneOffset.UTC).format(Instant.now()));
        if (!Files.isRegularFile(root.resolve("gradle.properties")) || !Files.isDirectory(root.resolve("src/main/java")))
            throw new IllegalArgumentException("Not a Mineclone checkout: " + root);
        return new Options(root, output, List.copyOf(scenes), repeats, warmup, seconds, width, height, heap, prepareOnly);
    }

    private static Map<String, Object> prepare(Options options) throws Exception {
        Path root = options.root(), suite = options.output(), frozen = suite.resolve("frozen");
        Files.createDirectories(frozen);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schema", 1); manifest.put("created_utc", Instant.now().toString());
        manifest.put("checkout", root.toString()); manifest.put("frozen_root", frozen.toString());
        String sha = command(root, List.of("git", "rev-parse", "HEAD"), 10).strip();
        String status = command(root, List.of("git", "status", "--porcelain=v1", "--untracked-files=all"), 10);
        manifest.put("git_sha", sha); manifest.put("git_dirty", !status.isBlank()); manifest.put("git_status", status);
        manifest.put("historical_release_baseline", false);
        manifest.put("provenance_note", "Measures this frozen working tree, including uncommitted changes;"
                + " a matching release name does not establish historical release performance.");
        Files.writeString(suite.resolve("working-tree.patch"), command(root,
                List.of("git", "diff", "--binary", "HEAD", "--", "."), 30), StandardCharsets.UTF_8);
        copyFile(root.resolve("gradle.properties"), frozen.resolve("gradle.properties"));
        copyFile(root.resolve("tools/BenchScenes.java"), frozen.resolve("tools/BenchScenes.java"));
        Map<String, String> sources = snapshotTree(root.resolve("src/main"), frozen.resolve("src/main"));
        Map<String, String> assets = snapshotTree(root.resolve("assets"), frozen.resolve("assets"));
        Map<String, String> jars = snapshotJars(root.resolve("libs"), frozen.resolve("libs"));
        manifest.put("source_sha256", sources); manifest.put("source_tree_sha256", treeHash(sources));
        manifest.put("asset_sha256", assets); manifest.put("asset_tree_sha256", treeHash(assets));
        manifest.put("dependency_sha256", jars); manifest.put("dependency_tree_sha256", treeHash(jars));
        manifest.put("launcher_sha256", sha256(frozen.resolve("tools/BenchScenes.java")));
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(frozen.resolve("gradle.properties"))) { properties.load(reader); }
        String version = properties.getProperty("version");
        if (version == null || !version.matches("[0-9A-Za-z.+_-]+") || !sha.matches("[a-f0-9]{40}"))
            throw new IOException("Invalid version or git provenance");
        manifest.put("version", version);
        Path generated = frozen.resolve("generated/com/mineclone/core/BuildInfo.java");
        Files.createDirectories(generated.getParent());
        Files.writeString(generated, Files.readString(frozen.resolve("src/main/java/com/mineclone/core/BuildInfo.java.template"))
                .replace("@VERSION@", version).replace("@GIT_SHA@", sha.substring(0, 12)));
        Path classes = frozen.resolve("classes"); Files.createDirectories(classes);
        List<String> sourceFiles;
        try (Stream<Path> stream = Files.walk(frozen.resolve("src/main/java"))) {
            sourceFiles = new ArrayList<>(stream.filter(p -> p.toString().endsWith(".java")).sorted()
                    .map(p -> quoteArgfile(p.toString())).toList());
        }
        sourceFiles.add(quoteArgfile(generated.toString()));
        Path sourceList = frozen.resolve("sources.txt"); Files.write(sourceList, sourceFiles);
        List<String> compile = List.of(javaTool("javac"), "--release", "17", "-encoding", "UTF-8", "-d",
                classes.toString(), "-cp", jarClasspath(frozen), "@" + sourceList);
        Process compiler = new ProcessBuilder(compile).directory(root.toFile()).redirectErrorStream(true)
                .redirectOutput(suite.resolve("compile.log").toFile()).start();
        if (!compiler.waitFor(180, TimeUnit.SECONDS)) { compiler.destroyForcibly(); throw new IOException("Frozen compilation timed out"); }
        if (compiler.exitValue() != 0) throw new IOException("Frozen compilation failed; see " + suite.resolve("compile.log"));
        manifest.put("compile_command", compile);
        Map<String, String> classHashes = hashes(classes);
        manifest.put("class_sha256", classHashes); manifest.put("class_tree_sha256", treeHash(classHashes));
        manifest.put("hardware", hardware(root));
        manifest.put("launcher_runtime", Map.of("java", System.getProperty("java.runtime.version"),
                "java_home", System.getProperty("java.home"), "os", System.getProperty("os.name"),
                "arch", System.getProperty("os.arch"), "logical_processors", Runtime.getRuntime().availableProcessors()));
        manifest.put("requested", Map.of("scenes", options.scenes(), "repetitions", options.repeats(),
                "warmup_seconds", options.warmup(), "measurement_seconds", options.seconds(),
                "width", options.width(), "height", options.height(), "max_heap", options.heap()));
        // Detect concurrent edits while the source snapshot was being assembled. Later edits cannot affect it.
        if (!sources.equals(hashes(root.resolve("src/main")))) throw new IOException("Source changed during snapshot; rerun after edits finish");
        if (!sha.equals(command(root, List.of("git", "rev-parse", "HEAD"), 10).strip()))
            throw new IOException("Git HEAD changed during snapshot; rerun after edits finish");
        return manifest;
    }

    private static Run launch(Options options, String scene, int repeat, Path manifest) throws Exception {
        Path suite = options.output(), frozen = suite.resolve("frozen");
        String id = scene + "-" + repeat;
        Path runtime = suite.resolve("runtime"); Files.createDirectories(runtime);
        Path reports = suite.resolve("raw"); Files.createDirectories(reports);
        Path report = reports.resolve(id + ".json"), log = reports.resolve(id + ".log");
        Path gcLog = runtime.resolve("gc-" + id + ".log");
        List<String> args = new ArrayList<>(List.of(javaTool("java"), "-Xmx" + options.heap(),
                "-Xlog:gc*:file=" + gcLog.getFileName() + ":uptime,level,tags:filecount=0",
                "-Dmineclone.appDir=" + frozen, "-Dmineclone.bench=" + scene, "-Dmineclone.bench.runId=" + id,
                "-Dmineclone.bench.output=" + report, "-Dmineclone.bench.manifest=" + manifest,
                "-Dmineclone.bench.warmup=" + options.warmup(), "-Dmineclone.bench.seconds=" + options.seconds(),
                "-Dmineclone.bench.width=" + options.width(), "-Dmineclone.bench.height=" + options.height(),
                "-cp", frozen.resolve("classes") + java.io.File.pathSeparator + jarClasspath(frozen), "com.mineclone.Main"));
        writeJson(reports.resolve(id + ".command.json"), args);
        Process process = new ProcessBuilder(args).directory(runtime.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        boolean finished = process.waitFor((long) Math.ceil(options.warmup() + options.seconds() + 360), TimeUnit.SECONDS);
        if (!finished) {
            process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
        int exit = process.isAlive() ? -1 : process.exitValue();
        Map<String, Object> data = Map.of(); String error = null;
        try {
            data = object(Json.parse(Files.readString(report), report.toString()));
            data.put("gc_pause_log", relative(report.getParent(), gcLog));
            if (Files.isRegularFile(gcLog)) data.put("gc_pause_log_sha256", sha256(gcLog));
            Object start = data.get("measurement_uptime_start_ms"), end = data.get("measurement_uptime_end_ms");
            if (start instanceof Number first && end instanceof Number last) {
                data.put("gc_pauses_ms", gcPauses(Files.readAllLines(gcLog), first.doubleValue(), last.doubleValue()));
                data.put("gc_pause_definition", "Unified JVM [gc] completed Pause events whose finish uptime is inside"
                        + " the measured window (inclusive, 1 ms end tolerance for decorator rounding). Full event duration;"
                        + " concurrent GC phases and the explicit post-measurement GC are excluded. Collector aggregate remains gc.");
            } else if ("PASS".equals(data.get("status"))) throw new IOException("Missing measured JVM uptime bounds for GC pause evidence");
            writeJson(report, data); // Final raw report includes evidence before its suite hash is recorded.
            if (!scene.equals(data.get("scene")) || !id.equals(data.get("run_id"))) error = "Report identity differs from launch";
            else if (!"PASS".equals(data.get("status"))) error = "Scene validation failed: " + data.get("validation_errors") + " " + data.get("error");
            else if (!(nested(data, "frames", "fps") instanceof Number fps) || !Double.isFinite(fps.doubleValue()) || fps.doubleValue() <= 0)
                error = "Missing or invalid frame metrics";
        } catch (Exception failure) { error = "Missing/invalid report: " + failure; }
        if (!finished) error = "Child timed out"; else if (exit != 0) error = "Child exit " + exit + "; " + error;
        return new Run(scene, repeat, report, log, exit, !finished, data, error);
    }

    private static Map<String, Object> writeSummary(Options options, Path manifest, List<Run> runs) throws IOException {
        List<Map<String, Object>> scenes = new ArrayList<>(); boolean allPass = true;
        for (String scene : options.scenes()) {
            List<Run> matching = runs.stream().filter(run -> run.scene().equals(scene)).toList();
            List<Map<String, Object>> valid = matching.stream().filter(run -> run.error() == null).map(Run::data).toList();
            boolean complete = matching.size() == options.repeats(), passed = complete && valid.size() == options.repeats();
            List<Double> fps = valid.stream().map(data -> ((Number) nested(data, "frames", "fps")).doubleValue()).toList();
            Map<String, Object> variation = repeatability(fps);
            if (Boolean.FALSE.equals(variation.get("passed"))) passed = false;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("scene", scene); entry.put("status", !complete ? "INCOMPLETE" : passed ? "PASS" : "FAIL");
            entry.put("repeatability", variation); entry.put("valid_runs", valid.size());
            Map<String, Object> medians = new LinkedHashMap<>();
            for (String metric : METRICS) medians.put(metric, medianTree(valid.stream().map(data -> data.get(metric)).toList()));
            entry.put("median", medians);
            List<Map<String, Object>> references = new ArrayList<>();
            for (Run run : matching) {
                Map<String, Object> reference = new LinkedHashMap<>();
                reference.put("repeat", run.repeat()); reference.put("json", relative(options.output(), run.report()));
                reference.put("sha256", Files.isRegularFile(run.report()) ? sha256(run.report()) : null);
                reference.put("log", relative(options.output(), run.log())); reference.put("exit_code", run.exitCode());
                reference.put("timed_out", run.timedOut()); reference.put("error", run.error()); references.add(reference);
            }
            entry.put("runs", references); scenes.add(entry); allPass &= passed;
        }
        boolean baselineEligible = allPass && options.repeats() >= 3 && options.warmup() >= 10 && options.seconds() >= 60
                && new HashSet<>(options.scenes()).equals(new HashSet<>(BenchDirector.SCENES));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", 1); result.put("kind", "benchmark-suite"); result.put("created_utc", Instant.now().toString());
        result.put("status", allPass ? "PASS" : "FAIL"); result.put("baseline_eligible", baselineEligible);
        result.put("historical_release_baseline", false); result.put("manifest", relative(options.output(), manifest));
        result.put("manifest_sha256", sha256(manifest)); result.put("scenes", scenes);
        result.put("notes", List.of("Medians use only valid reports; a missing or failed repeat still fails the scene.",
                "Repeatability is (maximum mean FPS - minimum mean FPS) / median mean FPS, limit 5%.",
                "A single repeat does not establish repeatability; baseline eligibility also requires all scenes and full timing.",
                "This working-tree snapshot is not an authentic historical 1.0.1 measurement."));
        writeJson(options.output().resolve("summary.json"), result);
        Files.writeString(options.output().resolve("summary.md"), markdown(result), StandardCharsets.UTF_8);
        return result;
    }

    /** Exposed for the small launcher self-test; missing metrics remain missing. */
    public static Object medianTree(List<?> values) {
        if (values.isEmpty() || values.stream().anyMatch(Objects::isNull)) return null;
        if (values.stream().allMatch(Number.class::isInstance)) {
            double[] numbers = values.stream().mapToDouble(value -> ((Number)value).doubleValue()).toArray();
            return BenchMetrics.median(numbers);
        }
        if (values.stream().allMatch(Map.class::isInstance)) {
            Set<String> keys = new TreeSet<>(); values.forEach(value -> keys.addAll(object(value).keySet()));
            Map<String, Object> result = new LinkedHashMap<>();
            for (String key : keys) result.put(key, medianTree(values.stream().map(value -> object(value).get(key)).toList()));
            return result;
        }
        return null;
    }

    public static Map<String, Object> repeatability(List<Double> fps) {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("samples", fps.size()); result.put("limit_percent", 5);
        if (fps.size() < 2) { result.put("spread_percent", null); result.put("passed", null); return result; }
        double median = BenchMetrics.median(fps.stream().mapToDouble(Double::doubleValue).toArray());
        if (median <= 0 || fps.stream().anyMatch(value -> value <= 0 || !Double.isFinite(value)))
            throw new IllegalArgumentException("FPS must be finite and positive");
        double spread = (Collections.max(fps) - Collections.min(fps)) / median * 100;
        result.put("spread_percent", spread); result.put("passed", spread <= 5); return result;
    }

    /** Parse completed STW pause lines only; concurrent collection time is not pause time. */
    public static Map<String, Object> gcPauses(List<String> lines, double startMs, double endMs) {
        if (!Double.isFinite(startMs) || !Double.isFinite(endMs) || startMs < 0 || endMs < startMs)
            throw new IllegalArgumentException("Invalid measured uptime window");
        var pattern = java.util.regex.Pattern.compile("^\\[([0-9]+(?:\\.[0-9]+)?)s\\]\\[info\\]\\[gc\\s*\\]\\s+GC\\([0-9]+\\)\\s+Pause\\b.*?\\s([0-9]+(?:\\.[0-9]+)?)(ms|us|ns|s)\\s*$");
        List<Double> pauses = new ArrayList<>();
        double total = 0;
        for (String line : lines) {
            var match = pattern.matcher(line);
            if (!match.matches()) continue;
            double finishMs = Double.parseDouble(match.group(1)) * 1000;
            if (finishMs < startMs || finishMs > endMs + 1.0) continue;
            double value = Double.parseDouble(match.group(2));
            double millis = value * switch (match.group(3)) { case "s" -> 1000; case "us" -> .001; case "ns" -> .000001; default -> 1; };
            if (!Double.isFinite(millis)) throw new IllegalArgumentException("Invalid GC pause duration");
            pauses.add(millis); total += millis;
        }
        Map<String, Object> result = new LinkedHashMap<>(BenchMetrics.distribution(pauses.stream().mapToDouble(Double::doubleValue).toArray()));
        result.put("total_ms", total);
        return result;
    }

    private static String markdown(Map<String, Object> report) {
        StringBuilder text = new StringBuilder("# Benchmark suite\n\nStatus: **" + report.get("status")
                + "**. Full baseline eligible: **" + report.get("baseline_eligible") + "**.\n\n"
                + "| Scene | Result | Mean FPS median | 1% low | p99 ms | FPS spread |\n|---|---|---:|---:|---:|---:|\n");
        for (Object value : (List<?>) report.get("scenes")) {
            Map<String, Object> scene = object(value), metrics = object(scene.get("median"));
            text.append('|').append(scene.get("scene")).append('|').append(scene.get("status")).append('|')
                    .append(number(nested(metrics, "frames", "fps"))).append('|')
                    .append(number(nested(metrics, "frames", "low_1pct_fps"))).append('|')
                    .append(number(nested(metrics, "frames", "p99_ms"))).append('|')
                    .append(number(object(scene.get("repeatability")).get("spread_percent"))).append("|\n");
        }
        return text.append("\nRaw reports and SHA-256 references are in `summary.json`; frozen code, assets and hardware are in `manifest.json`."
                + " This measures the recorded working tree, not a historical release.\n").toString();
    }

    private static String number(Object value) { return value instanceof Number n ? String.format(Locale.ROOT, "%.2f", n.doubleValue()) : "—"; }
    private static Object nested(Map<String, Object> root, String key, String leaf) { return root.get(key) instanceof Map<?, ?> map ? map.get(leaf) : null; }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("Expected JSON object");
        return (Map<String, Object>) value;
    }
    private static void writeJson(Path path, Object data) throws IOException { Files.writeString(path, BenchMetrics.json(data) + "\n", StandardCharsets.UTF_8); }
    private static String relative(Path root, Path path) { return root.relativize(path).toString().replace('\\', '/'); }
    private static String quoteArgfile(String text) { return "\"" + text.replace('\\', '/').replace("\"", "\\\"") + "\""; }
    private static String jarClasspath(Path frozen) { return frozen.resolve("libs") + java.io.File.separator + "*"; }
    private static String javaTool(String tool) {
        return Path.of(System.getProperty("java.home"), "bin", tool + (System.getProperty("os.name").startsWith("Windows") ? ".exe" : "")).toString();
    }
    private static void copyFile(Path source, Path target) throws IOException {
        if (Files.isSymbolicLink(source)) throw new IOException("Snapshot does not follow symbolic links: " + source);
        Files.createDirectories(target.getParent()); Files.copy(source, target);
    }
    private static Map<String, String> snapshotTree(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.sorted().toList()) {
                if (Files.isSymbolicLink(path)) throw new IOException("Snapshot does not follow symbolic links: " + path);
                if (Files.isRegularFile(path)) copyFile(path, target.resolve(source.relativize(path)));
            }
        }
        Map<String, String> copied = hashes(target);
        if (!copied.equals(hashes(source))) throw new IOException("Inputs changed during snapshot: " + source);
        return copied;
    }
    private static Map<String, String> snapshotJars(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        try (Stream<Path> jars = Files.list(source)) {
            for (Path jar : jars.filter(p -> p.toString().endsWith(".jar")).sorted().toList()) copyFile(jar, target.resolve(jar.getFileName()));
        }
        Map<String, String> result = hashes(target);
        if (result.isEmpty()) throw new IOException("No dependency jars; build the project first");
        return result;
    }
    private static Map<String, String> hashes(Path root) throws IOException {
        Map<String, String> hashes = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) hashes.put(relative(root, path), sha256(path));
        }
        return hashes;
    }
    private static String sha256(Path file) throws IOException {
        MessageDigest digest = digest();
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static String treeHash(Map<String, String> files) {
        MessageDigest digest = digest();
        files.forEach((path, hash) -> digest.update((path + "\0" + hash + "\n").getBytes(StandardCharsets.UTF_8)));
        return HexFormat.of().formatHex(digest.digest());
    }
    private static MessageDigest digest() { try { return MessageDigest.getInstance("SHA-256"); } catch (Exception impossible) { throw new AssertionError(impossible); } }
    private static String command(Path directory, List<String> args, int timeout) throws IOException, InterruptedException {
        Path temporary = Files.createTempFile("mineclone-bench-command-", ".txt");
        try {
            Process process = new ProcessBuilder(args).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(temporary.toFile()).start();
            if (!process.waitFor(timeout, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("Command timed out: " + args.get(0)); }
            String output = Files.readString(temporary, StandardCharsets.UTF_8);
            if (process.exitValue() != 0) throw new IOException("Command failed: " + args + "\n" + output);
            return output;
        } finally { Files.deleteIfExists(temporary); }
    }
    private static Object hardware(Path root) {
        if (!System.getProperty("os.name").startsWith("Windows"))
            return Map.of("status", "unavailable", "reason", "CIM hardware probe is Windows-only; per-run GL/runtime fields remain available");
        try {
            String script = "[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false); "
                    + "@{cpu=@(Get-CimInstance Win32_Processor | Select-Object Name,NumberOfCores,NumberOfLogicalProcessors);"
                    + "memory_bytes=(Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory;"
                    + "gpu=@(Get-CimInstance Win32_VideoController | Select-Object Name,DriverVersion,DriverDate,AdapterRAM)} | ConvertTo-Json -Depth 5 -Compress";
            return Json.parse(command(root, List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script), 20), "CIM");
        } catch (Exception failure) { return Map.of("status", "unavailable", "reason", failure.toString()); }
    }
}

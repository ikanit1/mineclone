package com.mineclone.game;

import com.mineclone.core.BuildInfo;
import com.mineclone.net.Multiplayer;
import com.mineclone.net.NetStats;
import com.mineclone.render.GpuTimers;
import com.mineclone.save.SaveManager;
import com.mineclone.ui.MenuAction;
import com.mineclone.ui.WorldSettings;
import com.mineclone.world.*;
import com.mineclone.world.entity.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.lwjgl.opengl.GL11.*;

/** Reproducible workloads driven through the actual Game update/render/save/network paths. */
public final class BenchDirector implements AutoCloseable {
    public static final List<String> SCENES = List.of("flat-idle", "forest-r10", "radius16-flight",
            "ocean-r10", "cave", "mobs-100", "items-400", "storm-night", "mp-host-3",
            "chunkgen-sprint", "save-heavy");
    private static final FrameProfiler.Phase[] CPU_PHASES = FrameProfiler.Phase.values();
    private static final GpuTimers.Phase[] GPU_PHASES = GpuTimers.Phase.values();
    public static boolean enabled() { return System.getProperty("mineclone.bench") != null; }
    public static int radius(String scene) { requireScene(scene); return scene.equals("radius16-flight") ? 16 : 10; }
    public static void requireScene(String scene) {
        if (!SCENES.contains(scene)) throw new IllegalArgumentException("Unknown benchmark scene: " + scene);
    }
    public static void configureLaunch() {
        String scene = System.getProperty("mineclone.bench"); requireScene(scene);
        String id = System.getProperty("mineclone.bench.runId", scene + "-" + System.currentTimeMillis());
        if (!id.matches("[a-zA-Z0-9._-]+") || id.equals(".") || id.equals(".."))
            throw new IllegalArgumentException("Unsafe benchmark run ID");
        Path saves = Path.of("out-test", "bench", "saves", id, "saves").toAbsolutePath().normalize();
        if (Files.exists(saves)) throw new IllegalArgumentException("Benchmark saves already exist: " + saves);
        System.setProperty("mineclone.bench.runId", id);
        System.setProperty("mineclone.savesDir", saves.toString());
        positiveProperty("mineclone.bench.seconds", 60); positiveProperty("mineclone.bench.warmup", 10);
    }
    private static double positiveProperty(String name, double fallback) {
        double value = Double.parseDouble(System.getProperty(name, Double.toString(fallback)));
        if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    interface Access {
        World world(); ChunkLoader loader(); Player player(); List<Mob> mobs(); List<ItemEntity> items();
        Multiplayer net(); SaveManager save(); void saveWorld(); void time(double radians);
        void weather(Weather.Kind kind); int meshCount(); int width(); int height();
    }
    private enum Stage { CREATE, OPEN, SETTLE, WARMUP, MEASURE, DONE }
    private final Autopilot.Driver driver;
    private final Access access;
    private final String scene = System.getProperty("mineclone.bench");
    private final long seed = Long.getLong("mineclone.bench.seed", 20260924L);
    private final double warmup = positiveProperty("mineclone.bench.warmup", 10);
    private final double duration = positiveProperty("mineclone.bench.seconds", 60);
    private final long created = System.nanoTime();
    private long stageStart = created, measuredStart, previousFrame, allocatedStart, lastGpuFrame = -1;
    private long gcCountStart, gcTimeStart;
    private long measurementUptimeStart, measurementUptimeEnd;
    private Stage stage = Stage.CREATE;
    private final BenchMetrics.Samples frames = new BenchMetrics.Samples();
    private final BenchMetrics.Samples work = new BenchMetrics.Samples();
    private final BenchMetrics.Samples gpuTotal = new BenchMetrics.Samples();
    private final BenchMetrics.Samples mobCpu = new BenchMetrics.Samples();
    private final BenchMetrics.Samples[] cpu = samples(CPU_PHASES.length), gpu = samples(GPU_PHASES.length);
    private final BenchMetrics.Samples[] queues = samples(4);
    private final BenchMetrics.Samples captureSave = new BenchMetrics.Samples(), flushSave = new BenchMetrics.Samples();
    private final List<CompletableFuture<Double>> saveCompletions = new ArrayList<>();
    private final com.sun.management.ThreadMXBean threads;
    private final long mainThread = Thread.currentThread().getId();
    private SaveManager.WriteMetrics writesStart;
    private NetStats.Snapshot netStart;
    private BenchPeers peers;
    private float originX, originY, originZ;
    private double nextSave;
    private int saveCycles, dirtyRevision, mobsMin = Integer.MAX_VALUE, mobsMax, itemsMin = Integer.MAX_VALUE, itemsMax;
    private boolean screenshotCaptured;
    private int meshMin = Integer.MAX_VALUE, meshMax;
    private final Map<String, Object> setup = new LinkedHashMap<>();
    private long gpuDroppedStart, gpuCompletedStart;

    BenchDirector(Autopilot.Driver driver, Access access) {
        requireScene(scene); this.driver = driver; this.access = access;
        var candidate = ManagementFactory.getThreadMXBean();
        threads = candidate instanceof com.sun.management.ThreadMXBean b && b.isThreadAllocatedMemorySupported() ? b : null;
        if (threads != null && !threads.isThreadAllocatedMemoryEnabled()) threads.setThreadAllocatedMemoryEnabled(true);
    }
    private static BenchMetrics.Samples[] samples(int size) {
        BenchMetrics.Samples[] result = new BenchMetrics.Samples[size];
        Arrays.setAll(result, i -> new BenchMetrics.Samples()); return result;
    }
    private void transition(Stage next) { stage = next; stageStart = System.nanoTime(); }
    void mobWork(long nanos) { if (stage == Stage.MEASURE) mobCpu.add(nanos / 1e6); }
    void update(float dt) {
        if (stage == Stage.DONE) return;
        try {
            long now = System.nanoTime();
            if (stage.ordinal() < Stage.WARMUP.ordinal() && (now - created) / 1e9 > 240)
                throw new IllegalStateException("Scene preparation timed out at " + stage);
            if (stage == Stage.CREATE && driver.state().equals("MENU")) {
                driver.act(MenuAction.create(new WorldSettings("Bench-" + scene, seed, GameMode.CREATIVE)));
                transition(Stage.OPEN);
            } else if (stage == Stage.OPEN && driver.state().equals("PLAYING")) {
                prepare(); transition(Stage.SETTLE);
            } else if (stage == Stage.SETTLE) {
                if (peers != null) peers.update(dt, 0);
                var loader = access.loader();
                if ((now - stageStart) / 1e9 > 2 && loader.pendingGenCount() == 0
                        && loader.pendingMeshCount() == 0 && loader.pendingLightCount() == 0
                        && loader.readyMeshCount() == 0 && (peers == null || peers.ready())) {
                    access.save().flushAndAwait(); driver.shot(scene); transition(Stage.WARMUP);
                    System.out.println("BENCH_WARMUP scene=" + scene + " seconds=" + warmup);
                }
            } else if (stage == Stage.WARMUP || stage == Stage.MEASURE) {
                double elapsed = (now - stageStart) / 1e9;
                double travel = stage == Stage.MEASURE ? warmup + elapsed : Math.min(elapsed, warmup);
                if (scene.equals("radius16-flight") || scene.equals("chunkgen-sprint")) {
                    float speed = scene.equals("chunkgen-sprint") ? 30 : 15;
                    driver.teleport(originX + (float) travel * speed, originY, originZ);
                    driver.lookAt(access.player().position.x + 100, originY - 60, originZ + 20);
                }
                if (peers != null) peers.update(dt, travel);
                if (stage == Stage.MEASURE && scene.equals("save-heavy") && elapsed >= nextSave) {
                    nextSave += 10; dirtySaveChunks();
                    long start = System.nanoTime(); access.saveWorld();
                    captureSave.add((System.nanoTime() - start) / 1e6); saveCycles++;
                    saveCompletions.add(CompletableFuture.supplyAsync(() -> {
                        access.save().flushAndAwait(); return (System.nanoTime() - start) / 1e6;
                    }));
                }
            }
        } catch (Throwable failure) { fail(failure); }
    }
    private void prepare() {
        World world = access.world();
        Biome biome = scene.equals("forest-r10") ? Biome.FOREST : scene.equals("ocean-r10") ? Biome.OCEAN : Biome.PLAINS;
        int[] point = scene.equals("flat-idle") ? new int[]{8, 8} : findBiome(world.biomes, biome);
        originX = point[0] + .5f; originZ = point[1] + .5f;
        int cx = Math.floorDiv(point[0], 16), cz = Math.floorDiv(point[1], 16);
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) access.loader().loadNow(cx + x, cz + z);
        originY = scene.equals("flat-idle") ? 67 : Math.max(ground(point[0], point[1]) + 5, World.SEA_LEVEL + 5);
        // Fixed-height flight must not tunnel through mountains along the deterministic route.
        if (scene.equals("radius16-flight") || scene.equals("chunkgen-sprint")) originY = Chunk.SIZE_Y + 12;
        access.mobs().clear(); access.items().clear();
        access.time(scene.equals("storm-night") || scene.equals("mobs-100") ? Math.PI * 1.5 : Math.PI * .5);
        access.weather(scene.equals("storm-night") ? Weather.Kind.STORM : Weather.Kind.CLEAR);
        driver.setCreative(); access.player().flying = true;
        if (scene.equals("cave")) {
            for (int x = -4; x <= 4; x++) for (int z = -18; z <= 6; z++) for (int y = 19; y <= 25; y++)
                world.setBlock(point[0] + x, y, point[1] + z,
                        y == 19 || y == 25 || Math.abs(x) == 4 ? BlockType.STONE : BlockType.AIR);
            for (int z = -16; z <= 4; z += 5) world.setBlock(point[0] - 3, 20, point[1] + z, BlockType.TORCH);
            originY = 20; setup.put("torches", 5);
        }
        if (scene.equals("mobs-100")) {
            Random random = new Random(seed);
            for (int i = 0; i < 100; i++) {
                float x = originX + (i % 10 - 5) * 3, z = originZ + (i / 10 - 5) * 3;
                access.mobs().add(new Mob(i < 60 ? MobType.COW : MobType.ZOMBIE, x, ground((int)x, (int)z) + 1, z, random));
            }
            originY += 16; setup.put("peaceful", 60); setup.put("hostile", 40);
        }
        if (scene.equals("items-400")) {
            for (int i = 0; i < 400; i++) {
                float x = originX + (i % 20 - 10) * 2, z = originZ + (i / 20 - 10) * 2;
                access.items().add(new ItemEntity(new ItemStack(BlockType.STONE, 1), x, ground((int)x, (int)z) + 1,
                        z, 1000, i * .618f));
            }
            originY += 22;
        }
        boolean population = scene.equals("items-400") || scene.equals("mobs-100");
        driver.teleport(originX, originY, originZ + (population ? 38 : 0));
        driver.lookAt(originX, population ? originY - 22 : originY - 3, originZ - (population ? 0 : 30));
        if (scene.equals("radius16-flight") || scene.equals("chunkgen-sprint"))
            driver.lookAt(originX + 100, originY - 60, originZ + 20);
        if (scene.equals("save-heavy")) { dirtySaveChunks(); setup.put("dirty_chunks_per_save", 300); }
        if (scene.equals("mp-host-3")) peers = new BenchPeers(access.net(), access.player().position);
        setup.put("origin", List.of(originX, originY, originZ)); setup.put("biome", world.biomes.biomeAt(point[0], point[1]).name());
        setup.put("generation", scene.equals("flat-idle") ? "FLAT" : "NORMAL");
        setup.put("natural_mob_spawning", false); setup.put("game_mode", "CREATIVE");
        setup.put("movement_blocks_per_second", scene.equals("chunkgen-sprint") ? 30 : scene.equals("radius16-flight") ? 15 : 0);
        System.out.println("BENCH_PREPARED " + scene + " " + BenchMetrics.json(setup));
    }
    public static int[] findBiome(BiomeProvider provider, Biome wanted) {
        for (int radius = 0; radius <= 64; radius++) for (int x = -radius; x <= radius; x++)
            for (int z = -radius; z <= radius; z++) if (Math.max(Math.abs(x), Math.abs(z)) == radius
                    && provider.biomeAt(x * 64, z * 64) == wanted) return new int[]{x * 64, z * 64};
        throw new IllegalStateException("Seed has no nearby " + wanted);
    }
    private int ground(int x, int z) {
        World world = access.world();
        for (int y = Chunk.SIZE_Y - 2; y > 0; y--) {
            BlockType block = world.getBlock(x, y, z);
            if (block.solid && block != BlockType.LEAVES && block != BlockType.WOOD) return y;
        }
        return 1;
    }
    private void dirtySaveChunks() {
        dirtyRevision++;
        int cx = Math.floorDiv((int)originX, 16), cz = Math.floorDiv((int)originZ, 16);
        for (int x = -7; x <= 7; x++) for (int z = -9; z <= 10; z++) {
            access.loader().loadNow(cx + x, cz + z);
            access.world().setBlock((cx + x) * 16 + 8, 110, (cz + z) * 16 + 8,
                    dirtyRevision % 2 == 0 ? BlockType.COBBLE : BlockType.STONE);
        }
    }

    void frame(double workMs, FrameProfiler profiler) {
        if (stage == Stage.DONE) return;
        try {
            long now = System.nanoTime();
            if (stage == Stage.WARMUP && !screenshotCaptured) {
                // Game captured and joined the PNG writer before this callback. Warm up after that I/O.
                screenshotCaptured = true; stageStart = now; return;
            }
            if (stage == Stage.WARMUP && (now - stageStart) / 1e9 >= warmup) {
                // Clock starts after telemetry reset: setup/reporting allocations are excluded.
                access.loader().pipelineMetrics().reset();
                writesStart = access.save().writeMetrics();
                netStart = peers == null ? null : peers.hostStats.snapshot();
                gcCountStart = gcCount(); gcTimeStart = gcTime(); gpuDroppedStart = profiler.gpuDroppedFrames();
                gpuCompletedStart = profiler.gpuSamples();
                allocatedStart = allocated(); lastGpuFrame = profiler.gpuSampleFrame();
                transition(Stage.MEASURE); measuredStart = stageStart; previousFrame = stageStart;
                measurementUptimeStart = ManagementFactory.getRuntimeMXBean().getUptime();
                System.out.println("BENCH_MEASURE scene=" + scene + " seconds=" + duration); return;
            }
            if (stage != Stage.MEASURE) return;
            frames.add((now - previousFrame) / 1e6); previousFrame = now; work.add(workMs);
            for (int i = 0; i < cpu.length; i++) cpu[i].add(profiler.rawMillis(CPU_PHASES[i]));
            if (profiler.gpuSampleFrame() != lastGpuFrame && profiler.gpuSampleFrame() >= 0) {
                for (int i = 0; i < gpu.length; i++) gpu[i].add(profiler.gpuMillis(GPU_PHASES[i]));
                gpuTotal.add(profiler.gpuTotalMillis());
                lastGpuFrame = profiler.gpuSampleFrame();
            }
            ChunkLoader loader = access.loader();
            queues[0].add(loader.pendingGenCount()); queues[1].add(loader.pendingMeshCount());
            queues[2].add(loader.pendingLightCount()); queues[3].add(loader.readyMeshCount());
            mobsMin = Math.min(mobsMin, access.mobs().size()); mobsMax = Math.max(mobsMax, access.mobs().size());
            itemsMin = Math.min(itemsMin, access.items().size()); itemsMax = Math.max(itemsMax, access.items().size());
            meshMin = Math.min(meshMin, access.meshCount()); meshMax = Math.max(meshMax, access.meshCount());
            if ((now - measuredStart) / 1e9 >= duration) finish(now, profiler);
        } catch (Throwable failure) { fail(failure); }
    }
    private void finish(long now, FrameProfiler profiler) throws Exception {
        measurementUptimeEnd = ManagementFactory.getRuntimeMXBean().getUptime();
        long allocatedEnd = allocated(), gcCountEnd = gcCount(), gcTimeEnd = gcTime();
        double measuredSeconds = (now - measuredStart) / 1e9;
        transition(Stage.DONE);
        var pipeline = access.loader().pipelineMetrics().snapshot();
        for (var completion : saveCompletions) flushSave.add(completion.join());
        access.save().flushAndAwait(); var writes = access.save().writeMetrics();
        Map<String, Object> root = base();
        root.put("measured_seconds", measuredSeconds); root.put("frames", BenchMetrics.summarize(frames.values()));
        root.put("measurement_uptime_start_ms", measurementUptimeStart);
        root.put("measurement_uptime_end_ms", measurementUptimeEnd);
        root.put("cpu_mobs_ms", BenchMetrics.distribution(mobCpu.values()));
        root.put("cpu_work_ms", BenchMetrics.distribution(work.values()));
        Map<String, Object> cpuReport = new LinkedHashMap<>(), gpuReport = new LinkedHashMap<>(), queueReport = new LinkedHashMap<>();
        for (int i = 0; i < cpu.length; i++) cpuReport.put(CPU_PHASES[i].label, BenchMetrics.distribution(cpu[i].values()));
        for (int i = 0; i < gpu.length; i++) gpuReport.put(GPU_PHASES[i].name().toLowerCase(Locale.ROOT), BenchMetrics.distribution(gpu[i].values()));
        String[] names = {"generation", "meshing", "lighting", "ready_upload"};
        for (int i = 0; i < queues.length; i++) queueReport.put(names[i], BenchMetrics.distribution(queues[i].values()));
        root.put("cpu_phases_ms", cpuReport); root.put("gpu_phases_ms", gpuReport);
        root.put("gpu_total_ms", BenchMetrics.distribution(gpuTotal.values()));
        root.put("gpu_dropped_frames", profiler.gpuDroppedFrames() - gpuDroppedStart);
        root.put("gpu_completed_samples", profiler.gpuSamples() - gpuCompletedStart);
        root.put("gpu_observed_samples", gpuTotal.size());
        root.put("gpu_unobserved_samples", Math.max(0, profiler.gpuSamples() - gpuCompletedStart - gpuTotal.size()));
        root.put("queues", queueReport);
        root.put("generation_latency_ms", BenchMetrics.distribution(pipeline.generationMillis()));
        root.put("mesh_latency_ms", BenchMetrics.distribution(pipeline.meshMillis()));
        root.put("pipeline_samples_dropped", pipeline.droppedSamples());
        root.put("main_thread_alloc_bytes_per_second", allocatedStart < 0 || allocatedEnd < 0 ? null : (allocatedEnd - allocatedStart) / measuredSeconds);
        root.put("gc", Map.of("collections", gcCountEnd - gcCountStart, "collection_time_ms", gcTimeEnd - gcTimeStart));
        root.put("saves", Map.of("capture_ms", BenchMetrics.distribution(captureSave.values()), "flush_latency_ms", BenchMetrics.distribution(flushSave.values()),
                "cycles", saveCycles, "gzip_writes", writes.completedWrites() - writesStart.completedWrites(),
                "gzip_bytes", writes.compressedBytes() - writesStart.compressedBytes(), "gzip_io_ms", (writes.totalNanos() - writesStart.totalNanos()) / 1e6,
                "failed_writes", writes.failedWrites() - writesStart.failedWrites()));
        if (peers != null) {
            var traffic = peers.hostStats.snapshot();
            root.put("network", Map.of("host_payload_bytes_sent", traffic.sentBytes() - netStart.sentBytes(),
                    "host_payload_bytes_received", traffic.receivedBytes() - netStart.receivedBytes(),
                    "host_packets_sent", traffic.sentPackets() - netStart.sentPackets(),
                    "host_packets_received", traffic.receivedPackets() - netStart.receivedPackets(), "guests", access.net().players().size()));
        } else root.put("network", null);
        root.put("observed", Map.of("mobs_min", mobsMin, "mobs_max", mobsMax, "items_min", itemsMin, "items_max", itemsMax,
                "chunk_meshes_min", meshMin, "chunk_meshes_max", meshMax));
        List<String> errors = new ArrayList<>();
        if (frames.size() < 10 || meshMin < 1) errors.add("No sustained rendered workload");
        if (scene.equals("mobs-100") && mobsMin < 100) errors.add("Mob population fell below 100");
        if (scene.equals("items-400") && itemsMin != 400) errors.add("Item population differs from 400");
        if (scene.equals("mp-host-3") && !peers.ready()) errors.add("Three guests not connected");
        if (scene.equals("save-heavy") && (saveCycles == 0 || writes.completedWrites() - writesStart.completedWrites() < 300)) errors.add("300 modified chunks were not saved");
        if (gpu[0].size() == 0) errors.add("No GPU samples");
        if (writes.failedWrites() != writesStart.failedWrites()) errors.add("Save I/O failed");
        long beforeGc = gcCount(); System.gc();
        root.put("heap_after_gc_bytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        root.put("explicit_gc_observed", gcCount() > beforeGc);
        root.put("validation_errors", errors); root.put("status", errors.isEmpty() ? "PASS" : "FAIL");
        write(root);
        System.out.println("BENCH_RESULT " + scene + " " + root.get("status") + " " + BenchMetrics.json(root.get("frames")));
        driver.quit(errors.isEmpty() ? 0 : 1);
    }
    private Map<String, Object> base() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", 1); root.put("scene", scene); root.put("run_id", System.getProperty("mineclone.bench.runId"));
        root.put("created_utc", Instant.now().toString()); root.put("version", BuildInfo.VERSION); root.put("git_sha", BuildInfo.GIT_SHA);
        root.put("provenance_manifest", System.getProperty("mineclone.bench.manifest", "unrecorded standalone run"));
        root.put("seed", seed); root.put("warmup_seconds", warmup); root.put("requested_seconds", duration); root.put("setup", setup);
        boolean ultra = scene.equals("radius16-flight");
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("width", access.width()); settings.put("height", access.height()); settings.put("render_radius", radius(scene));
        settings.put("quality", ultra ? "Ultra" : "Fancy"); settings.put("vsync", false); settings.put("fps_limit", 0);
        settings.put("render_scale", 100); settings.put("msaa", 4); settings.put("shadows", ultra ? 3 : 1);
        settings.put("particles", 3); settings.put("weather", 2); settings.put("chunk_lod", !ultra);
        settings.put("water_reflections", ultra); settings.put("fov", 70);
        root.put("settings", settings);
        root.put("runtime", Map.of("java", System.getProperty("java.runtime.version"), "vm", System.getProperty("java.vm.name"),
                "os", System.getProperty("os.name"), "arch", System.getProperty("os.arch"), "cpus", Runtime.getRuntime().availableProcessors(),
                "max_heap_bytes", Runtime.getRuntime().maxMemory(), "gpu_vendor", glGetString(GL_VENDOR),
                "gpu_renderer", glGetString(GL_RENDERER), "gl_driver", glGetString(GL_VERSION)));
        root.put("metric_notes", List.of("Frame interval includes benchmark observation and swap; CPU work excludes both.",
                "GPU samples arrive asynchronously and are deduplicated; window edges may include the previous few GPU frames.",
                "When several GPU samples finish in one poll, only the latest is observed; unobserved samples are counted explicitly.",
                "Chunk latencies include queue wait; empty distributions mean no completed work.",
                "Allocation is main thread only; heap GC occurs after the measured window.",
                "GC collection time is the JVM aggregate, not a per-pause percentile.",
                "Network counts application payload fanout, not socket headers; three clients run in-process.",
                "Save gzip metrics include all save-manager writes; capture and flush measure explicit save-heavy cycles."));
        return root;
    }
    private void write(Map<String, Object> report) throws java.io.IOException {
        Path path = Path.of(System.getProperty("mineclone.bench.output", "out-test/bench/" + System.getProperty("mineclone.bench.runId") + ".json"));
        Files.createDirectories(path.toAbsolutePath().getParent()); Files.writeString(path, BenchMetrics.json(report) + "\n");
        System.out.println("BENCH_JSON " + path.toAbsolutePath());
    }
    private void fail(Throwable failure) {
        transition(Stage.DONE); failure.printStackTrace();
        try { Map<String, Object> report = base(); report.put("status", "FAIL"); report.put("error", failure.toString()); write(report); }
        catch (Exception ignored) { ignored.printStackTrace(); }
        driver.quit(1);
    }
    private long allocated() { return threads == null ? -1 : threads.getThreadAllocatedBytes(mainThread); }
    private static long gcCount() { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b -> Math.max(0, b.getCollectionCount())).sum(); }
    private static long gcTime() { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b -> Math.max(0, b.getCollectionTime())).sum(); }
    public void close() { if (peers != null) { peers.close(); peers = null; } }
}

package com.mineclone.game;

import com.mineclone.ui.MenuAction;
import com.mineclone.ui.WorldSettings;
import com.mineclone.world.GameMode;

import java.util.Locale;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_W;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;

/**
 * Замер рывков в живой игре: {@code -Dmineclone.stress=<секунды>}.
 *
 * <p>Автопилот проверяет, что игра работает, но игрока он почти не двигает —
 * а рывки живут ровно там, где он бежит: стриминг чанков, выгрузка дальних,
 * перестройка каскадов теней, мусор от мешей. Поэтому здесь отдельный режим:
 * создать мир, бежать по прямой, крутить головой и копать, а в конце
 * напечатать не средний кадр, а худшие.
 *
 * <p>Средний FPS про рывки не говорит ничего, поэтому печатаются перцентили и
 * двадцать худших кадров с разбивкой по фазам. Рядом — пауза сборщика мусора
 * за тот же прогон: половина «необъяснимых» рывков это она, и отличить её от
 * работы можно только числом.
 *
 * <p>Вывод латиницей — у консоли Windows кодировка не UTF-8.
 */
final class StressFlight {

    /** Сколько худших кадров печатать. */
    private static final int WORST = 20;
    /** Кадр дольше этого попадает в отчёт целиком, с фазами. */
    private static final double SPIKE_MS = 25.0;
    /** Сид полёта. Фиксированный: замер обязан быть повторяемым. */
    private static final long SEED = 20260920L;
    /** Через сколько секунд полёта развернуться: так чанки грузятся с диска, а не с нуля. */
    private static final float TURN_EVERY = 12f;

    private final Autopilot.Driver driver;
    private final float seconds;

    private float clock;
    private float phaseClock;
    private int stage;
    private boolean flying;
    private boolean mining;

    private final java.util.ArrayList<double[]> frames = new java.util.ArrayList<>(16384);
    private final java.util.ArrayList<String> spikes = new java.util.ArrayList<>();
    private long gcCountStart, gcTimeStart;
    private final java.util.ArrayList<Double> gpuFrames = new java.util.ArrayList<>();
    private long lastGpuFrame = -1;
    private final double[] gpuPhaseTotals = new double[com.mineclone.render.GpuTimers.Phase.values().length];

    StressFlight(Autopilot.Driver driver, float seconds) {
        this.driver = driver;
        this.seconds = seconds;
    }

    /** Ведёт игрока. Зовётся раз в кадр из игрового цикла. */
    void update(float dt) {
        clock += dt;
        phaseClock += dt;
        switch (stage) {
            case 0 -> {
                // Дать меню проснуться и создать мир.
                if (phaseClock > 1.0f) {
                    driver.act(MenuAction.create(new WorldSettings("Stress", SEED, GameMode.SURVIVAL)));
                    advance();
                }
            }
            case 1 -> {
                if ("PLAYING".equals(driver.state())) {
                    System.out.println("stress: world loaded after " + fmt(clock) + " s, flying for "
                            + fmt(seconds) + " s");
                    startFlight();
                    advance();
                }
                if (clock > 120f)
                    fail("world never finished loading");
            }
            case 2 -> {
                // Бежим по прямой и медленно ведём головой: так работают и
                // стриминг, и отсечение, и каскады теней.
                // Раз в TURN_EVERY секунд разворот: обратный путь идёт по уже
                // сохранённым чанкам, и в замер попадает чтение с диска, а не
                // только генерация с нуля.
                float turn = (phaseClock % TURN_EVERY) < 0.6f ? 300f : 18f;
                driver.look(dt * turn, (float) Math.sin(clock * 0.7) * dt * 6f);
                // Копаем полсекунды из каждых двух: правка блока это свой путь.
                boolean wantMine = (clock % 2f) < 0.5f;
                if (wantMine != mining) {
                    mining = wantMine;
                    driver.mouseButton(GLFW_MOUSE_BUTTON_LEFT, mining);
                }
                if (phaseClock > seconds) {
                    stopFlight();
                    report();
                    driver.quit(0);
                    advance();
                }
            }
            default -> { }
        }
    }

    private void advance() {
        stage++;
        phaseClock = 0f;
    }

    private void startFlight() {
        driver.holdKey(GLFW_KEY_W, true);
        driver.holdKey(GLFW_KEY_LEFT_CONTROL, true);
        flying = true;
        var beans = java.lang.management.ManagementFactory.getGarbageCollectorMXBeans();
        for (var b : beans) {
            gcCountStart += Math.max(0, b.getCollectionCount());
            gcTimeStart += Math.max(0, b.getCollectionTime());
        }
    }

    private void stopFlight() {
        if (!flying)
            return;
        driver.holdKey(GLFW_KEY_W, false);
        driver.holdKey(GLFW_KEY_LEFT_CONTROL, false);
        if (mining)
            driver.mouseButton(GLFW_MOUSE_BUTTON_LEFT, false);
        flying = false;
    }

    /** Кадр закончился. Собираем, только пока летим: загрузка — не замер. */
    void frame(double workMs, double frameMs, String state, FrameProfiler profiler) {
        if (!flying)
            return;
        frames.add(new double[] { workMs, frameMs });
        if (profiler.gpuSampleFrame() >= 0 && profiler.gpuSampleFrame() != lastGpuFrame) {
            lastGpuFrame = profiler.gpuSampleFrame();
            gpuFrames.add(profiler.gpuTotalMillis());
            for (var phase : com.mineclone.render.GpuTimers.Phase.values())
                gpuPhaseTotals[phase.ordinal()] += profiler.gpuMillis(phase);
        }
        if (workMs >= SPIKE_MS)
            spikes.add(String.format(Locale.ROOT, "  %7.1f ms work  %7.1f ms frame  %s  %s",
                    workMs, frameMs, state, profiler.rawBreakdown() + " | " + profiler.gpuBreakdown()));
    }

    private void report() {
        if (frames.isEmpty()) {
            System.out.println("stress: no frames measured");
            return;
        }
        double[] work = new double[frames.size()];
        for (int i = 0; i < work.length; i++)
            work[i] = frames.get(i)[0];
        java.util.Arrays.sort(work);
        double sum = 0;
        for (double v : work)
            sum += v;

        long gcCount = -gcCountStart, gcTime = -gcTimeStart;
        for (var b : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCount += Math.max(0, b.getCollectionCount());
            gcTime += Math.max(0, b.getCollectionTime());
        }

        System.out.println();
        System.out.println("=== stress flight: " + work.length + " frames ===");
        System.out.printf(Locale.ROOT, "  avg %.2f ms (%.0f fps)   p50 %.2f   p95 %.2f   p99 %.2f   max %.2f%n",
                sum / work.length, work.length / Math.max(0.001, sum / 1000.0),
                pct(work, 0.50), pct(work, 0.95), pct(work, 0.99), work[work.length - 1]);
        System.out.printf(Locale.ROOT, "  frames over %.0f ms: %d of %d (%.2f %%)%n",
                SPIKE_MS, spikes.size(), work.length, 100.0 * spikes.size() / work.length);
        System.out.printf(Locale.ROOT, "  gc: %d collections, %d ms total%n", gcCount, gcTime);
        if (!gpuFrames.isEmpty()) {
            double[] gpu = gpuFrames.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            double gpuSum = 0;
            for (double value : gpu) gpuSum += value;
            System.out.printf(Locale.ROOT, "  GPU: %d completed samples, avg %.2f ms p95 %.2f p99 %.2f%n",
                    gpu.length, gpuSum / gpu.length, pct(gpu, 0.95), pct(gpu, 0.99));
            StringBuilder phases = new StringBuilder("  GPU phase averages (ms):");
            for (var phase : com.mineclone.render.GpuTimers.Phase.values()) phases.append(' ')
                    .append(phase.name().toLowerCase(Locale.ROOT)).append('=')
                    .append(String.format(Locale.ROOT, "%.2f", gpuPhaseTotals[phase.ordinal()] / gpu.length));
            System.out.println(phases);
        }
        if (!spikes.isEmpty()) {
            System.out.println("  worst frames (phases in ms):");
            java.util.List<String> worst = new java.util.ArrayList<>(spikes);
            worst.sort((a, b) -> Double.compare(parseFirst(b), parseFirst(a)));
            for (int i = 0; i < Math.min(WORST, worst.size()); i++)
                System.out.println(worst.get(i));
        }
        System.out.println("=== end ===");
    }

    private static double parseFirst(String line) {
        try {
            return Double.parseDouble(line.trim().split("\\s+")[0]);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static double pct(double[] sorted, double p) {
        int i = (int) Math.min(sorted.length - 1L, Math.round(p * (sorted.length - 1)));
        return sorted[i];
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private void fail(String why) {
        System.err.println("stress: " + why);
        driver.quit(1);
        stage = 99;
    }
}

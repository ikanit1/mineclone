package com.mineclone.game;

/**
 * Разбивка времени кадра по фазам плюс худший кадр за скользящее окно.
 *
 * <p>Средний FPS про рывки не говорит ничего: шестьдесят кадров в секунду со
 * стомиллисекундным провалом посередине и ровные шестьдесят читаются в
 * счётчике одинаково, а играются совершенно по-разному. Рывок виден только в
 * худшем кадре, поэтому он и есть главное число здесь.
 *
 * <p>Класс чистый — ни GL, ни GLFW: время подаётся снаружи. Отсюда его можно
 * дёргать из обычных тестов и из офлайновых замеров в {@code tools/}.
 */
public final class FrameProfiler {

    /** Фазы кадра в порядке их выполнения. */
    public enum Phase {
        UPDATE("update"),
        STREAM("stream"),
        SHADOW("shadow"),
        WORLD("world"),
        POST("post"),
        HUD("hud");

        public final String label;

        Phase(String label) {
            this.label = label;
        }
    }

    private static final Phase[] PHASES = Phase.values();

    /** Длина окна, по которому ищется худший кадр, в секундах. */
    public static final double WINDOW = 2.0;

    private final double[] current = new double[PHASES.length];
    private final double[] smoothed = new double[PHASES.length];

    private double frameTotal;
    private double worstInWindow;
    private double windowAge;
    /**
     * Худший кадр копится в двух корзинах: текущей и предыдущей. Показывается
     * максимум обеих, а обнуляется по очереди только текущая. Одна корзина
     * давала бы обнуление ровно в тот момент, когда игрок читает цифру, —
     * рывок успевал исчезнуть с экрана раньше, чем его замечали.
     */
    private double worstPrevWindow;

    private Phase open;
    private double openStart;
    private static final com.mineclone.render.GpuTimers.Phase[] GPU_PHASES =
            com.mineclone.render.GpuTimers.Phase.values();
    private final double[] gpuMillis = new double[GPU_PHASES.length];
    private long gpuFrame = -1;
    private long gpuSamples;
    private long gpuDropped;

    /**
     * Коэффициент сглаживания пофазных чисел. Мгновенные значения скачут так,
     * что читать их невозможно; худший кадр при этом НЕ сглаживается — иначе
     * пропадает ровно то, ради чего всё затевалось.
     */
    private static final double SMOOTH = 0.1;

    /** Начало кадра: сбрасывает накопленные фазы. */
    public void beginFrame() {
        java.util.Arrays.fill(current, 0.0);
        frameTotal = 0.0;
        open = null;
    }

    /**
     * Открывает фазу. Предыдущая, если была открыта, закрывается — вложенных
     * фаз нет намеренно: они превращают разбивку в дерево, которое в углу
     * экрана всё равно не прочитать.
     *
     * @param now текущее время в секундах
     */
    public void begin(Phase phase, double now) {
        if (open != null)
            end(now);
        open = phase;
        openStart = now;
    }

    /** Закрывает текущую фазу. Без открытой фазы ничего не делает. */
    public void end(double now) {
        if (open == null)
            return;
        double dt = now - openStart;
        current[open.ordinal()] += dt;
        frameTotal += dt;
        open = null;
    }

    /**
     * Конец кадра: сглаживает фазы и обновляет худший кадр окна.
     *
     * @param frameSeconds полная длительность кадра, включая ожидание vsync
     */
    public void endFrame(double now, double frameSeconds) {
        end(now);
        for (int i = 0; i < PHASES.length; i++)
            smoothed[i] += (current[i] - smoothed[i]) * SMOOTH;

        // Ожидание vsync и сон ограничителя кадров — не работа, а простой.
        // Считать их рывком значит объявить рывком любой спокойный кадр, но
        // и отбрасывать целиком нельзя: реальный провал обычно длиннее
        // собственно работы. Берём максимум из суммы фаз и длины кадра,
        // ограниченной сверху — потолок отсекает простой, но не провал.
        double measured = Math.max(frameTotal, Math.min(frameSeconds, frameTotal * 4.0));
        worstInWindow = Math.max(worstInWindow, measured);

        windowAge += frameSeconds;
        if (windowAge >= WINDOW / 2.0) {
            windowAge = 0.0;
            worstPrevWindow = worstInWindow;
            worstInWindow = 0.0;
        }
    }

    /** Сглаженная длительность фазы, мс. */
    public double millis(Phase phase) {
        return smoothed[phase.ordinal()] * 1000.0;
    }

    /** Unsmoothed completed CPU phase duration, milliseconds; no allocation or GPU readback. */
    public double rawMillis(Phase phase) { return current[phase.ordinal()] * 1000.0; }

    /** Сумма сглаженных фаз, мс. */
    public double totalMillis() {
        double sum = 0;
        for (double v : smoothed)
            sum += v;
        return sum * 1000.0;
    }

    /** Худший кадр за последние {@link #WINDOW} секунд, мс. */
    public double worstMillis() {
        return Math.max(worstInWindow, worstPrevWindow) * 1000.0;
    }

    /** Строка для F3: {@code "u1.2 s0.4 sh1.7 w3.1 p1.0 h0.3"}. */
    public String breakdown() {
        StringBuilder sb = new StringBuilder();
        for (Phase p : PHASES) {
            if (sb.length() > 0)
                sb.append("  ");
            sb.append(p.label).append(' ').append(String.format("%.1f", millis(p)));
        }
        return sb.toString();
    }

    /** Raw phase times for a spike report; unlike F3 this is not smoothed. */
    public String rawBreakdown() {
        StringBuilder sb = new StringBuilder();
        for (Phase p : PHASES) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(p.label).append("=")
                    .append(String.format(java.util.Locale.ROOT, "%.2f", current[p.ordinal()] * 1000.0));
        }
        return sb.toString();
    }

    /** Copy an already completed GPU sample; this method never makes an OpenGL call. */
    public void captureGpu(com.mineclone.render.GpuTimers timers) {
        if (timers.sampleFrame() != gpuFrame) {
            for (var phase : GPU_PHASES) gpuMillis[phase.ordinal()] = timers.millis(phase);
            gpuFrame = timers.sampleFrame();
        }
        gpuSamples = timers.sampleCount();
        gpuDropped = timers.droppedFrames();
    }

    public double gpuMillis(com.mineclone.render.GpuTimers.Phase phase) { return gpuMillis[phase.ordinal()]; }
    public double gpuTotalMillis() {
        double total = 0;
        for (double value : gpuMillis) total += value;
        return total;
    }
    public long gpuSampleFrame() { return gpuFrame; }
    public long gpuSamples() { return gpuSamples; }
    public long gpuDroppedFrames() { return gpuDropped; }
    public String gpuBreakdown() {
        if (gpuFrame < 0) return "GPU: pending";
        StringBuilder out = new StringBuilder(String.format(java.util.Locale.ROOT,
                "GPU %.2f ms [frame %d] ", gpuTotalMillis(), gpuFrame));
        for (var phase : GPU_PHASES) out.append(phase.name().toLowerCase(java.util.Locale.ROOT)).append('=')
                .append(String.format(java.util.Locale.ROOT, "%.2f ", gpuMillis(phase)));
        return out.toString().trim();
    }
}

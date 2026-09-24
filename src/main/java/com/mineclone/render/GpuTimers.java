package com.mineclone.render;

import java.util.Arrays;
import java.util.Locale;
import static org.lwjgl.opengl.GL33.*;

/** Nonblocking, sequential GPU phase queries with three complete frames in flight. */
public final class GpuTimers implements AutoCloseable {
    public enum Phase { SHADOW, OPAQUE, WATER, ENTITIES, PARTICLES, POST, HUD }
    private static final Phase[] PHASES = Phase.values();
    public static final int RING_SIZE = 3;

    /** Small test seam; the production backend maps directly to OpenGL 3.3 queries. */
    public interface Backend {
        int create();
        void begin(int query);
        void end();
        boolean available(int query);
        long nanos(int query);
        void delete(int query);
    }

    private static final class OpenGlBackend implements Backend {
        public int create() { return glGenQueries(); }
        public void begin(int query) { glBeginQuery(GL_TIME_ELAPSED, query); }
        public void end() { glEndQuery(GL_TIME_ELAPSED); }
        public boolean available(int query) { return glGetQueryObjecti(query, GL_QUERY_RESULT_AVAILABLE) != GL_FALSE; }
        public long nanos(int query) { return glGetQueryObjectui64(query, GL_QUERY_RESULT); }
        public void delete(int query) { glDeleteQueries(query); }
    }

    private final Backend backend;
    private final int[][] queries = new int[RING_SIZE][PHASES.length];
    private final int[] masks = new int[RING_SIZE];
    private final long[] frames = new long[RING_SIZE];
    private final boolean[] pending = new boolean[RING_SIZE];
    private final double[] latest = new double[PHASES.length];
    private int writeSlot;
    private int activePhase = -1;
    private boolean frameOpen, recording, closed;
    private long frameNumber = -1, sampleFrame = -1, samples, dropped;

    /** Must be constructed and used on the current OpenGL context thread. */
    public GpuTimers() { this(new OpenGlBackend()); }

    public GpuTimers(Backend backend) {
        this.backend = java.util.Objects.requireNonNull(backend);
        try {
            for (int ring = 0; ring < RING_SIZE; ring++)
                for (int phase = 0; phase < PHASES.length; phase++) queries[ring][phase] = backend.create();
        } catch (RuntimeException | Error e) {
            for (int[] ring : queries) for (int query : ring) if (query != 0) backend.delete(query);
            throw e;
        }
    }

    /** Poll once per frame, then reserve a free ring slot or skip this measurement. */
    public void beginFrame() {
        requireOpen();
        if (frameOpen) throw new IllegalStateException("Previous GPU frame is still open");
        poll();
        frameNumber++;
        frameOpen = true;
        recording = !pending[writeSlot];
        if (recording) {
            masks[writeSlot] = 0;
            frames[writeSlot] = frameNumber;
        } else dropped++;
    }

    /** Begin one interval; nesting and repeating a phase in the same frame are errors. */
    public void begin(Phase phase) {
        requireOpen();
        if (!frameOpen) throw new IllegalStateException("GPU phase outside a frame");
        if (activePhase >= 0) throw new IllegalStateException("GPU time queries cannot be nested");
        int index = java.util.Objects.requireNonNull(phase).ordinal();
        if (recording && (masks[writeSlot] & (1 << index)) != 0)
            throw new IllegalStateException("GPU phase already measured in this frame: " + phase);
        activePhase = index;
        if (recording) {
            masks[writeSlot] |= 1 << index;
            backend.begin(queries[writeSlot][index]);
        }
    }

    /** Close the previous interval and open the next, with no overlapping queries. */
    public void next(Phase phase) { end(); begin(phase); }

    public void end() {
        requireOpen();
        if (activePhase < 0) return;
        if (recording) backend.end();
        activePhase = -1;
    }

    public void endFrame() {
        requireOpen();
        if (!frameOpen) throw new IllegalStateException("No GPU frame is open");
        end();
        if (recording && masks[writeSlot] != 0) {
            pending[writeSlot] = true;
            writeSlot = (writeSlot + 1) % RING_SIZE;
        }
        recording = false;
        frameOpen = false;
    }

    /**
     * Only complete samples are published, in submission order. Query result
     * reads are never issued until every used phase reports availability.
     */
    public void poll() {
        requireOpen();
        if (frameOpen) throw new IllegalStateException("Poll GPU results between frames");
        for (int count = 0; count < RING_SIZE; count++) {
            int oldest = -1;
            for (int slot = 0; slot < RING_SIZE; slot++)
                if (pending[slot] && (oldest < 0 || frames[slot] < frames[oldest])) oldest = slot;
            if (oldest < 0) return;
            int mask = masks[oldest];
            for (int phase = 0; phase < PHASES.length; phase++)
                if ((mask & (1 << phase)) != 0 && !backend.available(queries[oldest][phase])) return;
            Arrays.fill(latest, 0);
            for (int phase = 0; phase < PHASES.length; phase++)
                if ((mask & (1 << phase)) != 0)
                    latest[phase] = Math.max(0, backend.nanos(queries[oldest][phase])) / 1_000_000.0;
            sampleFrame = frames[oldest];
            samples++;
            pending[oldest] = false;
        }
    }

    public double millis(Phase phase) { return latest[phase.ordinal()]; }
    public double totalMillis() {
        double total = 0;
        for (double millis : latest) total += millis;
        return total;
    }
    public long sampleCount() { return samples; }
    public long sampleFrame() { return sampleFrame; }
    public long droppedFrames() { return dropped; }
    public boolean hasSample() { return sampleFrame >= 0; }
    public String breakdown() {
        if (!hasSample()) return "GPU pending";
        StringBuilder out = new StringBuilder();
        for (Phase phase : PHASES) {
            if (!out.isEmpty()) out.append(' ');
            out.append(phase.name().toLowerCase(Locale.ROOT)).append('=')
                    .append(String.format(Locale.ROOT, "%.2f", millis(phase)));
        }
        return out.toString();
    }

    public void destroy() { close(); }
    @Override public void close() {
        if (closed) return;
        if (activePhase >= 0 && recording) backend.end();
        for (int[] ring : queries) for (int query : ring) backend.delete(query);
        closed = true;
        activePhase = -1;
        recording = false;
        frameOpen = false;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("GPU timers are closed");
    }
}

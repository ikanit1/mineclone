package com.mineclone;

import com.mineclone.game.FrameProfiler;
import com.mineclone.render.GpuTimers;
import java.util.HashMap;
import java.util.Map;

public final class GpuTimersRingTests {
    public static void runAll(TestMain.Runner runner) {
        runner.run("GPU ring never reads or replaces unfinished query results", GpuTimersRingTests::backpressure);
        runner.run("GPU samples contain one complete frame in submission order", GpuTimersRingTests::completeFrames);
        runner.run("GPU phases reject nesting and duplicate intervals", GpuTimersRingTests::sequentialIntervals);
        runner.run("GPU timer close ends the active interval and frees every query once", GpuTimersRingTests::cleanup);
    }

    public static void main(String[] args) {
        backpressure(); completeFrames(); sequentialIntervals(); cleanup();
        System.out.println("GPU_TIMERS_RING PASS: nonblocking backpressure, complete samples, sequential phases, cleanup");
    }

    private static void backpressure() {
        Fake backend = new Fake();
        try (GpuTimers timers = new GpuTimers(backend)) {
            for (int frame = 0; frame < 4; frame++) {
                timers.beginFrame();
                timers.begin(GpuTimers.Phase.OPAQUE);
                timers.endFrame();
            }
            check(backend.begins == 3 && backend.reads == 0, "unfinished queries were read or overwritten");
            check(timers.droppedFrames() == 1 && !timers.hasSample(), "full ring did not skip frame");
            backend.readyAll();
            timers.beginFrame();
            check(timers.sampleCount() == 3 && timers.sampleFrame() == 2, "ready ring was not consumed in order");
            timers.next(GpuTimers.Phase.OPAQUE);
            timers.endFrame();
            check(backend.begins == 4, "ring did not reuse a completed slot");
        }
    }

    private static void completeFrames() {
        Fake backend = new Fake();
        try (GpuTimers timers = new GpuTimers(backend)) {
            timers.beginFrame();
            timers.next(GpuTimers.Phase.SHADOW);
            timers.next(GpuTimers.Phase.OPAQUE);
            timers.endFrame();
            timers.beginFrame();
            timers.next(GpuTimers.Phase.OPAQUE);
            timers.endFrame();
            // The newer frame is ready; the first frame has only its shadow ready.
            backend.states.get(1).ready = true;
            backend.states.get(9).ready = true;
            timers.poll();
            check(backend.reads == 0 && !timers.hasSample(), "partial or out-of-order frame leaked");
            backend.states.get(2).ready = true;
            timers.poll();
            check(timers.sampleCount() == 2 && timers.sampleFrame() == 1, "both completed frames not consumed");
            check(timers.millis(GpuTimers.Phase.SHADOW) == 0, "old frame phase leaked into new sample");
            check(timers.millis(GpuTimers.Phase.OPAQUE) == 0.9 && timers.totalMillis() == 0.9,
                    "nanoseconds conversion or complete-frame sum is wrong");
            FrameProfiler profiler = new FrameProfiler();
            profiler.captureGpu(timers);
            check(profiler.gpuSampleFrame() == 1 && profiler.gpuTotalMillis() == 0.9,
                    "CPU profiler did not retain GPU sample identity");
        }
    }

    private static void sequentialIntervals() {
        Fake backend = new Fake();
        try (GpuTimers timers = new GpuTimers(backend)) {
            expectIllegal(() -> timers.begin(GpuTimers.Phase.HUD));
            timers.beginFrame();
            timers.begin(GpuTimers.Phase.OPAQUE);
            expectIllegal(() -> timers.begin(GpuTimers.Phase.WATER));
            timers.end();
            expectIllegal(() -> timers.begin(GpuTimers.Phase.OPAQUE));
            timers.begin(GpuTimers.Phase.WATER);
            timers.next(GpuTimers.Phase.HUD);
            timers.endFrame();
            check(backend.begins == 3 && backend.ends == 3, "phase intervals overlapped or leaked");
            expectIllegal(timers::endFrame);
        }
    }

    private static void cleanup() {
        Fake backend = new Fake();
        GpuTimers timers = new GpuTimers(backend);
        timers.beginFrame();
        timers.begin(GpuTimers.Phase.PARTICLES);
        timers.close();
        timers.close();
        check(backend.ends == 1, "active query not ended on close");
        check(backend.deletes == GpuTimers.RING_SIZE * GpuTimers.Phase.values().length,
                "query handles leaked or deleted twice");
        expectIllegal(timers::beginFrame);
    }

    private static final class State { boolean issued, ready, read, deleted; }
    private static final class Fake implements GpuTimers.Backend {
        final Map<Integer, State> states = new HashMap<>();
        int next, active, begins, ends, reads, deletes;
        public int create() { states.put(++next, new State()); return next; }
        public void begin(int query) {
            check(active == 0, "backend nested query");
            State state = states.get(query);
            check(!state.deleted && (!state.issued || state.read), "overwriting an unread query");
            state.issued = true; state.ready = false; state.read = false;
            active = query; begins++;
        }
        public void end() { check(active != 0, "backend end without begin"); active = 0; ends++; }
        public boolean available(int query) { return states.get(query).ready; }
        public long nanos(int query) {
            State state = states.get(query);
            check(state.ready && !state.read, "blocking or repeated result read");
            state.read = true; reads++;
            return query * 100_000L;
        }
        public void delete(int query) {
            State state = states.get(query);
            check(!state.deleted, "query deleted twice");
            state.deleted = true; deletes++;
        }
        void readyAll() { for (State state : states.values()) if (state.issued) state.ready = true; }
    }

    private static void expectIllegal(Runnable operation) {
        try { operation.run(); }
        catch (IllegalStateException expected) { return; }
        throw new AssertionError("invalid query operation accepted");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

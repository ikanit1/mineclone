package com.mineclone;

import com.mineclone.data.SectionCodec;
import com.mineclone.data.VarInt;
import com.mineclone.data.VarLong;
import com.mineclone.save.ChunkSectionCodec;
import com.mineclone.save.ChunkSnapshot;
import com.mineclone.save.RunLengthCodec;
import com.mineclone.save.SaveFormat;
import com.mineclone.save.SaveManager;
import com.mineclone.sim.EntityStore;
import com.mineclone.sim.Participants;
import com.mineclone.sim.WorldClock;
import com.mineclone.sim.WorldEvents;
import com.mineclone.sim.WorldSession;
import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.ChunkLoader;
import com.mineclone.world.ChunkMesher;
import com.mineclone.world.ScheduledTicks;
import com.mineclone.world.World;
import com.mineclone.world.entity.MobSpawner;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.joml.Vector3f;

/**
 * BLK-04: a block asks for a tick in N world ticks and gets it — in order,
 * once, within a budget, and across unloading, saving and a game left closed
 * for hours.
 */
final class ScheduledTickTests {
    static void runAll(TestMain.Runner r) {
        r.run("scheduled ticks run by due time, ties in a fixed order", ScheduledTickTests::order);
        r.run("asking again for a cell and kind moves its tick instead of adding one", ScheduledTickTests::replacing);
        r.run("a run stops at its budget and a handler's new ask waits for the next run", ScheduledTickTests::budget);
        r.run("a chunk holds a bounded number of ticks", ScheduledTickTests::capacity);
        r.run("restored ticks keep unknown kinds and shift back when the clock is behind", ScheduledTickTests::restoreAndAdopt);
        r.run("ticks section round-trips relative dues and refuses malformed data", ScheduledTickTests::section);
        r.run("the world runs each world tick in turn, only for the block that asked", ScheduledTickTests::worldRuns);
        r.run("the world spends 512 ticks a world tick and the rest wait", ScheduledTickTests::worldBudget);
        r.run("a counter block ticks exactly N times in N intervals, through save and load", ScheduledTickTests::counterThroughSave);
        r.run("a chunk left for hours catches up at most two world days", ScheduledTickTests::catchUp);
    }

    private static void check(boolean ok, String why) {
        if (!ok)
            throw new AssertionError(why);
    }

    private static final int X = 8, Y = 40, Z = 8;

    /** Stone to y 40 and air above, made on the spot: no generator involved. */
    private static World emptyWorld(long seed) {
        return new World(seed) {
            @Override
            public Chunk generateDetached(int cx, int cz) {
                Chunk c = new Chunk(cx, cz);
                for (int x = 0; x < Chunk.SIZE_X; x++)
                    for (int z = 0; z < Chunk.SIZE_Z; z++)
                        for (int y = 0; y <= Y; y++)
                            c.set(x, y, z, BlockType.STONE);
                return c;
            }
        };
    }

    private static World around(World w) {
        for (int cx = -1; cx <= 1; cx++)
            for (int cz = -1; cz <= 1; cz++)
                w.getChunk(cx, cz);
        return w;
    }

    /** Counts its ticks and asks again every {@code interval}. */
    private static class Counter implements World.ScheduledTickHandler {
        final long interval;
        final List<Long> at = new ArrayList<>(), late = new ArrayList<>();

        Counter(long interval) {
            this.interval = interval;
        }

        @Override
        public void tick(World world, int x, int y, int z, BlockType block, byte meta, long lateBy) {
            at.add(world.scheduledTickTime());
            late.add(lateBy);
            if (interval > 0)
                world.scheduleTick(x, y, z, block, interval);
        }
    }

    // ------------------------------------------------------------ the heap

    private static void order() {
        ScheduledTicks t = new ScheduledTicks();
        Random random = new Random(5);
        Set<Integer> keys = new HashSet<>();
        List<long[]> expected = new ArrayList<>();
        while (expected.size() < 400) {
            int index = random.nextInt(ScheduledTicks.MAX_ENTRIES), kind = random.nextInt(256);
            long due = random.nextInt(40);
            if (keys.add(index << 8 | kind)) {
                check(t.schedule(index, kind, due), "a free chunk refused a tick");
                expected.add(new long[] { due, kind, index });
            }
        }
        expected.sort(Comparator.<long[]>comparingLong(e -> e[0]).thenComparingLong(e -> e[1]).thenComparingLong(e -> e[2]));
        List<long[]> ran = new ArrayList<>();
        int n = t.runDue(100, Integer.MAX_VALUE, (index, kind, lateBy) -> ran.add(new long[] { 100 - lateBy, kind, index }));
        check(n == 400 && ran.size() == 400 && t.isEmpty(), "every tick runs once: " + n);
        for (int i = 0; i < ran.size(); i++)
            check(Arrays.equals(ran.get(i), expected.get(i)), "tick " + i + " out of order: "
                    + Arrays.toString(ran.get(i)) + " instead of " + Arrays.toString(expected.get(i)));
    }

    private static void replacing() {
        ScheduledTicks t = new ScheduledTicks();
        t.schedule(5, 3, 50);
        t.schedule(5, 3, 20);
        check(t.size() == 1 && t.due(5, 3) == 20, "an earlier ask replaces the later one");
        List<Long> late = new ArrayList<>();
        check(t.runDue(19, 10, (i, k, l) -> late.add(l)) == 0, "a tick ran before its time");
        check(t.runDue(20, 10, (i, k, l) -> late.add(l)) == 1 && late.get(0) == 0, "the moved tick runs on time");
        check(t.runDue(100, 10, (i, k, l) -> late.add(l)) == 0, "the replaced time came back");

        t.schedule(6, 3, 20);
        t.schedule(6, 3, 50);
        check(t.runDue(49, 10, (i, k, l) -> late.add(l)) == 0, "a tick moved later still ran at its old time");
        check(t.runDue(50, 10, (i, k, l) -> late.add(l)) == 1, "a tick moved later ran");

        t.schedule(7, 3, 30);
        t.schedule(7, 3, 30);
        t.schedule(7, 4, 30);
        check(t.size() == 2, "the same ask twice is one tick, another kind is another: " + t.size());
        check(t.runDue(30, 10, (i, k, l) -> {}) == 2 && t.isEmpty(), "same cell, two kinds: two ticks");
    }

    private static void budget() {
        ScheduledTicks t = new ScheduledTicks();
        for (int i = 0; i < 10; i++)
            t.schedule(i, 1, 5);
        check(t.runDue(5, 3, (i, k, l) -> {}) == 3 && t.size() == 7, "the budget was not kept");
        int[] calls = { 0 };
        int ran = t.runDue(5, 100, (index, kind, lateBy) -> {
            calls[0]++;
            t.schedule(index, kind, 6);          // again next tick
        });
        check(ran == 7 && calls[0] == 7 && t.size() == 7, "a handler's ask ran in the same run: " + calls[0]);
        check(t.runDue(6, 100, (i, k, l) -> {}) == 7, "the asks for the next tick ran then");
    }

    private static void capacity() {
        ScheduledTicks t = new ScheduledTicks();
        for (int i = 0; i < ScheduledTicks.MAX_ENTRIES; i++)
            check(t.schedule(i, 0, i), "tick " + i + " refused below the bound");
        check(!t.schedule(0, 1, 5), "a full chunk took another tick");
        check(t.schedule(0, 0, 9), "a full chunk refused to move a tick it has");
        check(t.size() == ScheduledTicks.MAX_ENTRIES, "size changed: " + t.size());
    }

    private static void restoreAndAdopt() {
        ScheduledTicks t = new ScheduledTicks();
        t.restore(1000, new long[] { 1, 2, 1010, 3, 250, 990, 4, 2, 1500 }, 200);
        check(t.size() == 2 && !t.isEmpty() && t.origin() == 1000, "restore");
        check(t.runDue(5000, 10, (i, k, l) -> check(k != 250, "a kind this build does not know ran")) == 2,
                "the known kinds did not run");
        check(Arrays.equals(t.entries(), new long[] { 3, 250, 990 }), "a held kind was lost: " + Arrays.toString(t.entries()));

        t.restore(1000, new long[] { 1, 2, 1010, 3, 250, 990, 4, 2, 1500 }, 200);
        t.adopt(900);
        check(t.origin() == ScheduledTicks.ADOPTED, "adopting keeps the origin");
        check(Arrays.equals(t.entries(), new long[] { 3, 250, 890, 1, 2, 910, 4, 2, 1400 }),
                "a clock 100 behind the save shifts dues back 100: " + Arrays.toString(t.entries()));
        List<Long> late = new ArrayList<>();
        check(t.runDue(909, 10, (i, k, l) -> late.add(l)) == 0 && t.runDue(910, 10, (i, k, l) -> late.add(l)) == 1
                && late.get(0) == 0, "a shifted tick came at the wrong time");

        t.restore(1000, new long[] { 1, 2, 1010 }, 200);
        t.adopt(5000);
        check(t.due(1, 2) == 1010, "time since the save must count, not shift");
        t.runDue(5000, 10, (i, k, l) -> late.add(l));
        check(late.get(late.size() - 1) == 3990, "lateness after the absence: " + late);
    }

    // ------------------------------------------------------------ the save

    private static byte[] body(ChunkSnapshot s) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            ChunkSectionCodec.write(out, s);
        }
        return bytes.toByteArray();
    }

    private static ChunkSnapshot read(byte[] body) throws IOException {
        return ChunkSectionCodec.read(new DataInputStream(new ByteArrayInputStream(body)), 0, 0);
    }

    private static ChunkSnapshot snapshot(long ticksAt, long[] ticks) {
        return new ChunkSnapshot(0, 0, new byte[SaveFormat.CHUNK_VOLUME], new byte[SaveFormat.CHUNK_VOLUME],
                Map.of(), Map.of(), List.of(), Map.of("future:thing", new byte[] { 4 }), ticksAt, ticks);
    }

    /** A chunk body with empty containers and the given ticks payload. */
    private static byte[] withTicks(byte[] payload) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            RunLengthCodec.write(out, new byte[SaveFormat.CHUNK_VOLUME]);
            RunLengthCodec.write(out, new byte[SaveFormat.CHUNK_VOLUME]);
            Map<String, byte[]> sections = new LinkedHashMap<>();
            sections.put("chests", new byte[4]);
            sections.put("furnaces", new byte[4]);
            sections.put("items", new byte[4]);
            sections.put("ticks", payload);
            SectionCodec.write(out, sections);
        }
        return bytes.toByteArray();
    }

    @FunctionalInterface private interface Payload { void write(DataOutputStream out) throws IOException; }

    private static byte[] payload(Payload p) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            p.write(out);
        }
        return bytes.toByteArray();
    }

    private static void section() throws Exception {
        long far = 3 * WorldClock.TICKS_PER_DAY;
        long[] ticks = { 5, 2, 900, 32767, 255, 1000 + far, 0, 0, 1000 };
        ChunkSnapshot back = read(body(snapshot(1000, ticks)));
        check(back.ticksAt == 1000 && Arrays.equals(back.ticks, ticks), "ticks changed: " + Arrays.toString(back.ticks));
        check(Arrays.equals(back.extra.get("future:thing"), new byte[] { 4 }) && !back.extra.containsKey("ticks"),
                "the ticks section leaked into the opaque ones, or those were lost");

        ChunkSnapshot none = read(body(snapshot(0, new long[0])));
        check(none.ticks.length == 0 && !none.extra.containsKey("ticks"), "a chunk without ticks");
        check(body(snapshot(0, new long[0])).length < body(snapshot(1000, ticks)).length,
                "an empty ticks section was written");
        // Relative dues: a tick 20 ahead costs a byte or two, whatever the world's age.
        int young = body(snapshot(20, new long[] { 1, 1, 40 })).length;
        int old = body(snapshot(ScheduledTicks.MAX_DUE - 100, new long[] { 1, 1, ScheduledTicks.MAX_DUE - 80 })).length;
        check(old - young <= 6, "dues are stored absolute: " + young + " vs " + old);

        Map<String, byte[]> bad = new LinkedHashMap<>();
        bad.put("duplicate", payload(o -> {
            VarLong.write(o, 10); VarInt.write(o, 2);
            for (int i = 0; i < 2; i++) { VarInt.write(o, 7); o.writeByte(1); VarLong.write(o, VarLong.zigzag(3)); }
        }));
        bad.put("cell outside the chunk", payload(o -> {
            VarLong.write(o, 10); VarInt.write(o, 1);
            VarInt.write(o, SaveFormat.CHUNK_VOLUME); o.writeByte(1); VarLong.write(o, 0);
        }));
        bad.put("too many", payload(o -> { VarLong.write(o, 10); VarInt.write(o, ScheduledTicks.MAX_ENTRIES + 1); }));
        bad.put("before the world began", payload(o -> {
            VarLong.write(o, 10); VarInt.write(o, 1);
            VarInt.write(o, 7); o.writeByte(1); VarLong.write(o, VarLong.zigzag(-11));
        }));
        bad.put("trailing bytes", payload(o -> { VarLong.write(o, 10); VarInt.write(o, 0); o.writeByte(0); }));
        bad.put("truncated", payload(o -> { VarLong.write(o, 10); VarInt.write(o, 1); VarInt.write(o, 7); }));
        for (Map.Entry<String, byte[]> e : bad.entrySet()) {
            try {
                read(withTicks(e.getValue()));
                throw new AssertionError("a malformed ticks section was accepted: " + e.getKey());
            } catch (IOException expected) {
                // refused
            }
        }
        check(read(withTicks(payload(o -> { VarLong.write(o, 10); VarInt.write(o, 0); }))).ticks.length == 0,
                "an empty section is fine");
    }

    // ------------------------------------------------------------ the world

    private static void worldRuns() {
        World mirror = around(emptyWorld(3));
        check(!mirror.scheduleTick(X, Y, Z, BlockType.STONE, 5), "a world nobody simulates took a tick");

        World w = around(emptyWorld(3));
        w.simulate(com.mineclone.world.behavior.DropSink.NONE, 100);
        Counter counter = new Counter(10);
        w.setScheduledTickHandler(counter);
        check(w.scheduleTick(X, Y, Z, BlockType.STONE, 10), "a tick was refused");
        check(!w.scheduleTick(X, Chunk.SIZE_Y, Z, BlockType.STONE, 10) && !w.scheduleTick(500, Y, Z, BlockType.STONE, 10),
                "a tick outside the loaded world was taken");
        check(w.pendingScheduledTicks() == 1, "pending: " + w.pendingScheduledTicks());
        check(w.runScheduledTicks(109, 512, Long.MAX_VALUE) == 0, "ran early");
        check(w.runScheduledTicks(110, 512, Long.MAX_VALUE) == 1 && counter.late.get(0) == 0, "not on time");
        // One frame spanning thirty world ticks: three ticks, each on its own world tick.
        check(w.runScheduledTicks(140, 512, Long.MAX_VALUE) == 3, "a long frame lost ticks: " + counter.at);
        check(counter.at.equals(List.of(110L, 120L, 130L, 140L)) && counter.late.stream().allMatch(l -> l == 0),
                "ticks off their world ticks: " + counter.at + " late " + counter.late);

        // A stall of a thousand world ticks: the last forty are worked, the tick runs late, not lost.
        w.runScheduledTicks(1140, 512, Long.MAX_VALUE);
        check(counter.at.size() == 8 && counter.late.get(4) == 1101 - 150, "after a stall: " + counter.at + " " + counter.late);
        // Lateness is capped by the caller.
        w.scheduleTick(X + 1, Y, Z, BlockType.STONE, 1);
        w.setScheduledTickHandler(new Counter(0) {
            @Override
            public void tick(World world, int x, int y, int z, BlockType block, byte meta, long lateBy) {
                if (x == X + 1) check(lateBy == 7, "lateness not capped: " + lateBy);
                super.tick(world, x, y, z, block, meta, lateBy);
            }
        });
        w.runScheduledTicks(1500, 512, 7);

        // The block that asked is gone: its tick does not reach the new one.
        Counter gone = new Counter(0);
        w.setScheduledTickHandler(gone);
        w.scheduleTick(X, Y, Z, BlockType.STONE, 5);
        w.setBlock(X, Y, Z, BlockType.PLANKS);
        w.runScheduledTicks(1510, 512, Long.MAX_VALUE);
        check(gone.at.isEmpty() && w.pendingScheduledTicks() == 0, "a tick reached the block that replaced its own");

        // A neighbour chunk away: the tick waits, then runs late when it is back.
        w.setBlock(X, Y, Z, BlockType.STONE);
        w.scheduleTick(X, Y, Z, BlockType.STONE, 5);
        check(w.removeChunk(1, 0) != null, "the east chunk was not there");
        w.runScheduledTicks(1520, 512, Long.MAX_VALUE);
        check(gone.at.isEmpty() && w.pendingScheduledTicks() == 1, "ran without a neighbour");
        w.getChunk(1, 0);
        w.runScheduledTicks(1530, 512, Long.MAX_VALUE);
        check(gone.at.equals(List.of(1521L)) && gone.late.equals(List.of(6L)),
                "the tick did not run, late, with the neighbour back: " + gone.at + " " + gone.late);

        // Without a handler the block's behaviour gets it (stone does nothing).
        w.setScheduledTickHandler(null);
        w.scheduleTick(X, Y, Z, BlockType.STONE, 1);
        check(w.runScheduledTicks(1531, 512, Long.MAX_VALUE) == 1, "the behaviour path did not run");

        // An ask for no delay still waits a world tick: a block asking every tick cannot spin a run.
        int[] calls = { 0 };
        w.setScheduledTickHandler((world, x, y, z, block, meta, lateBy) -> {
            calls[0]++;
            world.scheduleTick(x, y, z, block, 0);
        });
        w.scheduleTick(X, Y, Z, BlockType.STONE, -5);
        check(w.runScheduledTicks(1531, 512, Long.MAX_VALUE) == 0, "a tick asked for now ran in the past");
        check(w.runScheduledTicks(1541, 512, Long.MAX_VALUE) == 10 && calls[0] == 10,
                "a block asking every tick ran " + calls[0] + " times in ten world ticks");
    }

    private static void worldBudget() {
        World w = emptyWorld(4);
        for (int cx = -2; cx <= 2; cx++)
            for (int cz = -1; cz <= 1; cz++)
                w.getChunk(cx, cz);
        w.simulate(com.mineclone.world.behavior.DropSink.NONE, 0);
        Counter counter = new Counter(0);
        w.setScheduledTickHandler(counter);
        // Two hundred cells in each of three chunks, all due at world tick 3.
        for (int i = 0; i < 600; i++) {
            int cell = i / 3;
            check(w.scheduleTick((i % 3 - 1) * Chunk.SIZE_X + cell % Chunk.SIZE_X, Y, cell / Chunk.SIZE_X, BlockType.STONE, 3),
                    "tick " + i + " refused");
        }
        check(w.pendingScheduledTicks() == 600, "pending " + w.pendingScheduledTicks());
        int ran = w.runScheduledTicks(3, WorldSession.TICK_BUDGET, Long.MAX_VALUE);
        check(ran == WorldSession.TICK_BUDGET && w.pendingScheduledTicks() == 600 - ran, "one world tick ran " + ran);
        int later = w.runScheduledTicks(4, WorldSession.TICK_BUDGET, Long.MAX_VALUE);
        check(later == 600 - ran && w.pendingScheduledTicks() == 0, "the rest did not run next tick: " + later);
        check(counter.late.stream().filter(l -> l == 1).count() == later, "the rest were not one tick late");
    }

    // ------------------------------------------------------------ acceptance

    private static final int INTERVAL = 20;

    private record Saved(Path root, SaveManager save, long savedAt) {}

    /**
     * A counter asks every 20 ticks, ticks five times, is saved between two
     * ticks with a stranger's tick of a kind this build lacks beside it.
     */
    private static Saved saveCounter() throws Exception {
        Path root = Files.createTempDirectory("mineclone-scheduled-ticks-");
        Files.createDirectories(root.resolve("world/chunks"));
        SaveManager save = new SaveManager(root.toFile());
        World a = around(emptyWorld(11));
        WorldClock clock = WorldClock.synced(0.5, 5000);
        WorldSession session = new WorldSession(a, clock, new MobSpawner(1L), new EntityStore(),
                new Participants(), () -> new Vector3f(8f, 42f, 8f), WorldEvents.NONE);
        Counter counter = new Counter(INTERVAL);
        a.setScheduledTickHandler(counter);
        check(a.scheduleTick(X, Y, Z, BlockType.STONE, INTERVAL), "the counter's first ask");
        for (int i = 0; i < 5 * INTERVAL + 7; i++) {
            clock.advance(1.0 / WorldClock.TICKS_PER_SECOND);
            session.tickScheduled();
        }
        check(counter.at.size() == 5 && counter.at.get(0) == 5020 && counter.at.get(4) == 5100,
                "five ticks in five intervals: " + counter.at);
        ChunkSnapshot s = session.snapshotChunk(a.getChunkIfExists(0, 0));
        check(s != null && s.ticksAt == 5107 && Arrays.equals(s.ticks, new long[] { Chunk.idx(X, Y, Z), BlockType.STONE.ordinal(), 5120 }),
                "snapshot: " + (s == null ? null : s.ticksAt + " " + Arrays.toString(s.ticks)));
        long[] withStranger = Arrays.copyOf(s.ticks, 6);
        withStranger[3] = 7; withStranger[4] = 250; withStranger[5] = 5600;
        save.saveChunkAsync("world", new ChunkSnapshot(0, 0, s.blocks, s.meta, s.chests, s.furnaces, s.items,
                s.extra, s.ticksAt, withStranger));
        save.flushAndAwait();
        return new Saved(root, save, 5107);
    }

    /** A fresh game on the saved world whose clock reads {@code now}; the counter continues. */
    private static long[] resume(Saved saved, long now, int worldTicks, Counter counter) {
        World b = emptyWorld(11);
        ChunkLoader loader = new ChunkLoader(b, new ChunkMesher(b), saved.save(), "world");
        loader.setMeshing(false);
        try {
            for (int cx = -1; cx <= 1; cx++)
                for (int cz = -1; cz <= 1; cz++)
                    loader.loadNow(cx, cz);
            // The session comes after the chunks: restored ticks reach it all the same.
            WorldClock clock = WorldClock.synced(0.5, now);
            WorldSession session = new WorldSession(b, clock, new MobSpawner(1L), new EntityStore(),
                    new Participants(), () -> new Vector3f(8f, 42f, 8f), WorldEvents.NONE);
            b.setScheduledTickHandler(counter);
            for (int i = 0; i < worldTicks; i++) {
                clock.advance(1.0 / WorldClock.TICKS_PER_SECOND);
                session.tickScheduled();
            }
            ChunkSnapshot again = session.snapshotChunk(b.getChunkIfExists(0, 0));
            return again == null ? null : again.ticks;
        } finally {
            loader.shutdown();
        }
    }

    private static void delete(Saved saved) throws IOException {
        saved.save().flushAndAwait();
        try (var paths = Files.walk(saved.root())) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                Files.delete(path);
        }
    }

    private static void counterThroughSave() throws Exception {
        Saved saved = saveCounter();
        try {
            counterThroughSave(saved);
        } finally {
            delete(saved);
        }
    }

    private static void counterThroughSave(Saved saved) {
        Counter counter = new Counter(INTERVAL);
        // The game is closed at 5107 and opened on the same clock; 93 more ticks reach 5200.
        long[] after = resume(saved, saved.savedAt(), 93, counter);
        check(counter.at.equals(List.of(5120L, 5140L, 5160L, 5180L, 5200L)), "five more, on time: " + counter.at);
        check(counter.late.stream().allMatch(l -> l == 0), "late after a load: " + counter.late);
        // Ten ticks in ten intervals in all, and the stranger's tick is written back untouched.
        check(after != null && Arrays.equals(after, new long[] { Chunk.idx(X, Y, Z), BlockType.STONE.ordinal(), 5220, 7, 250, 5600 }),
                "ticks after the second game: " + Arrays.toString(after));

        // An edit before the world's first run saves the restored ticks as they came.
        World b = emptyWorld(11);
        ChunkLoader loader = new ChunkLoader(b, new ChunkMesher(b), saved.save(), "world");
        loader.setMeshing(false);
        try {
            loader.loadNow(0, 0);
            WorldSession session = new WorldSession(b, WorldClock.synced(0.5, 60), new MobSpawner(1L), new EntityStore(),
                    new Participants(), () -> new Vector3f(8f, 42f, 8f), WorldEvents.NONE);
            b.setBlock(X + 2, Y + 1, Z, BlockType.PLANKS);
            ChunkSnapshot early = session.snapshotChunk(b.getChunkIfExists(0, 0));
            check(early != null && early.ticksAt == saved.savedAt()
                    && Arrays.equals(early.ticks, new long[] { Chunk.idx(X, Y, Z), BlockType.STONE.ordinal(), 5120, 7, 250, 5600 }),
                    "unadopted ticks lost their origin: " + (early == null ? null : early.ticksAt));
        } finally {
            loader.shutdown();
        }

        // A level whose clock went back (restored from an older backup): the tick is
        // not pushed hundreds of ticks away, it comes 13 ticks after the load.
        Counter behind = new Counter(INTERVAL);
        resume(saved, 60, 13, behind);
        check(behind.at.equals(List.of(73L)) && behind.late.equals(List.of(0L)), "a clock behind the save: " + behind.at);
    }

    private static void catchUp() throws Exception {
        Saved saved = saveCounter();
        try {
            catchUp(saved);
        } finally {
            delete(saved);
        }
    }

    private static void catchUp(Saved saved) {
        // Half an hour away: the counter learns exactly how late it is.
        long halfHour = 30 * 60 * WorldClock.TICKS_PER_SECOND;
        Counter counter = new Counter(INTERVAL);
        resume(saved, saved.savedAt() + halfHour, 1, counter);
        check(counter.late.equals(List.of(saved.savedAt() + halfHour + 1 - 5120)), "half an hour: " + counter.late);
        // Three hours away: more than two world days pass; the counter is told two.
        long threeHours = 3 * 3600 * WorldClock.TICKS_PER_SECOND;
        check(threeHours > WorldSession.MAX_TICK_LATENESS, "three hours are more than the cap");
        Counter away = new Counter(INTERVAL);
        resume(saved, saved.savedAt() + threeHours, 2 * INTERVAL + 1, away);
        check(away.late.get(0) == WorldSession.MAX_TICK_LATENESS, "the catch-up is not capped: " + away.late);
        check(away.late.subList(1, away.late.size()).stream().allMatch(l -> l == 0) && away.at.size() == 3,
                "after catching up the counter keeps time: " + away.at + " " + away.late);
        check(WorldSession.MAX_TICK_LATENESS == 2 * WorldClock.TICKS_PER_DAY
                && Math.abs(WorldClock.TICKS_PER_DAY - 25_133) <= 1, "a world day is " + WorldClock.TICKS_PER_DAY);
    }
}

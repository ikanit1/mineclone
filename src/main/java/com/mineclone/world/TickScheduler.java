package com.mineclone.world;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The world's side of scheduled ticks (BLK-04): which chunks have ticks
 * waiting, the world tick asks count from, and the run of each world tick
 * under a budget. Only a world simulated here has one; a guest's mirror
 * schedules nothing.
 *
 * <p>Each world tick since the last run is worked in turn, so a block that
 * asks again every N ticks ticks exactly once per N even when a frame spans
 * several world ticks. A tick runs when its chunk and the four beside it are
 * loaded (a handler looks at neighbours) and the block that asked still
 * stands in its cell.
 */
final class TickScheduler {
    /** World ticks a run works through at most; beyond that the ticks run late, not lost. */
    static final int MAX_STEPS = 40;

    private final World world;
    /** Chunks with ticks waiting, in the order they first asked. */
    private final List<Chunk> ticking = new ArrayList<>();
    private World.ScheduledTickHandler handler = World.ScheduledTickHandler.BEHAVIOURS;
    private long now;
    /** One handler for every chunk, pointed at the chunk being run: no allocation a tick. */
    private final Cells cells = new Cells();

    TickScheduler(World world, long now) {
        this.world = world;
        this.now = Math.max(0, now);
    }

    long now() {
        return now;
    }

    void handler(World.ScheduledTickHandler h) {
        handler = h == null ? World.ScheduledTickHandler.BEHAVIOURS : h;
    }

    boolean schedule(Chunk c, int index, BlockType kind, long delay) {
        adopt(c);
        if (!c.scheduledTicks().schedule(index, kind.ordinal(), now + Math.max(1, delay)))
            return false;
        c.modified = true;
        list(c);
        return true;
    }

    /** Ticks waiting in the listed chunks. */
    int pending() {
        int n = 0;
        for (Chunk c : ticking)
            n += c.hasScheduledTicks() ? c.scheduledTicks().size() : 0;
        return n;
    }

    int run(long until, int budget, long maxLate) {
        for (Chunk c; (c = world.tickArrivals.poll()) != null; ) {
            if (world.getChunkIfExists(c.cx, c.cz) != c)
                continue;
            adopt(c);
            list(c);
        }
        if (until <= now)
            return 0;
        long from = Math.max(now + 1, until - MAX_STEPS + 1);
        int ran = 0;
        for (long t = from; t <= until; t++) {
            now = t;
            if (!ticking.isEmpty())
                ran += step(t, budget, maxLate);
        }
        now = until;
        return ran;
    }

    private int step(long t, int budget, long maxLate) {
        int left = budget;
        for (int i = 0; i < ticking.size(); i++) {
            Chunk c = ticking.get(i);
            if (world.getChunkIfExists(c.cx, c.cz) != c || !c.hasScheduledTicks()) {
                c.tickListed = false;
                ticking.remove(i--);
                continue;
            }
            if (!neighboursLoaded(c))
                continue;
            if (left == 0) {
                // Out of budget: the chunks served first go last next tick.
                Collections.rotate(ticking, -i);
                break;
            }
            cells.chunk = c;
            cells.maxLate = maxLate;
            left -= c.scheduledTicks().runDue(t, left, cells);
        }
        cells.chunk = null;
        return budget - left;
    }

    /** Turns a chunk's due tick into a call on the block that asked. */
    private final class Cells implements ScheduledTicks.Handler {
        Chunk chunk;
        long maxLate;

        @Override
        public void tick(int index, int kind, long lateBy) {
            Chunk c = chunk;
            int lx = index % Chunk.SIZE_X, lz = (index / Chunk.SIZE_X) % Chunk.SIZE_Z;
            int y = index / (Chunk.SIZE_X * Chunk.SIZE_Z);
            BlockType block = c.get(lx, y, lz);
            if (block.ordinal() != kind)
                return;                          // the block that asked is gone
            c.modified = true;
            handler.tick(world, c.cx * Chunk.SIZE_X + lx, y, c.cz * Chunk.SIZE_Z + lz, block,
                    c.getMeta(lx, y, lz), Math.min(lateBy, maxLate));
        }
    }

    private boolean neighboursLoaded(Chunk c) {
        return world.getChunkIfExists(c.cx + 1, c.cz) != null && world.getChunkIfExists(c.cx - 1, c.cz) != null
                && world.getChunkIfExists(c.cx, c.cz + 1) != null && world.getChunkIfExists(c.cx, c.cz - 1) != null;
    }

    private void adopt(Chunk c) {
        if (c.hasScheduledTicks())
            c.scheduledTicks().adopt(now);
    }

    private void list(Chunk c) {
        if (!c.tickListed && c.hasScheduledTicks()) {
            c.tickListed = true;
            ticking.add(c);
        }
    }
}

package com.mineclone.world;

import com.mineclone.world.behavior.BlockBehavior;
import com.mineclone.world.behavior.Behaviors;
import com.mineclone.world.behavior.DropSink;
import java.util.Iterator;
import java.util.LinkedHashSet;

/**
 * Cells whose support may have gone (BLK-03): a torch whose wall was dug
 * out, the upper half of a door whose lower half broke.
 *
 * <p>A change queues the cell and its six neighbours — only those whose
 * block can fall at all ({@link BlockBehavior#needsSupport}), because water
 * alone moves hundreds of cells a tick. The queue is worked at the end of the
 * world's tick, {@link #BUDGET} cells at a time; a collapse queues its own
 * neighbours, so a cascade continues on the next tick instead of stalling this
 * one. A cell is queued once however often it changes.
 */
public final class NeighbourUpdates {
    /** Cells checked per tick. */
    public static final int BUDGET = 256;

    private final LinkedHashSet<Long> queue = new LinkedHashSet<>();

    void around(World world, int x, int y, int z) {
        offer(world, x, y, z);
        offer(world, x + 1, y, z);
        offer(world, x - 1, y, z);
        offer(world, x, y + 1, z);
        offer(world, x, y - 1, z);
        offer(world, x, y, z + 1);
        offer(world, x, y, z - 1);
    }

    private void offer(World world, int x, int y, int z) {
        if (y < 0 || y >= Chunk.SIZE_Y)
            return;
        if (Behaviors.of(world.getBlock(x, y, z)).needsSupport())
            queue.add(key(x, y, z));
    }

    /** Cells waiting for a check. */
    public int pending() {
        return queue.size();
    }

    /**
     * Checks up to {@code budget} queued cells; a block that cannot stay
     * collapses and drops into {@code drops}.
     *
     * @return cells checked
     */
    int process(World world, DropSink drops, int budget) {
        int done = 0;
        while (done < budget && !queue.isEmpty()) {
            Iterator<Long> head = queue.iterator();
            long k = head.next();
            head.remove();
            done++;
            int x = (int) (k >> 38), z = (int) ((k << 26) >> 38), y = (int) (k & 0xFFF);
            // A support across a chunk border that has since been unloaded
            // reads as air; the check waits for the next change instead.
            if (!loadedAround(world, x, z))
                continue;
            BlockBehavior b = Behaviors.of(world.getBlock(x, y, z));
            byte meta = world.getBlockMeta(x, y, z);
            if (b.needsSupport() && !b.canSurvive(world, x, y, z, meta))
                b.collapse(world, x, y, z, meta, drops);
        }
        return done;
    }

    private static boolean loadedAround(World world, int x, int z) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                if ((dx == 0 || dz == 0) && world.getChunkIfExists(
                        Math.floorDiv(x + dx, Chunk.SIZE_X), Math.floorDiv(z + dz, Chunk.SIZE_Z)) == null)
                    return false;
        return true;
    }

    static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | (y & 0xFFF);
    }
}

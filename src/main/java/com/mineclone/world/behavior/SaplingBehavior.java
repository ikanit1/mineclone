package com.mineclone.world.behavior;

import com.mineclone.sim.WorldClock;
import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.ScheduledTicks;
import com.mineclone.world.World;
import com.mineclone.world.gen.TreeShapes;

/** Replenishable trees; a whole loaded, writable crown is validated before growth. */
public final class SaplingBehavior implements BlockBehavior {
    /** Natural scar seedlings preserve player blocks and pause with Living World. */
    public static final int NATURAL_SCAR = 0x4;

    @Override public boolean needsSupport() { return true; }
    @Override public boolean canSurvive(World w, int x, int y, int z, byte meta) {
        return w.getBlock(x, y - 1, z).isSoil();
    }
    @Override public boolean canPlaceAt(World w, int x, int y, int z, PlaceContext context) {
        return canSurvive(w, x, y, z, (byte) 0);
    }
    @Override public byte placementMeta(PlaceContext context) {
        // Ctrl-pick carries world state, but a player's new tree keeps only its species.
        return (byte) (context.carriesMeta() ? context.carriedMeta() & 3 : 0);
    }

    /** The unchanged deterministic player-sapling delay, also used by scar seedlings. */
    public static long growthDelay(World w, int x, int y, int z) {
        return 6000 + Math.floorMod(hash(w, x, y, z), 6001);
    }

    private static long hash(World w, int x, int y, int z) {
        return w.seed ^ x * 341873128712L ^ z * 132897987541L ^ y * 524287L;
    }

    private static boolean writable(World w, int x, int y, int z) {
        Chunk chunk = y < 0 || y >= Chunk.SIZE_Y ? null
                : w.getChunkIfExists(Math.floorDiv(x, Chunk.SIZE_X), Math.floorDiv(z, Chunk.SIZE_Z));
        return chunk != null && !chunk.isReadOnly();
    }

    /** Daily replay plants before the dispatcher catches up; count from the logical birth. */
    public static void scheduleNatural(World w, int x, int y, int z, long logicalDay, WorldClock clock) {
        if (!writable(w, x, y, z) || w.getBlock(x, y, z) != BlockType.SAPLING) return;
        long born = clock.logicalDayTicks(logicalDay);
        long delay = growthDelay(w, x, y, z);
        long due = born > ScheduledTicks.MAX_DUE - delay ? ScheduledTicks.MAX_DUE : born + delay;
        w.scheduleTickAt(x, y, z, BlockType.SAPLING, due);
    }

    @Override public void onPlaced(World w, int x, int y, int z, byte meta) {
        if (writable(w, x, y, z)) w.scheduleTick(x, y, z, BlockType.SAPLING, growthDelay(w, x, y, z));
    }

    @Override public void scheduledTick(World w, int x, int y, int z, byte meta, long lateBy) {
        if (!writable(w, x, y, z) || w.getBlock(x, y, z) != BlockType.SAPLING) return;
        boolean natural = (meta & NATURAL_SCAR) != 0;
        if (natural && !w.livingWorldEnabled()) {
            onPlaced(w, x, y, z, meta);
            return;
        }
        if (!canSurvive(w, x, y, z, meta)) {
            collapse(w, x, y, z, meta, w.dropSink());
            return;
        }
        if (w.plantLight(x, y, z) < 9) { onPlaced(w, x, y, z, meta); return; }
        var cells = TreeShapes.cells(Math.min(2, meta & 3), x, y - 1, z, hash(w, x, y, z));
        if (cells.isEmpty()) { onPlaced(w, x, y, z, meta); return; }
        for (var cell : cells) {
            if (!writable(w, cell.x(), cell.y(), cell.z())) { onPlaced(w, x, y, z, meta); return; }
            if (natural && !w.canGrowNaturalTreeAt(cell.x(), cell.y(), cell.z())) {
                onPlaced(w, x, y, z, meta);
                return;
            }
            BlockType block = w.getBlock(cell.x(), cell.y(), cell.z());
            boolean own = cell.x() == x && cell.y() == y && cell.z() == z && block == BlockType.SAPLING;
            if (block != BlockType.AIR && !(natural ? own : block == BlockType.SAPLING || block == BlockType.LEAVES)) {
                onPlaced(w, x, y, z, meta);
                return;
            }
        }
        w.batchBlocks(() -> {
            for (var cell : cells)
                w.setBlock(cell.x(), cell.y(), cell.z(), cell.block(),
                        (byte) (cell.block() == BlockType.LEAVES ? meta & 3 : 0));
        });
    }
}

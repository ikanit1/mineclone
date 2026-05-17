package com.mineclone.world;

import java.util.*;

/**
 * Incremental water flow simulator.
 *
 * Each tick advances the state by ONE step:
 *  - Spread:  every existing water cell tries to fill an immediate empty
 *             neighbour (down preserves level; sideways adds +1, capped at 7).
 *  - Decay:   a flow cell that has lost upstream support is removed.
 *
 * Result: a wavefront expands outward from sources at one cell per tick,
 * and a stranded plume contracts inward at one cell per tick — visible flow
 * instead of an instantaneous fill.
 *
 * "Upstream support" for a flow cell at level L means: a flow neighbour at
 * level < L horizontally, OR a source horizontally, OR any water directly
 * above (vertical column). Otherwise the cell is orphaned and removed.
 */
public final class WaterSimulator {
    private static final Set<Long> activeChunks = new HashSet<>();
    private static boolean seededLoadedChunks = false;

    private WaterSimulator() { }

    public static void activateChunkIfWater(World world, int cx, int cz) {
        Chunk chunk = world.getChunkIfExists(cx, cz);
        if (chunk != null && chunkHasWater(chunk))
            activeChunks.add(World.key(cx, cz));
    }

    public static void activateAround(int wx, int wz) {
        int cx = Math.floorDiv(wx, Chunk.SIZE_X);
        int cz = Math.floorDiv(wz, Chunk.SIZE_Z);
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                activeChunks.add(World.key(cx + dx, cz + dz));
    }

    public static void forgetChunk(long key) {
        activeChunks.remove(key);
    }

    public static void tick(World world) {
        if (activeChunks.isEmpty() && !seededLoadedChunks) {
            seedLoadedWaterChunks(world);
            seededLoadedChunks = true;
        }
        if (activeChunks.isEmpty())
            return;

        Set<Long> toRemove = new HashSet<>(256);
        Map<Long, Integer> toAdd = new HashMap<>(256);
        Set<Long> scan = new HashSet<>(activeChunks);
        activeChunks.clear();
        int[][] sides = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };

        for (long key : scan) {
            int cx = (int) (key >> 32);
            int cz = (int) key;
            Chunk chunk = world.getChunkIfExists(cx, cz);
            if (chunk == null)
                continue;
            int bx = chunk.cx * Chunk.SIZE_X;
            int bz = chunk.cz * Chunk.SIZE_Z;
            for (int lx = 0; lx < Chunk.SIZE_X; lx++) {
                for (int y = 0; y < Chunk.SIZE_Y; y++) {
                    for (int lz = 0; lz < Chunk.SIZE_Z; lz++) {
                        BlockType b = chunk.get(lx, y, lz);
                        if (b != BlockType.WATER && b != BlockType.WATER_FLOW) continue;

                        int wx = bx + lx, wy = y, wz = bz + lz;
                        int myLevel = (b == BlockType.WATER) ? 0 : (chunk.getMeta(lx, y, lz) & 0xF);

                        // Flow cells without support are scheduled for removal.
                        if (b == BlockType.WATER_FLOW && !hasSupport(world, wx, wy, wz, myLevel, sides)) {
                            toRemove.add(pack(wx, wy, wz));
                            continue;
                        }

                        // Try to spread by one step.
                        trySpread(world, wx, wy, wz, myLevel, toAdd, sides);
                    }
                }
            }
        }

        // Apply removals first so freed cells can be re-filled in the same tick
        // if a different source still reaches them next pass.
        for (long pk : toRemove) {
            int wx = unpackX(pk), wy = unpackY(pk), wz = unpackZ(pk);
            setBlockSafe(world, wx, wy, wz, BlockType.AIR, (byte) 0);
        }

        // Apply additions. Skip cells we just removed (they might re-appear next tick).
        for (Map.Entry<Long, Integer> e : toAdd.entrySet()) {
            long pk = e.getKey();
            if (toRemove.contains(pk)) continue;
            int wx = unpackX(pk), wy = unpackY(pk), wz = unpackZ(pk);
            // Re-check the cell is still AIR (the world may have changed between scan and apply)
            if (world.getBlock(wx, wy, wz) != BlockType.AIR) continue;
            setBlockSafe(world, wx, wy, wz, BlockType.WATER_FLOW, (byte) (int) e.getValue());
        }
    }

    private static void seedLoadedWaterChunks(World world) {
        for (Chunk chunk : world.getLoadedChunks()) {
            if (chunkHasWater(chunk))
                activeChunks.add(World.key(chunk.cx, chunk.cz));
        }
    }

    private static boolean chunkHasWater(Chunk chunk) {
        for (int lx = 0; lx < Chunk.SIZE_X; lx++) {
            for (int y = 0; y < Chunk.SIZE_Y; y++) {
                for (int lz = 0; lz < Chunk.SIZE_Z; lz++) {
                    BlockType b = chunk.get(lx, y, lz);
                    if (b == BlockType.WATER || b == BlockType.WATER_FLOW)
                        return true;
                }
            }
        }
        return false;
    }

    /**
     * A flow cell at {@code myLevel} is supported by:
     *   - any water directly above (it's part of a vertical column / waterfall), or
     *   - a horizontal source neighbour, or
     *   - a horizontal flow neighbour at a STRICTLY LOWER level (closer to source).
     */
    private static boolean hasSupport(World world, int wx, int wy, int wz, int myLevel, int[][] sides) {
        BlockType above = world.getBlock(wx, wy + 1, wz);
        if (above == BlockType.WATER || above == BlockType.WATER_FLOW) return true;
        for (int[] d : sides) {
            BlockType nb = world.getBlock(wx + d[0], wy, wz + d[1]);
            if (nb == BlockType.WATER) return true;
            if (nb == BlockType.WATER_FLOW) {
                int nbLevel = world.getBlockMeta(wx + d[0], wy, wz + d[1]) & 0xF;
                if (nbLevel < myLevel) return true;
            }
        }
        return false;
    }

    /**
     * One step of spread.
     *
     * Down: any cell can fall straight down regardless of horizontal level cap.
     * Sideways: every cell at level &lt; 7 spreads to AIR neighbours… EXCEPT
     * when the cell is part of a falling column (water above AND still able to
     * fall below). Falling-column cells ONLY continue the fall — they do not
     * sprout horizontal arms at every height. Once the fall hits a solid floor
     * (below is no longer AIR), the bottom cell pools sideways normally. This
     * matches Minecraft, where a waterfall is a clean vertical column.
     *
     * Multiple sources may target the same empty cell; the lowest proposed
     * level wins.
     */
    private static void trySpread(World world, int wx, int wy, int wz, int myLevel,
                                  Map<Long, Integer> toAdd, int[][] sides) {
        BlockType below = world.getBlock(wx, wy - 1, wz);
        boolean canFall = (below == BlockType.AIR);
        if (canFall) {
            long pk = pack(wx, wy - 1, wz);
            int newLevel = (myLevel == 0) ? 1 : myLevel;
            Integer prev = toAdd.get(pk);
            if (prev == null || newLevel < prev) toAdd.put(pk, newLevel);
        }

        // A cell with water above AND open air below is mid-fall — skip sideways.
        if (canFall) {
            BlockType above = world.getBlock(wx, wy + 1, wz);
            if (above == BlockType.WATER || above == BlockType.WATER_FLOW) return;
        }

        if (myLevel >= 7) return;
        int sideLevel = myLevel + 1;
        for (int[] d : sides) {
            int nx = wx + d[0], nz = wz + d[1];
            BlockType nb = world.getBlock(nx, wy, nz);
            if (nb != BlockType.AIR) continue;
            long pk = pack(nx, wy, nz);
            Integer prev = toAdd.get(pk);
            if (prev == null || sideLevel < prev) toAdd.put(pk, sideLevel);
        }
    }

    private static void setBlockSafe(World world, int wx, int wy, int wz, BlockType type, byte meta) {
        int cx = Math.floorDiv(wx, Chunk.SIZE_X);
        int cz = Math.floorDiv(wz, Chunk.SIZE_Z);
        Chunk c = world.getChunkIfExists(cx, cz);
        if (c == null) return;
        int lx = Math.floorMod(wx, Chunk.SIZE_X);
        int lz = Math.floorMod(wz, Chunk.SIZE_Z);
        c.set(lx, wy, lz, type);
        c.setMeta(lx, wy, lz, meta);
        c.dirty = true;
        c.modified = true;
        activateAround(wx, wz);
        // Edge cells: neighbouring chunk needs a rebuild too so its mesh sees the change.
        if (lx == 0)                       markNeighbourDirty(world, cx - 1, cz);
        if (lx == Chunk.SIZE_X - 1)        markNeighbourDirty(world, cx + 1, cz);
        if (lz == 0)                       markNeighbourDirty(world, cx, cz - 1);
        if (lz == Chunk.SIZE_Z - 1)        markNeighbourDirty(world, cx, cz + 1);
    }

    private static void markNeighbourDirty(World world, int cx, int cz) {
        Chunk c = world.getChunkIfExists(cx, cz);
        if (c != null) c.dirty = true;
    }

    private static long pack(int wx, int wy, int wz) {
        return ((long) (wx & 0x3FFFFF) << 30) | ((long) (wy & 0xFF) << 22) | (long) (wz & 0x3FFFFF);
    }
    private static int unpackX(long pk) { int v = (int) ((pk >> 30) & 0x3FFFFF); return v >= (1 << 21) ? v - (1 << 22) : v; }
    private static int unpackY(long pk) { return (int) ((pk >> 22) & 0xFF); }
    private static int unpackZ(long pk) { int v = (int) (pk & 0x3FFFFF);       return v >= (1 << 21) ? v - (1 << 22) : v; }
}

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
    /**
     * Maximum amount of liquid state inspected by one 5.5 Hz water tick.
     *
     * <p>Render distance must not become a frame-time budget.  A radius-16
     * stream can discover tens of thousands of water cells at once; consuming
     * that entire backlog on the main thread produced 20-70 ms hitches.  The
     * backlog is therefore advanced over several ticks.  A single unusually
     * wet chunk is still processed whole so it can never starve.
     */
    private static final int MAX_CELLS_PER_TICK = 2048;
    private static final int MAX_CHUNKS_PER_TICK = 64;

    private static final Set<Long> activeChunks = new HashSet<>();
    private static final ArrayDeque<Long> activeQueue = new ArrayDeque<>();
    /** Streamed terrain only needs its seam checked; edits promote it to a full scan. */
    private static final Set<Long> borderOnlyChunks = new HashSet<>();
    /**
     * Чанки, чью воду уже осматривали после загрузки.
     *
     * <p>Загрузка меша будила симулятор на каждый чанк с водой — а меш
     * перестраивается постоянно, и один и тот же спокойный океан обходился
     * заново по нескольку раз в секунду. Осмотреть его надо ровно один раз:
     * всё, что потом меняет воду, идёт через {@code setBlock}, а тот будит
     * соседей сам.
     */
    private static final Set<Long> scanned = new HashSet<>();
    private static boolean seededLoadedChunks = false;

    private WaterSimulator() { }

    public static void activateChunkIfWater(World world, int cx, int cz) {
        long key = World.key(cx, cz);
        // Remeshing the same chunk is common.  Avoid the 32k-cell water probe
        // before checking whether this loaded incarnation was already seen.
        if (!scanned.add(key))
            return;
        Chunk chunk = world.getChunkIfExists(cx, cz);
        if (chunk == null || !chunkHasWater(chunk)) {
            if (chunk == null) scanned.remove(key);
            return;
        }
        // A mesh completion must never downgrade a pending player edit.
        enqueue(key, false);
    }

    /**
     * Activate the 3×3 chunks around a world-space (x, z) so the simulator
     * rescans them next tick. Only adds chunks that actually exist — prevents
     * the set from accumulating dead keys for never-loaded coordinates.
     */
    public static void activateAround(World world, int wx, int wz) {
        int cx = Math.floorDiv(wx, Chunk.SIZE_X);
        int cz = Math.floorDiv(wz, Chunk.SIZE_Z);
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                int ncx = cx + dx, ncz = cz + dz;
                if (world.getChunkIfExists(ncx, ncz) != null) {
                    long key = World.key(ncx, ncz);
                    borderOnlyChunks.remove(key);
                    enqueue(key, true);
                }
            }
    }

    /** Player and simulation edits go to the front; streamed terrain waits behind them. */
    private static void enqueue(long key, boolean urgent) {
        if (activeChunks.add(key)) {
            if (urgent) activeQueue.addFirst(key);
            else activeQueue.addLast(key);
            return;
        }
        // Keep queued chunks in place: repeatedly promoting neighbours starves
        // the older work when the active region exceeds the tick budget.
    }

    public static void forgetChunk(long key) {
        activeChunks.remove(key);
        activeQueue.remove(key);
        borderOnlyChunks.remove(key);
        scanned.remove(key);
    }

    /**
     * Clear all simulator state. Call when transitioning between worlds so the
     * seeded-loaded-chunks flag and stale active-chunk keys from the previous
     * world don't leak into the new one.
     */
    public static void reset() {
        activeChunks.clear();
        activeQueue.clear();
        borderOnlyChunks.clear();
        scanned.clear();
        seededLoadedChunks = false;
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
        Map<Long, Integer> toWeaken = new HashMap<>();
        Set<Long> toSource = new HashSet<>(64);
        ArrayList<Long> scan = new ArrayList<>(Math.min(MAX_CHUNKS_PER_TICK, activeQueue.size()));
        int cells = 0;
        while (!activeQueue.isEmpty() && scan.size() < MAX_CHUNKS_PER_TICK) {
            long key = activeQueue.peekFirst();
            Chunk chunk = world.getChunkIfExists((int) (key >> 32), (int) key);
            int chunkCells = chunk == null ? 0 : chunk.waterCellCount();
            if (!scan.isEmpty() && cells + chunkCells > MAX_CELLS_PER_TICK)
                break;
            activeQueue.removeFirst();
            activeChunks.remove(key);
            scan.add(key);
            cells += chunkCells;
            if (cells >= MAX_CELLS_PER_TICK)
                break;
        }
        int[][] sides = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };

        for (long key : scan) {
            int cx = (int) (key >> 32);
            int cz = (int) (key & 0xFFFFFFFFL);
            boolean borderOnly = borderOnlyChunks.remove(key);
            Chunk chunk = world.getChunkIfExists(cx, cz);
            if (chunk == null)
                continue;
            int bx = chunk.cx * Chunk.SIZE_X;
            int bz = chunk.cz * Chunk.SIZE_Z;
            for (int wi = 0; wi < chunk.waterCellCount(); wi++) {
                        int packed = chunk.waterCellAt(wi);
                        int lx = packed % Chunk.SIZE_X;
                        int rest = packed / Chunk.SIZE_X;
                        int lz = rest % Chunk.SIZE_Z;
                        int y = rest / Chunk.SIZE_Z;
                        if (borderOnly && lx != 0 && lx != Chunk.SIZE_X - 1
                                && lz != 0 && lz != Chunk.SIZE_Z - 1)
                            continue;
                        BlockType b = chunk.get(lx, y, lz);
                        if (b != BlockType.WATER && b != BlockType.WATER_FLOW) continue;

                        int wx = bx + lx, wy = y, wz = bz + lz;
                        int myLevel = (b == BlockType.WATER) ? 0 : (chunk.getMeta(lx, y, lz) & 0xF);

                        // Existing WATER_FLOW with 2+ source neighbours becomes a source.
                        // Skip trySpread — this cell becomes WATER at end of tick; neighbours rescan next tick.
                        if (b == BlockType.WATER_FLOW && sourceEligible(world, wx, wy, wz, sides)) {
                            toSource.add(pack(wx, wy, wz));
                            continue;
                        }

                        // Flow cells without support are scheduled for removal.
                        if (b == BlockType.WATER_FLOW && !hasSupport(world, wx, wy, wz, myLevel, sides)) {
                            int level = supportedLevel(world, wx, wy, wz, sides);
                            if (level > 7) toRemove.add(pack(wx, wy, wz));
                            else toWeaken.put(pack(wx, wy, wz), level);
                            continue;
                        }

                        // Try to spread by one step.
                        trySpread(world, wx, wy, wz, myLevel, toAdd, sides);
            }
        }

        for (Map.Entry<Long, Integer> e : toWeaken.entrySet()) {
            long pk = e.getKey();
            setBlockSafe(world, unpackX(pk), unpackY(pk), unpackZ(pk), BlockType.WATER_FLOW, e.getValue().byteValue());
        }
        // Apply removals first so freed cells can be re-filled in the same tick
        // if a different source still reaches them next pass.
        for (long pk : toRemove) {
            int wx = unpackX(pk), wy = unpackY(pk), wz = unpackZ(pk);
            setBlockSafe(world, wx, wy, wz, BlockType.AIR, (byte) 0);
        }

        // Apply additions. Skip cells we just removed (they might re-appear next tick).
        // A cell is fillable when AIR, or when it holds a strictly weaker WATER_FLOW
        // (we strengthen it to the new lower level). Never overwrite WATER sources
        // or solid blocks.
        for (Map.Entry<Long, Integer> e : toAdd.entrySet()) {
            long pk = e.getKey();
            if (toRemove.contains(pk)) continue;
            int wx = unpackX(pk), wy = unpackY(pk), wz = unpackZ(pk);
            int newLevel = e.getValue();
            BlockType current = world.getBlock(wx, wy, wz);
            if (current == BlockType.AIR) {
                // Newly filled cell: upgrade to source if it has 2+ source neighbours.
                // toRemove only removes WATER_FLOW cells, so WATER source neighbours are stable here.
                if (sourceEligible(world, wx, wy, wz, sides)) {
                    toSource.add(pk);
                } else {
                    setBlockSafe(world, wx, wy, wz, BlockType.WATER_FLOW, (byte) newLevel);
                }
            } else if (current == BlockType.WATER_FLOW) {
                int curLevel = world.getBlockMeta(wx, wy, wz) & 0xF;
                if (newLevel < curLevel) {
                    // Strengthened flow: check for source upgrade before writing.
                    if (sourceEligible(world, wx, wy, wz, sides)) {
                        toSource.add(pk);
                    } else {
                        setBlockSafe(world, wx, wy, wz, BlockType.WATER_FLOW, (byte) newLevel);
                    }
                }
            }
            // else: WATER source, solid, or any other block — leave it alone
        }

        // Upgrade eligible WATER_FLOW cells to full sources (infinite-source rule).
        // Guard: only write to AIR or WATER_FLOW — never overwrite a WATER source or solid block.
        for (long pk : toSource) {
            int wx = unpackX(pk), wy = unpackY(pk), wz = unpackZ(pk);
            BlockType cur = world.getBlock(wx, wy, wz);
            if (cur == BlockType.AIR || cur == BlockType.WATER_FLOW)
                setBlockSafe(world, wx, wy, wz, BlockType.WATER, (byte) 0);
        }
    }

    private static void seedLoadedWaterChunks(World world) {
        for (Chunk chunk : world.getLoadedChunks()) {
            if (chunkHasWater(chunk)) {
                long key = World.key(chunk.cx, chunk.cz);
                enqueue(key, false);
            }
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
        return supportedLevel(world, wx, wy, wz, sides) <= myLevel;
    }

    private static int supportedLevel(World world, int wx, int wy, int wz, int[][] sides) {
        BlockType above = world.getBlock(wx, wy + 1, wz);
        if (above == BlockType.WATER) return 0;
        if (above == BlockType.WATER_FLOW) return world.getBlockMeta(wx, wy + 1, wz) & 15;
        int level = 8;
        for (int[] d : sides) {
            BlockType nb = world.getBlock(wx + d[0], wy, wz + d[1]);
            if (nb == BlockType.WATER) return 1;
            if (nb == BlockType.WATER_FLOW) {
                int nbLevel = world.getBlockMeta(wx + d[0], wy, wz + d[1]) & 0xF;
                level = Math.min(level, nbLevel + 1);
            }
        }
        return level;
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
        // A falling stream is also allowed to OVERWRITE a weaker flow below
        // (higher meta = less full). Lets stronger water refresh stale pools.
        if (!canFall && below == BlockType.WATER_FLOW) {
            int belowLevel = world.getBlockMeta(wx, wy - 1, wz) & 0xF;
            if (myLevel < belowLevel) canFall = true;
        }
        if (canFall) {
            if (wy <= 0) return;
            long pk = pack(wx, wy - 1, wz);
            // Preserve level on fall. A source (level 0) creates a level-0 column
            // so the bottom-of-fall pools up to 7 cells horizontally — matches MC.
            // Non-source flows keep their level: level-3 falls as level 3, spreading
            // 4 more blocks. This prevents cascading resets over gentle slopes.
            int newLevel = myLevel;
            Integer prev = toAdd.get(pk);
            if (prev == null || newLevel < prev) toAdd.put(pk, newLevel);
            return;
        }

        // Skip sideways spread for any block that is part of a vertical column.
        // Only pool sideways when resting on a solid floor (water above, solid below).
        BlockType above = world.getBlock(wx, wy + 1, wz);
        boolean hasWaterAbove = above == BlockType.WATER || above == BlockType.WATER_FLOW;
        if (hasWaterAbove) {
            if (canFall) return; // still falling — no sideways arms
            if (below == BlockType.WATER_FLOW || below == BlockType.WATER) return; // mid-column
        }

        // A terrain step must not refill the horizontal spread budget.
        // Preserve the upstream level at the bottom of every falling column.
        if (myLevel >= 7) return;
        int sideLevel = myLevel + 1;
        // Есть ли вообще куда течь. У клетки посреди океана со всех четырёх
        // сторон вода, и карта стока ниже — четыре поиска вглубь на четыре
        // шага — считалась вхолостую. Именно на этом тик воды над водой стоил
        // десятки миллисекунд и повторялся пять раз в секунду.
        boolean anyFillable = false;
        for (int[] d : sides) {
            int nx = wx + d[0], nz = wz + d[1];
            BlockType nb = world.getBlock(nx, wy, nz);
            if (nb == BlockType.AIR
                    || (nb == BlockType.WATER_FLOW
                        && sideLevel < (world.getBlockMeta(nx, wy, nz) & 0xF))) {
                anyFillable = true;
                break;
            }
        }
        if (!anyFillable) return;
        int[] costs = new int[sides.length];
        int best = 99;
        for (int i = 0; i < sides.length; i++) {
            costs[i] = dropDistance(world, wx + sides[i][0], wy, wz + sides[i][1],
                    wx, wz, Math.min(4, 7 - sideLevel), sides);
            best = Math.min(best, costs[i]);
        }
        int direction = -1;
        for (int[] d : sides) {
            direction++;
            if (best < 99 && costs[direction] != best) continue;
            int nx = wx + d[0], nz = wz + d[1];
            BlockType nb = world.getBlock(nx, wy, nz);
            boolean canFill = (nb == BlockType.AIR);
            // Also fill cells that already hold a WEAKER flow (higher meta).
            // This is how stronger flows reach areas previously settled by a far source.
            if (!canFill && nb == BlockType.WATER_FLOW) {
                int nbLevel = world.getBlockMeta(nx, wy, nz) & 0xF;
                if (sideLevel < nbLevel) canFill = true;
            }
            if (!canFill) continue;
            long pk = pack(nx, wy, nz);
            Integer prev = toAdd.get(pk);
            if (prev == null || sideLevel < prev) toAdd.put(pk, sideLevel);
        }
    }

    private static void setBlockSafe(World world, int wx, int wy, int wz, BlockType type, byte meta) {
        if (wy < 0 || wy >= Chunk.SIZE_Y) return;
        int cx = Math.floorDiv(wx, Chunk.SIZE_X);
        int cz = Math.floorDiv(wz, Chunk.SIZE_Z);
        Chunk c = world.getChunkIfExists(cx, cz);
        if (c == null) return;
        int lx = Math.floorMod(wx, Chunk.SIZE_X);
        int lz = Math.floorMod(wz, Chunk.SIZE_Z);
        world.setBlock(wx, wy, wz, type, meta);
        activateAround(world, wx, wz);
        // Edge cells: neighbouring chunk needs a rebuild too so its mesh sees the change.
        if (lx == 0)                       markNeighbourDirty(world, cx - 1, cz);
        if (lx == Chunk.SIZE_X - 1)        markNeighbourDirty(world, cx + 1, cz);
        if (lz == 0)                       markNeighbourDirty(world, cx, cz - 1);
        if (lz == Chunk.SIZE_Z - 1)        markNeighbourDirty(world, cx, cz + 1);
    }

    private static void markNeighbourDirty(World world, int cx, int cz) {
        Chunk c = world.getChunkIfExists(cx, cz);
        if (c != null) c.markDirty();
    }

    private static long pack(int wx, int wy, int wz) {
        return ((long) (wx & 0x3FFFFF) << 30) | ((long) (wy & 0xFF) << 22) | (long) (wz & 0x3FFFFF);
    }
    private static int unpackX(long pk) { int v = (int) ((pk >> 30) & 0x3FFFFF); return v >= (1 << 21) ? v - (1 << 22) : v; }
    private static int unpackY(long pk) { return (int) ((pk >> 22) & 0xFF); }
    private static int unpackZ(long pk) { int v = (int) (pk & 0x3FFFFF);       return v >= (1 << 21) ? v - (1 << 22) : v; }

    private static int countSourceNeighbors(World world, int wx, int wy, int wz, int[][] sides) {
        int n = 0;
        for (int[] d : sides)
            if (world.getBlock(wx + d[0], wy, wz + d[1]) == BlockType.WATER) n++;
        return n;
    }

    /** Find the nearest reachable drop without flowing through solid terrain. */
    private static int dropDistance(World world, int x, int y, int z, int previousX,
                                    int previousZ, int remaining, int[][] sides) {
        if (y <= 0 || world.getChunkIfExists(Math.floorDiv(x, Chunk.SIZE_X),
                Math.floorDiv(z, Chunk.SIZE_Z)) == null) return 99;
        BlockType cell = world.getBlock(x, y, z);
        if (cell != BlockType.AIR && cell != BlockType.WATER_FLOW) return 99;
        BlockType below = world.getBlock(x, y - 1, z);
        if (below == BlockType.AIR || below == BlockType.WATER_FLOW) return 0;
        if (remaining <= 0) return 99;
        int best = 99;
        for (int[] d : sides) {
            int nx = x + d[0], nz = z + d[1];
            if (nx == previousX && nz == previousZ) continue;
            int cost = dropDistance(world, nx, y, nz, x, z, remaining - 1, sides);
            if (cost < 99) best = Math.min(best, cost + 1);
        }
        return best;
    }

    private static boolean sourceEligible(World world, int x, int y, int z, int[][] sides) {
        BlockType below = world.getBlock(x, y - 1, z);
        return (below.solid || below == BlockType.WATER)
                && countSourceNeighbors(world, x, y, z, sides) >= 2;
    }
}

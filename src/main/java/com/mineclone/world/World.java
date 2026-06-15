package com.mineclone.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;

public class World {
    public static final int SEA_LEVEL = 50;

    private final Map<Long, Chunk> chunks = new ConcurrentHashMap<>();
    private final PerlinNoise heightNoise;
    private final PerlinNoise detailNoise;
    public final BiomeProvider biomes;
    public final long seed;

    public World(long seed) {
        this.seed = seed;
        this.heightNoise = new PerlinNoise(seed);
        this.detailNoise = new PerlinNoise(seed ^ 0x9E3779B97F4A7C15L);
        this.biomes = new BiomeProvider(seed);
    }

    public static long key(int cx, int cz) {
        return (((long) cx) << 32) ^ (cz & 0xFFFFFFFFL);
    }

    public Chunk getChunk(int cx, int cz) {
        return chunks.computeIfAbsent(key(cx, cz), k -> generate(cx, cz));
    }

    public Chunk getChunkIfExists(int cx, int cz) {
        return chunks.get(key(cx, cz));
    }

    public Chunk removeChunk(int cx, int cz) {
        return chunks.remove(key(cx, cz));
    }

    public Iterable<Chunk> getLoadedChunks() {
        return chunks.values();
    }

    private Chunk generate(int cx, int cz) {
        Chunk c = new Chunk(cx, cz);
        int[][] heights = new int[Chunk.SIZE_X][Chunk.SIZE_Z];

        // Biome grid: 8x8 array covering the chunk (16 cols / 4 = 4 cells) plus
        // a 2-cell margin on each side for the 5x5 smoothing window.
        // gx0 = first grid index this chunk needs (2 margin cells to the left).
        final int G = 8;
        int gx0 = cx * 4 - 2, gz0 = cz * 4 - 2;
        Biome[][] grid = new Biome[G][G];
        for (int gx = 0; gx < G; gx++)
            for (int gz = 0; gz < G; gz++)
                grid[gx][gz] = biomes.biomeAtGrid(gx0 + gx, gz0 + gz);

        // Pass 1: terrain only — no trees yet so leaves are never overwritten by later
        // columns.
        for (int x = 0; x < Chunk.SIZE_X; x++) {
            for (int z = 0; z < Chunk.SIZE_Z; z++) {
                int wx = cx * Chunk.SIZE_X + x;
                int wz = cz * Chunk.SIZE_Z + z;

                // Smooth base height/amplitude over a 5x5 grid window so biome
                // borders slope instead of forming cliffs.
                int gi = x / 4 + 2, gj = z / 4 + 2;
                double base = 0, amp = 0;
                for (int ox = -2; ox <= 2; ox++)
                    for (int oz = -2; oz <= 2; oz++) {
                        Biome b = grid[gi + ox][gj + oz];
                        base += b.baseHeight;
                        amp  += b.amplitude;
                    }
                base /= 25.0;
                amp  /= 25.0;

                double n = heightNoise.fbm(wx * 0.012, wz * 0.012, 5, 2.0, 0.5);
                double d = detailNoise.fbm(wx * 0.05,  wz * 0.05,  3, 2.0, 0.5);
                int height = (int) (base + n * 22 * amp + d * 4);
                height = Math.max(2, Math.min(Chunk.SIZE_Y - 4, height));
                heights[x][z] = height;

                // Point biome (4x4 quantised) picks the surface blocks; the
                // beach rule overrides every biome at the waterline.
                Biome biome = grid[gi][gj];
                boolean beach = height <= SEA_LEVEL + 1;
                BlockType surface = beach ? BlockType.SAND : biome.surfaceBlock;
                BlockType filler  = beach ? BlockType.SAND : biome.fillerBlock;

                for (int y = 0; y < Chunk.SIZE_Y; y++) {
                    BlockType t;
                    if (y == 0)
                        t = BlockType.BEDROCK;
                    else if (y < height - 4)
                        t = BlockType.STONE;
                    else if (y < height)
                        t = filler;
                    else if (y == height)
                        t = surface;
                    else if (y <= SEA_LEVEL)
                        t = BlockType.WATER;
                    else
                        t = BlockType.AIR;
                    c.set(x, y, z, t);
                }
            }
        }

        // Pass 2: vegetation — all terrain exists so leaves land correctly.
        for (int x = 0; x < Chunk.SIZE_X; x++) {
            for (int z = 0; z < Chunk.SIZE_Z; z++) {
                Biome biome = grid[x / 4 + 2][z / 4 + 2];
                if (biome.treeType == Biome.TreeType.NONE)
                    continue;
                int height = heights[x][z];
                if (height <= SEA_LEVEL + 1)
                    continue;
                if (c.get(x, height, z) != biome.surfaceBlock)
                    continue;
                int wx = cx * Chunk.SIZE_X + x;
                int wz = cz * Chunk.SIZE_Z + z;
                long h = mix(wx, wz, seed);
                if ((h & 0x7F) >= biome.treesPer128 || x < 2 || x >= Chunk.SIZE_X - 2
                        || z < 2 || z >= Chunk.SIZE_Z - 2)
                    continue;
                switch (biome.treeType) {
                    case OAK    -> placeOak(c, x, height, z, h);
                    case SPRUCE -> placeSpruce(c, x, height, z, h);
                    case CACTUS -> placeCactus(c, x, height, z, h);
                    case NONE   -> { }
                }
            }
        }

        c.computeSkyLight();
        c.dirty = true;
        return c;
    }

    private static void placeOak(Chunk c, int x, int height, int z, long h) {
        int th = 4 + (int) ((h >>> 7) & 0x3);
        int top = height + th;
        if (top + 2 >= Chunk.SIZE_Y)
            return;
        for (int i = 1; i <= th; i++)
            c.set(x, height + i, z, BlockType.WOOD);
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int dy = 0; dy <= 2; dy++) {
                    int lx = x + dx, ly = top - 2 + dy, lz = z + dz;
                    int rad = (dy == 2) ? 1 : 2;
                    if (Math.abs(dx) + Math.abs(dz) <= rad + 1
                            && c.inBounds(lx, ly, lz)
                            && c.get(lx, ly, lz) == BlockType.AIR) {
                        c.set(lx, ly, lz, BlockType.LEAVES);
                    }
                }
    }

    private static void placeSpruce(Chunk c, int x, int height, int z, long h) {
        int th = 5 + (int) ((h >>> 7) & 0x3);
        int top = height + th;
        if (top + 2 >= Chunk.SIZE_Y)
            return;
        for (int i = 1; i <= th; i++)
            c.set(x, height + i, z, BlockType.WOOD);
        int[] radii = { 2, 1, 2, 1, 1 };
        for (int level = 0; level < radii.length; level++) {
            int ly = top - (radii.length - 1) + level;
            int rad = radii[level];
            for (int dx = -rad; dx <= rad; dx++)
                for (int dz = -rad; dz <= rad; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) > rad)
                        continue;
                    int lx = x + dx, lz2 = z + dz;
                    if (c.inBounds(lx, ly, lz2) && c.get(lx, ly, lz2) == BlockType.AIR)
                        c.set(lx, ly, lz2, BlockType.LEAVES);
                }
        }
        c.set(x, top + 1, z, BlockType.LEAVES);
    }

    private static void placeCactus(Chunk c, int x, int height, int z, long h) {
        int ch = 1 + (int) ((h >>> 7) % 3);
        if (height + ch + 1 >= Chunk.SIZE_Y)
            return;
        for (int i = 1; i <= ch; i++)
            c.set(x, height + i, z, BlockType.CACTUS);
    }

    public int getSkyLight(int wx, int wy, int wz) {
        if (wy < 0)
            return 0;
        if (wy >= Chunk.SIZE_Y)
            return Chunk.MAX_LIGHT;
        int cx = Math.floorDiv(wx, Chunk.SIZE_X);
        int cz = Math.floorDiv(wz, Chunk.SIZE_Z);
        Chunk c = getChunkIfExists(cx, cz);
        if (c == null)
            return Chunk.MAX_LIGHT; // assume sky-lit for not-yet-loaded chunks
        int lx = Math.floorMod(wx, Chunk.SIZE_X);
        int lz = Math.floorMod(wz, Chunk.SIZE_Z);
        return c.getSkyLight(lx, wy, lz);
    }

    public int getBlockLightWorld(int wx, int wy, int wz) {
        if (wy < 0 || wy >= Chunk.SIZE_Y)
            return 0;
        int cx = Math.floorDiv(wx, Chunk.SIZE_X);
        int cz = Math.floorDiv(wz, Chunk.SIZE_Z);
        Chunk c = getChunkIfExists(cx, cz);
        if (c == null)
            return 0;
        return c.getBlockLight(Math.floorMod(wx, Chunk.SIZE_X), wy, Math.floorMod(wz, Chunk.SIZE_Z));
    }

    private void setBlockLightWorld(int wx, int wy, int wz, int val) {
        if (wy < 0 || wy >= Chunk.SIZE_Y)
            return;
        int cx = Math.floorDiv(wx, Chunk.SIZE_X);
        int cz = Math.floorDiv(wz, Chunk.SIZE_Z);
        Chunk c = getChunkIfExists(cx, cz);
        if (c == null)
            return;
        c.setBlockLight(Math.floorMod(wx, Chunk.SIZE_X), wy, Math.floorMod(wz, Chunk.SIZE_Z), val);
        c.dirty = true;
    }

    public void floodFillAdd(int wx, int wy, int wz) {
        int emitted = getBlock(wx, wy, wz).emittedLight;
        if (emitted <= 0)
            return;
        setBlockLightWorld(wx, wy, wz, emitted);

        int[][] dirs = { { 1, 0, 0 }, { -1, 0, 0 }, { 0, 1, 0 }, { 0, -1, 0 }, { 0, 0, 1 }, { 0, 0, -1 } };
        Queue<int[]> queue = new ArrayDeque<>();
        if (emitted > 1)
            for (int[] d : dirs)
                enqueueBlockLight(queue, wx + d[0], wy + d[1], wz + d[2], emitted - 1);

        while (!queue.isEmpty()) {
            int[] cur = queue.poll();
            int x = cur[0], y = cur[1], z = cur[2], val = cur[3];
            if (val > 1)
                for (int[] d : dirs)
                    enqueueBlockLight(queue, x + d[0], y + d[1], z + d[2], val - 1);
        }
    }

    private void enqueueBlockLight(Queue<int[]> queue, int wx, int wy, int wz, int val) {
        if (wy < 0 || wy >= Chunk.SIZE_Y)
            return;
        int cx = Math.floorDiv(wx, Chunk.SIZE_X);
        int cz = Math.floorDiv(wz, Chunk.SIZE_Z);
        Chunk c = getChunkIfExists(cx, cz);
        if (c == null)
            return;
        int lx = Math.floorMod(wx, Chunk.SIZE_X);
        int lz = Math.floorMod(wz, Chunk.SIZE_Z);
        BlockType bt = c.get(lx, wy, lz);
        if (bt.solid && !bt.transparent && !bt.cutout)
            return;
        if (c.getBlockLight(lx, wy, lz) >= val)
            return;
        c.setBlockLight(lx, wy, lz, val);
        c.dirty = true;
        queue.add(new int[] { wx, wy, wz, val });
    }

    /**
     * Seed block-light into chunk (cx,cz) from the border cells of its loaded
     * neighbours.  Called when a chunk first loads so it inherits torch light
     * that was already propagated in adjacent chunks.  Re-flooding the emitter
     * in the neighbour would fail (BFS stops at cells that already hold the
     * correct value); reading the border directly and pushing inward avoids that.
     */
    public void injectNeighbourLight(int cx, int cz) {
        int bx = cx * Chunk.SIZE_X;
        int bz = cz * Chunk.SIZE_Z;
        Queue<int[]> queue = new ArrayDeque<>();
        int[][] dirs = { {1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1} };

        Chunk nPX = getChunkIfExists(cx + 1, cz);
        if (nPX != null)
            for (int y = 0; y < Chunk.SIZE_Y; y++)
                for (int lz = 0; lz < Chunk.SIZE_Z; lz++) {
                    int nv = nPX.getBlockLight(0, y, lz);
                    if (nv > 1) enqueueBlockLight(queue, bx + Chunk.SIZE_X - 1, y, bz + lz, nv - 1);
                }
        Chunk nNX = getChunkIfExists(cx - 1, cz);
        if (nNX != null)
            for (int y = 0; y < Chunk.SIZE_Y; y++)
                for (int lz = 0; lz < Chunk.SIZE_Z; lz++) {
                    int nv = nNX.getBlockLight(Chunk.SIZE_X - 1, y, lz);
                    if (nv > 1) enqueueBlockLight(queue, bx, y, bz + lz, nv - 1);
                }
        Chunk nPZ = getChunkIfExists(cx, cz + 1);
        if (nPZ != null)
            for (int y = 0; y < Chunk.SIZE_Y; y++)
                for (int lx = 0; lx < Chunk.SIZE_X; lx++) {
                    int nv = nPZ.getBlockLight(lx, y, 0);
                    if (nv > 1) enqueueBlockLight(queue, bx + lx, y, bz + Chunk.SIZE_Z - 1, nv - 1);
                }
        Chunk nNZ = getChunkIfExists(cx, cz - 1);
        if (nNZ != null)
            for (int y = 0; y < Chunk.SIZE_Y; y++)
                for (int lx = 0; lx < Chunk.SIZE_X; lx++) {
                    int nv = nNZ.getBlockLight(lx, y, Chunk.SIZE_Z - 1);
                    if (nv > 1) enqueueBlockLight(queue, bx + lx, y, bz, nv - 1);
                }

        while (!queue.isEmpty()) {
            int[] cur = queue.poll();
            int x = cur[0], y = cur[1], z = cur[2], val = cur[3];
            if (val > 1)
                for (int[] d : dirs)
                    enqueueBlockLight(queue, x + d[0], y + d[1], z + d[2], val - 1);
        }
    }

    public void floodFillRemove(int wx, int wy, int wz) {
        int R = 15;
        List<int[]> sources = new ArrayList<>();
        for (int x = wx - R; x <= wx + R; x++) {
            for (int y = Math.max(0, wy - R); y <= Math.min(Chunk.SIZE_Y - 1, wy + R); y++) {
                for (int z = wz - R; z <= wz + R; z++) {
                    if (getBlockLightWorld(x, y, z) > 0)
                        setBlockLightWorld(x, y, z, 0);
                    BlockType bt = getBlock(x, y, z);
                    if (bt.emittedLight > 0 && !(x == wx && y == wy && z == wz))
                        sources.add(new int[] { x, y, z });
                }
            }
        }
        for (int[] src : sources)
            floodFillAdd(src[0], src[1], src[2]);
    }

    private static long mix(int a, int b, long seed) {
        long h = seed ^ (a * 0x9E3779B97F4A7C15L) ^ (b * 0xBF58476D1CE4E5B9L);
        h ^= h >>> 30;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 27;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return h;
    }

    // ---- world-space accessors ----
    public BlockType getBlock(int wx, int wy, int wz) {
        if (wy < 0 || wy >= Chunk.SIZE_Y)
            return BlockType.AIR;
        int cx = Math.floorDiv(wx, Chunk.SIZE_X);
        int cz = Math.floorDiv(wz, Chunk.SIZE_Z);
        Chunk c = getChunkIfExists(cx, cz);
        if (c == null)
            return BlockType.AIR;
        int lx = Math.floorMod(wx, Chunk.SIZE_X);
        int lz = Math.floorMod(wz, Chunk.SIZE_Z);
        return c.get(lx, wy, lz);
    }

    public void setBlock(int wx, int wy, int wz, BlockType t) {
        if (wy < 0 || wy >= Chunk.SIZE_Y)
            return;
        int cx = Math.floorDiv(wx, Chunk.SIZE_X);
        int cz = Math.floorDiv(wz, Chunk.SIZE_Z);
        // Never force-generate here: chunk generation is heavy (terrain + trees +
        // sky-light BFS) and would stall the game thread mid-frame. Edits only ever
        // target already-loaded chunks (player raycast hits / fills around the
        // player), so a null means "not loaded" — silently ignore rather than
        // synchronously generating on the loop.
        Chunk c = getChunkIfExists(cx, cz);
        if (c == null)
            return;
        int lx = Math.floorMod(wx, Chunk.SIZE_X);
        int lz = Math.floorMod(wz, Chunk.SIZE_Z);
        BlockType old = c.get(lx, wy, lz);
        c.set(lx, wy, lz, t);
        c.modified = true;
        WaterSimulator.activateAround(this, wx, wz);
        c.computeSkyLight();
        // mark neighbors dirty if on edge so their borders update
        if (lx == 0)
            remeshNeighbour(cx - 1, cz);
        if (lx == Chunk.SIZE_X - 1)
            remeshNeighbour(cx + 1, cz);
        if (lz == 0)
            remeshNeighbour(cx, cz - 1);
        if (lz == Chunk.SIZE_Z - 1)
            remeshNeighbour(cx, cz + 1);
        // Block must be committed (c.set called above) before flood fill —
        // floodFillAdd reads the new block's emittedLight via getBlock().
        //
        // Re-propagate whenever:
        // • a light source is removed/changed (old had emittedLight)
        // • a block's opacity flipped (solid placed/removed in a lit region)
        boolean wasOpaque = old != BlockType.AIR && !old.transparent && !old.cutout;
        boolean isOpaque = t != BlockType.AIR && !t.transparent && !t.cutout;
        if (old.emittedLight > 0 || wasOpaque != isOpaque)
            floodFillRemove(wx, wy, wz);
        if (t.emittedLight > 0)
            floodFillAdd(wx, wy, wz);
    }

    public byte getBlockMeta(int wx, int wy, int wz) {
        if (wy < 0 || wy >= Chunk.SIZE_Y)
            return 0;
        int cx = Math.floorDiv(wx, Chunk.SIZE_X);
        int cz = Math.floorDiv(wz, Chunk.SIZE_Z);
        Chunk c = getChunkIfExists(cx, cz);
        if (c == null)
            return 0;
        return c.getMeta(Math.floorMod(wx, Chunk.SIZE_X), wy, Math.floorMod(wz, Chunk.SIZE_Z));
    }

    public void setBlock(int wx, int wy, int wz, BlockType t, byte meta) {
        setBlock(wx, wy, wz, t);
        if (wy < 0 || wy >= Chunk.SIZE_Y)
            return;
        int cx = Math.floorDiv(wx, Chunk.SIZE_X);
        int cz = Math.floorDiv(wz, Chunk.SIZE_Z);
        Chunk c = getChunkIfExists(cx, cz);
        if (c == null)
            return;
        c.setMeta(Math.floorMod(wx, Chunk.SIZE_X), wy, Math.floorMod(wz, Chunk.SIZE_Z), meta);
    }

    private void remeshNeighbour(int cx, int cz) {
        Chunk c = getChunkIfExists(cx, cz);
        if (c != null) {
            // neighbour light at the seam depends on this chunk; cheap to recompute.
            c.computeSkyLight();
            c.dirty = true;
        }
    }

}

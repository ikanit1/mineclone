package com.mineclone.world;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class World {
    public static final int SEA_LEVEL = 50;

    private final Map<Long, Chunk> chunks = new ConcurrentHashMap<>();
    private final PerlinNoise heightNoise;
    private final PerlinNoise detailNoise;
    public final long seed;

    public World(long seed) {
        this.seed = seed;
        this.heightNoise = new PerlinNoise(seed);
        this.detailNoise = new PerlinNoise(seed ^ 0x9E3779B97F4A7C15L);
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

    public Iterable<Chunk> getLoadedChunks() { return chunks.values(); }

    private Chunk generate(int cx, int cz) {
        Chunk c = new Chunk(cx, cz);
        int[][] heights = new int[Chunk.SIZE_X][Chunk.SIZE_Z];

        // Pass 1: terrain only — no trees yet so leaves are never overwritten by later columns.
        for (int x = 0; x < Chunk.SIZE_X; x++) {
            for (int z = 0; z < Chunk.SIZE_Z; z++) {
                int wx = cx * Chunk.SIZE_X + x;
                int wz = cz * Chunk.SIZE_Z + z;

                double n = heightNoise.fbm(wx * 0.012, wz * 0.012, 5, 2.0, 0.5);
                double d = detailNoise.fbm(wx * 0.05, wz * 0.05, 3, 2.0, 0.5);
                int height = (int) (SEA_LEVEL + 6 + n * 22 + d * 4);
                height = Math.max(2, Math.min(Chunk.SIZE_Y - 4, height));
                heights[x][z] = height;

                for (int y = 0; y < Chunk.SIZE_Y; y++) {
                    BlockType t;
                    if (y == 0) t = BlockType.BEDROCK;
                    else if (y < height - 4) t = BlockType.STONE;
                    else if (y < height) t = (height <= SEA_LEVEL + 1) ? BlockType.SAND : BlockType.DIRT;
                    else if (y == height) {
                        if (height <= SEA_LEVEL + 1) t = BlockType.SAND;
                        else t = BlockType.GRASS;
                    }
                    else if (y <= SEA_LEVEL) t = BlockType.WATER;
                    else t = BlockType.AIR;
                    c.set(x, y, z, t);
                }
            }
        }

        // Pass 2: trees — all terrain exists so leaves land correctly.
        for (int x = 0; x < Chunk.SIZE_X; x++) {
            for (int z = 0; z < Chunk.SIZE_Z; z++) {
                int height = heights[x][z];
                if (c.get(x, height, z) != BlockType.GRASS) continue;
                int wx = cx * Chunk.SIZE_X + x;
                int wz = cz * Chunk.SIZE_Z + z;
                long h = mix(wx, wz, seed);
                if ((h & 0x7F) >= 2 || x < 2 || x >= Chunk.SIZE_X - 2 || z < 2 || z >= Chunk.SIZE_Z - 2) continue;
                int th = 4 + (int) ((h >>> 7) & 0x3);
                int top = height + th;
                if (top + 2 >= Chunk.SIZE_Y) continue;
                for (int i = 1; i <= th; i++) c.set(x, height + i, z, BlockType.WOOD);
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
        }

        c.computeSkyLight();
        c.dirty = true;
        return c;
    }

    public int getSkyLight(int wx, int wy, int wz) {
        if (wy < 0) return 0;
        if (wy >= Chunk.SIZE_Y) return Chunk.MAX_LIGHT;
        int cx = Math.floorDiv(wx, Chunk.SIZE_X);
        int cz = Math.floorDiv(wz, Chunk.SIZE_Z);
        Chunk c = getChunkIfExists(cx, cz);
        if (c == null) return Chunk.MAX_LIGHT; // assume sky-lit for not-yet-loaded chunks
        int lx = Math.floorMod(wx, Chunk.SIZE_X);
        int lz = Math.floorMod(wz, Chunk.SIZE_Z);
        return c.getSkyLight(lx, wy, lz);
    }

    private static long mix(int a, int b, long seed) {
        long h = seed ^ (a * 0x9E3779B97F4A7C15L) ^ (b * 0xBF58476D1CE4E5B9L);
        h ^= h >>> 30; h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 27; h *= 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return h;
    }

    // ---- world-space accessors ----
    public BlockType getBlock(int wx, int wy, int wz) {
        if (wy < 0 || wy >= Chunk.SIZE_Y) return BlockType.AIR;
        int cx = Math.floorDiv(wx, Chunk.SIZE_X);
        int cz = Math.floorDiv(wz, Chunk.SIZE_Z);
        Chunk c = getChunkIfExists(cx, cz);
        if (c == null) return BlockType.AIR;
        int lx = Math.floorMod(wx, Chunk.SIZE_X);
        int lz = Math.floorMod(wz, Chunk.SIZE_Z);
        return c.get(lx, wy, lz);
    }

    public void setBlock(int wx, int wy, int wz, BlockType t) {
        if (wy < 0 || wy >= Chunk.SIZE_Y) return;
        int cx = Math.floorDiv(wx, Chunk.SIZE_X);
        int cz = Math.floorDiv(wz, Chunk.SIZE_Z);
        Chunk c = getChunk(cx, cz);
        int lx = Math.floorMod(wx, Chunk.SIZE_X);
        int lz = Math.floorMod(wz, Chunk.SIZE_Z);
        c.set(lx, wy, lz, t);
        c.computeSkyLight();
        // mark neighbors dirty if on edge so their borders update
        if (lx == 0)  remeshNeighbour(cx - 1, cz);
        if (lx == Chunk.SIZE_X - 1) remeshNeighbour(cx + 1, cz);
        if (lz == 0)  remeshNeighbour(cx, cz - 1);
        if (lz == Chunk.SIZE_Z - 1) remeshNeighbour(cx, cz + 1);
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

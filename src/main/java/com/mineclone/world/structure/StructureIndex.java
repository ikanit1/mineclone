package com.mineclone.world.structure;

import com.mineclone.world.Chunk;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where a world's structures are (GEN-03): the start of each type in each
 * region, computed on demand from the seed and kept in a bounded cache
 * shared by the generation threads. Pure: two indexes over the same seed and
 * types answer the same, whichever questions came first.
 */
public final class StructureIndex {
    /** Starts kept; generation around one chunk asks for a few dozen. */
    public static final int CACHE_SIZE = 512;

    /** A block inside a structure: which type, which start, which piece. */
    public record Hit(StructureType type, StructureStart start, StructurePiece piece) {}

    private static final StructureStart NONE = new StructureStart(null, 0, 0, 0, 0, 0, List.of(),
            new BoundingBox(0, 0, 0, 0, 0, 0));

    private final long seed;
    private final List<StructureType> types;
    private final StructureStart.Terrain terrain;
    private final Map<Long, StructureStart> cache = new LinkedHashMap<>(CACHE_SIZE * 2, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, StructureStart> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    public StructureIndex(long seed, List<StructureType> types, StructureStart.Terrain terrain) {
        if (types.size() > 255)
            throw new IllegalArgumentException("too many structure types");
        this.seed = seed;
        this.types = List.copyOf(types);
        this.terrain = terrain;
    }

    public List<StructureType> types() {
        return types;
    }

    /** The start of type number {@code t} in a region, or null. */
    public StructureStart start(int t, int rx, int rz) {
        long key = (long) t << 56 | (rx & 0xFFFFFFFL) << 28 | (rz & 0xFFFFFFFL);
        StructureStart s;
        synchronized (cache) {
            s = cache.get(key);
        }
        if (s == null) {
            // Computed outside the lock: two threads may both build it, and
            // both get the same answer.
            s = StructureStart.create(types.get(t), seed, rx, rz, terrain);
            if (s == null)
                s = NONE;
            synchronized (cache) {
                cache.put(key, s);
            }
        }
        return s == NONE ? null : s;
    }

    /** Starts cached now; never more than {@link #CACHE_SIZE}. */
    public int cached() {
        synchronized (cache) {
            return cache.size();
        }
    }

    /** Every start whose structure reaches into {@code area}, types in order, regions row by row. */
    public List<StructureStart> reaching(BoundingBox area) {
        List<StructureStart> out = new ArrayList<>();
        int cx0 = Math.floorDiv(area.minX(), Chunk.SIZE_X), cx1 = Math.floorDiv(area.maxX(), Chunk.SIZE_X);
        int cz0 = Math.floorDiv(area.minZ(), Chunk.SIZE_Z), cz1 = Math.floorDiv(area.maxZ(), Chunk.SIZE_Z);
        for (int t = 0; t < types.size(); t++) {
            StructureType type = types.get(t);
            int r = type.maxRadiusChunks();
            for (int rx = Math.floorDiv(cx0 - r, type.spacing()); rx <= Math.floorDiv(cx1 + r, type.spacing()); rx++)
                for (int rz = Math.floorDiv(cz0 - r, type.spacing()); rz <= Math.floorDiv(cz1 + r, type.spacing()); rz++) {
                    StructureStart s = start(t, rx, rz);
                    if (s != null && s.box().intersects(area))
                        out.add(s);
                }
        }
        return out;
    }

    /** The structure a block belongs to, or null — for the spawner, advancements and F3. */
    public Hit at(int x, int y, int z) {
        if (y < 0 || y >= Chunk.SIZE_Y)
            return null;
        for (StructureStart s : reaching(new BoundingBox(x, y, z, x, y, z))) {
            StructurePiece p = s.pieceAt(x, y, z);
            if (p != null)
                return new Hit(s.type(), s, p);
        }
        return null;
    }
}

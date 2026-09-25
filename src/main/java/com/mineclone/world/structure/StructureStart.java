package com.mineclone.world.structure;

import com.mineclone.world.Biome;
import com.mineclone.world.Chunk;
import java.util.List;
import java.util.SplittableRandom;

/**
 * One structure in one region: where it starts and its pieces (GEN-03).
 * {@link #create} is a pure function of the seed and the region, so every
 * chunk the structure crosses computes the same start, on any thread, in any
 * order.
 *
 * @param chunkX the start chunk
 * @param box    all pieces together
 */
public record StructureStart(StructureType type, int regionX, int regionZ, int chunkX, int chunkZ,
                             int y, List<StructurePiece> pieces, BoundingBox box) {
    /** What the generator tells an assembler about the ground; pure functions of the seed. */
    public interface Terrain {
        int height(int x, int z);

        Biome biome(int x, int z);
    }

    /**
     * What an assembler builds from: the start chunk, the block its structure is
     * anchored at (the chunk's centre, at the start height), a random stream of
     * its own and the terrain.
     */
    public record Context(StructureType type, long seed, int chunkX, int chunkZ, int x, int y, int z,
                          SplittableRandom random, Terrain terrain) {}

    private static final long REGION_X = 0x9E3779B97F4A7C15L, REGION_Z = 0xC2B2AE3D27D4EB4FL;

    /**
     * The start of {@code type} in region {@code (rx, rz)}, or null when the
     * region has none: its chosen chunk is in the wrong biome, or the assembler
     * found the site unsuitable.
     *
     * @throws IllegalStateException when the assembler's pieces overlap or reach
     *         past {@link StructureType#maxRadiusChunks} — a bug in the type
     */
    public static StructureStart create(StructureType type, long seed, int rx, int rz, Terrain terrain) {
        SplittableRandom random = new SplittableRandom(seed ^ type.salt() ^ rx * REGION_X ^ rz * REGION_Z);
        int span = type.spacing() - type.separation();
        int chunkX = rx * type.spacing() + random.nextInt(span);
        int chunkZ = rz * type.spacing() + random.nextInt(span);
        int x = chunkX * Chunk.SIZE_X + Chunk.SIZE_X / 2, z = chunkZ * Chunk.SIZE_Z + Chunk.SIZE_Z / 2;
        // Drawn before the biome test, so the stream an assembler gets is the
        // same whatever the height mode does with it.
        int band = type.height().surface() ? 0 : random.nextInt(type.height().maxY() - type.height().minY() + 1);
        if (!type.biomes().test(terrain.biome(x, z)))
            return null;
        int y = type.height().surface() ? terrain.height(x, z) : type.height().minY() + band;
        List<StructurePiece> pieces = List.copyOf(type.assembler().assemble(
                new Context(type, seed, chunkX, chunkZ, x, y, z, random, terrain)));
        if (pieces.isEmpty())
            return null;
        BoundingBox reach = new BoundingBox(
                (chunkX - type.maxRadiusChunks()) * Chunk.SIZE_X, 0, (chunkZ - type.maxRadiusChunks()) * Chunk.SIZE_Z,
                (chunkX + type.maxRadiusChunks() + 1) * Chunk.SIZE_X - 1, Chunk.SIZE_Y - 1,
                (chunkZ + type.maxRadiusChunks() + 1) * Chunk.SIZE_Z - 1);
        BoundingBox all = null;
        for (int i = 0; i < pieces.size(); i++) {
            BoundingBox b = pieces.get(i).box();
            if (reach.intersection(b) == null || !reach.intersection(b).equals(b))
                throw new IllegalStateException(type.id() + ": piece " + b + " reaches past "
                        + type.maxRadiusChunks() + " chunks from " + chunkX + "," + chunkZ);
            for (int j = 0; j < i; j++)
                if (b.intersects(pieces.get(j).box()))
                    throw new IllegalStateException(type.id() + ": pieces " + j + " and " + i + " overlap");
            all = all == null ? b : all.union(b);
        }
        return new StructureStart(type, rx, rz, chunkX, chunkZ, y, pieces, all);
    }

    /** The piece holding a block, or null. */
    public StructurePiece pieceAt(int x, int y, int z) {
        if (!box.contains(x, y, z))
            return null;
        for (StructurePiece p : pieces)
            if (p.box().contains(x, y, z))
                return p;
        return null;
    }
}

package com.mineclone.world.structure;

/**
 * One part of a structure: a box it owns and how to fill it (GEN-03).
 *
 * <p>A piece is placed once per chunk it crosses, each time cut to that
 * chunk ({@code clip}). The cuts must add up to the whole, whatever order the
 * chunks come in, so a piece decides every block from its position and its
 * own fields — {@link #noise} — never from a random stream that advances as it
 * writes: the chunk that places the second half would otherwise see other
 * numbers than a single placement would.
 */
public interface StructurePiece {
    /** The blocks this piece may write. Pieces of one structure never share a block. */
    BoundingBox box();

    /** Writes the part of this piece inside {@code clip} (a part of {@link #box()}). */
    void place(StructureWriter out, BoundingBox clip);

    /** A well-mixed hash of a position and a salt, for per-block choices. */
    static long noise(int x, int y, int z, long salt) {
        long v = x * 341873128712L + y * 2654435761L + z * 132897987541L + salt;
        v ^= v >>> 33;
        v *= 0xff51afd7ed558ccdL;
        v ^= v >>> 33;
        v *= 0xc4ceb9fe1a85ec53L;
        v ^= v >>> 33;
        return v & Long.MAX_VALUE;
    }
}

package com.mineclone.world.gen;

/**
 * The generation changes a chunk is made with. Each 1.1 change reads its own
 * flag, so V1 chunks keep coming out exactly as 1.0 made them.
 *
 * <p>A world stores the flags it was created with (the level's {@code worldgen}
 * section), not just its version: a flag switched on by a later build must not
 * reach land an earlier build already showed. Every flag keeps its bit forever —
 * append new ones at the end, like block ordinals.
 */
public record GenFeatures(boolean copperOre, boolean vegetation2, boolean structures2,
                          boolean caves2, boolean livestockAtGen) {
    /** How many flags this build has names for; a bit at or past this index came from a newer build. */
    public static final int KNOWN_FLAGS = 5;

    public static final GenFeatures V1 = new GenFeatures(false, false, false, false, false);
    /**
     * The 1.1 changes this build actually implements. Switch a flag on here in the
     * commit that implements it, never before: worlds record this set.
     */
    public static final GenFeatures V2 = new GenFeatures(false, false, false, false, false);

    public static GenFeatures of(WorldGenVersion version) {
        return switch (version) {
            case V1 -> V1;
            case V2 -> V2;
        };
    }

    public int bits() {
        return (copperOre ? 1 : 0) | (vegetation2 ? 1 << 1 : 0) | (structures2 ? 1 << 2 : 0)
                | (caves2 ? 1 << 3 : 0) | (livestockAtGen ? 1 << 4 : 0);
    }

    /** @throws IllegalArgumentException for a bit this build does not know */
    public static GenFeatures fromBits(int bits) {
        if ((bits & -(1 << KNOWN_FLAGS)) != 0)
            throw new IllegalArgumentException("unknown world generator features 0x" + Integer.toHexString(bits));
        return new GenFeatures((bits & 1) != 0, (bits & 1 << 1) != 0, (bits & 1 << 2) != 0,
                (bits & 1 << 3) != 0, (bits & 1 << 4) != 0);
    }

    /** Whether every flag set here is also set in {@code other}. */
    public boolean within(GenFeatures other) {
        return (bits() & ~other.bits()) == 0;
    }
}

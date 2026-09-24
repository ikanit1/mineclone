package com.mineclone.world.gen;

/**
 * Which generator made a chunk.
 *
 * <p>Only edited chunks are saved; everything else is regenerated from the seed
 * each time it loads. A generator change would therefore silently rewrite every
 * untouched chunk of every old world, and a guest generating with another
 * version would receive wrong deltas. So generation is versioned: V1 is the
 * generator of 1.0-alpha, frozen by {@code WorldGenGoldenTests}; V2 gathers what
 * 1.1 adds. The chunk ledger (GEN-02) keeps each chunk on the version that first
 * generated it, so new features appear only in land nobody has seen.
 */
public enum WorldGenVersion {
    V1(1),
    V2(2);

    /**
     * The version new worlds are created with. It stays V1 until V2 has
     * something to generate (GEN-08's copper is the first): a V2 world with
     * nothing new would only make 1.0 builds refuse it. Guests learn the host's
     * generator from the welcome (protocol v8), so moving it breaks no room.
     */
    public static final WorldGenVersion LATEST = V1;

    private final int id;

    WorldGenVersion(int id) { this.id = id; }

    /** Stable number written to saves and packets; never the ordinal. */
    public int id() { return id; }

    /** The version with this number, or null when this build does not know it. */
    public static WorldGenVersion byId(int id) {
        for (WorldGenVersion version : values()) if (version.id == id) return version;
        return null;
    }
}

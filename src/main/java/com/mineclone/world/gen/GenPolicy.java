package com.mineclone.world.gen;

/**
 * Which generator version a chunk is generated with.
 *
 * <p>Asked on every chunk generation, from worker threads, so implementations
 * must be thread-safe. A fixed policy covers every world today; the chunk
 * ledger (GEN-02) will answer per chunk.
 */
@FunctionalInterface
public interface GenPolicy {
    WorldGenVersion versionAt(int cx, int cz);

    default GenFeatures featuresAt(int cx, int cz) {
        return GenFeatures.of(versionAt(cx, cz));
    }

    static GenPolicy fixed(WorldGenVersion version) {
        java.util.Objects.requireNonNull(version, "version");
        return (cx, cz) -> version;
    }
}

package com.mineclone.world.gen;

import java.util.Map;

/**
 * Which generator version a chunk is generated with.
 *
 * <p>Asked on every chunk generation, from worker threads, so implementations
 * must be thread-safe. The game and the server answer from the chunk ledger
 * ({@link LedgerPolicy}); guests from what the host told them; tests and
 * benchmarks from a fixed version.
 */
@FunctionalInterface
public interface GenPolicy {
    WorldGenVersion versionAt(int cx, int cz);

    default GenFeatures featuresAt(int cx, int cz) {
        return GenFeatures.of(versionAt(cx, cz));
    }

    /**
     * The world's own generator — what every chunk not {@link #pinned()} gets —
     * or null when this policy cannot say. A host sends it to its guests.
     */
    default WorldGenSettings settings() {
        return null;
    }

    /** Chunks generated with another version than the world's own, by chunk key. */
    default Map<Long, WorldGenVersion> pinned() {
        return Map.of();
    }

    /** Every chunk on {@code version}, with everything that version implements. */
    static GenPolicy fixed(WorldGenVersion version) {
        java.util.Objects.requireNonNull(version, "version");
        return new Fixed(new WorldGenSettings(version, GenFeatures.of(version), 0L));
    }

    /** Every chunk on the settings' version, with the settings' features. */
    record Fixed(WorldGenSettings settings) implements GenPolicy {
        public Fixed {
            java.util.Objects.requireNonNull(settings, "settings");
        }

        @Override public WorldGenVersion versionAt(int cx, int cz) { return settings.version(); }
        @Override public GenFeatures featuresAt(int cx, int cz) { return settings.features(); }
    }
}

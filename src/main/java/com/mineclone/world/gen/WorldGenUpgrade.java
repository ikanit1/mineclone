package com.mineclone.world.gen;

import com.mineclone.world.World;

/**
 * Moving a world to a newer generator (GEN-02) without changing land anyone
 * has seen.
 *
 * <p>Every chunk the ledger recorded keeps its version already. What it cannot
 * know — chunks generated before the ledger existed — is approximated from what
 * the world saved: every saved chunk and {@link #SEEN_RADIUS} chunks around it,
 * and as much around the spawn point and the player. Those are pinned to the
 * old version; everything else gets the new generator as it is first reached.
 * The same approximation rebuilds a lost ledger, conservatively.
 */
public final class WorldGenUpgrade {
    /** How far around a saved chunk, the spawn point or a player land counts as seen, in chunks. */
    public static final int SEEN_RADIUS = 12;

    private WorldGenUpgrade() {}

    /**
     * Pins the seen land to the world's current version and returns the settings
     * of the upgraded world. {@code anchors} are block positions (x, z) of the
     * spawn point and of every stored player.
     */
    public static WorldGenSettings upgrade(WorldGenSettings from, WorldGenVersion target, ChunkLedger ledger,
                                           Iterable<Long> savedChunks, float[][] anchors, long now) {
        if (target.id() <= from.version().id())
            throw new IllegalArgumentException("cannot upgrade " + from.version() + " to " + target);
        pinSeen(ledger, from.version(), savedChunks, anchors);
        return new WorldGenSettings(target, GenFeatures.of(target), now);
    }

    /** Records every chunk near a saved chunk or an anchor with {@code version}, unless it is recorded already. */
    public static void pinSeen(ChunkLedger ledger, WorldGenVersion version, Iterable<Long> savedChunks,
                               float[][] anchors) {
        for (long key : savedChunks)
            pinAround(ledger, version, (int) (key >> 32), (int) key);
        for (float[] at : anchors)
            pinAround(ledger, version, Math.floorDiv((int) Math.floor(at[0]), 16),
                    Math.floorDiv((int) Math.floor(at[1]), 16));
    }

    private static void pinAround(ChunkLedger ledger, WorldGenVersion version, int cx, int cz) {
        for (int dx = -SEEN_RADIUS; dx <= SEEN_RADIUS; dx++)
            for (int dz = -SEEN_RADIUS; dz <= SEEN_RADIUS; dz++)
                ledger.record(World.key(cx + dx, cz + dz), version);
    }

    /**
     * The recorded chunks whose side neighbour generates with another version, in
     * key order; a chunk the ledger does not know yet takes {@code own}, the
     * world's version. After an upgrade they ring the pinned land: where terrain
     * of two generators meets and may not line up.
     */
    public static java.util.List<Long> seams(ChunkLedger ledger, WorldGenVersion own) {
        java.util.Map<Long, WorldGenVersion> recorded = ledger.snapshot();
        java.util.List<Long> seams = new java.util.ArrayList<>();
        for (var entry : recorded.entrySet()) {
            int cx = (int) (entry.getKey() >> 32), cz = (int) (long) entry.getKey();
            WorldGenVersion version = entry.getValue();
            if (recorded.getOrDefault(World.key(cx + 1, cz), own) != version
                    || recorded.getOrDefault(World.key(cx - 1, cz), own) != version
                    || recorded.getOrDefault(World.key(cx, cz + 1), own) != version
                    || recorded.getOrDefault(World.key(cx, cz - 1), own) != version)
                seams.add(entry.getKey());
        }
        java.util.Collections.sort(seams);
        return seams;
    }
}

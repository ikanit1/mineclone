package com.mineclone.world.gen;

import java.nio.ByteBuffer;

/**
 * The level's {@code worldgen} section: which generator the world uses, the
 * 1.1 changes it was created with, and when it was last upgraded (0 when never).
 *
 * <p>Layout: {@code int version, int feature bits, long upgradedAt}. The M0
 * build wrote the version alone, four bytes; that form still reads, without
 * features. A level without the section predates versioning and is V1. A
 * version this build does not know, or a change it does not implement yet,
 * refuses the world ({@code SaveManager}) rather than regenerating its unedited
 * land with the wrong generator. The layout is fixed: new generator data belongs
 * in a section of its own, or an older build would report a newer world as
 * damaged.
 */
public record WorldGenSettings(WorldGenVersion version, GenFeatures features, long upgradedAt) {
    public static final String SAVE_SECTION = "worldgen";
    /** What a level without the section means: everything before 1.1 is V1. */
    public static final WorldGenSettings LEGACY = new WorldGenSettings(WorldGenVersion.V1, GenFeatures.V1, 0L);

    private static final int M0_LENGTH = Integer.BYTES;
    private static final int LENGTH = Integer.BYTES + Integer.BYTES + Long.BYTES;

    public WorldGenSettings {
        java.util.Objects.requireNonNull(version, "version");
        java.util.Objects.requireNonNull(features, "features");
        // V1 is frozen; a later version never gains a change it did not have.
        if (!features.within(GenFeatures.of(version)))
            throw new IllegalArgumentException(version + " cannot generate " + features);
        if (upgradedAt < 0) throw new IllegalArgumentException("invalid upgrade time " + upgradedAt);
    }

    /** Settings for a world created now. */
    public static WorldGenSettings forNewWorld() {
        return new WorldGenSettings(WorldGenVersion.LATEST, GenFeatures.of(WorldGenVersion.LATEST), 0L);
    }

    public byte[] encode() {
        return ByteBuffer.allocate(LENGTH).putInt(version.id()).putInt(features.bits()).putLong(upgradedAt).array();
    }

    /** The stored version number, readable even when this build does not know it. */
    public static int storedVersion(byte[] section) {
        if (section == null) return WorldGenVersion.V1.id();
        checkLength(section);
        return ByteBuffer.wrap(section).getInt();
    }

    /** The stored feature bits, readable even when this build does not know them all. */
    public static int storedFeatures(byte[] section) {
        if (section == null) return 0;
        checkLength(section);
        return section.length == M0_LENGTH ? 0 : ByteBuffer.wrap(section).getInt(Integer.BYTES);
    }

    private static void checkLength(byte[] section) {
        if (section.length != M0_LENGTH && section.length != LENGTH)
            throw new IllegalArgumentException("invalid worldgen section length " + section.length);
    }

    /**
     * @throws IllegalArgumentException for a malformed section, an unknown version
     *         or feature, or a feature its version cannot have
     */
    public static WorldGenSettings decode(byte[] section) {
        if (section == null) return LEGACY;
        WorldGenVersion version = WorldGenVersion.byId(storedVersion(section));
        if (version == null)
            throw new IllegalArgumentException("unknown world generator version " + storedVersion(section));
        GenFeatures features = GenFeatures.fromBits(storedFeatures(section));
        long upgradedAt = section.length == M0_LENGTH ? 0L : ByteBuffer.wrap(section).getLong(2 * Integer.BYTES);
        return new WorldGenSettings(version, features, upgradedAt);
    }

    /**
     * Every chunk on the version its ledger recorded; a chunk not there yet is
     * generated with the world's own version and its features, and recorded —
     * so a later upgrade knows exactly which land was already seen.
     */
    public GenPolicy policy(ChunkLedger ledger) {
        java.util.Objects.requireNonNull(ledger, "ledger");
        WorldGenVersion own = version;
        GenFeatures ownFeatures = features;
        return new GenPolicy() {
            @Override
            public WorldGenVersion versionAt(int cx, int cz) {
                long key = com.mineclone.world.World.key(cx, cz);
                WorldGenVersion at = ledger.versionAt(key, own);
                ledger.record(key, at);
                return at;
            }

            @Override
            public GenFeatures featuresAt(int cx, int cz) {
                WorldGenVersion at = versionAt(cx, cz);
                return at == own ? ownFeatures : GenFeatures.of(at);
            }
        };
    }

    /** The whole world on its own version, with the features it recorded. */
    public GenPolicy policy() {
        WorldGenVersion fixed = version;
        return new GenPolicy() {
            @Override public WorldGenVersion versionAt(int cx, int cz) { return fixed; }
            @Override public GenFeatures featuresAt(int cx, int cz) { return features; }
        };
    }
}

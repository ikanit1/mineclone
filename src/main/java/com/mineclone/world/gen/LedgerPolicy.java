package com.mineclone.world.gen;

import com.mineclone.world.World;
import java.util.Map;
import java.util.Objects;

/**
 * Every chunk on the version its ledger recorded (GEN-02); a chunk not there
 * yet is generated with the world's own version and features, and recorded —
 * so a later upgrade knows exactly which land was already seen. A guest builds
 * one from the host's settings and pinned chunks ({@code S_GEN_MAP}) and never
 * saves it.
 */
public final class LedgerPolicy implements GenPolicy {
    private final WorldGenSettings settings;
    private final ChunkLedger ledger;

    public LedgerPolicy(WorldGenSettings settings, ChunkLedger ledger) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
    }

    @Override
    public WorldGenVersion versionAt(int cx, int cz) {
        long key = World.key(cx, cz);
        WorldGenVersion at = ledger.versionAt(key, settings.version());
        ledger.record(key, at);
        return at;
    }

    @Override
    public GenFeatures featuresAt(int cx, int cz) {
        return featuresOf(versionAt(cx, cz));
    }

    /** What {@code version} generates here: the world's own version with the features it recorded. */
    public GenFeatures featuresOf(WorldGenVersion version) {
        return version == settings.version() ? settings.features() : GenFeatures.of(version);
    }

    @Override public WorldGenSettings settings() { return settings; }

    @Override
    public Map<Long, WorldGenVersion> pinned() {
        return ledger.except(settings.version());
    }

    public ChunkLedger ledger() { return ledger; }
}

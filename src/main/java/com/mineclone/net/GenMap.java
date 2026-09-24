package com.mineclone.net;

import com.mineclone.world.gen.ChunkLedger;
import com.mineclone.world.gen.WorldGenVersion;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * {@code S_GEN_MAP}, protocol v8 (GEN-02): the chunks of the host's world that
 * were generated with another version than the world's own — land an upgrade
 * kept on the old generator. It comes before {@code S_WELCOME}, so the guest's
 * world generates them right from its first chunk. A world that was never
 * upgraded sends an empty map.
 *
 * <p>Body: {@code bytes gzip(ledger)} — the chunk ledger's own encoding
 * ({@link ChunkLedger#encode}): sorted key deltas as VarLongs, versions as
 * runs; 2 000 pinned chunks take a few hundred bytes.
 */
public record GenMap(Map<Long, WorldGenVersion> pinned) {
    /** A gzip that inflates past this is a bomb, not a world. */
    public static final int MAX_INFLATED = 4 * 1024 * 1024;

    public GenMap {
        pinned = Map.copyOf(pinned);
    }

    public void write(PacketBuf b) {
        var bytes = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(bytes)) {
            gzip.write(ChunkLedger.of(pinned).encode());
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        b.bytes(bytes.toByteArray());
    }

    /** @return the map, or null for a body that is cut, damaged or names a version this build lacks */
    public static GenMap read(PacketBuf b) {
        byte[] body = b.readBytes();
        if (b.truncated())
            return null;
        try (var in = new GZIPInputStream(new ByteArrayInputStream(body))) {
            byte[] raw = in.readNBytes(MAX_INFLATED + 1);
            if (raw.length > MAX_INFLATED)
                return null;
            return new GenMap(ChunkLedger.decode(raw).snapshot());
        } catch (IOException | RuntimeException damaged) {
            return null;
        }
    }
}

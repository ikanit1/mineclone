package com.mineclone.save;

/** One chunk's full block + meta arrays (length = SaveFormat.CHUNK_VOLUME). */
public final class ChunkSnapshot {
    public final int cx, cz;
    public final byte[] blocks;
    public final byte[] meta;

    public ChunkSnapshot(int cx, int cz, byte[] blocks, byte[] meta) {
        this.cx = cx; this.cz = cz;
        this.blocks = blocks; this.meta = meta;
    }
}

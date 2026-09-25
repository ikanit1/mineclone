package com.mineclone.world.structure;

import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;

/**
 * Writes into the one chunk being generated, in world coordinates. Generation
 * runs on worker threads, each on its own detached chunk, so a write that
 * falls outside that chunk is refused and counted, never forwarded: a piece
 * that asks for one has ignored its clip box ({@code StructureTests} checks
 * the count stays zero).
 */
public final class ChunkWriter implements StructureWriter {
    private final Chunk chunk;
    private final int x0, z0;
    private final BoundingBox box;
    private int refused;

    public ChunkWriter(Chunk chunk) {
        this.chunk = chunk;
        this.x0 = chunk.cx * Chunk.SIZE_X;
        this.z0 = chunk.cz * Chunk.SIZE_Z;
        this.box = BoundingBox.chunk(chunk.cx, chunk.cz);
    }

    /** The chunk's column. */
    public BoundingBox box() {
        return box;
    }

    @Override
    public void set(int x, int y, int z, BlockType block, byte meta) {
        if (!box.contains(x, y, z)) {
            refused++;
            return;
        }
        chunk.set(x - x0, y, z - z0, block);
        chunk.setMeta(x - x0, y, z - z0, meta);
    }

    @Override
    public BlockType get(int x, int y, int z) {
        return box.contains(x, y, z) ? chunk.get(x - x0, y, z - z0) : BlockType.AIR;
    }

    /** Writes that fell outside the chunk. */
    public int refused() {
        return refused;
    }
}

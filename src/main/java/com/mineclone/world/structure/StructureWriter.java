package com.mineclone.world.structure;

import com.mineclone.world.BlockType;

/**
 * Where a structure piece puts its blocks, in world coordinates: the chunk
 * being generated ({@link ChunkWriter}) or, in tests, one large buffer.
 */
public interface StructureWriter {
    void set(int x, int y, int z, BlockType block, byte meta);

    default void set(int x, int y, int z, BlockType block) {
        set(x, y, z, block, (byte) 0);
    }

    /** What stands there now; air outside what this writer covers. */
    BlockType get(int x, int y, int z);
}

package com.mineclone.world.structure;

import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.item.Items;
import com.mineclone.world.ItemStack;

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
    private final long seed;

    public ChunkWriter(Chunk chunk) {
        this(chunk, 0);
    }

    public ChunkWriter(Chunk chunk, long seed) {
        this.chunk = chunk;
        this.seed = seed;
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

    @Override
    public void chest(int x, int y, int z, String tableId) {
        if (!box.contains(x, y, z)) {
            refused++;
            return;
        }
        int lx = x - x0, lz = z - z0;
        // Repeated placement never replenishes an existing (even empty) chest.
        if (chunk.get(lx, y, lz) == BlockType.CHEST && chunk.getChest(lx, y, lz) != null) return;
        ItemStack[] loot = new ItemStack[Chunk.CHEST_SLOTS];
        Items.get().loot().fillChest(loot, tableId, seed, x, y, z);
        set(x, y, z, BlockType.CHEST);
        System.arraycopy(loot, 0, chunk.createChest(lx, y, lz), 0, loot.length);
    }

    /** Writes that fell outside the chunk. */
    public int refused() {
        return refused;
    }
}

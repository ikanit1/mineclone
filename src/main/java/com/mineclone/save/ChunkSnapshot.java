package com.mineclone.save;

import com.mineclone.world.Furnace;
import com.mineclone.world.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * Снимок чанка: блоки, meta, сундуки и печи.
 *
 * Всё содержимое едет вместе с чанком, а не отдельными файлами: иначе
 * пришлось бы заводить вторую систему выгрузки и сохранения — ровно ту же,
 * что уже есть у чанков, только с собственными гонками.
 */
public final class ChunkSnapshot {
    public final int cx, cz;
    public final byte[] blocks;
    public final byte[] meta;
    /** Ключ — {@code Chunk.idx} позиции блока, значение — слоты сундука. */
    public final Map<Integer, ItemStack[]> chests;
    /** Ключ — {@code Chunk.idx} позиции блока, значение — состояние печи. */
    public final Map<Integer, Furnace> furnaces;
    /** Предметы, лежащие на земле в этом чанке. */
    public final java.util.List<com.mineclone.world.DroppedItem> items;

    public ChunkSnapshot(int cx, int cz, byte[] blocks, byte[] meta) {
        this(cx, cz, blocks, meta, new HashMap<>(), new HashMap<>());
    }

    public ChunkSnapshot(int cx, int cz, byte[] blocks, byte[] meta,
                         Map<Integer, ItemStack[]> chests) {
        this(cx, cz, blocks, meta, chests, new HashMap<>());
    }

    public ChunkSnapshot(int cx, int cz, byte[] blocks, byte[] meta,
                         Map<Integer, ItemStack[]> chests,
                         Map<Integer, Furnace> furnaces) {
        this(cx, cz, blocks, meta, chests, furnaces, null);
    }

    public ChunkSnapshot(int cx, int cz, byte[] blocks, byte[] meta,
                         Map<Integer, ItemStack[]> chests,
                         Map<Integer, Furnace> furnaces,
                         java.util.List<com.mineclone.world.DroppedItem> items) {
        this.cx = cx; this.cz = cz;
        this.blocks = blocks; this.meta = meta;
        this.chests = chests == null ? new HashMap<>() : chests;
        this.furnaces = furnaces == null ? new HashMap<>() : furnaces;
        this.items = items == null ? java.util.List.of() : items;
    }
}

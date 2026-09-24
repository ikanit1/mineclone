package com.mineclone.item.loot;

import com.mineclone.data.DataPack;
import com.mineclone.data.ResourceId;
import com.mineclone.item.Item;
import com.mineclone.item.ItemRegistry;
import com.mineclone.world.BlockType;
import com.mineclone.world.GameMode;
import com.mineclone.world.ItemStack;
import com.mineclone.world.entity.MobType;
import java.util.*;

/** Content registry; block harvestability stays in BlockProps, tables decide only the outputs. */
public final class LootRegistry {
    private final ItemRegistry items;
    private final Map<ResourceId, LootTable> tables;
    private final Map<BlockType, LootTable> blocks;
    private final Map<MobType, LootTable> entities;

    private LootRegistry(ItemRegistry items, Map<ResourceId, LootTable> tables,
                         Map<BlockType, LootTable> blocks, Map<MobType, LootTable> entities) {
        this.items = items;
        this.tables = Collections.unmodifiableMap(new LinkedHashMap<>(tables));
        this.blocks = Map.copyOf(blocks); this.entities = Map.copyOf(entities);
    }
    public static LootRegistry empty(ItemRegistry items) { return new LootRegistry(items, Map.of(), Map.of(), Map.of()); }
    public static LootRegistry load(DataPack pack) { return ItemRegistry.load(pack).loot(); }
    public static LootRegistry load(DataPack pack, ItemRegistry items) {
        Map<ResourceId, LootTable> tables = new LinkedHashMap<>();
        Map<BlockType, LootTable> blocks = new EnumMap<>(BlockType.class);
        Map<MobType, LootTable> entities = new EnumMap<>(MobType.class);
        for (DataPack.Entry file : pack.files("loot_tables")) {
            String path = file.relPath().substring(0, file.relPath().length() - ".json".length());
            ResourceId id;
            try { id = new ResourceId(file.namespace(), path); }
            catch (IllegalArgumentException bad) { throw file.json().error("", bad.getMessage()); }
            if (!(path.startsWith("blocks/") || path.startsWith("entities/") || path.startsWith("chests/")))
                throw file.json().error("", "loot table must be in blocks, entities or chests");
            LootTable table = LootTable.parse(id, file.json(), items);
            if (tables.putIfAbsent(id, table) != null) throw file.json().error("", "duplicate loot table " + id);
            if (file.namespace().equals(ResourceId.DEFAULT_NAMESPACE)) {
                if (path.startsWith("blocks/")) {
                    try { blocks.put(BlockType.valueOf(path.substring(7).toUpperCase(Locale.ROOT)), table); }
                    catch (IllegalArgumentException bad) { throw file.json().error("", "unknown block " + path.substring(7)); }
                } else if (path.startsWith("entities/")) {
                    try { entities.put(MobType.valueOf(path.substring(9).toUpperCase(Locale.ROOT)), table); }
                    catch (IllegalArgumentException bad) { throw file.json().error("", "unknown mob " + path.substring(9)); }
                }
            }
        }
        return new LootRegistry(items, tables, blocks, entities);
    }

    public Map<ResourceId, LootTable> tables() { return tables; }
    public LootTable get(ResourceId id) { return tables.get(id); }
    public LootTable require(String id) {
        LootTable table = tables.get(ResourceId.of(id));
        if (table == null) throw new IllegalArgumentException("unknown loot table " + id);
        return table;
    }
    public static boolean canHarvest(BlockType block, ItemStack held) {
        if (block == null) return false;
        int required = block.requiredToolLevel();
        if (required <= 0) return true;
        var tool = held == null || held.count <= 0 ? null : held.tool();
        return tool != null && tool.suits(block) && tool.level() >= required;
    }
    public List<ItemStack> blockDrops(BlockType block, LootContext context) {
        if (context.mode() == GameMode.CREATIVE || !canHarvest(block, context.tool())) return List.of();
        LootTable table = blocks.get(block);
        if (table != null) return table.roll(context);
        Item self = items.forBlock(block);
        return self == null ? List.of() : List.of(new ItemStack(self, 1));
    }
    public Set<Item> blockSources(BlockType block, LootContext context) {
        if (context.mode() == GameMode.CREATIVE || !canHarvest(block, context.tool())) return Set.of();
        LootTable table = blocks.get(block);
        if (table != null) return table.possibleItems(context);
        Item self = items.forBlock(block);
        return self == null ? Set.of() : Set.of(self);
    }
    public List<ItemStack> entityDrops(MobType type, LootContext context) {
        LootTable table = entities.get(type);
        return table == null ? List.of() : table.roll(context);
    }
    public Set<Item> entitySources(MobType type, LootContext context) {
        LootTable table = entities.get(type);
        return table == null ? Set.of() : table.possibleItems(context);
    }
    public List<ItemStack> chestDrops(String table, long seed, int x, int y, int z) {
        LootTable resolved = require(table);
        if (!resolved.id().path().startsWith("chests/")) throw new IllegalArgumentException("not a chest loot table " + table);
        return resolved.roll(LootContext.chest(seed, x, y, z));
    }
}

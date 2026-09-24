package com.mineclone.item;

import com.mineclone.data.DataPack;
import com.mineclone.data.JsonException;
import com.mineclone.data.JsonObject;
import com.mineclone.data.ResourceId;
import com.mineclone.item.recipe.RecipeRegistry;
import com.mineclone.render.TextureAtlas;
import com.mineclone.world.BlockType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Реестр предметов, собранный из каталога данных.
 *
 * <p>Единственный владелец экземпляров {@link Item}: стопка хранит ссылку, а
 * не копию, поэтому два предмета одинаковы тогда и только тогда, когда это
 * один и тот же объект. Реестр неизменяем после загрузки.
 *
 * <p>Любая ошибка в данных — {@link JsonException} с именем файла и путём до
 * ключа. Терпимость здесь была бы вредной: опечатка в категории тихо
 * выбросила бы предмет из креатива, и искать причину пришлось бы глазами.
 */
public final class ItemRegistry {

    /** Блоки, которых не бывает в руках: их предмет — ошибка данных. */
    private static final Set<BlockType> TECHNICAL =
            Set.of(BlockType.AIR, BlockType.WATER_FLOW, BlockType.DOOR_OPEN);

    private static final int DEFAULT_MAX_STACK = 64;

    private final Map<ResourceId, Item> byId;
    private final Item[] byBlock;
    private final Categories categories;
    private final Map<ResourceId, Item> missing = new HashMap<>();
    private TagRegistry tags = TagRegistry.empty();
    private RecipeRegistry recipes = RecipeRegistry.empty();

    private ItemRegistry(Map<ResourceId, Item> byId, Item[] byBlock, Categories categories) {
        this.byId = byId;
        this.byBlock = byBlock;
        this.categories = categories;
    }

    // ------------------------------------------------------------- загрузка

    public static ItemRegistry load(DataPack pack) {
        List<JsonObject> catFiles = new ArrayList<>();
        for (String ns : pack.namespaces()) {
            JsonObject root = pack.root(ns, "categories.json");
            if (root != null)
                catFiles.add(root);
        }
        Categories categories = Categories.load(catFiles);

        Map<ResourceId, Item> byId = new LinkedHashMap<>();
        Item[] byBlock = new Item[BlockType.VALUES.length];
        for (DataPack.Entry e : pack.files("items")) {
            JsonObject file = e.json();
            for (String key : file.keys()) {
                Item item = parse(file, key, e.namespace(), categories);
                if (byId.put(item.id, item) != null)
                    throw file.error(key, "duplicate item " + item.id);
                if (item.block != null) {
                    Item old = byBlock[item.block.ordinal()];
                    if (old != null)
                        throw file.error(key, "block " + item.block + " already belongs to " + old.id);
                    byBlock[item.block.ordinal()] = item;
                }
            }
        }

        ItemRegistry reg = new ItemRegistry(byId, byBlock, categories);
        reg.tags = TagRegistry.load(pack, byId.values(), byId);
        // Recipes resolve item identities and tag memberships from this registry.
        reg.recipes = RecipeRegistry.load(pack, reg);
        return reg;
    }

    private static Item parse(JsonObject file, String key, String namespace, Categories categories) {
        JsonObject o = file.object(key);
        o.allowOnly("name", "icon", "category", "mass", "max_stack", "durability",
                "hidden", "fuel", "block", "tool", "attack", "food");

        ResourceId id;
        try {
            id = new ResourceId(namespace, key);
        } catch (IllegalArgumentException bad) {
            throw file.error(key, bad.getMessage());
        }

        String category = o.string("category");
        if (!categories.has(category))
            throw o.error("category", "unknown category " + category);

        BlockType block = null;
        if (o.has("block")) {
            String name = o.string("block");
            try {
                block = BlockType.valueOf(name.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException bad) {
                throw o.error("block", "unknown block " + name);
            }
            if (TECHNICAL.contains(block))
                throw o.error("block", block + " is technical and cannot be an item");
        }

        ToolSpec tool = null;
        if (o.has("tool")) {
            JsonObject t = o.object("tool");
            t.allowOnly("class", "level", "speed");
            ToolClass cls = ToolClass.byName(t.string("class"));
            if (cls == null)
                throw t.error("class", "unknown tool class " + t.string("class"));
            tool = new ToolSpec(cls, t.integer("level"), t.number("speed"));
        }

        AttackSpec attack = null;
        if (o.has("attack")) {
            JsonObject a = o.object("attack");
            a.allowOnly("damage", "speed");
            attack = new AttackSpec(a.number("damage"), a.number("speed", 1f));
        }

        FoodSpec food = null;
        if (o.has("food")) {
            JsonObject f = o.object("food");
            f.allowOnly("nutrition");
            food = new FoodSpec(f.integer("nutrition"));
        }

        int durability = o.integer("durability", 0);
        if (durability < 0)
            throw o.error("durability", "must not be negative");
        // Изнашиваемый предмет не стопкуется: две кирки с разным износом —
        // это две разные стопки, и сливать их некуда.
        int maxStack = o.integer("max_stack", durability > 0 ? 1 : DEFAULT_MAX_STACK);
        if (maxStack < 1)
            throw o.error("max_stack", "must be at least 1");
        if (durability > 0 && maxStack != 1)
            throw o.error("max_stack", "an item with durability cannot stack");

        int iconTile = icon(o, block);

        return new Item(id, o.string("name"), category, o.number("mass", 1f), maxStack,
                durability, o.bool("hidden", false), o.number("fuel", 0f),
                block, tool, attack, food, iconTile, false);
    }

    /**
     * Тайл плоской иконки. Куб рисуется по самому блоку, поэтому у него
     * {@code -1}; кресту, слою и жидкости объёмная иконка только вредит.
     */
    private static int icon(JsonObject o, BlockType block) {
        if (o.has("icon")) {
            String name = o.string("icon");
            int tile = tileByName(name);
            if (tile < 0)
                throw o.error("icon", "unknown tile " + name);
            return tile;
        }
        if (block == null)
            throw o.error("icon", "missing: an item without a block needs an icon");
        if (!isFlat(block))
            return -1;
        return block == BlockType.GRASS || block == BlockType.SNOWY_GRASS
                ? block.topTile : block.sideTile;
    }

    private static boolean isFlat(BlockType b) {
        return b.isCross() || b.isLayered()
                || b == BlockType.WATER || b == BlockType.WATER_FLOW || b == BlockType.LAVA;
    }

    private static int tileByName(String name) {
        String[] names = TextureAtlas.TILE_NAMES;
        for (int i = 0; i < names.length; i++)
            if (names[i].equals(name))
                return i;
        return -1;
    }

    // ---------------------------------------------------------------- доступ

    /** {@code "stone"} или {@code "mineclone:stone"}; {@code null} — такого нет. */
    public Item get(String id) {
        try {
            return byId.get(ResourceId.of(id));
        } catch (IllegalArgumentException bad) {
            return null;
        }
    }

    public Item get(ResourceId id) {
        return byId.get(id);
    }

    public Item require(String id) {
        Item item = get(id);
        if (item == null)
            throw new IllegalArgumentException("no such item: " + id);
        return item;
    }

    /** Предмет, который ставит этот блок; {@code null} у технических блоков. */
    public Item forBlock(BlockType block) {
        return block == null ? null : byBlock[block.ordinal()];
    }

    /**
     * Заглушка для id, которого больше нет в данных.
     *
     * <p>Сейв со снесённым модом обязан открыться: предмет становится видимой
     * пустышкой, а не исчезает молча — иначе игрок не узнает, что потерял.
     * Экземпляр кэшируется: стопки сравниваются по тождеству предмета.
     */
    public synchronized Item missing(ResourceId id) {
        return missing.computeIfAbsent(id, k -> new Item(k, k.toString(), fallbackCategory(),
                1f, DEFAULT_MAX_STACK, 0, true, 0f, null, null, null, null, 0, true));
    }

    private String fallbackCategory() {
        return categories.size() > 0 ? categories.ids().get(categories.size() - 1) : "misc";
    }

    /** Порядок файлов, затем ключей внутри файла. */
    public List<Item> all() {
        return List.copyOf(byId.values());
    }

    public TagRegistry tags() {
        return tags;
    }

    public RecipeRegistry recipes() { return recipes; }

    public Categories categories() {
        return categories;
    }

    public int size() {
        return byId.size();
    }
}

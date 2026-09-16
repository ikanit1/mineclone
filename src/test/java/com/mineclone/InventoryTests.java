package com.mineclone;

import com.mineclone.data.Json;
import com.mineclone.data.JsonException;
import com.mineclone.data.JsonObject;
import com.mineclone.data.DataPack;
import com.mineclone.data.ResourceId;
import com.mineclone.item.Categories;
import com.mineclone.item.Item;
import com.mineclone.item.ItemRegistry;
import com.mineclone.item.Items;
import com.mineclone.item.Tag;
import com.mineclone.item.TagRegistry;
import com.mineclone.item.ToolClass;
import com.mineclone.world.BlockType;

import java.util.List;
import java.util.Map;

/**
 * Проверки меню выживания и креатива: данные предметов, компоненты, сейвы,
 * логика окон. Отдельным классом, как {@link FeatureTests}: раннер и счётчики
 * общие, {@code TestMain} зовёт {@link #runAll} перед итогом.
 */
final class InventoryTests {
    private InventoryTests() {}

    interface Check { void run() throws Exception; }
    interface Runner { void run(String name, Check check); }

    static void runAll(Runner r) {
        r.run("json parses nested objects, arrays and escapes", InventoryTests::testJsonParses);
        r.run("json allows line comments and trailing commas", InventoryTests::testJsonLenient);
        r.run("json errors carry file, line and column", InventoryTests::testJsonErrors);
        r.run("json object accessors report the key path", InventoryTests::testJsonAccessors);
        r.run("json allowOnly rejects a typo", InventoryTests::testJsonAllowOnly);
        r.run("resource ids parse with and without a namespace", InventoryTests::testResourceIdParse);
        r.run("resource ids reject upper case and spaces", InventoryTests::testResourceIdRejects);
        r.run("data pack lists files by kind in a stable order", InventoryTests::testDataPackListing);
        r.run("every placeable block has exactly one item", InventoryTests::testBlockItems);
        r.run("technical blocks have no item", InventoryTests::testTechnicalBlocks);
        r.run("tools keep their old level, speed and durability", InventoryTests::testToolNumbers);
        r.run("food keeps its old nutrition", InventoryTests::testFoodNumbers);
        r.run("computed tags find light sources and durable items", InventoryTests::testComputedTags);
        r.run("tag search matches russian aliases and treats yo as ye", InventoryTests::testTagSearch);
        r.run("tag includes resolve and a cycle is an error", InventoryTests::testTagIncludes);
        r.run("registry rejects an unknown property with its path", InventoryTests::testRegistryStrict);
        r.run("missing items are cached placeholders", InventoryTests::testMissingItems);
    }

    // -------------------------------------------------------------- реестр

    /** Блоки без предмета: существуют внутри мира, но в руки не берутся. */
    private static final java.util.Set<BlockType> TECHNICAL =
            java.util.Set.of(BlockType.AIR, BlockType.WATER_FLOW, BlockType.DOOR_OPEN);

    private static void testBlockItems() {
        ItemRegistry reg = Items.get();
        Categories cats = reg.categories();
        for (BlockType b : BlockType.VALUES) {
            if (TECHNICAL.contains(b))
                continue;
            Item item = reg.forBlock(b);
            assertTrue("item for " + b, item != null);
            assertEq("block round trip " + b, b, item.block);
            assertTrue("name of " + b, !item.name.isBlank());
            assertTrue("category of " + b + " is known", cats.has(item.category));
        }
        int blockItems = 0;
        for (Item i : reg.all())
            if (i.block != null)
                blockItems++;
        assertEq("one item per placeable block", BlockType.VALUES.length - TECHNICAL.size(), blockItems);
    }

    private static void testTechnicalBlocks() {
        ItemRegistry reg = Items.get();
        for (BlockType b : TECHNICAL)
            assertTrue("no item for " + b, reg.forBlock(b) == null);
    }

    private static void testToolNumbers() {
        // Таблица прежних значений ToolType. Числа стоят прямо здесь, а не
        // берутся из кода: перенос в данные не имел права тронуть баланс, и
        // сверять данные с ними же самими смысла нет.
        Object[][] table = {
                { "wooden_pickaxe",  ToolClass.PICKAXE, 1, 2.2f,  60 },
                { "stone_pickaxe",   ToolClass.PICKAXE, 2, 4.0f, 132 },
                { "iron_pickaxe",    ToolClass.PICKAXE, 3, 6.5f, 251 },
                { "diamond_pickaxe", ToolClass.PICKAXE, 4, 9.0f, 900 },
                { "wooden_axe",      ToolClass.AXE,     1, 2.2f,  60 },
                { "stone_axe",       ToolClass.AXE,     2, 4.0f, 132 },
                { "iron_axe",        ToolClass.AXE,     3, 6.5f, 251 },
                { "diamond_axe",     ToolClass.AXE,     4, 9.0f, 900 },
                { "wooden_shovel",   ToolClass.SHOVEL,  1, 2.2f,  60 },
                { "stone_shovel",    ToolClass.SHOVEL,  2, 4.0f, 132 },
                { "iron_shovel",     ToolClass.SHOVEL,  3, 6.5f, 251 },
                { "diamond_shovel",  ToolClass.SHOVEL,  4, 9.0f, 900 },
        };
        ItemRegistry reg = Items.get();
        for (Object[] row : table) {
            Item t = reg.require((String) row[0]);
            assertTrue("tool spec " + row[0], t.tool != null);
            assertEq("class " + row[0], row[1], t.tool.toolClass());
            assertEq("level " + row[0], row[2], t.tool.level());
            assertEq("speed " + row[0], row[3], t.tool.speed());
            assertEq("durability " + row[0], row[4], t.durability);
            assertEq("tools do not stack " + row[0], 1, t.maxStack);
            assertTrue("no fuel from a tool " + row[0], t.fuelSeconds == 0f);
        }
        assertTrue("pickaxe suits stone", reg.require("iron_pickaxe").tool.suits(BlockType.STONE));
        assertTrue("pickaxe does not suit dirt", !reg.require("iron_pickaxe").tool.suits(BlockType.DIRT));
    }

    private static void testFoodNumbers() {
        Object[][] table = {
                { "beef", 3 }, { "porkchop", 3 }, { "chicken", 2 }, { "mutton", 2 },
                { "cooked_beef", 7 }, { "cooked_porkchop", 7 },
                { "cooked_chicken", 5 }, { "cooked_mutton", 5 },
        };
        ItemRegistry reg = Items.get();
        for (Object[] row : table) {
            Item f = reg.require((String) row[0]);
            assertTrue("food spec " + row[0], f.food != null);
            assertEq("nutrition " + row[0], row[1], f.food.nutrition());
        }
    }

    private static void testComputedTags() {
        ItemRegistry reg = Items.get();
        assertTrue("torch is a light source", reg.require("torch").hasTag(ResourceId.of("light")));
        assertTrue("stone is not a light source", !reg.require("stone").hasTag(ResourceId.of("light")));
        assertTrue("pickaxe is durable", reg.require("iron_pickaxe").hasTag(ResourceId.of("durable")));
        assertTrue("coal ore is fuel", reg.require("coal_ore").hasTag(ResourceId.of("fuel")));
        assertTrue("stone is not fuel", !reg.require("stone").hasTag(ResourceId.of("fuel")));
        assertTrue("beef is food", reg.require("beef").hasTag(ResourceId.of("food")));
        assertTrue("planks burn", reg.require("planks").hasTag(ResourceId.of("flammable")));
        // Вычисляемый тег обязан попадать и в состав: иначе его не перечислить.
        assertTrue("computed tag is listed",
                !reg.tags().members(ResourceId.of("light")).isEmpty());
    }

    private static void testTagSearch() {
        TagRegistry tags = Items.get().tags();
        assertTrue("by id prefix", named(tags.find("wooden_to"), "mineclone:wooden_tools"));
        assertTrue("by russian name", named(tags.find("дерев"), "mineclone:wood"));
        assertTrue("by alias", named(tags.find("древес"), "mineclone:wood"));
        assertTrue("case is ignored", named(tags.find("ДЕРЕВ"), "mineclone:wood"));
        assertTrue("yo reads as ye", named(tags.find("брев"), "mineclone:logs"));
        assertTrue("ye reads as yo", named(tags.find("брёв"), "mineclone:logs"));
        assertTrue("nothing matches", tags.find("zzz").isEmpty());
    }

    private static boolean named(List<Tag> found, String id) {
        for (Tag t : found)
            if (t.id().toString().equals(id))
                return true;
        return false;
    }

    private static void testTagIncludes() throws Exception {
        ItemRegistry reg = Items.get();
        java.util.Set<Item> wood = reg.tags().members(ResourceId.of("wood"));
        assertTrue("include pulled the wooden pickaxe in", wood.contains(reg.require("wooden_pickaxe")));
        assertTrue("direct value", wood.contains(reg.require("planks")));
        assertTrue("stone stayed out", !wood.contains(reg.require("stone")));
        assertTrue("wooden pickaxe knows the tag", reg.require("wooden_pickaxe").hasTag(ResourceId.of("wood")));

        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("tagcycle");
        write(root.resolve("mineclone/categories.json"), CATEGORIES_MISC);
        write(root.resolve("mineclone/items/a.json"), STONE_ITEM);
        write(root.resolve("mineclone/tags/items/a.json"), "{\"name\": \"a\", \"values\": [\"#mineclone:b\"]}");
        write(root.resolve("mineclone/tags/items/b.json"), "{\"name\": \"b\", \"values\": [\"#mineclone:a\"]}");
        try {
            ItemRegistry.load(new DataPack(root));
            throw new AssertionError("a tag cycle loaded");
        } catch (JsonException expected) {
            assertTrue("cycle is named: " + expected.getMessage(),
                    expected.getMessage().contains("cycle"));
        }
    }

    private static final String CATEGORIES_MISC =
            "{\"categories\": [{\"id\": \"misc\", \"name\": \"m\"}]}";
    private static final String STONE_ITEM =
            "{\"stone\": {\"name\": \"s\", \"category\": \"misc\", \"block\": \"stone\"}}";

    private static void testRegistryStrict() throws Exception {
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("strict");
        write(root.resolve("mineclone/categories.json"), CATEGORIES_MISC);
        write(root.resolve("mineclone/items/a.json"),
                "{\"stone\": {\"name\": \"s\", \"category\": \"misc\", \"block\": \"stone\", \"masss\": 1}}");
        try {
            ItemRegistry.load(new DataPack(root));
            throw new AssertionError("a typo loaded");
        } catch (JsonException e) {
            assertTrue("file named: " + e.getMessage(), e.getMessage().contains("mineclone/items/a.json"));
            assertTrue("key path named: " + e.getMessage(), e.getMessage().contains("stone.masss"));
        }

        java.nio.file.Path bad = java.nio.file.Files.createTempDirectory("strict2");
        write(bad.resolve("mineclone/categories.json"), CATEGORIES_MISC);
        write(bad.resolve("mineclone/items/a.json"),
                "{\"stone\": {\"name\": \"s\", \"category\": \"nope\", \"block\": \"stone\"}}");
        try {
            ItemRegistry.load(new DataPack(bad));
            throw new AssertionError("an unknown category loaded");
        } catch (JsonException e) {
            assertTrue("category named: " + e.getMessage(), e.getMessage().contains("nope"));
        }
    }

    private static void testMissingItems() {
        ItemRegistry reg = Items.get();
        assertTrue("absent id is null", reg.get("mineclone:no_such_item") == null);
        Item a = reg.missing(ResourceId.of("no_such_item"));
        Item b = reg.missing(ResourceId.of("no_such_item"));
        assertTrue("placeholder is cached", a == b);
        assertTrue("placeholder is marked", a.missing);
        assertTrue("placeholder is hidden from creative", a.hidden);
        assertEq("placeholder stacks like a block", 64, a.maxStack);
        assertTrue("placeholder has no block", a.block == null);
        assertEq("placeholder keeps its id", "mineclone:no_such_item", a.id.toString());
    }

    // ------------------------------------------------------------------ JSON

    @SuppressWarnings("unchecked")
    private static void testJsonParses() {
        Object root = Json.parse("{\"a\": [1, 2.5, -3e2, true, false, null], "
                + "\"b\": {\"c\": \"q\\\"\\\\\\/\\n\\u0416\"}}", "t.json");
        Map<String, Object> m = (Map<String, Object>) root;
        List<Object> a = (List<Object>) m.get("a");
        assertEq("int as double", 1.0, a.get(0));
        assertEq("fraction", 2.5, a.get(1));
        assertEq("exponent", -300.0, a.get(2));
        assertEq("true", Boolean.TRUE, a.get(3));
        assertEq("false", Boolean.FALSE, a.get(4));
        assertTrue("null kept", a.get(5) == null && a.size() == 6);
        Map<String, Object> b = (Map<String, Object>) m.get("b");
        assertEq("escapes", "q\"\\/\nЖ", b.get("c"));
        assertEq("key order kept", List.of("a", "b"), List.copyOf(m.keySet()));
    }

    @SuppressWarnings("unchecked")
    private static void testJsonLenient() {
        String text = """
                // шапка файла
                {
                  "items": [1, 2, 3,], // хвост массива
                  "name": "a // не комментарий", // а это комментарий
                }
                """;
        Map<String, Object> m = (Map<String, Object>) Json.parse(text, "c.json");
        assertEq("trailing comma in array", 3, ((List<Object>) m.get("items")).size());
        assertEq("slashes inside a string stay", "a // не комментарий", m.get("name"));
    }

    private static void testJsonErrors() {
        String text = "{\n  \"a\": 1,\n  \"b\": tru\n}";
        try {
            Json.parse(text, "items/bad.json");
            throw new AssertionError("expected a parse error");
        } catch (JsonException e) {
            assertEq("source", "items/bad.json", e.source);
            assertEq("line", 3, e.line);
            assertEq("column", 8, e.column);
            assertTrue("message names the place: " + e.getMessage(),
                    e.getMessage().startsWith("items/bad.json:3:8:"));
        }
        try {
            Json.parse("{\"a\": 1} 2", "x.json");
            throw new AssertionError("expected trailing garbage to fail");
        } catch (JsonException e) {
            assertEq("garbage line", 1, e.line);
        }
    }

    private static void testJsonAccessors() {
        JsonObject o = Json.parseObject("{\"tool\": {\"level\": 3, \"speed\": 6.5, \"name\": \"x\"},"
                + " \"tags\": [\"a\", \"b\"]}", "items/tools.json", "iron_pickaxe");
        JsonObject tool = o.object("tool");
        assertEq("int", 3, tool.integer("level"));
        assertEq("float", 6.5f, tool.number("speed"));
        assertEq("default int", 7, tool.integer("missing", 7));
        assertEq("strings", List.of("a", "b"), o.strings("tags"));
        assertTrue("null object for absent key", o.objectOrNull("armor") == null);
        try {
            tool.integer("speed");
            throw new AssertionError("6.5 is not an integer");
        } catch (JsonException e) {
            assertTrue("path in message: " + e.getMessage(),
                    e.getMessage().contains("items/tools.json") && e.getMessage().contains("iron_pickaxe.tool.speed"));
        }
        try {
            tool.string("name2");
            throw new AssertionError("missing required key");
        } catch (JsonException e) {
            assertTrue("missing key named: " + e.getMessage(), e.getMessage().contains("iron_pickaxe.tool.name2"));
        }
    }

    private static void testJsonAllowOnly() {
        JsonObject o = Json.parseObject("{\"name\": \"a\", \"categroy\": \"tools\"}", "items/t.json", "pick");
        try {
            o.allowOnly("name", "category");
            throw new AssertionError("typo must be rejected");
        } catch (JsonException e) {
            assertTrue("typo named: " + e.getMessage(), e.getMessage().contains("pick.categroy"));
        }
        o.allowOnly("name", "categroy");
    }

    // ----------------------------------------------------------- ids and pack

    private static void testResourceIdParse() {
        ResourceId a = ResourceId.parse("stone", "mineclone");
        assertEq("default namespace", new ResourceId("mineclone", "stone"), a);
        assertEq("printed form", "mineclone:stone", a.toString());
        ResourceId b = ResourceId.parse("mymod:blocks/ruby_ore", "mineclone");
        assertEq("namespace", "mymod", b.namespace());
        assertEq("path", "blocks/ruby_ore", b.path());
    }

    private static void testResourceIdRejects() {
        for (String bad : new String[] { "Stone", "my mod:x", "mineclone:", ":stone", "a:b:c", "" }) {
            try {
                ResourceId.parse(bad, "mineclone");
                throw new AssertionError("accepted " + bad);
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
    }

    private static void testDataPackListing() throws Exception {
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("pack");
        write(root.resolve("mineclone/items/tools.json"), "{\"items\": {}}");
        write(root.resolve("mineclone/items/blocks.json"), "{\"items\": {}}");
        write(root.resolve("mineclone/items/sub/extra.json"), "{\"items\": {}}");
        write(root.resolve("aaa/items/one.json"), "{\"items\": {}}");
        write(root.resolve("mineclone/tags/items/logs.json"), "{\"values\": []}");
        write(root.resolve("mineclone/categories.json"), "{\"categories\": []}");
        DataPack pack = new DataPack(root);
        List<String> seen = new java.util.ArrayList<>();
        for (DataPack.Entry e : pack.files("items"))
            seen.add(e.namespace() + ":" + e.relPath());
        assertEq("sorted by namespace then path",
                List.of("aaa:one.json", "mineclone:blocks.json", "mineclone:sub/extra.json", "mineclone:tools.json"),
                seen);
        assertEq("nested kind", 1, pack.files("tags/items").size());
        assertTrue("root file", pack.root("mineclone", "categories.json") != null);
        assertTrue("absent root file", pack.root("mineclone", "nope.json") == null);
        assertEq("source names the file", "mineclone/items/tools.json",
                pack.files("items").get(3).json().source());
    }

    private static void write(java.nio.file.Path p, String text) throws java.io.IOException {
        java.nio.file.Files.createDirectories(p.getParent());
        java.nio.file.Files.writeString(p, text);
    }

    // --------------------------------------------------------------- helpers

    static void assertTrue(String what, boolean cond) {
        if (!cond) throw new AssertionError("expected true: " + what);
    }

    static void assertEq(String what, Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual))
            throw new AssertionError(what + ": expected <" + expected + "> but was <" + actual + ">");
    }
}

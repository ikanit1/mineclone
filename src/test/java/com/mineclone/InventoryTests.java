package com.mineclone;

import com.mineclone.data.Json;
import com.mineclone.data.JsonException;
import com.mineclone.data.JsonObject;
import com.mineclone.data.DataPack;
import com.mineclone.data.ResourceId;

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

package com.mineclone;

import com.mineclone.data.DataPack;
import com.mineclone.data.JsonException;
import com.mineclone.item.Item;
import com.mineclone.item.ItemRegistry;
import com.mineclone.item.Items;
import com.mineclone.item.recipe.RecipeRegistry;
import com.mineclone.world.Furnace;
import com.mineclone.world.Inventory;
import com.mineclone.world.ItemStack;
import com.mineclone.world.Recipes;
import com.mineclone.world.Smelting;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/** Frozen pre-migration tables plus executable data-pack matching and validation contracts. */
public final class RecipeDataTests {
    public static void runAll(TestMain.Runner runner) {
        runner.run("JSON crafting preserves all 35 legacy shapes, outputs and order", RecipeDataTests::craftingGolden);
        runner.run("JSON smelting preserves all nine legacy inputs, outputs and times", RecipeDataTests::smeltingGolden);
        runner.run("all legacy shapes craft at every allowed offset and reflection", RecipeDataTests::legacyMatching);
        runner.run("recipe data supports tags, three ingredients, order and immutable previews", RecipeDataTests::registryContracts);
        runner.run("shapeless tag matching reserves exact ingredients and consumes once per cell", RecipeDataTests::tagMatching);
        runner.run("JSON smelting time and result count drive furnace behavior", RecipeDataTests::furnaceData);
        runner.run("invalid recipe fixtures report source file and precise key", RecipeDataTests::badFixtures);
        runner.run("Recipes has no hardcoded item identifiers", RecipeDataTests::noHardcodedItems);
    }

    public static void main(String[] args) {
        runAll((name, check) -> {
            try { check.run(); System.out.println("PASS " + name); }
            catch (Exception failure) { throw new AssertionError(name, failure); }
        });
    }

    private static void craftingGolden() throws Exception {
        List<String> actual = new ArrayList<>();
        Recipes.Recipe[] all = Recipes.all();
        for (int i = 0; i < all.length; i++) {
            var r = all[i];
            String pattern = Arrays.stream(r.pattern()).map(item -> item == null ? "_" : item.id.toString())
                    .collect(Collectors.joining(","));
            actual.add(i + "|" + r.result().id + "|" + r.resultCount() + "|" + r.width() + "|" + r.height()
                    + "|" + r.shapeless() + "|" + r.mirrored() + "|" + pattern);
        }
        equal(Files.readAllLines(Path.of("src/test/resources/recipes/legacy-crafting.tsv")), actual, "crafting golden");
    }

    private static void smeltingGolden() throws Exception {
        List<String> actual = new ArrayList<>();
        for (var r : Items.get().recipes().smelting()) {
            actual.add(r.input().key() + "|" + r.result().id + "|" + r.count() + "|" + r.time());
            ItemStack input = new ItemStack(r.input().example(), 5);
            ItemStack output = Smelting.result(input);
            check(output.item == r.result() && output.count == r.count(), "runtime smelting output");
            check(input.count == 5 && output != Smelting.result(input), "smelting must return fresh outputs");
            check(Smelting.cookTime(input) == r.time(), "runtime smelting duration");
        }
        equal(Files.readAllLines(Path.of("src/test/resources/recipes/legacy-smelting.tsv")), actual, "smelting golden");
    }

    private static void legacyMatching() {
        for (var recipe : Recipes.all()) {
            if (recipe.shapeless()) {
                ItemStack[] grid = new ItemStack[9];
                grid[8] = new ItemStack(recipe.at(0, 0), 2);
                check(Recipes.match(grid, 3) == recipe, "shifted shapeless legacy recipe");
                ItemStack result = Recipes.consume(grid, 3);
                check(result.item == recipe.result() && result.count == recipe.resultCount() && grid[8].count == 1,
                        "shapeless output/consumption");
                continue;
            }
            for (int size = 2; size <= 3; size++) {
                for (int oy = 0; oy <= size - recipe.height(); oy++) {
                    for (int ox = 0; ox <= size - recipe.width(); ox++) {
                        for (int mirror = 0; mirror < (recipe.mirrored() ? 2 : 1); mirror++) {
                            ItemStack[] grid = new ItemStack[size * size];
                            for (int y = 0; y < recipe.height(); y++) for (int x = 0; x < recipe.width(); x++) {
                                Item item = recipe.at(mirror == 0 ? x : recipe.width() - 1 - x, y);
                                if (item != null) grid[(oy + y) * size + ox + x] = new ItemStack(item, 2);
                            }
                            check(Recipes.match(grid, size) == recipe, "legacy placement " + recipe.result().id);
                            ItemStack output = Recipes.consume(grid, size);
                            check(output.item == recipe.result() && output.count == recipe.resultCount(), "legacy output");
                            for (ItemStack stack : grid) if (stack != null) check(stack.count == 1, "consumed wrong amount");
                        }
                    }
                }
            }
        }
    }

    private static void registryContracts() throws Exception {
        withProbe(items -> {
            var entries = items.recipes().crafting();
            equal(List.of("probe:z_many", "probe:a_overlap", "probe:m_shaped", "probe:a_order", "probe:z_order"),
                    entries.stream().map(e -> e.id().toString()).toList(), "order then stable id tie break");
            var many = entries.get(0).recipe();
            Item a = items.require("probe:a"), b = items.require("probe:b"), c = items.require("probe:c");
            check(many.need() == a && many.needCount() == 3 && many.handle() == b && many.handleCount() == 2,
                    "preview must use the two most frequent ingredients");
            check(many.ingredientCount() == 6 && many.at(2, 1) == c, "third ingredient was discarded");
            Item[] pattern = many.pattern(); pattern[0] = c;
            var ingredients = many.ingredients(); ingredients[0] = null;
            check(many.at(0, 0) == a && many.ingredientAt(0, 0).matches(a), "mutable recipe arrays escaped");
            equal("tests", entries.get(0).group(), "recipe group");

            // This reflected 2x2 pattern is offset into the bottom-right of a 3x3 grid.
            ItemStack[] reflected = new ItemStack[9];
            reflected[4] = new ItemStack(b, 1); reflected[5] = new ItemStack(a, 1); reflected[7] = new ItemStack(c, 1); reflected[8] = new ItemStack(c, 1);
            check(Recipes.match(reflected, 3) == entries.get(2).recipe(), "tag-aware shaped reflection/offset");
            reflected[0] = new ItemStack(c, 1);
            check(Recipes.match(reflected, 3) == null, "extra cell outside shape was ignored");
            check(Recipes.match(new ItemStack[5], 2) == null, "invalid grid dimensions");
        });
    }

    private static void tagMatching() throws Exception {
        withProbe(items -> {
            var recipe = items.recipes().crafting().get(1).recipe();
            Item a = items.require("probe:a"), b = items.require("probe:b"), c = items.require("probe:c");
            Inventory inv = new Inventory();
            inv.set(0, new ItemStack(a, 1)); inv.set(1, new ItemStack(b, 1)); inv.set(2, new ItemStack(c, 1));
            check(Recipes.canCraft(inv, recipe) && Recipes.craft(inv, recipe), "broad tag stole exact ingredient");
            check(Recipes.count(inv, a) == 0 && Recipes.count(inv, b) == 0 && Recipes.count(inv, c) == 0,
                    "inventory consumption did not use all three ingredients");
            check(Recipes.count(inv, items.require("probe:out")) == 3, "inventory output count");
            inv = new Inventory(); inv.set(0, new ItemStack(a, 2)); inv.set(1, new ItemStack(c, 1));
            check(Recipes.craft(inv, recipe), "one inventory stack can supply two recipe ingredients");

            ItemStack[] grid = {new ItemStack(a, 2), new ItemStack(b, 4), null, new ItemStack(c, 3)};
            check(Recipes.match(grid, 2) == recipe, "shapeless tag/exact grid matching");
            ItemStack output = Recipes.consume(grid, 2);
            check(output.count == 3 && grid[0].count == 1 && grid[1].count == 3 && grid[3].count == 2,
                    "shapeless consumption must take one per cell");
            grid[1] = null; grid[0].count = 64;
            check(Recipes.match(grid, 2) == null, "large grid stack substituted for a missing occupied cell");

            Inventory blocked = new Inventory();
            for (int slot = 0; slot < blocked.size(); slot++) blocked.set(slot, new ItemStack(c, 64));
            blocked.set(0, new ItemStack(a, 64)); blocked.set(1, new ItemStack(b, 64));
            int before = blocked.contentHash();
            check(!Recipes.craft(blocked, recipe) && before == blocked.contentHash(), "full output consumed inputs");
            Inventory missing = new Inventory(); missing.set(0, new ItemStack(a, 64));
            before = missing.contentHash();
            check(!Recipes.craft(missing, recipe) && before == missing.contentHash(), "failed matching mutated inputs");
        });
    }

    private static void furnaceData() throws Exception {
        withProbe(items -> {
            Item a = items.require("probe:a"), out = items.require("probe:out"), fuel = items.require("probe:fuel");
            Furnace furnace = new Furnace();
            furnace.input = new ItemStack(a, 2); furnace.fuel = new ItemStack(fuel, 1);
            check(furnace.tick(.125f) && furnace.output == null && furnace.cookFraction() == .5f, "JSON cook time first half");
            check(furnace.tick(.125f) && furnace.output.item == out && furnace.output.count == 2 && furnace.input.count == 1,
                    "JSON cook time/output count");
            furnace.output.count = 63;
            check(!furnace.tick(.25f) && furnace.output.count == 63 && furnace.input.count == 1,
                    "multi-item smelting overflowed output capacity");
            furnace.output.count = 62;
            check(furnace.tick(.25f) && furnace.output.count == 64 && furnace.input == null, "exact smelting capacity");
            check(Smelting.result(new ItemStack(items.require("probe:b"), 1)).item == out, "smelting tag alternative");
            check(Smelting.result(new ItemStack(items.require("probe:c"), 1)) == null, "unregistered smelting");
        });
    }

    private static void badFixtures() throws Exception {
        Path root = Path.of("src/test/resources/datapacks/bad/recipes");
        for (String line : Files.readAllLines(root.resolve("expected.tsv"))) {
            String[] columns = line.split("\\|", -1);
            try {
                RecipeRegistry.load(new DataPack(root.resolve(columns[0])), Items.get());
                throw new AssertionError("accepted bad recipe fixture " + columns[0]);
            } catch (JsonException expected) {
                String message = expected.getMessage();
                check(message.contains(columns[1]) && message.contains(columns[2]) && message.contains(columns[3]),
                        columns[0] + " lost diagnostic source/path: " + message);
            }
        }
    }

    private static void noHardcodedItems() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/mineclone/world/Recipes.java"));
        for (Item item : Items.get().all()) {
            check(!source.contains("\"" + item.id + "\"") && !source.contains("\"" + item.id.path() + "\""),
                    "hardcoded recipe item " + item.id);
        }
    }

    @FunctionalInterface private interface Probe { void run(ItemRegistry items) throws Exception; }
    private static void withProbe(Probe check) throws Exception {
        ItemRegistry previous = Items.get();
        Path root = Files.createTempDirectory("mineclone-recipes-");
        try {
            write(root, "categories.json", """
                    {"categories":[{"id":"test","name":"Test"}]}
                    """);
            write(root, "items/test.json", """
                    {"a":{"name":"A","category":"test","block":"stone"},
                     "b":{"name":"B","category":"test","block":"dirt"},
                     "c":{"name":"C","category":"test","block":"sand"},
                     "out":{"name":"Result","category":"test","block":"glass"},
                     "fuel":{"name":"Fuel","category":"test","block":"wood","fuel":10}}
                    """);
            write(root, "tags/items/ab.json", """
                    {"values":["a","b"]}
                    """);
            write(root, "recipes/main.json", """
                    {"z_order":{"type":"shaped","pattern":["B"],"key":{"B":"b"},"result":"out","order":30},
                     "a_order":{"type":"shaped","pattern":["C"],"key":{"C":"c"},"result":"out","order":30},
                     "m_shaped":{"type":"shaped","pattern":["AB","CC"],"key":{"A":"#probe:ab","B":"b","C":"c"},"result":"out","mirrored":true,"order":20},
                     "a_overlap":{"type":"shapeless","ingredients":["#probe:ab","a","c"],"result":{"item":"out","count":3},"order":10},
                     "z_many":{"type":"shaped","pattern":["AAA","BBC"],"key":{"A":"a","B":"b","C":"c"},"result":{"item":"out","count":2},"order":0,"group":"tests"}}
                    """);
            write(root, "smelting/main.json", """
                    {"quick":{"input":"#probe:ab","result":{"item":"out","count":2},"time":0.25,"order":0},
                     "fallback":{"input":"a","result":"c","time":1,"order":1}}
                    """);
            ItemRegistry items = ItemRegistry.load(new DataPack(root));
            Items.set(items);
            check.run(items);
        } finally {
            Items.set(previous);
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
    private static void write(Path root, String relative, String text) throws Exception {
        Path path = root.resolve("probe").resolve(relative);
        Files.createDirectories(path.getParent()); Files.writeString(path, text);
    }
    private static void equal(Object expected, Object actual, String message) {
        check(expected.equals(actual), message + " expected=" + expected + " actual=" + actual);
    }
    private static void check(boolean okay, String message) { if (!okay) throw new AssertionError(message); }
}

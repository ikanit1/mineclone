package com.mineclone;

import com.mineclone.data.DataPack;
import com.mineclone.data.JsonException;
import com.mineclone.item.ItemRegistry;
import com.mineclone.item.Items;
import com.mineclone.item.loot.LootContext;
import com.mineclone.item.loot.LootRegistry;
import com.mineclone.item.loot.LootTable;
import com.mineclone.world.BlockType;
import com.mineclone.world.GameMode;
import com.mineclone.world.ItemStack;
import com.mineclone.world.entity.MobType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

/**
 * BLK-06: what blocks, mobs and chests drop lives in loot data.
 *
 * <p>{@code src/test/resources/loot/legacy-*.tsv} is the frozen M0 policy. It was
 * checked against the M0 build itself ({@code 0b7a12a}): its private
 * {@code Game.blockDrop} and {@code MobType.drop/dropCount} were invoked over
 * every block and mob type and produced these rows exactly.
 */
final class LootTableTests {
    static void runAll(TestMain.Runner r) {
        r.run("block loot reproduces every frozen M0 drop", LootTableTests::blockGolden);
        r.run("mob loot reproduces every frozen M0 drop, whoever killed", LootTableTests::mobGolden);
        r.run("tool level and creative mode still gate block drops", LootTableTests::harvestGate);
        r.run("constant drops consume no session randomness", LootTableTests::constantDropsAreFree);
        r.run("leaves drop leaves one time in four, enough bedding for a bedroll", LootTableTests::leavesMakeBedding);
        r.run("chest loot is a pure function of seed, position and table", LootTableTests::chestDeterminism);
        r.run("weighted entries follow their weights within three points over 10000 rolls", LootTableTests::distribution);
        r.run("loot conditions and set_damage behave as documented", LootTableTests::conditionsAndDamage);
        r.run("invalid loot fixtures report source file and precise key", LootTableTests::badFixtures);
        r.run("no drop table is left in Java", LootTableTests::noJavaDropTables);
    }

    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    private static LootContext mining(ItemStack tool, GameMode mode, RandomGenerator random) {
        return new LootContext(1234, 5, 64, -7, tool, true, mode, random);
    }

    private static String describe(List<ItemStack> drops) {
        if (drops.isEmpty()) return "_|0";
        check(drops.size() == 1, "a legacy drop became several stacks: " + drops);
        return drops.get(0).item.id + "|" + drops.get(0).count;
    }

    /**
     * Deliberate departures from M0, each with its reason and its own test. Leaves
     * dropped nothing, yet the bedroll's bedding has been leaves since 31afedb: the
     * bed and its respawn point could not be made in survival.
     */
    static final List<BlockType> CHANGED_SINCE_M0 = List.of(BlockType.LEAVES);

    private static void blockGolden() throws Exception {
        LootRegistry loot = Items.get().loot();
        ItemStack best = ItemStack.of("diamond_pickaxe");
        List<String> expected = Files.readAllLines(Path.of("src/test/resources/loot/legacy-block-drops.tsv"));
        check(expected.size() == BlockType.values().length, "golden does not cover every block");
        for (String row : expected) {
            String[] cells = row.split("\\|");
            BlockType block = BlockType.valueOf(cells[0]);
            if (CHANGED_SINCE_M0.contains(block)) continue;
            ItemStack tool = LootRegistry.canHarvest(block, null) ? null : best;
            List<ItemStack> drops = loot.blockDrops(block, mining(tool, GameMode.SURVIVAL, new SplittableRandom(1)));
            check((cells[1] + "|" + cells[2]).equals(describe(drops)), block + " drops " + describe(drops) + ", M0 dropped " + cells[1]);
        }
    }

    private static void mobGolden() throws Exception {
        LootRegistry loot = Items.get().loot();
        List<String> expected = Files.readAllLines(Path.of("src/test/resources/loot/legacy-mob-drops.tsv"));
        check(expected.size() == MobType.values().length, "golden does not cover every mob");
        for (String row : expected) {
            String[] cells = row.split("\\|");
            MobType type = MobType.valueOf(cells[0]);
            // M0 never asked who killed: a mob that burned or fell dropped the same.
            for (boolean killed : new boolean[] { true, false }) {
                var context = new LootContext(99, 0, 70, 0, null, killed, GameMode.SURVIVAL, new SplittableRandom(2));
                String actual = describe(loot.entityDrops(type, context));
                check((cells[1] + "|" + cells[2]).equals(actual), type + " (killed=" + killed + ") drops " + actual);
            }
            var creative = new LootContext(99, 0, 70, 0, null, true, GameMode.CREATIVE, new SplittableRandom(2));
            check(loot.entityDrops(type, creative).isEmpty(), type + " dropped loot in creative");
        }
    }

    private static void harvestGate() {
        LootRegistry loot = Items.get().loot();
        RandomGenerator random = new SplittableRandom(3);
        check(loot.blockDrops(BlockType.STONE, mining(null, GameMode.SURVIVAL, random)).isEmpty(), "stone by hand");
        check(describe(loot.blockDrops(BlockType.STONE, mining(ItemStack.of("wooden_pickaxe"), GameMode.SURVIVAL, random)))
                .equals("mineclone:cobblestone|1"), "stone gives cobblestone");
        check(describe(loot.blockDrops(BlockType.COAL_ORE, mining(ItemStack.of("wooden_pickaxe"), GameMode.SURVIVAL, random)))
                .equals("mineclone:coal|1"), "coal ore gives coal");
        check(loot.blockDrops(BlockType.IRON_ORE, mining(ItemStack.of("wooden_pickaxe"), GameMode.SURVIVAL, random)).isEmpty(),
                "iron ore dropped below level 2");
        check(loot.blockDrops(BlockType.IRON_ORE, mining(ItemStack.of("stone_axe"), GameMode.SURVIVAL, random)).isEmpty(),
                "iron ore dropped for the wrong tool class");
        check(describe(loot.blockDrops(BlockType.IRON_ORE, mining(ItemStack.of("stone_pickaxe"), GameMode.SURVIVAL, random)))
                .equals("mineclone:iron_ore|1"), "iron ore at level 2");
        ItemStack emptied = ItemStack.of("diamond_pickaxe");
        emptied.count = 0;
        check(loot.blockDrops(BlockType.DIAMOND_ORE, mining(emptied, GameMode.SURVIVAL, random)).isEmpty(),
                "a used-up tool still harvested");
        for (BlockType block : BlockType.values())
            check(loot.blockDrops(block, mining(ItemStack.of("diamond_pickaxe"), GameMode.CREATIVE, random)).isEmpty(),
                    block + " dropped in creative");
    }

    /** Counts how many values a roll draws; every default method funnels into nextLong. */
    private static final class CountingRandom implements RandomGenerator {
        final SplittableRandom base = new SplittableRandom(4);
        int draws;
        @Override public long nextLong() { draws++; return base.nextLong(); }
    }

    private static void constantDropsAreFree() {
        LootRegistry loot = Items.get().loot();
        CountingRandom random = new CountingRandom();
        for (BlockType block : BlockType.values())
            if (!CHANGED_SINCE_M0.contains(block))
                loot.blockDrops(block, mining(ItemStack.of("diamond_pickaxe"), GameMode.SURVIVAL, random));
        for (MobType type : MobType.values())
            loot.entityDrops(type, new LootContext(1, 0, 0, 0, null, true, GameMode.SURVIVAL, random));
        // Every M0 drop was constant; porting it must not shift the session RNG that
        // item bounce and everything else downstream share.
        check(random.draws == 0, "constant tables drew " + random.draws + " values from the session");
        loot.blockDrops(BlockType.LEAVES, mining(null, GameMode.SURVIVAL, random));
        check(random.draws == 1, "a leaf chance drew " + random.draws + " values");
    }

    private static void leavesMakeBedding() {
        LootRegistry loot = Items.get().loot();
        RandomGenerator random = new SplittableRandom(8);
        int leaves = 0, rolls = 10_000;
        for (int i = 0; i < rolls; i++) {
            List<ItemStack> drops = loot.blockDrops(BlockType.LEAVES, mining(null, GameMode.SURVIVAL, random));
            check(drops.isEmpty() || describe(drops).equals("mineclone:leaves|1"), "leaves dropped " + drops);
            leaves += drops.size();
        }
        check(Math.abs(leaves / (double) rolls - .25) <= .03, "leaf share " + leaves / (double) rolls);
        check(loot.blockDrops(BlockType.LEAVES, mining(null, GameMode.CREATIVE, random)).isEmpty(), "creative leaves");
    }

    private static void chestDeterminism() throws Exception {
        withProbe(items -> {
            LootRegistry loot = items.loot();
            List<ItemStack> first = loot.chestDrops("probe:chests/rich", 777, 10, 40, -3);
            check(!first.isEmpty(), "probe chest rolled nothing");
            for (int i = 0; i < 20; i++)
                check(same(first, loot.chestDrops("probe:chests/rich", 777, 10, 40, -3)), "chest loot is not repeatable");
            // A session RNG in the context would be ignored for chests: they build their own.
            LootTable table = loot.require("probe:chests/rich");
            check(same(first, table.roll(LootContext.chest(777, 10, 40, -3))), "chest context changed the roll");
            int differing = 0;
            for (int x = 0; x < 16; x++)
                if (!same(first, loot.chestDrops("probe:chests/rich", 777, x + 100, 40, -3))) differing++;
            check(differing >= 12, "position barely affects chest loot: " + differing + "/16");
            check(!same(first, loot.chestDrops("probe:chests/rich", 778, 10, 40, -3))
                    || !same(loot.chestDrops("probe:chests/rich", 778, 11, 40, -3), loot.chestDrops("probe:chests/rich", 777, 11, 40, -3)),
                    "the world seed does not affect chest loot");
            boolean refused = false;
            try { loot.chestDrops("probe:blocks/guarded", 1, 0, 0, 0); }
            catch (IllegalArgumentException expected) { refused = true; }
            check(refused, "a block table was rolled as a chest");
        });
    }

    private static void distribution() throws Exception {
        withProbe(items -> {
            LootTable table = items.loot().require("probe:chests/weighted");
            Map<String, Integer> counts = new HashMap<>();
            RandomGenerator random = new SplittableRandom(5);
            int rolls = 10_000;
            for (int i = 0; i < rolls; i++)
                for (ItemStack stack : table.roll(new LootContext(0, 0, 0, 0, null, false, GameMode.SURVIVAL, random)))
                    counts.merge(stack.item.id.path(), 1, Integer::sum);
            double[] weights = { 10, 5, 1 };
            String[] names = { "a", "b", "c" };
            for (int i = 0; i < names.length; i++) {
                double expected = weights[i] / 16.0;
                double actual = counts.getOrDefault(names[i], 0) / (double) rolls;
                check(Math.abs(actual - expected) <= 0.03, names[i] + " share " + actual + " vs " + expected);
            }
        });
    }

    private static void conditionsAndDamage() throws Exception {
        withProbe(items -> {
            LootRegistry loot = items.loot();
            LootTable guarded = loot.require("probe:blocks/guarded");
            ItemStack weak = new ItemStack(items.require("probe:pick_weak"), 1);
            ItemStack strong = new ItemStack(items.require("probe:pick"), 1);
            RandomGenerator random = new SplittableRandom(6);
            check(guarded.roll(new LootContext(0, 0, 0, 0, null, true, GameMode.SURVIVAL, random)).isEmpty(), "hand passed tool_class");
            check(guarded.roll(new LootContext(0, 0, 0, 0, weak, true, GameMode.SURVIVAL, random)).isEmpty(), "level 1 passed level 2");
            check(guarded.roll(new LootContext(0, 0, 0, 0, strong, true, GameMode.SURVIVAL, random)).size() == 1, "level 2 refused");
            check(guarded.roll(new LootContext(0, 0, 0, 0, strong, false, GameMode.SURVIVAL, random)).isEmpty(), "killed_by_participant ignored");
            check(guarded.roll(new LootContext(0, 0, 0, 0, strong, true, GameMode.CREATIVE, random)).isEmpty(), "not_creative ignored");
            check(guarded.possibleItems(new LootContext(0, 0, 0, 0, weak, true, GameMode.SURVIVAL, null)).isEmpty()
                    && guarded.possibleItems(new LootContext(0, 0, 0, 0, strong, true, GameMode.SURVIVAL, null)).size() == 1,
                    "reachability view disagrees with rolling");

            LootTable coin = loot.require("probe:chests/coin");
            int heads = 0;
            for (int i = 0; i < 10_000; i++)
                heads += coin.roll(new LootContext(0, 0, 0, 0, null, false, GameMode.SURVIVAL, random)).size();
            check(Math.abs(heads / 10_000.0 - .5) <= .03, "random_chance 0.5 landed " + heads);

            LootTable worn = loot.require("probe:chests/worn");
            int durability = items.require("probe:pick").durability;
            for (int i = 0; i < 200; i++) {
                List<ItemStack> drops = worn.roll(LootContext.chest(i, i, 0, 0));
                check(drops.size() == 2, "worn table size");
                int ranged = drops.get(0).damage(), exact = drops.get(1).damage();
                check(ranged >= Math.round(durability * .2) && ranged <= Math.round(durability * .8), "set_damage range " + ranged);
                check(exact == Math.round(durability * .5), "set_damage exact " + exact);
            }
            List<ItemStack> split = loot.require("probe:chests/split").roll(LootContext.chest(0, 0, 0, 0));
            check(split.size() == 3 && split.stream().mapToInt(s -> s.count).sum() == 130
                    && split.stream().allMatch(s -> s.count <= s.item.maxStack), "large counts must split into stacks");
        });
    }

    private static void badFixtures() throws Exception {
        Path root = Path.of("src/test/resources/datapacks/bad/loot_tables");
        List<String> lines = Files.readAllLines(root.resolve("expected.tsv"));
        check(lines.size() >= 16, "bad loot fixtures missing");
        for (String line : lines) {
            String[] columns = line.split("\\|", -1);
            try {
                LootRegistry.load(new DataPack(root.resolve(columns[0])), Items.get());
                throw new AssertionError("accepted bad loot fixture " + columns[0]);
            } catch (JsonException expected) {
                String message = expected.getMessage();
                check(message.contains(columns[1]) && message.contains(columns[2]) && message.contains(columns[3]),
                        columns[0] + " lost diagnostic source/path: " + message);
                check(!message.contains(": :"), columns[0] + " printed an empty path: " + message);
            }
        }
    }

    private static void noJavaDropTables() throws Exception {
        for (var owner : List.of(BlockType.class, MobType.class))
            for (var method : owner.getDeclaredMethods())
                check(!List.of("getDrop", "drop", "dropCount").contains(method.getName()),
                        owner.getSimpleName() + "." + method.getName() + " still decides drops");
        Class<?> game = Class.forName("com.mineclone.game.Game", false, LootTableTests.class.getClassLoader());
        for (var method : game.getDeclaredMethods())
            check(!method.getName().equals("blockDrop"), "Game.blockDrop still decides drops");
    }

    private static boolean same(List<ItemStack> a, List<ItemStack> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!a.get(i).contentEquals(b.get(i))) return false;
        return true;
    }

    @FunctionalInterface private interface Probe { void run(ItemRegistry items) throws Exception; }

    private static void withProbe(Probe check) throws Exception {
        ItemRegistry previous = Items.get();
        Path root = Files.createTempDirectory("mineclone-loot-");
        try {
            write(root, "categories.json", """
                    {"categories":[{"id":"test","name":"Test"}]}
                    """);
            write(root, "items/test.json", """
                    {"a":{"name":"A","category":"test","block":"stone"},
                     "b":{"name":"B","category":"test","block":"dirt"},
                     "c":{"name":"C","category":"test","block":"sand"},
                     "pick":{"name":"Pick","category":"test","icon":"iron_pickaxe","durability":100,
                             "tool":{"class":"pickaxe","level":2,"speed":4}},
                     "pick_weak":{"name":"Weak pick","category":"test","icon":"wood_pickaxe","durability":20,
                             "tool":{"class":"pickaxe","level":1,"speed":2}}}
                    """);
            write(root, "loot_tables/chests/weighted.json", """
                    {"pools":[{"entries":[{"item":"a","weight":10},{"item":"b","weight":5},{"item":"c","weight":1}]}]}
                    """);
            write(root, "loot_tables/chests/rich.json", """
                    {"pools":[{"rolls":[2,5],"entries":[{"item":"a","weight":3,"count":[1,8]},
                                                      {"item":"b","weight":2,"count":[2,4]},
                                                      {"item":"pick","weight":1,"functions":[{"set_damage":[0.1,0.9]}]}]},
                              {"rolls":1,"entries":[{"item":"c","count":[0,3]}]}]}
                    """);
            write(root, "loot_tables/chests/coin.json", """
                    {"pools":[{"conditions":[{"random_chance":0.5}],"entries":[{"item":"a"}]}]}
                    """);
            write(root, "loot_tables/chests/worn.json", """
                    {"pools":[{"entries":[{"item":"pick","functions":[{"set_damage":[0.2,0.8]}]}]},
                              {"entries":[{"item":"pick","functions":[{"set_damage":0.5}]}]}]}
                    """);
            write(root, "loot_tables/chests/split.json", """
                    {"pools":[{"entries":[{"item":"a","count":130}]}]}
                    """);
            write(root, "loot_tables/blocks/guarded.json", """
                    {"conditions":[{"not_creative":true}],
                     "pools":[{"conditions":[{"killed_by_participant":true}],
                               "entries":[{"item":"a","conditions":[{"tool_class":"pickaxe","tool_level_min":2}]}]}]}
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
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
    }
}

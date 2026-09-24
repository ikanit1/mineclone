package com.mineclone;

import com.mineclone.data.DataPack;
import com.mineclone.data.ResourceId;
import com.mineclone.item.Item;
import com.mineclone.item.ItemRegistry;
import com.mineclone.item.Items;
import com.mineclone.item.loot.LootReachability;
import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.World;
import com.mineclone.world.entity.MobType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static com.mineclone.world.BlockType.*;

/**
 * BLK-06: every recipe and smelting result can be obtained in survival.
 *
 * <p>Creative-only building blocks (water, fire, grass...) legitimately have no
 * survival source, so the property is about dead content: a recipe whose
 * result cannot be reached means one of its inputs cannot be either. The known
 * gaps are listed with the task that closes them; the list must shrink, never
 * grow, and a gap that closes fails the test until it is removed here.
 */
final class ReachabilityTests {
    /** Blocks that survival play meets without creative mode or commands. */
    static final Set<BlockType> NATURAL = EnumSet.of(
            // Terrain, biome surfaces and fillers (World.generate, Biome, Rivers).
            AIR, STONE, DIRT, GRASS, SAND, GRAVEL, BEDROCK, WATER, ICE,
            SNOWY_GRASS, PODZOL, PEAT, MUD, DRY_GRASS, RED_SAND, TERRACOTTA, LIMESTONE, ASH, BASALT,
            // Vegetation.
            WOOD, LEAVES, CACTUS,
            // Ores (OreGenerator.VEINS).
            COAL_ORE, IRON_ORE, GOLD_ORE, DIAMOND_ORE,
            // Structure templates (Structures.block): ruins, huts, obelisks, dungeons.
            COBBLE, PLANKS, GLASS, TORCH, MOSSY_COBBLE, WEB, JOURNAL,
            // Made by the running world: flow, freezing, snowfall, lightning.
            WATER_FLOW, THIN_ICE, SNOW_LAYER, FIRE);

    /** Recipe and smelting results with no survival source yet, and what closes each gap. */
    static final Set<String> KNOWN_DEAD_RESULTS = Set.of(
            // GEN-08: there is no copper ore, so no copper ingot.
            "mineclone:copper_axe", "mineclone:copper_pickaxe", "mineclone:copper_shovel", "mineclone:copper_sword",
            // Leaves drop nothing, and the bedroll's bedding is leaves (since 31afedb).
            "mineclone:bedroll");
    static final Set<String> KNOWN_DEAD_INPUTS = Set.of("mineclone:copper_ingot", "mineclone:leaves");

    static void runAll(TestMain.Runner r) {
        r.run("every recipe and smelting result is obtainable in survival, except listed gaps", ReachabilityTests::recipes);
        r.run("a crafting cycle never bootstraps itself and 3x3 needs a workbench", ReachabilityTests::fixedPoint);
        r.run("natural sources cover every block world generation produces", ReachabilityTests::naturalSources);
    }

    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    static LootReachability.Result survival(ItemRegistry items) {
        Set<ResourceId> chests = items.loot().tables().keySet().stream()
                .filter(id -> id.path().startsWith("chests/")).collect(Collectors.toSet());
        return LootReachability.analyze(items, new LootReachability.Sources(
                NATURAL, EnumSet.allOf(MobType.class), chests, Set.of()));
    }

    private static void recipes() {
        ItemRegistry items = Items.get();
        LootReachability.Result result = survival(items);
        Set<String> deadResults = new TreeSet<>(), deadInputs = new TreeSet<>();
        for (var entry : items.recipes().crafting()) {
            var recipe = entry.recipe();
            if (!result.reachable(recipe.result())) deadResults.add(recipe.result().id.toString());
            for (var ingredient : recipe.ingredients())
                if (ingredient != null && ingredient.alternatives().stream().noneMatch(result::reachable))
                    deadInputs.add(ingredient.example().id.toString());
        }
        for (var recipe : items.recipes().smelting()) {
            if (!result.reachable(recipe.result())) deadResults.add(recipe.result().id.toString());
            if (recipe.input().alternatives().stream().noneMatch(result::reachable))
                deadInputs.add(recipe.input().example().id.toString());
        }
        check(deadResults.equals(new TreeSet<>(KNOWN_DEAD_RESULTS)), "dead recipes " + deadResults
                + "; expected only " + new TreeSet<>(KNOWN_DEAD_RESULTS));
        check(deadInputs.equals(new TreeSet<>(KNOWN_DEAD_INPUTS)), "unobtainable ingredients " + deadInputs);
        // The core loop stays open end to end.
        for (String id : new String[] { "mineclone:diamond_pickaxe", "mineclone:iron_sword", "mineclone:furnace",
                "mineclone:bow", "mineclone:cooked_beef", "mineclone:chest" })
            check(result.reachable(items.require(id)), id + " is not reachable in survival");
    }

    private static void fixedPoint() throws Exception {
        Path root = Files.createTempDirectory("mineclone-reach-");
        try {
            write(root, "categories.json", """
                    {"categories":[{"id":"test","name":"Test"}]}
                    """);
            write(root, "items/test.json", """
                    {"seed":{"name":"Seed","category":"test","block":"dirt"},
                     "x":{"name":"X","category":"test","block":"sand"},
                     "y":{"name":"Y","category":"test","block":"gravel"},
                     "bench":{"name":"Bench","category":"test","block":"crafting_table"},
                     "big":{"name":"Big","category":"test","block":"glass"},
                     "small":{"name":"Small","category":"test","block":"planks"}}
                    """);
            write(root, "recipes/main.json", """
                    {"x_from_y":{"type":"shapeless","ingredients":["y"],"result":"x"},
                     "y_from_x":{"type":"shapeless","ingredients":["x"],"result":"y"},
                     "small":{"type":"shaped","pattern":["S"],"key":{"S":"seed"},"result":"small"},
                     "big":{"type":"shaped","pattern":["SSS","SSS","SSS"],"key":{"S":"seed"},"result":"big"},
                     "bench":{"type":"shaped","pattern":["SS","SS"],"key":{"S":"small"},"result":"bench"}}
                    """);
            ItemRegistry items = ItemRegistry.load(new DataPack(root));
            var withoutBench = LootReachability.analyze(items, new LootReachability.Sources(
                    EnumSet.of(DIRT), Set.of(), Set.of(), Set.of()));
            check(withoutBench.reachable(items.require("probe:small")) && withoutBench.reachable(items.require("probe:bench")),
                    "2x2 chain from a natural block");
            check(withoutBench.reachable(items.require("probe:big")), "3x3 after the bench was crafted");
            check(!withoutBench.reachable(items.require("probe:x")) && !withoutBench.reachable(items.require("probe:y")),
                    "a recipe cycle made its own input");
            var direct = LootReachability.analyze(items, new LootReachability.Sources(
                    Set.of(), Set.of(), Set.of(), Set.of(items.require("probe:y"))));
            check(direct.reachable(items.require("probe:x")), "a direct source did not feed the cycle");
            check(!direct.reachable(items.require("probe:big")), "3x3 recipe without any workbench");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    /**
     * One-way check that keeps {@link #NATURAL} honest: whatever the generator
     * actually places must be declared. Sparse grid over three seeds, spread
     * wide enough to cross biomes.
     */
    private static void naturalSources() {
        Set<BlockType> seen = EnumSet.noneOf(BlockType.class);
        for (long seed : new long[] { 1, 73, 20260922 }) {
            World world = new World(seed);
            for (int gx = -2; gx <= 2; gx++)
                for (int gz = -2; gz <= 2; gz++) {
                    int cx = gx * 40, cz = gz * 40;
                    Chunk chunk = world.getChunk(cx, cz);
                    for (byte id : chunk.copyBlocks()) seen.add(BlockType.byId(id));
                    world.removeChunk(cx, cz);
                }
        }
        Set<BlockType> undeclared = EnumSet.copyOf(seen);
        undeclared.removeAll(NATURAL);
        check(undeclared.isEmpty(), "generation places blocks the reachability roots omit: " + undeclared);
        check(seen.containsAll(EnumSet.of(STONE, DIRT, WATER, WOOD, LEAVES, COAL_ORE, IRON_ORE)),
                "the sample is too small to mean anything: " + seen);
    }

    private static void write(Path root, String relative, String text) throws Exception {
        Path path = root.resolve("probe").resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
    }
}

package com.mineclone;

import com.mineclone.item.Items;
import com.mineclone.item.loot.LootContext;
import com.mineclone.render.TextureAtlas;
import com.mineclone.save.ChunkSnapshot;
import com.mineclone.save.SaveManager;
import com.mineclone.world.*;
import com.mineclone.world.gen.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import javax.imageio.ImageIO;

/** GEN-08: V2 copper closes the survival crafting chain without touching V1 land or IDs. */
final class CopperOreTests {
    static void runAll(TestMain.Runner r) {
        r.run("copper exists only behind its feature flag, in stone at y20..70", CopperOreTests::generation);
        r.run("mined copper smelts and crafts a copper pickaxe in survival", CopperOreTests::survival);
        r.run("copper blocks, item and ore tag survive a real chunk save", CopperOreTests::save);
        r.run("copper sprite retains the opaque 32x32 stone base and atlas indices", CopperOreTests::texture);
    }
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    private static void generation() throws Exception {
        check(WorldGenVersion.LATEST == WorldGenVersion.V2 && GenFeatures.V2.copperOre(), "new worlds do not get copper");
        int copper = 0;
        for (long seed : new long[] {0, 20260922, -77231}) {
            World v1 = new World(seed, GenProfile.NORMAL, GenPolicy.fixed(WorldGenVersion.V1));
            World v2 = new World(seed, GenProfile.NORMAL, new WorldGenSettings(WorldGenVersion.V2,new GenFeatures(true,false,false,false,false),0).policy());
            for (int cx = -2; cx <= 2; cx++) for (int cz = -2; cz <= 2; cz++) {
                Chunk old = v1.generateDetached(cx, cz), added = v2.generateDetached(cx, cz);
                check(WorldGenGoldenTests.hash(added).equals(WorldGenGoldenTests.hash(v2.generateDetached(cx, cz))), "V2 nondeterministic");
                for (int x = 0; x < 16; x++) for (int y = 0; y < 128; y++) for (int z = 0; z < 16; z++) {
                    check(old.get(x, y, z) != BlockType.COPPER_ORE, "copper leaked into V1");
                    if (added.get(x, y, z) == BlockType.COPPER_ORE) {
                        copper++;
                        check(y >= 20 && y <= 70 && old.get(x, y, z) == BlockType.STONE, "ore outside stone/height band");
                    } else check(added.get(x, y, z) == old.get(x, y, z), "copper changed another generation pass");
                }
                check(!added.modified, "ore generation marked chunk modified");
            }
        }
        check(copper >= 100, "copper absent/too rare: " + copper);
    }

    private static void survival() {
        var items = Items.get();
        check(BlockType.COPPER_ORE.hardness == 10 && BlockType.COPPER_ORE.requiredToolLevel() == 1, "copper properties");
        check(items.loot().blockDrops(BlockType.COPPER_ORE,
                new LootContext(0, 0, 30, 0, null, false, GameMode.SURVIVAL, new SplittableRandom(1))).isEmpty(), "ore harvested by hand");
        Furnace furnace = new Furnace();
        World world = new World(20260922, GenPolicy.fixed(WorldGenVersion.V2));
        int mined = 0;
        for (int cx = -2; cx <= 2 && mined < 3; cx++) {
            Chunk chunk = world.generateDetached(cx, 0);
            for (int x = 0; x < 16 && mined < 3; x++) for (int z = 0; z < 16 && mined < 3; z++)
                for (int y = 20; y <= 70 && mined < 3; y++) if (chunk.get(x, y, z) == BlockType.COPPER_ORE) {
                    var loot = items.loot().blockDrops(chunk.get(x, y, z), new LootContext(world.seed,
                            cx * 16 + x, y, z, ItemStack.of("stone_pickaxe"), false, GameMode.SURVIVAL, new SplittableRandom(1)));
                    for (ItemStack stack : loot) {
                        check(stack.item == items.require("copper_ore"), "mined ore changed item");
                        if (furnace.input == null) furnace.input = stack.copy(); else furnace.input.addUpTo(stack.count);
                        mined += stack.count;
                    }
                }
        }
        check(mined == 3, "could not mine three generated copper blocks");
        furnace.fuel = ItemStack.of("coal");
        for (int i = 0; i < 500; i++) furnace.tick(.05f);
        check(furnace.input == null && furnace.output != null && furnace.output.item == items.require("copper_ingot")
                && furnace.output.count == 3, "copper did not smelt at runtime");
        var drops = items.loot().blockDrops(BlockType.COPPER_ORE,
                new LootContext(0, 0, 30, 0, ItemStack.of("stone_pickaxe"), false, GameMode.SURVIVAL, new SplittableRandom(1)));
        check(drops.size() == 1 && drops.get(0).item == items.require("copper_ore"), "wrong ore drop");
        ItemStack[] grid = new ItemStack[9];
        for (int i = 0; i < 3; i++) grid[i] = ItemStack.of("copper_ingot");
        grid[4] = ItemStack.of("stick"); grid[7] = ItemStack.of("stick");
        ItemStack crafted = Recipes.consume(grid, 3);
        check(crafted != null && crafted.item == items.require("copper_pickaxe"), "copper pickaxe cannot be crafted");
        check(ReachabilityTests.survival(items).reachable(crafted.item), "copper tool chain is unreachable");
    }

    private static void save() throws Exception {
        var items = Items.get();
        check(items.tags().members(com.mineclone.data.ResourceId.of("mineclone:ores")).contains(items.require("copper_ore")), "ore tag missing copper");
        Path root = Files.createTempDirectory("mineclone-copper-");
        try {
            SaveManager saves = new SaveManager(root.toFile());
            Chunk c = new Chunk(-3, 4);
            c.set(1, 32, 2, BlockType.COPPER_ORE);
            c.set(3, 40, 4, BlockType.CHEST);
            c.createChest(3, 40, 4)[2] = ItemStack.of("copper_ore", 5);
            saves.saveChunkAsync("test", new ChunkSnapshot(c.cx, c.cz, c.copyBlocks(), c.copyMeta(), c.copyChests()));
            saves.flushAndAwait();
            var read = saves.loadChunk("test", c.cx, c.cz);
            check(read != null && BlockType.byId(read.blocks[Chunk.idx(1, 32, 2)]) == BlockType.COPPER_ORE, "new block ID lost");
            check(read.chests.get(Chunk.idx(3, 40, 4))[2].contentEquals(c.getChest(3, 40, 4)[2]), "ore stack lost");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void texture() throws Exception {
        check(BlockType.COPPER_ORE.ordinal() == 48 && TextureAtlas.TILE_NAMES[139].equals("copper_ore"), "new IDs were inserted");
        var ore = ImageIO.read(Path.of(TextureAtlas.BLOCKS_DIR, "copper_ore.png").toFile());
        var stone = ImageIO.read(Path.of(TextureAtlas.BLOCKS_DIR, "stone.png").toFile());
        check(ore.getWidth() == 32 && ore.getHeight() == 32, "ore sprite dimensions");
        int same = 0, changed = 0;
        for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) {
            check((ore.getRGB(x, y) >>> 24) == 255, "ore sprite is translucent");
            if (ore.getRGB(x, y) == stone.getRGB(x, y)) same++; else changed++;
        }
        check(same >= 800 && changed >= 40, "ore lost stone base or copper flecks: " + same + "/" + changed);
    }
}

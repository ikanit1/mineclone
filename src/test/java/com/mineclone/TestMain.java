package com.mineclone;

import com.mineclone.audio.Sounds;
import com.mineclone.save.ChunkSnapshot;
import com.mineclone.save.LevelData;
import com.mineclone.save.Options;
import com.mineclone.save.SaveFormat;
import com.mineclone.save.SaveManager;
import com.mineclone.world.Biome;
import com.mineclone.world.BiomeProvider;
import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.GameMode;
import com.mineclone.world.Inventory;
import com.mineclone.world.ItemStack;
import com.mineclone.world.World;

import java.util.EnumSet;

import java.io.File;
import java.util.Arrays;

/**
 * Zero-dependency test runner for the deterministic core (no GL / no game loop).
 * Each check throws AssertionError on failure; main() tallies and exits non-zero
 * if anything failed, so CI / run-tests.ps1 can gate on the exit code.
 *
 * Deliberately dependency-free: the project has no Maven/Gradle, so pulling in
 * JUnit would mean wiring another download into run.ps1. A plain main + asserts
 * covers the pure logic that actually benefits from regression tests today.
 */
public final class TestMain {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        run("World.key round-trips through (cx,cz)", TestMain::testWorldKeyRoundTrip);
        run("BlockType.byId guards out-of-range ids", TestMain::testByIdGuard);
        run("ItemStack clamps and stacks", TestMain::testItemStack);
        run("Inventory add merges then fills", TestMain::testInventoryAdd);
        run("Inventory removeOne empties slot", TestMain::testInventoryRemoveOne);
        run("Inventory left/right click stack ops", TestMain::testInventoryClick);
        run("new biome blocks registered", TestMain::testBiomeBlocks);
        run("level.dat save/load round-trip", TestMain::testLevelRoundTrip);
        run("chunk save/load round-trip", TestMain::testChunkRoundTrip);
        run("options.dat save/load round-trip", TestMain::testOptionsRoundTrip);
        run("atomic save leaves no .tmp files", TestMain::testNoTempLeftovers);
        run("save overwrite keeps old data on rewrite", TestMain::testOverwriteRoundTrip);
        run("biome params sane", TestMain::testBiomeParams);
        run("biome climate table", TestMain::testBiomeClassify);
        run("biome provider deterministic", TestMain::testBiomeDeterminism);
        run("biome provider covers all biomes", TestMain::testBiomeCoverage);
        run("chunk surface matches biome", TestMain::testChunkSurfaceMatchesBiome);
        run("biome borders have no cliffs", TestMain::testHeightSmoothness);
        run("vegetation matches biome rules", TestMain::testVegetationInvariants);
        run("sound materials for biome blocks", TestMain::testBiomeSoundMaterials);
        run("BlockType drop table", TestMain::testDropTable);
        run("MobType params sane", TestMain::testMobTypeParams);
        run("EntityPhysics lands on ground", TestMain::testEntityPhysicsFall);
        run("EntityPhysics stops at wall", TestMain::testEntityPhysicsWall);
        run("EntityPhysics ray vs AABB", TestMain::testRayAabb);

        System.out.println();
        System.out.println("==== " + passed + " passed, " + failed + " failed ====");
        if (failed > 0) System.exit(1);
    }

    // ---- individual checks ----

    private static void testWorldKeyRoundTrip() {
        int[][] cases = { {0, 0}, {1, -1}, {-1, 1}, {123, -456}, {-2000000, 2000000},
                          {Integer.MAX_VALUE, Integer.MIN_VALUE} };
        for (int[] c : cases) {
            long key = World.key(c[0], c[1]);
            int cx = (int) (key >> 32);
            int cz = (int) (key & 0xFFFFFFFFL);
            assertEq("cx for " + Arrays.toString(c), c[0], cx);
            assertEq("cz for " + Arrays.toString(c), c[1], cz);
        }
        // Distinct chunks must not collide on the same key.
        assertTrue("(1,0) != (0,1)", World.key(1, 0) != World.key(0, 1));
    }

    private static void testBiomeBlocks() {
        // Новые блоки добавлены В КОНЕЦ enum — старые ordinal не сдвинуты (иначе ломаются сейвы).
        assertEq("WATER_FLOW ordinal stays 16", 16, BlockType.WATER_FLOW.ordinal());
        BlockType sg = BlockType.valueOf("SNOWY_GRASS");
        BlockType ca = BlockType.valueOf("CACTUS");
        assertTrue("SNOWY_GRASS solid", sg.solid);
        assertTrue("CACTUS solid", ca.solid);
        assertTrue("SNOWY_GRASS byId round-trip", BlockType.byId((byte) sg.ordinal()) == sg);
        assertTrue("CACTUS byId round-trip", BlockType.byId((byte) ca.ordinal()) == ca);
    }

    private static void testBiomeParams() {
        assertEq("5 biomes", 5, Biome.values().length);
        assertTrue("ocean floor below sea level", Biome.OCEAN.baseHeight < World.SEA_LEVEL);
        assertTrue("tundra surface is snowy grass", Biome.TUNDRA.surfaceBlock == BlockType.SNOWY_GRASS);
        assertTrue("desert surface is sand", Biome.DESERT.surfaceBlock == BlockType.SAND);
        assertTrue("forest denser than plains", Biome.FOREST.treesPer128 > Biome.PLAINS.treesPer128);
        assertTrue("ocean has no trees", Biome.OCEAN.treeType == Biome.TreeType.NONE);
        for (Biome b : Biome.values())
            assertTrue(b + " amplitude in (0,1]", b.amplitude > 0 && b.amplitude <= 1.0);
    }

    private static void testByIdGuard() {
        assertTrue("byId(0) == AIR", BlockType.byId((byte) 0) == BlockType.AIR);
        assertTrue("byId(STONE.ordinal())",
                BlockType.byId((byte) BlockType.STONE.ordinal()) == BlockType.STONE);
        // Bytes past the enum length (corrupt save) must degrade to AIR, not throw.
        assertTrue("byId(127) == AIR", BlockType.byId((byte) 127) == BlockType.AIR);
        assertTrue("byId(-1 => 255) == AIR", BlockType.byId((byte) -1) == BlockType.AIR);
    }

    private static void testLevelRoundTrip() throws Exception {
        SaveManager sm = freshManager();
        ItemStack[] inv = LevelData.creativeInventory();
        inv[0] = new ItemStack(BlockType.COBBLE, 17);
        LevelData in = new LevelData("Test World", 42L, 1.5, 2.5, 3.5,
                10.0, 20.0, 30.0, 0.1f, 0.2f, 0.3f, 4, inv,
                GameMode.SURVIVAL, 999L);
        sm.saveLevel("w1", in);
        LevelData out = sm.loadLevel("w1");
        assertTrue("loadLevel non-null", out != null);
        assertEq("seed", 42L, out.seed);
        assertEq("name", "Test World", out.name);
        assertEq("px", 1.5, out.px);
        assertEq("spawnY", 20.0, out.spawnY);
        assertEq("selectedSlot", 4, out.selectedSlot);
        assertEq("lastPlayed", 999L, out.lastPlayed);
        assertEq("gameMode", GameMode.SURVIVAL, out.gameMode);
        assertEq("inv[0] type", BlockType.COBBLE, out.inventory[0].type);
        assertEq("inv[0] count", 17, out.inventory[0].count);
    }

    private static void testChunkRoundTrip() throws Exception {
        SaveManager sm = freshManager();
        byte[] blocks = new byte[SaveFormat.CHUNK_VOLUME];
        byte[] meta = new byte[SaveFormat.CHUNK_VOLUME];
        for (int i = 0; i < blocks.length; i++) {
            blocks[i] = (byte) (i % BlockType.VALUES.length);
            meta[i] = (byte) (i % 7);
        }
        sm.saveChunkAsync("w1", new ChunkSnapshot(-3, 5, blocks, meta));
        sm.flushAndAwait();
        ChunkSnapshot out = sm.loadChunk("w1", -3, 5);
        assertTrue("loadChunk non-null", out != null);
        assertEq("cx", -3, out.cx);
        assertEq("cz", 5, out.cz);
        assertTrue("blocks equal", Arrays.equals(blocks, out.blocks));
        assertTrue("meta equal", Arrays.equals(meta, out.meta));
    }

    private static void testOptionsRoundTrip() throws Exception {
        SaveManager sm = freshManager();
        Options in = new Options(8, 90, 0.7f, 0.5f, 144, false, true, false,
                1.5f, true, 0.25f, 0.9f, 2);
        sm.saveOptions(in);
        Options out = sm.loadOptions();
        assertEq("renderRadius", 8, out.renderRadius);
        assertEq("fovDegrees", 90, out.fovDegrees);
        assertEq("maxFps", 144, out.maxFps);
        assertTrue("vsync", !out.vsync);
        assertTrue("fullscreen", out.fullscreen);
        assertTrue("invertMouseY", out.invertMouseY);
        assertEq("guiScale", 2, out.guiScale);
    }

    private static void testNoTempLeftovers() throws Exception {
        File root = freshRoot();
        SaveManager sm = new SaveManager(new File(root, "saves"));
        sm.saveLevel("w1", new LevelData(1L, 0, 0, 0, 0f, 0f, 0f, 0));
        File worldDir = new File(new File(root, "saves"), "w1");
        File[] leftovers = worldDir.listFiles((d, name) -> name.endsWith(".tmp"));
        assertTrue("no .tmp left after level save",
                leftovers == null || leftovers.length == 0);
    }

    private static void testOverwriteRoundTrip() throws Exception {
        SaveManager sm = freshManager();
        sm.saveLevel("w1", new LevelData(1L, 0, 0, 0, 0f, 0f, 0f, 0));
        // Rewrite over the existing file; atomic replace must yield the new data.
        sm.saveLevel("w1", new LevelData(2L, 0, 0, 0, 0f, 0f, 0f, 0));
        LevelData out = sm.loadLevel("w1");
        assertTrue("reload non-null", out != null);
        assertEq("seed after overwrite", 2L, out.seed);
    }

    private static void testBiomeClassify() {
        assertTrue("deep negative cont -> OCEAN",
                BiomeProvider.classify(-0.9, 0, 0) == Biome.OCEAN);
        assertTrue("ocean wins over cold",
                BiomeProvider.classify(-0.9, -0.9, 0) == Biome.OCEAN);
        assertTrue("cold -> TUNDRA",
                BiomeProvider.classify(0.5, -0.9, 0) == Biome.TUNDRA);
        assertTrue("hot+dry -> DESERT",
                BiomeProvider.classify(0.5, 0.9, -0.9) == Biome.DESERT);
        assertTrue("wet -> FOREST",
                BiomeProvider.classify(0.5, 0.0, 0.9) == Biome.FOREST);
        assertTrue("temperate default -> PLAINS",
                BiomeProvider.classify(0.5, 0.0, 0.0) == Biome.PLAINS);
    }

    private static void testBiomeDeterminism() {
        BiomeProvider a = new BiomeProvider(777L);
        BiomeProvider b = new BiomeProvider(777L);
        BiomeProvider c = new BiomeProvider(778L);
        boolean anyDiff = false;
        for (int x = -1000; x <= 1000; x += 67)
            for (int z = -1000; z <= 1000; z += 67) {
                assertTrue("same seed same biome @" + x + "," + z,
                        a.biomeAt(x, z) == b.biomeAt(x, z));
                if (a.biomeAt(x, z) != c.biomeAt(x, z))
                    anyDiff = true;
            }
        assertTrue("different seeds differ somewhere", anyDiff);
    }

    private static void testBiomeCoverage() {
        BiomeProvider p = new BiomeProvider(12345L);
        EnumSet<Biome> seen = EnumSet.noneOf(Biome.class);
        for (int x = -4000; x <= 4000; x += 32)
            for (int z = -4000; z <= 4000; z += 32)
                seen.add(p.biomeAt(x, z));
        assertEq("all five biomes occur within 4000 blocks", 5, seen.size());
    }

    private static int surfaceY(Chunk c, int x, int z) {
        for (int y = Chunk.SIZE_Y - 1; y > 0; y--) {
            BlockType t = c.get(x, y, z);
            if (t == BlockType.AIR || t == BlockType.WATER || t == BlockType.WATER_FLOW
                    || t == BlockType.LEAVES || t == BlockType.WOOD || t == BlockType.CACTUS)
                continue;
            return y;
        }
        return 0;
    }

    private static void testChunkSurfaceMatchesBiome() {
        long seed = 4242L;
        World w = new World(seed);
        BiomeProvider bp = new BiomeProvider(seed);
        for (int cx = -2; cx <= 2; cx++)
            for (int cz = -2; cz <= 2; cz++) {
                Chunk c = w.getChunk(cx, cz);
                for (int x = 0; x < Chunk.SIZE_X; x++)
                    for (int z = 0; z < Chunk.SIZE_Z; z++) {
                        int wx = cx * Chunk.SIZE_X + x, wz = cz * Chunk.SIZE_Z + z;
                        int y = surfaceY(c, x, z);
                        Biome b = bp.biomeAtGrid(Math.floorDiv(wx, BiomeProvider.GRID_STEP),
                                Math.floorDiv(wz, BiomeProvider.GRID_STEP));
                        BlockType expected = (y <= World.SEA_LEVEL + 1) ? BlockType.SAND : b.surfaceBlock;
                        BlockType actual = c.get(x, y, z);
                        assertTrue("surface @" + wx + "," + wz + " biome=" + b
                                + " expected=" + expected + " got=" + actual, actual == expected);
                    }
            }
    }

    private static void testHeightSmoothness() {
        long seed = 991L;
        World w = new World(seed);
        int prev = Integer.MIN_VALUE;
        for (int wx = -160; wx < 160; wx++) {
            int cx = Math.floorDiv(wx, Chunk.SIZE_X);
            Chunk c = w.getChunk(cx, 0);
            int y = surfaceY(c, Math.floorMod(wx, Chunk.SIZE_X), 7);
            if (prev != Integer.MIN_VALUE)
                assertTrue("step at wx=" + wx + ": " + prev + " -> " + y,
                        Math.abs(y - prev) <= 4);
            prev = y;
        }
    }

    private static void testVegetationInvariants() {
        long seed = 1001L;
        World w = new World(seed);
        boolean sawCactus = false, sawTrunk = false;
        for (int cx = -6; cx <= 6; cx++)
            for (int cz = -6; cz <= 6; cz++) {
                Chunk c = w.getChunk(cx, cz);
                for (int x = 0; x < Chunk.SIZE_X; x++)
                    for (int z = 0; z < Chunk.SIZE_Z; z++)
                        for (int y = 1; y < Chunk.SIZE_Y; y++) {
                            BlockType t = c.get(x, y, z);
                            BlockType below = c.get(x, y - 1, z);
                            if (t == BlockType.CACTUS) {
                                sawCactus = true;
                                assertTrue("cactus on sand/cactus @" + x + "," + y + "," + z,
                                        below == BlockType.SAND || below == BlockType.CACTUS);
                                assertTrue("cactus above water line", y > World.SEA_LEVEL + 1);
                            }
                            if (t == BlockType.WOOD && below != BlockType.WOOD) {
                                sawTrunk = true;
                                assertTrue("trunk base on grass/snowy grass, got " + below,
                                        below == BlockType.GRASS || below == BlockType.SNOWY_GRASS);
                            }
                        }
            }
        assertTrue("saw at least one trunk", sawTrunk);
        assertTrue("saw at least one cactus", sawCactus);
    }

    private static void testBiomeSoundMaterials() {
        Sounds s = new Sounds();
        assertEq("snowy grass -> SNOW", Sounds.Material.valueOf("SNOW"),
                s.materialOf(BlockType.SNOWY_GRASS));
        assertEq("cactus -> CLOTH", Sounds.Material.valueOf("CLOTH"),
                s.materialOf(BlockType.CACTUS));
    }

    private static void testDropTable() {
        assertEq("stone drops cobble", BlockType.COBBLE, BlockType.STONE.getDrop());
        assertEq("grass drops dirt", BlockType.DIRT, BlockType.GRASS.getDrop());
        assertEq("snowy grass drops dirt", BlockType.DIRT, BlockType.SNOWY_GRASS.getDrop());
        assertEq("leaves drop nothing", BlockType.AIR, BlockType.LEAVES.getDrop());
        assertEq("water drops nothing", BlockType.AIR, BlockType.WATER.getDrop());
        assertEq("dirt drops itself", BlockType.DIRT, BlockType.DIRT.getDrop());
        assertEq("wood drops itself", BlockType.WOOD, BlockType.WOOD.getDrop());
    }

    private static void testMobTypeParams() {
        for (com.mineclone.world.entity.MobType t : com.mineclone.world.entity.MobType.values()) {
            assertTrue(t + " width > 0", t.width > 0f);
            assertTrue(t + " height > 0", t.height > 0f);
            assertTrue(t + " maxHealth > 0", t.maxHealth > 0f);
            assertTrue(t + " walkSpeed > 0", t.walkSpeed > 0f);
            assertTrue(t + " fall speed negative", t.maxFallSpeed < 0f);
            assertTrue(t + " soundDir set", t.soundDir != null && !t.soundDir.isBlank());
            assertEq(t + " particleColor rgb", 3, t.particleColor.length);
        }
        // Ровно один враждебный тип в E1, и только он горит на солнце.
        int hostile = 0;
        for (com.mineclone.world.entity.MobType t : com.mineclone.world.entity.MobType.values()) {
            if (t.hostile) hostile++;
            assertTrue(t + " burns only if hostile", !t.burnsInSunlight || t.hostile);
        }
        assertEq("one hostile type", 1, hostile);
        assertTrue("zombie chases faster than it walks",
                com.mineclone.world.entity.MobType.ZOMBIE.chaseSpeed
                        > com.mineclone.world.entity.MobType.ZOMBIE.walkSpeed);
        // PEACEFUL — список для спавнера: враждебных в нём быть не должно.
        assertEq("4 peaceful types", 4, com.mineclone.world.entity.MobType.PEACEFUL.length);
        for (com.mineclone.world.entity.MobType t : com.mineclone.world.entity.MobType.PEACEFUL)
            assertTrue(t + " not hostile", !t.hostile);
        EnumSet<com.mineclone.world.entity.MobType> peacefulSet =
                EnumSet.noneOf(com.mineclone.world.entity.MobType.class);
        for (com.mineclone.world.entity.MobType t : com.mineclone.world.entity.MobType.PEACEFUL)
            peacefulSet.add(t);
        assertEq("PEACEFUL entries are distinct",
                com.mineclone.world.entity.MobType.PEACEFUL.length, peacefulSet.size());
        // Курица падает медленнее остальных.
        assertTrue("chicken slow fall",
                com.mineclone.world.entity.MobType.CHICKEN.maxFallSpeed
                        > com.mineclone.world.entity.MobType.COW.maxFallSpeed);
    }

    /**
     * Каменная платформа на y=10 в 3×3 чанках вокруг (0,0), остальное — воздух.
     * Три чанка в ширину, а не один: мобы в тестах гуляют по 10+ метров, и с
     * платформой 16×16 они успевали свалиться с края — тест мигал.
     */
    private static World flatTestWorld() {
        World w = new World(1234L);
        for (int cx = -1; cx <= 1; cx++)
            for (int cz = -1; cz <= 1; cz++) {
                Chunk c = w.getChunk(cx, cz);
                for (int x = 0; x < Chunk.SIZE_X; x++)
                    for (int z = 0; z < Chunk.SIZE_Z; z++)
                        for (int y = 0; y < Chunk.SIZE_Y; y++)
                            c.set(x, y, z, y == 10 ? BlockType.STONE : BlockType.AIR);
            }
        return w;
    }

    private static void testEntityPhysicsFall() {
        World w = flatTestWorld();
        org.joml.Vector3f pos = new org.joml.Vector3f(8.5f, 14f, 8.5f);
        org.joml.Vector3f vel = new org.joml.Vector3f();
        com.mineclone.world.entity.EntityPhysics.Contact c = null;
        for (int i = 0; i < 120; i++)  // 2 c по 1/60
            c = com.mineclone.world.entity.EntityPhysics.step(w, pos, vel, 0.6f, 1.8f, 1f / 60f, -55f);
        assertTrue("onGround after falling", c.onGround());
        assertTrue("vertical velocity zeroed, was " + vel.y, Math.abs(vel.y) < 1e-3f);
        assertTrue("stands on top of the block, y=" + pos.y, Math.abs(pos.y - 11f) < 0.01f);
    }

    private static void testEntityPhysicsWall() {
        World w = flatTestWorld();
        // Стена на x=10, во всю высоту тела.
        for (int y = 11; y <= 13; y++)
            w.getChunk(0, 0).set(10, y, 8, BlockType.STONE);
        org.joml.Vector3f pos = new org.joml.Vector3f(8.5f, 11f, 8.5f);
        org.joml.Vector3f vel = new org.joml.Vector3f(4f, 0f, 0f);
        boolean sawWall = false;
        for (int i = 0; i < 60; i++) {
            vel.x = 4f; // ИИ каждый тик заново задаёт горизонтальную скорость
            com.mineclone.world.entity.EntityPhysics.Contact c =
                    com.mineclone.world.entity.EntityPhysics.step(w, pos, vel, 0.6f, 1.8f, 1f / 60f, -55f);
            if (c.hitWall()) sawWall = true;
        }
        assertTrue("hitWall reported", sawWall);
        assertTrue("did not tunnel through the wall, x=" + pos.x, pos.x < 10f);
    }

    private static void testRayAabb() {
        // Луч из (0,0,0) в +X сквозь куб 5..6 — попадание на дистанции 5.
        float t = com.mineclone.world.entity.EntityPhysics.rayAabbDistance(
                0f, 0.5f, 0.5f, 1f, 0f, 0f, 5f, 0f, 0f, 6f, 1f, 1f);
        assertTrue("hit at ~5, got " + t, t > 4.99f && t < 5.01f);
        // Мимо: тот же куб, луч уходит в +Z.
        float miss = com.mineclone.world.entity.EntityPhysics.rayAabbDistance(
                0f, 0.5f, 0.5f, 0f, 0f, 1f, 5f, 0f, 0f, 6f, 1f, 1f);
        assertTrue("miss returns negative, got " + miss, miss < 0f);
        // Луч в противоположную сторону тоже мимо.
        float behind = com.mineclone.world.entity.EntityPhysics.rayAabbDistance(
                0f, 0.5f, 0.5f, -1f, 0f, 0f, 5f, 0f, 0f, 6f, 1f, 1f);
        assertTrue("behind returns negative, got " + behind, behind < 0f);
    }

    private static void testItemStack() {
        ItemStack s = new ItemStack(BlockType.STONE, 1);
        assertEq("type", BlockType.STONE, s.type);
        assertEq("count", 1, s.count);
        assertTrue("isFull false at 1", !s.isFull());

        s.count = ItemStack.MAX_STACK;
        assertTrue("isFull true at MAX", s.isFull());

        // add returns leftover that didn't fit
        ItemStack t = new ItemStack(BlockType.DIRT, 60);
        int left = t.addUpTo(10); // 60 + 10 = 70 -> capped 64, leftover 6
        assertEq("count capped", ItemStack.MAX_STACK, t.count);
        assertEq("leftover", 6, left);

        ItemStack copy = t.copy();
        assertTrue("copy distinct", copy != t);
        assertEq("copy type", BlockType.DIRT, copy.type);
        assertEq("copy count", t.count, copy.count);

        // constructor clamping edges
        assertEq("clamp 0->1", 1, new ItemStack(BlockType.STONE, 0).count);
        assertEq("clamp 100->64", 64, new ItemStack(BlockType.STONE, 100).count);
        // addUpTo negative input
        ItemStack u = new ItemStack(BlockType.DIRT, 5);
        assertEq("addUpTo negative is 0", 0, u.addUpTo(-3));
        assertEq("count unchanged after addUpTo(negative)", 5, u.count);
    }

    private static void testInventoryAdd() {
        Inventory inv = new Inventory();
        int left = inv.add(BlockType.STONE, 10);
        assertEq("no leftover", 0, left);
        assertEq("slot0 count", 10, inv.get(0).count);

        // merges into the same existing stack first
        inv.add(BlockType.STONE, 5);
        assertEq("merged into slot0", 15, inv.get(0).count);
        assertTrue("slot1 still empty", inv.get(1) == null);

        // overflow spills into the next free slot
        inv.add(BlockType.STONE, 60); // 15 + 60 = 75 -> 64 in slot0, 11 in next free
        assertEq("slot0 full", 64, inv.get(0).count);
        assertEq("spill slot count", 11, inv.get(1).count);

        // full inventory returns leftover
        Inventory full = new Inventory();
        for (int i = 0; i < 36; i++) full.set(i, new ItemStack(BlockType.DIRT, 64));
        int rem = full.add(BlockType.DIRT, 5);
        assertEq("leftover when full", 5, rem);

        // add with amount <= 0 returns 0
        Inventory inv2 = new Inventory();
        assertEq("add(0) returns 0", 0, inv2.add(BlockType.STONE, 0));
        assertEq("add(-1) returns 0", 0, inv2.add(BlockType.STONE, -1));
        assertTrue("no slot filled", inv2.get(0) == null);
    }

    private static void testInventoryRemoveOne() {
        Inventory inv = new Inventory();
        inv.set(3, new ItemStack(BlockType.WOOD, 2));
        inv.removeOne(3);
        assertEq("count decremented", 1, inv.get(3).count);
        inv.removeOne(3);
        assertTrue("slot emptied at 0", inv.get(3) == null);
    }

    private static void testInventoryClick() {
        Inventory inv = new Inventory();
        inv.set(0, new ItemStack(BlockType.STONE, 10));

        // Left-click empty cursor on a stack: pick it all up
        ItemStack cursor = inv.leftClick(0, null);
        assertEq("cursor took all", 10, cursor.count);
        assertTrue("slot now empty", inv.get(0) == null);

        // Left-click full cursor on empty slot: drop it all
        cursor = inv.leftClick(0, cursor);
        assertTrue("cursor cleared", cursor == null);
        assertEq("slot got 10", 10, inv.get(0).count);

        // Right-click empty cursor on a stack: take half (ceil)
        cursor = inv.rightClick(0, null); // 10 -> cursor 5, slot 5
        assertEq("cursor half", 5, cursor.count);
        assertEq("slot half", 5, inv.get(0).count);

        // Right-click holding same type on same type: deposit one
        cursor = inv.rightClick(0, cursor); // slot 5 -> 6, cursor 5 -> 4
        assertEq("slot +1", 6, inv.get(0).count);
        assertEq("cursor -1", 4, cursor.count);

        // Left-click same type merges up to max with remainder on cursor
        inv.set(0, new ItemStack(BlockType.STONE, 60));
        cursor = new ItemStack(BlockType.STONE, 10);
        cursor = inv.leftClick(0, cursor); // 60+10 -> slot 64, cursor 6
        assertEq("slot merged to max", 64, inv.get(0).count);
        assertEq("cursor remainder", 6, cursor.count);

        // Left-click different type swaps
        inv.set(1, new ItemStack(BlockType.DIRT, 3));
        cursor = new ItemStack(BlockType.WOOD, 2);
        cursor = inv.leftClick(1, cursor);
        assertEq("slot took wood", BlockType.WOOD, inv.get(1).type);
        assertEq("cursor took dirt", BlockType.DIRT, cursor.type);
    }

    // ---- harness ----

    private static SaveManager freshManager() throws Exception {
        return new SaveManager(new File(freshRoot(), "saves"));
    }

    private static File freshRoot() throws Exception {
        File dir = File.createTempFile("mineclone-test-", "");
        if (!dir.delete() || !dir.mkdirs())
            throw new IllegalStateException("could not create temp test dir: " + dir);
        dir.deleteOnExit();
        return dir;
    }

    private interface Check { void run() throws Exception; }

    private static void run(String name, Check c) {
        try {
            c.run();
            passed++;
            System.out.println("[PASS] " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println("[FAIL] " + name + " -> " + t);
        }
    }

    private static void assertTrue(String what, boolean cond) {
        if (!cond) throw new AssertionError("expected true: " + what);
    }

    private static void assertEq(String what, Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual))
            throw new AssertionError(what + ": expected <" + expected + "> but was <" + actual + ">");
    }
}

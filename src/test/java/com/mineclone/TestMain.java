package com.mineclone;

import com.mineclone.save.ChunkSnapshot;
import com.mineclone.save.LevelData;
import com.mineclone.save.Options;
import com.mineclone.save.SaveFormat;
import com.mineclone.save.SaveManager;
import com.mineclone.world.Biome;
import com.mineclone.world.BiomeProvider;
import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
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
        BlockType[] inv = LevelData.defaultInventory();
        inv[0] = BlockType.COBBLE;
        LevelData in = new LevelData("Test World", 42L, 1.5, 2.5, 3.5,
                10.0, 20.0, 30.0, 0.1f, 0.2f, 0.3f, 4, inv, 999L);
        sm.saveLevel("w1", in);
        LevelData out = sm.loadLevel("w1");
        assertTrue("loadLevel non-null", out != null);
        assertEq("seed", 42L, out.seed);
        assertEq("name", "Test World", out.name);
        assertEq("px", 1.5, out.px);
        assertEq("spawnY", 20.0, out.spawnY);
        assertEq("selectedSlot", 4, out.selectedSlot);
        assertEq("lastPlayed", 999L, out.lastPlayed);
        assertTrue("inventory[0] preserved", out.inventory[0] == BlockType.COBBLE);
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

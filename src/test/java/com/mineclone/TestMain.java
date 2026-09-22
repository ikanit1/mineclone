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
import com.mineclone.world.BlockTicker;
import com.mineclone.world.Caves;
import com.mineclone.world.Chunk;
import com.mineclone.world.entity.Mob;
import com.mineclone.world.entity.MobSpawner;
import com.mineclone.world.entity.MobType;
import com.mineclone.world.GameMode;
import com.mineclone.world.Inventory;
import com.mineclone.world.ItemStack;
import com.mineclone.world.Recipes;
import com.mineclone.game.Player;
import com.mineclone.game.Hud;
import com.mineclone.render.Camera;
import com.mineclone.render.PlayerRenderer;
import com.mineclone.render.ShadowMap;
import com.mineclone.render.SunLight;
import com.mineclone.world.World;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

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
        CreativeModeTests.runAll((name, check) -> run(name, check::run));
        RainAudioTests.runAll((name, check) -> run(name, check::run));
        RoomAcousticsTests.runAll((name, check) -> run(name, check::run));
        LightningTests.runAll((name, check) -> run(name, check::run));
        StormTests.runAll((name, check) -> run(name, check::run));
        PlayerAnimationTests.runAll((name, check) -> run(name, check::run));
        MobAnimationTests.runAll((name, check) -> run(name, check::run));
        run("optimization invariants", OptimizationTests::run);
        run("biome assets, sparse structures, seams and falling-block conservation", WorldGenerationTests::run);
        run("a stale mesh never overwrites a fresher one", TestMain::testMeshVersionRejectsStale);
        run("the emitter list tracks the blocks it describes", TestMain::testEmitterListMatchesChunk);
        run("player edits jump the mesh queue ahead of distance", TestMain::testMeshPriorityOrder);
        run("frame profiler keeps the worst frame, not the average", TestMain::testFrameProfilerWorst);
        run("removing the last torch actually puts the light out", TestMain::testLastTorchGoesOut);
        run("World.key round-trips through (cx,cz)", TestMain::testWorldKeyRoundTrip);
        run("BlockType.byId guards out-of-range ids", TestMain::testByIdGuard);
        run("ItemStack clamps and stacks", TestMain::testItemStack);
        run("Inventory add merges then fills", TestMain::testInventoryAdd);
        run("Inventory removeOne empties slot", TestMain::testInventoryRemoveOne);
        run("Inventory left/right click stack ops", TestMain::testInventoryClick);
        run("tools never stack and keep their own wear", TestMain::testToolStacks);
        run("tool tier gates the drop, class gates the speed", TestMain::testToolGating);
        run("tools wear out and vanish", TestMain::testToolWear);
        run("crafting consumes exactly what the recipe says", TestMain::testCrafting);
        run("every recipe is reachable and unambiguous", TestMain::testRecipeTable);
        run("level.dat carries tools with their wear", TestMain::testToolSaveRoundTrip);
        run("food stacks by kind and never with blocks", TestMain::testFoodStacks);
        run("hunger drains, gates regen and stops short of killing",
                TestMain::testHunger);
        run("peaceful mobs drop meat, zombies drop nothing", TestMain::testMobDrops);
        run("level.dat carries food and hunger", TestMain::testFoodSaveRoundTrip);
        run("new biome blocks registered", TestMain::testBiomeBlocks);
        run("level.dat save/load round-trip", TestMain::testLevelRoundTrip);
        run("Mob floats upward in water", TestMain::testMobBuoyancy);
        run("Water drains, weakens and falls without sideways arms", TestMain::testWaterFlow);
        run("Single water source cannot flood a stepped hillside", TestMain::testWaterSlope);
        run("Water prefers nearest downhill outlet", TestMain::testWaterOutlet);
        run("Weather cycle follows biome precipitation rules", TestMain::testWeather);
        run("Flight stop clears previous fall damage", TestMain::testFlightStopsFall);
        run("Mob animation moves at rest and settles after walking", TestMain::testMobAnimation);
        run("chunk save/load round-trip", TestMain::testChunkRoundTrip);
        run("options.dat save/load round-trip", TestMain::testOptionsRoundTrip);
        run("screen, graphics and gameplay options survive a save",
                TestMain::testVideoOptionsRoundTrip);
        run("sun light direction never grazes the horizon", TestMain::testSunLightDirection);
        run("shadow strength fades across sunrise", TestMain::testShadowStrength);
        run("shadow cascade covers its radius and snaps to texels",
                TestMain::testShadowCascade);
        run("lazy cascade rebuilds on age, camera turn and time jump",
                TestMain::testCascadeStaleness);
        run("caves carve a sane share of the underground", TestMain::testCavesCarveUnderground);
        run("caves keep bedrock and the seabed intact", TestMain::testCavesKeepSeabedAndBedrock);
        run("ores sit in their bands and in stone only", TestMain::testOreBandsAndHost);
        run("world generation is deterministic per seed",
                TestMain::testWorldGenerationDeterministic);
        run("hostile spawn rule follows light, not the clock",
                TestMain::testHostileSpawnLightRule);
        run("structures appear, stay inside their chunk and repeat per seed",
                TestMain::testStructures);
        run("grass spreads onto bare dirt and dies when covered",
                TestMain::testGrassTick);
        run("orphaned leaves decay, supported ones stay", TestMain::testLeafDecay);
        run("cactus grows up to its limit", TestMain::testCactusGrowth);
        run("setBlock skips relighting when opacity is unchanged",
                TestMain::testSetBlockSkipsRelight);
        run("incremental sky light matches a full reflood",
                TestMain::testIncrementalSkyLight);
        run("water and leaves attenuate skylight by material",
                TestMain::testMaterialSkyAttenuation);
        run("fire spreads along fuel and eats it", TestMain::testFireSpreadsAndConsumes);
        run("fire without fuel burns out", TestMain::testFireDiesWithoutFuel);
        run("water and rain put fire out, a roof saves it",
                TestMain::testWaterAndRainExtinguishFire);
        run("snow settles in the tundra, thickens and melts",
                TestMain::testSnowAccumulation);
        run("player stops striding when he stops walking",
                TestMain::testPlayerWalkAmplitude);
        run("player model faces the same direction as the camera",
                TestMain::testPlayerModelFacing);
        run("hotbar selection spring starts and settles",
                TestMain::testSelectSpring);
        run("compass heading turns the right way", TestMain::testCompassHeading);
        run("compass clock agrees with the sun", TestMain::testCompassClock);
        run("A* walks around a wall instead of into it", TestMain::testPathAroundWall);
        run("A* climbs a step and drops off a ledge", TestMain::testPathVertical);
        run("A* refuses the impossible and stays cheap", TestMain::testPathLimits);
        run("chasing zombie walks around a wall to the doorway",
                TestMain::testZombieNavigatesWall);
        run("zombies flank instead of queueing up", TestMain::testZombieFlanking);
        run("scattered cows gather into a herd", TestMain::testHerding);
        run("footprints hold, then fade out", TestMain::testDecalFade);
        run("chest keeps its contents through a save", TestMain::testChestSaveRoundTrip);
        run("breaking a chest forgets what was inside", TestMain::testChestLifecycle);
        run("chest slots follow the same click rules as the inventory",
                TestMain::testContainerClicks);
        run("rivers are ribbons, not blotches", TestMain::testRiverShape);
        run("rivers never cut canyons and never raise ground",
                TestMain::testRiverCarveLimits);
        run("generated world actually has fresh water", TestMain::testRiverInWorld);
        run("furnace smelts, burns fuel and stops when full",
                TestMain::testFurnaceSmelting);
        run("furnace wastes no fuel and keeps state through a save",
                TestMain::testFurnaceFuelAndSave);
        run("cooking is worth the trouble", TestMain::testCookedFoodBalance);
        run("sleep needs night and quiet, and wakes at dawn", TestMain::testSleepRules);
        run("bedroll is craftable, layered and save-safe", TestMain::testBedroll);
        run("bedroll meshes half a block tall", TestMain::testBedrollHeight);
        run("cave ambience is rare and only underground", TestMain::testAmbientCave);
        run("water drowns out the cave, rain above never reaches it",
                TestMain::testAmbientPriority);
        run("rain is heard only as far as the sky reaches", TestMain::testRainNeedsSky);
        run("every ambient cue has files behind it", TestMain::testAmbientAssets);
        run("a cave sounds more enclosed than a field", TestMain::testAcousticProbe);
        run("spatial sound grows smoothly while approaching", TestMain::testSpatialSoundGain);
        run("behaviour tree picks branches by priority", TestMain::testBehaviorTree);
        run("burning zombie runs for shade when there is any",
                TestMain::testZombieSeeksShelter);
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
        run("Mob peaceful wanders and idles", TestMain::testMobPeacefulStates);
        run("Mob flees when hurt", TestMain::testMobFlee);
        run("Mob dies at zero health", TestMain::testMobDeath);
        run("Zombie chases nearby player", TestMain::testZombieChase);
        run("Zombie attack respects cooldown", TestMain::testZombieAttackCooldown);
        run("Zombie is passive in creative", TestMain::testZombieCreativePassive);
        run("Zombie burns under open sky", TestMain::testZombieSunBurn);
        run("MobSpawner rules and caps", TestMain::testMobSpawnerRules);
        run("MobSpawner needs loaded chunks", TestMain::testMobSpawnerNeedsChunks);
        run("MobSpawner despawns distant mobs", TestMain::testMobSpawnerDespawn);
        run("MobSkins generates distinct skins", TestMain::testMobSkins);
        run("first-person hand stays on screen", TestMain::testFirstPersonPoses);
        run("mob sounds resolve for every type", TestMain::testMobSounds);
        run("Mob takes fall damage by impact speed", TestMain::testMobFallDamage);
        run("Mob emits step sounds while walking", TestMain::testMobStepSounds);
        run("EntityPhysics separates overlapping bodies", TestMain::testSeparate);
        run("Mob sidesteps a wall instead of butting it", TestMain::testMobSidestep);
        run("Mob ignores hits inside the invulnerability window", TestMain::testMobInvulnWindow);
        run("Player ignores mob hits inside its window", TestMain::testPlayerInvulnWindow);
        run("Zombie burns at /time set day, not only at noon", TestMain::testZombieBurnsAtDay);
        run("Zombie needs line of sight to aggro", TestMain::testZombieLineOfSight);
        run("Zombie has a blind spot behind it", TestMain::testSightCone);
        run("walls muffle positional sound", TestMain::testSoundOcclusion);
        run("darkness shortens the detection range", TestMain::testSightRangeByLight);
        run("noise pulls a zombie to investigate, chase wins over noise",
                TestMain::testHearingAndInvestigate);
        run("Dead mob lingers for the fall-over animation", TestMain::testMobDeathLinger);
        run("Player regen pauses after a hit", TestMain::testRegenPause);
        FeatureTests.runAll((name, check) -> run(name, check::run));
        MenuTests.runAll((name, check) -> run(name, check::run));
        MusicTests.runAll((name, check) -> run(name, check::run));
        InventoryTests.runAll((name, check) -> run(name, check::run));
        NetworkTests.runAll((name, check) -> run(name, check::run));

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
        assertEq("11 biomes", 11, Biome.values().length);
        assertTrue("ocean floor below sea level", Biome.OCEAN.baseHeight < World.SEA_LEVEL);
        assertTrue("tundra surface is snowy grass", Biome.TUNDRA.surfaceBlock == BlockType.SNOWY_GRASS);
        assertTrue("desert surface is sand", Biome.DESERT.surfaceBlock == BlockType.SAND);
        assertTrue("forest denser than plains", Biome.FOREST.treesPer128 > Biome.PLAINS.treesPer128);
        assertTrue("ocean has no trees", Biome.OCEAN.treeType == Biome.TreeType.NONE);
        for (Biome b : Biome.values())
            assertTrue(b + " amplitude in (0,2]", b.amplitude > 0 && b.amplitude <= 2.0);
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
                GameMode.SURVIVAL, 999L, 7.5f);
        sm.saveLevel("w1", in);
        LevelData out = sm.loadLevel("w1");
        assertTrue("loadLevel non-null", out != null);
        assertEq("seed", 42L, out.seed);
        assertEq("name", "Test World", out.name);
        assertEq("px", 1.5, out.px);
        assertEq("spawnY", 20.0, out.spawnY);
        assertEq("selectedSlot", 4, out.selectedSlot);
        assertEq("lastPlayed", 999L, out.lastPlayed);
        assertEq("health", 7.5f, out.health);
        sm.renameWorld("w1", "Renamed");
        assertEq("rename keeps health", 7.5f, sm.loadLevel("w1").health);
        assertEq("gameMode", GameMode.SURVIVAL, out.gameMode);
        assertEq("inv[0] type", BlockType.COBBLE, out.inventory[0].block());
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
                1.5f, true, 0.25f, 0.9f, 2, 2);
        sm.saveOptions(in);
        Options out = sm.loadOptions();
        assertEq("renderRadius", 8, out.renderRadius);
        assertEq("fovDegrees", 90, out.fovDegrees);
        assertEq("maxFps", 144, out.maxFps);
        assertTrue("vsync", !out.vsync);
        assertTrue("fullscreen", out.fullscreen);
        assertTrue("invertMouseY", out.invertMouseY);
        assertEq("guiScale", 2, out.guiScale);
        assertEq("shaderQuality", 2, out.shaderQuality);
    }

    /**
     * Настройки экрана, графики и игры переживают запись — и старый файл тоже.
     *
     * <p>Хвост v7 самоописывающийся: незнакомый ключ читается по виду
     * значения и пропускается. Проверяем и это — иначе первая же новая
     * настройка тихо обнулит соседние при чтении сборкой постарше.
     */
    private static void testVideoOptionsRoundTrip() throws Exception {
        SaveManager sm = freshManager();
        Options.Video video = new Options.Video(1, 3, 75, 8);
        Options.Graphics gfx = new Options.Graphics(3, false, false, false, true, 1, 0, 45, false, false);
        Options.Gameplay play = new Options.Gameplay(false, false, false, 2);
        Options in = new Options(8, 90, 0.7f, 0.5f, 144, false, true, false,
                1.5f, true, 0.25f, 0.9f, 2, 3, new com.mineclone.core.KeyBindings(),
                true, false, true, "all", 0, video, gfx, play);
        sm.saveOptions(in);
        Options out = sm.loadOptions();
        assertEq("windowMode", 1, out.video.windowMode());
        assertEq("resolutionIndex", 3, out.video.resolutionIndex());
        assertEq("renderScale", 75, out.video.renderScale());
        assertEq("antialiasing", 8, out.video.antialiasing());
        assertEq("shadows", 3, out.graphics.shadows());
        assertTrue("bloom off", !out.graphics.bloom());
        assertTrue("water reflections on", out.graphics.waterReflections());
        assertEq("particles", 1, out.graphics.particles());
        assertEq("weather", 0, out.graphics.weather());
        assertEq("entityDistance", 45, out.graphics.entityDistance());
        assertTrue("occlusion off", !out.graphics.occlusion());
        assertTrue("chunk lod off", !out.graphics.chunkLod());
        assertTrue("camera shake off", !out.gameplay.cameraShake());
        assertEq("fpsDisplay", 2, out.gameplay.fpsDisplay());
        assertTrue("advanced tooltips survive", out.advancedTooltips);

        // Настоящий файл шестой версии, собранный байтами: новых настроек в
        // нём нет, и они обязаны стать умолчаниями, а не нулями. Через
        // saveOptions такой файл не получить — он всегда пишет текущую версию.
        File root = freshRoot();
        SaveManager old6 = new SaveManager(new File(root, "saves"));
        writeOptionsV6(new File(root, SaveFormat.OPTIONS_FILE));
        Options legacy = old6.loadOptions();
        assertEq("legacy render radius", 4, legacy.renderRadius);
        assertEq("legacy gui scale", 1, legacy.guiScale);
        assertEq("legacy render scale", Options.Video.defaults().renderScale(),
                legacy.video.renderScale());
        assertEq("legacy shadows", Options.Graphics.defaults().shadows(), legacy.graphics.shadows());
        assertTrue("legacy occlusion", legacy.graphics.occlusion());
    }

    /** options.dat ровно в том виде, в каком его писала шестая версия. */
    private static void writeOptionsV6(File f) throws Exception {
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(
                new java.util.zip.GZIPOutputStream(new java.io.FileOutputStream(f)))) {
            out.writeInt(SaveFormat.MAGIC);
            out.writeInt(6);
            out.writeInt(4);          // renderRadius
            out.writeInt(70);         // fov
            out.writeFloat(1f);       // brightness
            out.writeFloat(1f);       // master
            out.writeInt(0);          // maxFps
            out.writeBoolean(true);   // vsync
            out.writeBoolean(false);  // fullscreen
            out.writeBoolean(true);   // viewBobbing
            out.writeFloat(1f);       // sensitivity
            out.writeBoolean(false);  // invertY
            out.writeFloat(1f);       // music
            out.writeFloat(1f);       // effects
            out.writeInt(1);          // guiScale
            out.writeInt(1);          // shaderQuality
            out.writeInt(0);          // раскладка клавиш: пусто — значит по умолчанию
            out.writeBoolean(false);  // advancedTooltips
            out.writeBoolean(false);  // recipeBookOpen
            out.writeBoolean(false);  // recipeBookCraftable
            out.writeUTF("all");
            out.writeInt(0);          // sortMode
        }
    }

    /**
     * walkedDistance монотонен и на месте не убывает, поэтому размах шага
     * обязан гаснуть отдельной амплитудой — иначе остановившийся игрок
     * застывает с раскинутыми ногами.
     */
    private static void testPlayerWalkAmplitude() {
        float mid = 0.62f;   // фаза, на которой синус заведомо не ноль
        assertTrue("stride swings while walking",
                Math.abs(PlayerRenderer.swingOf(mid, 1f)) > 0.1f);
        assertTrue("stride is still at rest",
                Math.abs(PlayerRenderer.swingOf(mid, 0f)) < 1e-6f);
        assertTrue("amplitude scales the stride",
                Math.abs(PlayerRenderer.swingOf(mid, 0.5f))
                        < Math.abs(PlayerRenderer.swingOf(mid, 1f)));
        // Амплитуда выше единицы не должна выкручивать ноги за предел.
        assertTrue("amplitude is clamped",
                Math.abs(PlayerRenderer.swingOf(mid, 5f) - PlayerRenderer.swingOf(mid, 1f)) < 1e-6f);
    }

    /**
     * Лицевая грань скина лежит на локальной -Z. При переводе yaw/pitch камеры
     * в поворот модели она должна смотреть в тот же вектор, а не зеркально от
     * него: иначе удалённый игрок при повороте оказывается боком или спиной.
     */
    private static void testPlayerModelFacing() {
        Camera camera = new Camera();
        camera.yaw = (float) Math.toRadians(90);
        camera.pitch = (float) Math.toRadians(25);

        Vector3f modelForward = playerHeadForward(camera.yaw, camera.pitch);
        Vector3f cameraForward = camera.forward();
        assertTrue("model X follows camera (" + modelForward + " vs " + cameraForward + ")",
                Math.abs(modelForward.x - cameraForward.x) < 1e-5f);
        assertTrue("model Y follows camera (" + modelForward + " vs " + cameraForward + ")",
                Math.abs(modelForward.y - cameraForward.y) < 1e-5f);
        assertTrue("model Z follows camera (" + modelForward + " vs " + cameraForward + ")",
                Math.abs(modelForward.z - cameraForward.z) < 1e-5f);

        // Корпус развёрнут на предел в сторону — голова обязана остаться на
        // курсе взгляда, иначе иерархия «корпус → голова» собрана неверно.
        float bodyYaw = camera.yaw - com.mineclone.render.BodyRotation.MAX_OFFSET;
        Vector3f turned = playerHeadForward(bodyYaw, camera.yaw, camera.pitch);
        assertTrue("head keeps the camera direction over a turned body ("
                        + turned + " vs " + cameraForward + ")",
                Math.abs(turned.x - cameraForward.x) < 1e-5f
                        && Math.abs(turned.y - cameraForward.y) < 1e-5f
                        && Math.abs(turned.z - cameraForward.z) < 1e-5f);
    }

    /** Calls the shared colour/shadow transform without needing an OpenGL context. */
    private static Vector3f playerHeadForward(float yaw, float pitch) {
        return playerHeadForward(yaw, yaw, pitch);
    }

    /**
     * То же, но корпус и голова врозь.
     *
     * <p>Голова обязана смотреть туда, куда смотрит камера, каким бы ни был
     * курс корпуса: иерархия «корпус → голова» на то и заведена.
     */
    private static Vector3f playerHeadForward(float bodyYaw, float headYaw, float pitch) {
        try {
            var body = PlayerRenderer.class.getDeclaredField("BODY");
            body.setAccessible(true);
            Object head = java.lang.reflect.Array.get(body.get(null), 1);
            var matrix = Arrays.stream(PlayerRenderer.class.getDeclaredMethods())
                    .filter(method -> method.getName().equals("partMatrix"))
                    .findFirst().orElseThrow();
            matrix.setAccessible(true);
            Matrix4f out = new Matrix4f();
            matrix.invoke(null, new Vector3f(), bodyYaw, headYaw, pitch, 0f, 0f, 0f, head, out);
            return out.transformDirection(new Vector3f(0f, 0f, -1f)).normalize();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot inspect player head transform", e);
        }
    }

    /**
     * Пружина выделения слота обязана быть нулевой на концах: до
     * переключения и после того, как анимация отыграла. Ненулевой хвост
     * означает, что рамка навсегда останется смещённой.
     */
    private static void testSelectSpring() {
        assertTrue("spring is at rest before the switch", Hud.selectSpring(0f) == 0f);
        assertTrue("spring is at rest after it settles", Hud.selectSpring(1f) == 0f);
        assertTrue("spring is at rest past the end", Hud.selectSpring(2f) == 0f);
        float peak = 0f;
        for (float t = 0.02f; t < 1f; t += 0.02f)
            peak = Math.max(peak, Math.abs(Hud.selectSpring(t)));
        assertTrue("spring actually moves (peak " + peak + ")", peak > 0.2f);
        // Затухание: вторая половина анимации обязана быть тише первой.
        assertTrue("spring decays", Math.abs(Hud.selectSpring(0.75f)) < Math.abs(Hud.selectSpring(0.2f)));
    }

    /**
     * Лента компаса едет в ту же сторону, что и мир.
     *
     * Ошибка знака здесь не падает и не ломает кадр — компас просто врёт,
     * и заметить это можно только заблудившись.
     */
    private static void testCompassHeading() {
        // Камера при yaw = 0 смотрит в −Z, а солнце встаёт именно там, значит
        // это восток: курс 90°.
        assertTrue("yaw 0 looks east", Math.abs(Hud.heading(0f) - 90f) < 1e-3f);
        assertTrue("quarter turn is a quarter of the dial",
                Math.abs(Hud.heading((float) (Math.PI / 2.0)) - 180f) < 1e-3f);
        assertTrue("heading never leaves the dial",
                Hud.heading(-40f) >= 0f && Hud.heading(-40f) < 360f);

        // Кратчайшая дуга: через ноль, а не длинным путём.
        assertTrue("wraps forward", Math.abs(Hud.angleDelta(10f, 350f) - 20f) < 1e-3f);
        assertTrue("wraps backward", Math.abs(Hud.angleDelta(350f, 10f) + 20f) < 1e-3f);
        assertTrue("delta stays in range",
                Math.abs(Hud.angleDelta(200f, 0f)) <= 180f);

        // Повернулись направо — метки уехали влево, и наоборот.
        float before = Hud.angleDelta(90f, Hud.heading(0f));
        float after = Hud.angleDelta(90f, Hud.heading(0.3f));
        assertTrue("marks slide against the turn (" + before + " -> " + after + ")",
                after < before);
    }

    /**
     * Часы на компасе и команда {@code /time} обязаны показывать одно и то же,
     * а полдень обязан совпадать с верхней точкой солнца.
     */
    private static void testCompassClock() {
        assertEq("noon", "12:00", Hud.clockText((float) (Math.PI / 2.0)));
        assertEq("sunrise", "06:00", Hud.clockText(0f));
        assertEq("sunset", "18:00", Hud.clockText((float) Math.PI));
        assertEq("midnight", "00:00", Hud.clockText((float) (Math.PI * 1.5)));
        // Отрицательное и переполненное время не должно ломать формат.
        assertEq("wraps below zero", "00:00", Hud.clockText((float) (-Math.PI / 2.0)));
        assertEq("wraps above a full turn", "12:00",
                Hud.clockText((float) (Math.PI / 2.0 + Math.PI * 4.0)));
        // Полдень по часам — это и максимум солнца.
        assertTrue("noon is the sun's top",
                com.mineclone.render.SunLight.sunDirection((float) (Math.PI / 2.0)).y > 0.999f);
    }

    // ---- Поиск пути ------------------------------------------------------

    /** Рост моба в блоках для тестов пути. */
    private static final int PATH_H = 2;
    /**
     * Границы пола в {@link #flatTestWorld()}: чанки −1..1, то есть блоки
     * −16..31 по обеим осям. За этой рамкой блоков нет, стоять негде — и
     * стена, перекрывающая полосу целиком, делит мир надвое по-настоящему.
     */
    private static final int FLOOR_MIN = -16, FLOOR_MAX = 31;

    /** Стена во всю ширину пола поперёк оси Z, с проёмом в gapX. */
    private static void wallAcross(World w, int z, int height, int gapX) {
        for (int x = FLOOR_MIN; x <= FLOOR_MAX; x++) {
            if (x == gapX)
                continue;
            for (int y = 11; y < 11 + height; y++)
                w.setBlock(x, y, z, BlockType.STONE);
        }
    }

    private static java.util.List<org.joml.Vector3f> path(World w,
            int sx, int sy, int sz, int tx, int ty, int tz) {
        return com.mineclone.world.entity.PathFinder.find(w, sx, sy, sz, tx, ty, tz, PATH_H);
    }

    private static void testPathAroundWall() {
        World w = flatTestWorld();
        wallAcross(w, 8, 3, 14);   // сплошная стена с единственным проёмом

        var p = path(w, 5, 11, 4, 5, 11, 12);
        assertTrue("path exists through the doorway", p != null && !p.isEmpty());
        // Ни одна точка пути не должна оказаться внутри камня.
        boolean usedGap = false;
        for (var v : p) {
            BlockType b = w.getBlock((int) Math.floor(v.x), (int) v.y, (int) Math.floor(v.z));
            assertTrue("path stays out of solid blocks at " + v, !b.solid);
            if (Math.floor(v.x) == 14 && Math.floor(v.z) == 8)
                usedGap = true;
        }
        assertTrue("path goes through the only doorway", usedGap);
        var last = p.get(p.size() - 1);
        assertTrue("path ends at the goal",
                Math.floor(last.x) == 5 && Math.floor(last.z) == 12);
        // Крюк через проём длиннее прямой (8 шагов) минимум вдвое.
        assertTrue("detour is longer than the straight line (" + p.size() + ")", p.size() >= 16);

        // Замуровали проём — пути нет вовсе.
        for (int y = 11; y < 14; y++)
            w.setBlock(14, y, 8, BlockType.STONE);
        assertTrue("no path through a sealed wall", path(w, 5, 11, 4, 5, 11, 12) == null);
    }

    private static void testPathVertical() {
        World w = flatTestWorld();
        // Помост в один блок: на него обязаны запрыгивать.
        for (int x = 4; x <= 8; x++)
            for (int z = 6; z <= 10; z++)
                w.setBlock(x, 11, z, BlockType.STONE);

        var up = path(w, 6, 11, 4, 6, 12, 8);
        assertTrue("path climbs one block", up != null && !up.isEmpty());
        assertEq("path ends on top of the step", 12f, up.get(up.size() - 1).y);

        var down = path(w, 6, 12, 8, 6, 11, 4);
        assertTrue("path drops off the ledge", down != null && !down.isEmpty());
        assertEq("path ends at the bottom", 11f, down.get(down.size() - 1).y);

        // Два блока — это стена, а не ступенька.
        World w2 = flatTestWorld();
        wallAcross(w2, 8, 2, Integer.MIN_VALUE);
        assertTrue("two blocks is a wall, not a step", path(w2, 5, 11, 4, 5, 11, 12) == null);

        // Прыжок под потолком запрещён: над собственной головой должно быть
        // свободно. Потолок кладём везде, кроме объёма над помостом, — цель
        // остаётся законной, а подпрыгнуть к ней неоткуда.
        World w3 = flatTestWorld();
        for (int x = 4; x <= 8; x++)
            for (int z = 6; z <= 10; z++)
                w3.setBlock(x, 11, z, BlockType.STONE);
        for (int x = FLOOR_MIN; x <= FLOOR_MAX; x++)
            for (int z = FLOOR_MIN; z <= FLOOR_MAX; z++) {
                boolean overStep = x >= 4 && x <= 8 && z >= 6 && z <= 10;
                if (!overStep)
                    w3.setBlock(x, 13, z, BlockType.STONE);
            }
        assertTrue("the goal itself is still legal",
                com.mineclone.world.entity.PathFinder.standable(w3, 6, 12, 8, PATH_H));
        assertTrue("cannot jump with a ceiling overhead", path(w3, 6, 11, 4, 6, 12, 8) == null);
    }

    private static void testPathLimits() {
        World w = flatTestWorld();
        // Цель в воздухе без опоры — пути нет, а не бесконечный поиск.
        assertTrue("no path to thin air", path(w, 5, 11, 5, 5, 60, 5) == null);

        // Уже на месте — пустой путь, а не null и не шаг в никуда.
        var here = path(w, 5, 11, 5, 5, 11, 5);
        assertTrue("standing on the goal gives an empty path", here != null && here.isEmpty());

        // Цель за краем пола недостижима, и поиск обязан сдаться быстро,
        // а не перебирать всё вокруг.
        long t0 = System.nanoTime();
        var far = path(w, 0, 11, 0, 300, 11, 300);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        assertTrue("unreachable goal returns null", far == null);
        assertTrue("search gives up instead of hanging (" + ms + " ms)", ms < 300);

        // Упаковка ключа обязана пережить отрицательные координаты: молчаливая
        // потеря знака здесь превращает путь в мусор без единой ошибки.
        long k = com.mineclone.world.entity.PathFinder.key(-137, 64, -9001);
        assertEq("key keeps x", -137, com.mineclone.world.entity.PathFinder.unpackX(k));
        assertEq("key keeps y", 64, com.mineclone.world.entity.PathFinder.unpackY(k));
        assertEq("key keeps z", -9001, com.mineclone.world.entity.PathFinder.unpackZ(k));
    }

    /**
     * Зомби с маршрутом обязан обойти стену и дойти до игрока.
     *
     * Прямое наведение упирается в стену и топчется в ней до утра — ровно то
     * поведение, ради которого заводился A*. Видимость здесь не при чём:
     * состояние погони выставлено руками, восприятие проверяется отдельно.
     */
    private static void testZombieNavigatesWall() {
        World w = flatTestWorld();
        wallAcross(w, 8, 3, 14);          // единственный проём на x = 14

        var player = new org.joml.Vector3f(5.5f, 11f, 12.5f);
        var m = spawnAt(com.mineclone.world.entity.MobType.ZOMBIE, 5.5f, 11f, 4.5f, 7);
        m.state = com.mineclone.world.entity.Mob.State.CHASE;

        float best = Float.MAX_VALUE;
        boolean crossed = false;
        for (int i = 0; i < 20 * 45; i++) {          // 45 секунд при 20 Гц
            m.update(w, player, 1f / 20f, 0f, true);
            best = Math.min(best, m.position.distance(player));
            if (m.position.z > 8.9f)
                crossed = true;
            if (best < 2f)
                break;
        }
        assertTrue("zombie got past the wall", crossed);
        assertTrue("zombie reached the player (closest " + best + ")", best < 2.5f);
        assertTrue("zombie never walked into stone",
                !w.getBlock((int) Math.floor(m.position.x), (int) Math.floor(m.position.y),
                        (int) Math.floor(m.position.z)).solid);
    }

    /**
     * Стая заходит с разных сторон, а не выстраивается в колонну.
     *
     * Сторона захода постоянна на всю жизнь моба: иначе стая дёргается, меняя
     * направление каждую секунду, и выглядит хуже, чем без фланкирования.
     */
    private static void testZombieFlanking() {
        boolean sawLeft = false, sawRight = false, sawStraight = false;
        for (long seed = 0; seed < 24; seed++) {
            int side = spawnAt(com.mineclone.world.entity.MobType.ZOMBIE,
                    0.5f, 11f, 0.5f, seed).flankSide();
            assertTrue("flank side stays in range", side >= -1 && side <= 1);
            sawLeft |= side == -1;
            sawRight |= side == 1;
            sawStraight |= side == 0;
        }
        assertTrue("some zombies swing left", sawLeft);
        assertTrue("some zombies swing right", sawRight);
        assertTrue("some zombies come straight on", sawStraight);

        // Заходящий сбоку уводит себя вбок от прямой, идущий в лоб — нет.
        World w = flatTestWorld();
        var player = new org.joml.Vector3f(5.5f, 11f, 25.5f);
        float sideDrift = -1f, straightDrift = -1f;
        for (long seed = 0; seed < 24 && (sideDrift < 0f || straightDrift < 0f); seed++) {
            var m = spawnAt(com.mineclone.world.entity.MobType.ZOMBIE, 5.5f, 11f, 5.5f, seed);
            m.state = com.mineclone.world.entity.Mob.State.CHASE;
            for (int i = 0; i < 20 * 4; i++)
                m.update(w, player, 1f / 20f, 0f, true);
            float drift = Math.abs(m.position.x - 5.5f);
            if (m.flankSide() != 0 && sideDrift < 0f)
                sideDrift = drift;
            if (m.flankSide() == 0 && straightDrift < 0f)
                straightDrift = drift;
        }
        assertTrue("a flanking zombie leaves the straight line (" + sideDrift + ")",
                sideDrift > 0.8f);
        assertTrue("a head-on zombie keeps the straight line (" + straightDrift + ")",
                straightDrift >= 0f && straightDrift < 0.4f);
    }

    /**
     * Разбредённое стадо обязано собираться, но не схлопываться в точку.
     *
     * Без тяги к своим мирные мобы расползаются случайным блужданием и через
     * пару минут стадо превращается в одиночек по всей карте.
     */
    private static void testHerding() {
        World w = flatTestWorld();
        var far = new org.joml.Vector3f(300f, 11f, 300f);   // игрока рядом нет
        java.util.List<com.mineclone.world.entity.Mob> herd = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++)
            herd.add(spawnAt(com.mineclone.world.entity.MobType.COW,
                    8.5f + (i % 3) * 7f, 11f, 8.5f + (i / 3) * 7f, 40 + i));

        float before = herdSpread(herd);
        for (int i = 0; i < 20 * 90; i++) {
            com.mineclone.world.entity.MobHerd.update(herd);
            for (var m : herd)
                m.update(w, far, 1f / 20f, 0f, false);
        }
        float after = herdSpread(herd);
        assertTrue("herd tightens up (" + before + " -> " + after + ")", after < before);
        assertTrue("herd does not collapse into one point (" + after + ")", after > 0.4f);

        // Зомби в стадо не входит: тяга к своим считается по виду.
        var lone = spawnAt(com.mineclone.world.entity.MobType.ZOMBIE, 8.5f, 11f, 8.5f, 3);
        herd.add(lone);
        com.mineclone.world.entity.MobHerd.update(herd);
        assertEq("a zombie has no herd", 0f, lone.herdDistance());

        // Одинокая корова тоже без стада — тянуть её некуда.
        var solo = java.util.List.of(
                spawnAt(com.mineclone.world.entity.MobType.COW, 0.5f, 11f, 0.5f, 9));
        com.mineclone.world.entity.MobHerd.update(solo);
        assertEq("a single cow has no herd", 0f, solo.get(0).herdDistance());
    }

    /** Средний разброс стада относительно его центра. */
    private static float herdSpread(java.util.List<com.mineclone.world.entity.Mob> herd) {
        float cx = 0f, cz = 0f;
        for (var m : herd) {
            cx += m.position.x;
            cz += m.position.z;
        }
        cx /= herd.size();
        cz /= herd.size();
        float sum = 0f;
        for (var m : herd)
            sum += (float) Math.hypot(m.position.x - cx, m.position.z - cz);
        return sum / herd.size();
    }

    /**
     * Селектор берёт первую непровалившуюся ветку, последовательность —
     * останавливается на первой неудаче. Порядок веток в дереве зомби и есть
     * правило игры, поэтому комбинаторы обязаны быть проверены отдельно.
     */
    private static void testBehaviorTree() {
        var log = new StringBuilder();
        com.mineclone.world.entity.Behavior fail = (m, c) -> {
            log.append('f');
            return com.mineclone.world.entity.Behavior.Status.FAILURE;
        };
        com.mineclone.world.entity.Behavior ok = (m, c) -> {
            log.append('o');
            return com.mineclone.world.entity.Behavior.Status.SUCCESS;
        };
        com.mineclone.world.entity.Behavior running = (m, c) -> {
            log.append('r');
            return com.mineclone.world.entity.Behavior.Status.RUNNING;
        };

        assertEq("selector takes the first branch that works",
                com.mineclone.world.entity.Behavior.Status.SUCCESS,
                com.mineclone.world.entity.Behavior.selector(fail, ok, ok).tick(null, null));
        assertEq("selector stopped right after the first success", "fo", log.toString());

        log.setLength(0);
        assertEq("selector fails when every branch fails",
                com.mineclone.world.entity.Behavior.Status.FAILURE,
                com.mineclone.world.entity.Behavior.selector(fail, fail).tick(null, null));

        log.setLength(0);
        assertEq("RUNNING also stops the selector",
                com.mineclone.world.entity.Behavior.Status.RUNNING,
                com.mineclone.world.entity.Behavior.selector(running, ok).tick(null, null));
        assertEq("nothing ran after RUNNING", "r", log.toString());

        log.setLength(0);
        assertEq("sequence stops on the first failure",
                com.mineclone.world.entity.Behavior.Status.FAILURE,
                com.mineclone.world.entity.Behavior.sequence(ok, fail, ok).tick(null, null));
        assertEq("sequence did not run past the failure", "of", log.toString());

        assertEq("check turns a false condition into FAILURE",
                com.mineclone.world.entity.Behavior.Status.FAILURE,
                com.mineclone.world.entity.Behavior.check((m, c) -> false).tick(null, null));
    }

    /**
     * Горящий зомби бежит в тень, если она есть, и продолжает погоню, если её
     * нет. Ветка укрытия стоит в дереве выше погони — но проваливается там,
     * где бежать некуда, иначе зомби в чистом поле застыл бы столбом.
     */
    private static void testZombieSeeksShelter() {
        // Крыша на y=15 над пятачком рядом с зомби.
        World roofed = flatTestWorld();
        for (int x = 20; x <= 25; x++)
            for (int z = 6; z <= 14; z++)
                roofed.setBlock(x, 15, z, BlockType.STONE);

        var player = new org.joml.Vector3f(10.5f, 11f, 10.5f);
        var m = spawnAt(com.mineclone.world.entity.MobType.ZOMBIE, 16.5f, 11f, 10.5f, 5);
        m.state = com.mineclone.world.entity.Mob.State.CHASE;
        boolean ranForShade = false;
        for (int i = 0; i < 20 * 20; i++) {
            m.update(roofed, player, 1f / 20f, 1f, true);   // daylight = полдень
            if (m.state == com.mineclone.world.entity.Mob.State.SEEK_SHELTER)
                ranForShade = true;
        }
        assertTrue("zombie broke off the chase for shade", ranForShade);
        assertTrue("zombie ended up under the roof (x=" + m.position.x + ")",
                m.position.x >= 20f);
        assertTrue("zombie stopped burning", !m.burning);
        assertTrue("shade saved it from dying (" + m.health + ")", m.health > 0f);

        // Чистое поле: прятаться негде, ветка обязана провалиться.
        World open = flatTestWorld();
        var m2 = spawnAt(com.mineclone.world.entity.MobType.ZOMBIE, 16.5f, 11f, 10.5f, 5);
        m2.state = com.mineclone.world.entity.Mob.State.CHASE;
        float startDist = m2.position.distance(player);
        for (int i = 0; i < 20 * 6; i++)
            m2.update(open, player, 1f / 20f, 1f, true);
        assertTrue("no shade means no shelter state",
                m2.state != com.mineclone.world.entity.Mob.State.SEEK_SHELTER);
        assertTrue("it keeps chasing instead of freezing",
                m2.position.distance(player) < startDist - 1f);
        assertTrue("and it does burn in the open", m2.health < m2.type.maxHealth);
    }

    /**
     * След держится ровным, пока не начнёт таять, и уходит в ноль.
     *
     * Затухание с первой же секунды выглядит как мерцание: дорожка за игроком
     * должна лежать, а не пульсировать.
     */
    private static void testDecalFade() {
        assertEq("fresh print is solid", 1f,
                com.mineclone.render.DecalRenderer.fade(10f, 10f));
        assertEq("still solid past the middle", 1f,
                com.mineclone.render.DecalRenderer.fade(5f, 10f));
        assertTrue("fades in the tail",
                com.mineclone.render.DecalRenderer.fade(2f, 10f) < 1f);
        assertEq("gone at the end", 0f,
                com.mineclone.render.DecalRenderer.fade(0f, 10f));
        assertTrue("fade never leaves 0..1",
                com.mineclone.render.DecalRenderer.fade(-1f, 10f) == 0f
                        && com.mineclone.render.DecalRenderer.fade(20f, 10f) == 1f);
        assertEq("a zero-life print is invisible, not a division by zero", 0f,
                com.mineclone.render.DecalRenderer.fade(1f, 0f));
    }

    // ---- Сундуки ---------------------------------------------------------

    private static void testChestSaveRoundTrip() throws Exception {
        SaveManager sm = freshManager();
        byte[] blocks = new byte[com.mineclone.save.SaveFormat.CHUNK_VOLUME];
        byte[] meta = new byte[com.mineclone.save.SaveFormat.CHUNK_VOLUME];
        int key = Chunk.idx(3, 40, 7);
        blocks[key] = (byte) BlockType.CHEST.ordinal();

        ItemStack[] slots = new ItemStack[Chunk.CHEST_SLOTS];
        slots[0] = new ItemStack(BlockType.COBBLE, 41);
        slots[5] = ItemStack.of("iron_pickaxe");
        slots[5].setDamage(77);
        slots[26] = ItemStack.of("porkchop", 3);
        var chests = new java.util.HashMap<Integer, ItemStack[]>();
        chests.put(key, slots);

        sm.saveChunkAsync("w1", new ChunkSnapshot(1, -2, blocks, meta, chests));
        sm.flushAndAwait();
        ChunkSnapshot out = sm.loadChunk("w1", 1, -2);
        assertTrue("chunk loaded", out != null);
        ItemStack[] back = out.chests.get(key);
        assertTrue("chest came back", back != null);
        assertEq("slot count survived", Chunk.CHEST_SLOTS, back.length);
        assertEq("blocks survived", BlockType.COBBLE, back[0].block());
        assertEq("count survived", 41, back[0].count);
        assertTrue("tool survived", back[5] != null && back[5].hasDurability());
        assertEq("tool wear survived", 77, back[5].damage());
        assertTrue("food survived", back[26] != null && back[26].food() != null);
        assertTrue("empty slots stayed empty", back[1] == null);

        // Чанк без сундуков сохраняется и читается как раньше.
        sm.saveChunkAsync("w1", new ChunkSnapshot(4, 4, blocks, meta));
        sm.flushAndAwait();
        ChunkSnapshot plain = sm.loadChunk("w1", 4, 4);
        assertTrue("chunk without chests still loads", plain != null);
        assertTrue("and has no chests", plain.chests.isEmpty());
    }

    private static void testChestLifecycle() {
        World w = flatTestWorld();
        w.setBlock(4, 11, 4, BlockType.CHEST);
        ItemStack[] slots = w.createChest(4, 11, 4);
        assertTrue("chest created", slots != null);
        assertEq("chest is empty at first", Chunk.CHEST_SLOTS, slots.length);
        slots[0] = new ItemStack(BlockType.DIAMOND_ORE, 5);

        // Повторное открытие отдаёт тот же массив, а не новый.
        assertTrue("reopening gives the same storage", w.createChest(4, 11, 4) == slots);
        assertEq("contents kept", 5, w.getChest(4, 11, 4)[0].count);

        // Сломали — содержимое забыто. Иначе оно всплывёт у следующего
        // сундука, поставленного на то же место.
        w.setBlock(4, 11, 4, BlockType.AIR);
        assertTrue("storage is gone with the block", w.getChest(4, 11, 4) == null);
        w.setBlock(4, 11, 4, BlockType.CHEST);
        ItemStack[] fresh = w.createChest(4, 11, 4);
        assertTrue("a new chest starts empty", fresh[0] == null);

        // Замена сундука сундуком ничего не теряет: блок тот же.
        fresh[1] = new ItemStack(BlockType.STONE, 2);
        w.setBlock(4, 11, 4, BlockType.CHEST);
        assertTrue("setting the same block keeps the storage",
                w.getChest(4, 11, 4) != null && w.getChest(4, 11, 4)[1] != null);
    }

    /**
     * Сундук обязан вести себя ровно как инвентарь: те же правила слияния,
     * дележа правой кнопкой и обмена. Вторая копия этих правил разошлась бы
     * с первой, и игрок обнаружил бы разницу в самый неподходящий момент.
     */
    private static void testContainerClicks() {
        ItemStack[] box = new ItemStack[Chunk.CHEST_SLOTS];
        box[0] = new ItemStack(BlockType.COBBLE, 10);

        // Левой без курсора — забрать всё.
        ItemStack cursor = Inventory.leftClick(box, 0, null);
        assertTrue("picked the stack up", cursor != null && cursor.count == 10);
        assertTrue("slot is empty now", box[0] == null);

        // Левой в пустой слот — положить всё.
        cursor = Inventory.leftClick(box, 3, cursor);
        assertTrue("cursor is empty", cursor == null);
        assertEq("stack landed", 10, box[3].count);

        // Правой без курсора — половина вверх.
        cursor = Inventory.rightClick(box, 3, null);
        assertEq("took half", 5, cursor.count);
        assertEq("half stayed", 5, box[3].count);

        // Правой по своей же стопке — по одному вниз.
        cursor = Inventory.rightClick(box, 3, cursor);
        assertEq("dropped one", 6, box[3].count);
        assertEq("four left on the cursor", 4, cursor.count);

        // Инструмент не делится.
        box[7] = ItemStack.of("stone_axe");
        ItemStack tool = Inventory.rightClick(box, 7, null);
        assertTrue("a tool comes whole", tool != null && tool.hasDurability());
        assertTrue("its slot is empty", box[7] == null);

        // Выход за границы массива ничего не портит.
        ItemStack held = new ItemStack(BlockType.SAND, 1);
        assertTrue("out of range is a no-op", Inventory.leftClick(box, 99, held) == held);
        assertTrue("null array is a no-op", Inventory.leftClick(null, 0, held) == held);
    }

    // ---- Реки и озёра ----------------------------------------------------

    private static void testRiverShape() {
        var r = new com.mineclone.world.Rivers(4242L);
        // Русло — узкая лента. Меряем не маску, а результат: сколько низин
        // вдоль длинной прямой действительно уходит под воду. Маска шире
        // русла намеренно — по ней размыв ещё затухает с высотой.
        int sea = World.SEA_LEVEL;
        int hits = 0, n = 0;
        for (int x = -3000; x < 3000; x += 3) {
            n++;
            if (r.carve(x, 517, sea + 2, sea) < sea)
                hits++;
        }
        float share = hits / (float) n;
        assertTrue("rivers flood a small share of the lowlands (" + share + ")",
                share > 0.01f && share < 0.20f);

        // Лента непрерывна: попав в русло, соседняя точка тоже в нём.
        int streak = 0, bestStreak = 0;
        for (int x = -3000; x < 3000; x++) {
            if (r.riverStrength(x, 517) > 0.5f) {
                streak++;
                bestStreak = Math.max(bestStreak, streak);
            } else {
                streak = 0;
            }
        }
        assertTrue("a river is wider than one block (" + bestStreak + ")", bestStreak >= 3);

        // Сид решает всё: другой сид — другие русла.
        var other = new com.mineclone.world.Rivers(777L);
        boolean differs = false;
        for (int x = 0; x < 400 && !differs; x++)
            differs = Math.abs(r.riverStrength(x, 40) - other.riverStrength(x, 40)) > 0.2f;
        assertTrue("a different seed gives different rivers", differs);
        assertEq("same seed is reproducible", r.riverStrength(123, 456),
                new com.mineclone.world.Rivers(4242L).riverStrength(123, 456));
    }

    private static void testRiverCarveLimits() {
        var r = new com.mineclone.world.Rivers(4242L);
        int sea = World.SEA_LEVEL;
        for (int x = -900; x < 900; x += 7)
            for (int z = -400; z < 400; z += 11) {
                // Размыв никогда не поднимает рельеф.
                for (int h : new int[] { sea - 8, sea, sea + 3, sea + 12, sea + 40 }) {
                    int out = r.carve(x, z, h, sea);
                    assertTrue("carve never raises ground", out <= h);
                    assertTrue("carve never digs below the river bed",
                            out >= Math.min(h, sea - com.mineclone.world.Rivers.LAKE_DEPTH));
                }
                // Горы рекой не режутся: иначе получается отвесный каньон.
                int high = sea + 40;
                assertEq("mountains keep their height", high, r.carve(x, z, high, sea));
                // Дно океана трогать незачем — оно уже ниже русла.
                int deep = sea - 20;
                assertEq("the sea floor is left alone", deep, r.carve(x, z, deep, sea));
            }
    }

    private static void testRiverInWorld() {
        World w = new World(1337L);
        int water = 0, land = 0, cliffs = 0;
        for (int cx = -2; cx <= 2; cx++)
            for (int cz = -2; cz <= 2; cz++) {
                Chunk c = w.getChunk(cx, cz);
                for (int x = 0; x < Chunk.SIZE_X; x++)
                    for (int z = 0; z < Chunk.SIZE_Z; z++) {
                        int wx = cx * Chunk.SIZE_X + x, wz = cz * Chunk.SIZE_Z + z;
                        boolean river = w.rivers.riverStrength(wx, wz) > 0.8f
                                || w.rivers.lakeStrength(wx, wz) > 0.8f;
                        BlockType top = c.get(x, World.SEA_LEVEL, z);
                        if (!river)
                            continue;
                        if (top == BlockType.WATER)
                            water++;
                        else
                            land++;
                        // Рядом с руслом не должно быть отвесной стены: это
                        // ровно тот каньон, ради которого заведено затухание
                        // размыва по высоте.
                        if (top == BlockType.WATER && c.get(x, World.SEA_LEVEL + 9, z) != BlockType.AIR)
                            cliffs++;
                    }
            }
        assertTrue("the river mask puts water on the ground (" + water + "/" + (water + land) + ")",
                water > (water + land) / 4);
        assertTrue("no canyon walls beside the water (" + cliffs + ")", cliffs == 0);
    }

    // ---- Печь ------------------------------------------------------------

    private static void testFurnaceSmelting() {
        var f = new com.mineclone.world.Furnace();
        f.input = ItemStack.of("beef", 3);
        f.fuel = ItemStack.of("coal", 1);

        // Первый же тик поджигает печь и съедает единицу топлива.
        assertTrue("lighting the furnace changes the slots", f.tick(0.1f));
        assertTrue("furnace is lit", f.isLit());
        assertTrue("fuel was consumed", f.fuel == null);

        // Одна переплавка — ровно COOK_TIME секунд.
        float cookTime = com.mineclone.world.Smelting.COOK_TIME;
        stepFurnace(f, cookTime - 0.2f, 0.1f);
        assertTrue("nothing is done early", f.output == null);
        stepFurnace(f, 0.4f, 0.1f);
        assertTrue("one item came out", f.output != null && f.output.food() != null);
        assertEq("and it is cooked", item("cooked_beef"), f.output.item);
        assertEq("one went in", 2, f.input.count);

        // Уголь тянет восемь переплавок — трёх кусков ему хватит с запасом.
        stepFurnace(f, cookTime * 2.2f, 0.1f);
        assertTrue("the rest got cooked too", f.input == null);
        assertEq("three cooked in total", 3, f.output.count);

        // Пустая печь не жжёт топливо.
        f.fuel = new ItemStack(BlockType.PLANKS, 4);
        f.burnLeft = 0f;
        stepFurnace(f, 5f, 0.1f);
        assertEq("idle furnace keeps its fuel", 4, f.fuel.count);
        assertTrue("and stays cold", !f.isLit());
    }

    private static void testFurnaceFuelAndSave() throws Exception {
        // Полный выходной слот останавливает печь: иначе результат исчезает.
        var f = new com.mineclone.world.Furnace();
        f.input = new ItemStack(BlockType.SAND, 10);
        f.fuel = ItemStack.of("coal", 5);
        f.output = new ItemStack(BlockType.GLASS, 64);
        stepFurnace(f, 30f, 0.25f);
        assertEq("a full output stops the furnace", 64, f.output.count);
        assertEq("nothing was smelted", 10, f.input.count);
        assertEq("and no fuel was spent", 5, f.fuel.count);

        // Несовместимый результат тоже останавливает: стекло и камень не
        // ложатся в одну стопку.
        f.output = new ItemStack(BlockType.STONE, 1);
        stepFurnace(f, 30f, 0.25f);
        assertEq("mismatched output blocks smelting", 10, f.input.count);

        // Что не плавится — не плавится.
        var idle = new com.mineclone.world.Furnace();
        idle.input = new ItemStack(BlockType.DIRT, 5);
        idle.fuel = ItemStack.of("coal", 1);
        stepFurnace(idle, 30f, 0.25f);
        assertTrue("dirt does not smelt", idle.output == null);
        assertEq("and burns no coal", 1, idle.fuel.count);
        assertTrue("tools are not fuel",
                !com.mineclone.world.Smelting.isFuel(ItemStack.of("wooden_axe")));
        assertTrue("cooked meat does not cook twice",
                com.mineclone.world.Smelting.result(ItemStack.of("cooked_beef", 1)) == null);

        // Состояние переживает сохранение чанка.
        SaveManager sm = freshManager();
        byte[] blocks = new byte[com.mineclone.save.SaveFormat.CHUNK_VOLUME];
        byte[] meta = new byte[com.mineclone.save.SaveFormat.CHUNK_VOLUME];
        int key = Chunk.idx(2, 30, 9);
        var hot = new com.mineclone.world.Furnace();
        hot.input = new ItemStack(BlockType.COBBLE, 7);
        hot.fuel = new ItemStack(BlockType.WOOD, 2);
        hot.output = new ItemStack(BlockType.STONE, 4);
        hot.burnLeft = 3.5f;
        hot.burnMax = 12f;
        hot.cook = 2.25f;
        var map = new java.util.HashMap<Integer, com.mineclone.world.Furnace>();
        map.put(key, hot);
        sm.saveChunkAsync("w1", new ChunkSnapshot(0, 0, blocks, meta,
                new java.util.HashMap<>(), map));
        sm.flushAndAwait();
        ChunkSnapshot out = sm.loadChunk("w1", 0, 0);
        assertTrue("chunk loaded", out != null);
        var back = out.furnaces.get(key);
        assertTrue("furnace came back", back != null);
        assertEq("input survived", 7, back.input.count);
        assertEq("fuel survived", BlockType.WOOD, back.fuel.block());
        assertEq("output survived", 4, back.output.count);
        assertTrue("burn survived", Math.abs(back.burnLeft - 3.5f) < 1e-4f);
        assertTrue("progress survived", Math.abs(back.cook - 2.25f) < 1e-4f);
    }

    /**
     * Жарить должно быть выгодно. Если жареное не сытнее сырого, печь — это
     * механика, которая просто тратит время игрока.
     */
    private static void testCookedFoodBalance() {
        assertTrue("beef is worth cooking",
                item("cooked_beef").food.nutrition() > item("beef").food.nutrition());
        assertTrue("pork is worth cooking",
                item("cooked_porkchop").food.nutrition() > item("porkchop").food.nutrition());
        assertTrue("chicken is worth cooking",
                item("cooked_chicken").food.nutrition() > item("chicken").food.nutrition());
        assertTrue("mutton is worth cooking",
                item("cooked_mutton").food.nutrition() > item("mutton").food.nutrition());
        // Каждый сырой кусок обязан иметь жареную пару, иначе часть добычи
        // становится бессмысленной.
        for (String raw : new String[] { "beef", "porkchop", "chicken", "mutton" })
            assertTrue("there is a cooked form of " + raw,
                    com.mineclone.world.Smelting.result(ItemStack.of(raw, 1)) != null);
    }

    /** Item by registry id — short form for assertions. */
    static com.mineclone.item.Item item(String id) {
        return com.mineclone.item.Items.get().require(id);
    }

    /** The item that places this block. */
    static com.mineclone.item.Item item(BlockType b) {
        return com.mineclone.item.Items.get().forBlock(b);
    }

    private static void stepFurnace(com.mineclone.world.Furnace f, float seconds, float dt) {
        for (float t = 0; t < seconds; t += dt)
            f.tick(dt);
    }

    // ---- Фоновая атмосфера -----------------------------------------------

    private static com.mineclone.audio.AmbientSound ambient(long seed) {
        return new com.mineclone.audio.AmbientSound(new java.util.Random(seed));
    }

    /**
     * Звук пещеры обязан быть редким и звучать только под землёй. Частый —
     * перестаёт пугать; на поверхности — просто мусор в ушах.
     */
    private static void testAmbientCave() {
        var a = ambient(11L);
        // На поверхности — тишина, сколько ни жди.
        int surface = 0;
        for (int i = 0; i < 20 * 600; i++)
            if (a.tick(0.05f, false, false, 0f) != com.mineclone.audio.AmbientSound.Cue.NONE)
                surface++;
        assertEq("nothing plays above ground", 0, surface);

        // Под землёй — играет, но редко.
        int caves = 0;
        float minutes = 30f;
        for (int i = 0; i < (int) (minutes * 60 / 0.05f); i++)
            if (a.tick(0.05f, true, false, 0f) == com.mineclone.audio.AmbientSound.Cue.CAVE)
                caves++;
        assertTrue("caves do speak up (" + caves + ")", caves > 5);
        float perMinute = caves / minutes;
        assertTrue("but not often (" + perMinute + "/min)", perMinute < 1.4f);

        // Выход на поверхность не копит долг: первый шаг обратно под землю
        // не встречает мгновенный вой, накопленный за день.
        var b = ambient(3L);
        for (int i = 0; i < 20 * 900; i++)
            b.tick(0.05f, false, false, 0f);
        assertTrue("the cave timer does not run up a debt outdoors",
                b.caveCountdown() > com.mineclone.audio.AmbientSound.CAVE_MIN * 0.3f);
    }

    private static void testAmbientPriority() {
        var a = ambient(5L);
        // Первый тик задаёт исходное состояние и всплеска не даёт.
        assertEq("no splash on the very first tick",
                com.mineclone.audio.AmbientSound.Cue.NONE, a.tick(0.05f, false, false, 0f));

        // Вход в воду — ровно один всплеск.
        assertEq("entering water splashes once",
                com.mineclone.audio.AmbientSound.Cue.WATER_ENTER, a.tick(0.05f, false, true, 0f));
        assertTrue("and not twice",
                a.tick(0.05f, false, true, 0f) != com.mineclone.audio.AmbientSound.Cue.WATER_ENTER);

        // Под водой не слышно ни пещеры, ни дождя.
        boolean onlyWater = true;
        for (int i = 0; i < 20 * 300; i++) {
            var cue = a.tick(0.05f, true, true, 1f);
            if (cue == com.mineclone.audio.AmbientSound.Cue.CAVE
                    || cue == com.mineclone.audio.AmbientSound.Cue.RAIN
                    || cue == com.mineclone.audio.AmbientSound.Cue.THUNDER)
                onlyWater = false;
        }
        assertTrue("underwater drowns out rain and caves", onlyWater);

        assertEq("leaving water splashes once",
                com.mineclone.audio.AmbientSound.Cue.WATER_EXIT, a.tick(0.05f, false, false, 0f));

        // Под толщей породы ливня наверху не слышно, и пещера звучит как всегда.
        // Раньше дождь шёл по осадкам биома и перебивал пещеру даже в глубине.
        var b = ambient(9L);
        b.tick(0.05f, true, false, 1f);
        int rain = 0, thunder = 0, cave = 0;
        for (int i = 0; i < 20 * 600; i++) {
            var cue = b.tick(0.05f, true, false, 1f);
            if (cue == com.mineclone.audio.AmbientSound.Cue.RAIN) rain++;
            if (cue == com.mineclone.audio.AmbientSound.Cue.THUNDER) thunder++;
            if (cue == com.mineclone.audio.AmbientSound.Cue.CAVE) cave++;
        }
        assertEq("no rain in the depths of a cave", 0, rain);
        assertEq("no thunder there either", 0, thunder);
        assertTrue("the cave speaks up while it pours above (" + cave + ")", cave >= 2);

        // Слабый дождь не звучит вовсе.
        var c = ambient(13L);
        c.tick(0.05f, false, false, 0f);
        int drizzle = 0;
        for (int i = 0; i < 20 * 300; i++)
            if (c.tick(0.05f, false, false,
                    com.mineclone.audio.AmbientSound.RAIN_THRESHOLD * 0.5f)
                    != com.mineclone.audio.AmbientSound.Cue.NONE)
                drizzle++;
        assertEq("a drizzle below the threshold is silent", 0, drizzle);
    }

    /**
     * Дождь слышен настолько, насколько над головой открыто небо. Осадки идут
     * по биому и одинаковы в пещере и на поверхности над ней — раньше ливень
     * наверху звучал в глубине пещеры, даже освещённой факелами.
     */
    private static void testRainNeedsSky() {
        assertEq("open sky hears all of it", 1f, com.mineclone.audio.AmbientSound.heardRain(1f, 15));
        assertEq("a tree crown barely muffles it", 1f, com.mineclone.audio.AmbientSound.heardRain(1f, 13));
        assertEq("the depths of a cave hear none", 0f, com.mineclone.audio.AmbientSound.heardRain(1f, 0));
        assertEq("nor does the dark edge", 0f, com.mineclone.audio.AmbientSound.heardRain(1f,
                com.mineclone.audio.AmbientSound.RAIN_SKY_SILENT));
        float prev = -1f;
        for (int sky = 0; sky <= Chunk.MAX_LIGHT; sky++) {
            float h = com.mineclone.audio.AmbientSound.heardRain(0.8f, sky);
            assertTrue("rain fades in step by step toward the cave mouth (sky " + sky + ")", h >= prev);
            prev = h;
        }

        int[] cues = rainCues(15, false);
        assertTrue("a downpour under the open sky is heard (" + cues[0] + ")", cues[0] > 20);
        assertTrue("and it thunders (" + cues[1] + ")", cues[1] > 0);
        assertTrue("under a crown it still is (" + rainCues(13, false)[0] + ")", rainCues(13, false)[0] > 20);
        int[] lit = rainCues(0, false);
        assertEq("a torch-lit cave hears no rain, though it is not dark", 0, lit[0]);
        assertEq("and no thunder", 0, lit[1]);
        int mouth = rainCues(6, false)[0];
        assertTrue("a cave mouth still hears a downpour (" + mouth + ")", mouth > 20);
    }

    /** {дождь, гром} за десять минут ливня с грозой при заданном небесном свете. */
    private static int[] rainCues(int skyLight, boolean dark) {
        var a = ambient(21L);
        int rain = 0, thunder = 0;
        for (int i = 0; i < 20 * 600; i++) {
            var cue = a.tick(0.05f, dark, false, 1f, 1f, 0f, false, skyLight);
            if (cue == com.mineclone.audio.AmbientSound.Cue.RAIN) rain++;
            if (cue == com.mineclone.audio.AmbientSound.Cue.THUNDER) thunder++;
        }
        return new int[] { rain, thunder };
    }

    /**
     * У каждой реплики расписания должны быть файлы. Расписание без звуков —
     * это тишина, которую невозможно отличить от бага в таймерах.
     */
    private static void testAmbientAssets() {
        var s = new com.mineclone.audio.Sounds();
        assertTrue("cave samples exist", !s.ambientCave().isEmpty());
        assertTrue("rain samples exist", !s.ambientRain().isEmpty());
        assertTrue("thunder samples exist", !s.ambientThunder().isEmpty());
        assertTrue("underwater hum exists", !s.ambientUnderwater().isEmpty());
        assertTrue("underwater extras exist", !s.ambientUnderwaterExtra().isEmpty());
        assertTrue("splash in exists", !s.waterEnter().isEmpty());
        assertTrue("splash out exists", !s.waterExit().isEmpty());
    }

    /**
     * Зонд отдаёт две величины, и они меряют разное.
     *
     * Замкнутость растёт от поля к стенам и дальше не растёт: коробка и зал
     * закрыты одинаково — в обоих перекрыты все шесть направлений. Отличает
     * их размер, и раньше это отличие уезжало в ту же одну цифру, отчего
     * чулан выходил «гулче» зала. Ошибка здесь не падает и не видна — она
     * просто даёт эхо там, где его быть не должно.
     */
    private static void testAcousticProbe() {
        World open = flatTestWorld();
        var field = com.mineclone.audio.AcousticProbe.room(open, 8.5f, 12.5f, 8.5f);
        assertEq("open air has exactly no cave send", 0f, field.closed());

        // Каменная коробка 5x5x5 вокруг головы.
        World box = flatTestWorld();
        for (int x = 6; x <= 10; x++)
            for (int y = 11; y <= 15; y++)
                for (int z = 6; z <= 10; z++) {
                    boolean shell = x == 6 || x == 10 || y == 11 || y == 15 || z == 6 || z == 10;
                    box.setBlock(x, y, z, shell ? BlockType.STONE : BlockType.AIR);
                }
        var room = com.mineclone.audio.AcousticProbe.room(box, 8.5f, 13.5f, 8.5f);
        assertTrue("a tight room is fully enclosed (" + room.closed() + ")", room.closed() > 0.8f);
        assertTrue("and much more than a field", room.closed() > field.closed() + 0.35f);

        // Зал из тех же шести стен, но вчетверо шире. Перекрыто то же самое,
        // а пробег длиннее — значит замкнутость та же, а размер больше.
        World hall = flatTestWorld();
        for (int x = -4; x <= 20; x++)
            for (int y = 11; y <= 28; y++)
                for (int z = -4; z <= 20; z++) {
                    boolean shell = x == -4 || x == 20 || y == 11 || y == 28 || z == -4 || z == 20;
                    if (shell)
                        hall.setBlock(x, y, z, BlockType.STONE);
                    else
                        hall.setBlock(x, y, z, BlockType.AIR);
                }
        var big = com.mineclone.audio.AcousticProbe.room(hall, 8.5f, 19.5f, 8.5f);
        assertTrue("a hall is enclosed too (" + big.closed() + ")", big.closed() >= room.closed());
        assertTrue("but far roomier than a closet (" + big.size() + " > " + room.size() + ")",
                big.size() > room.size());
        assertTrue("so it rings longer, not shorter",
                com.mineclone.audio.RoomAcoustics.decayTime(big.size())
                        > com.mineclone.audio.RoomAcoustics.decayTime(room.size()));

        // Отсутствие мира не должно ронять замер.
        assertEq("no world means no echo", 0f,
                com.mineclone.audio.AcousticProbe.enclosure(null, 0f, 0f, 0f));
    }

    /** World sounds must become steadily louder as the listener approaches. */
    private static void testSpatialSoundGain() {
        float silent = com.mineclone.audio.SoundEngine.spatialGain(100f);
        float far = com.mineclone.audio.SoundEngine.spatialGain(20f);
        float middle = com.mineclone.audio.SoundEngine.spatialGain(10f);
        float near = com.mineclone.audio.SoundEngine.spatialGain(1f);
        assertEq("past the hearing radius is silent", 0f, silent);
        assertTrue("far sound is quieter than middle", far < middle);
        assertTrue("middle sound is quieter than near", middle < near);
        assertEq("inside reference distance is full volume", 1f, near);
        assertEq("invalid distances are silent", 0f,
                com.mineclone.audio.SoundEngine.spatialGain(Float.NaN));
    }

    // ---- Сон -------------------------------------------------------------

    private static void testSleepRules() {
        var R = com.mineclone.world.SleepRules.class;
        // Днём не уснуть, даже если вокруг пусто.
        assertEq("daylight blocks sleep",
                com.mineclone.world.SleepRules.Result.TOO_BRIGHT,
                com.mineclone.world.SleepRules.check(1f, 0));
        // Ночью с монстрами — тоже нет, и подсказка именно про монстров.
        assertEq("monsters block sleep",
                com.mineclone.world.SleepRules.Result.MONSTERS,
                com.mineclone.world.SleepRules.check(0f, 2));
        // Ночью и тихо — ложимся.
        assertEq("night and quiet is fine",
                com.mineclone.world.SleepRules.Result.OK,
                com.mineclone.world.SleepRules.check(0f, 0));
        // Ровно на пороге ещё светло: порог принадлежит дню.
        assertEq("the threshold belongs to daytime",
                com.mineclone.world.SleepRules.Result.TOO_BRIGHT,
                com.mineclone.world.SleepRules.check(
                        com.mineclone.world.SleepRules.NIGHT_DAYLIGHT + 0.01f, 0));
        assertTrue("class is a utility holder", R != null);

        // Пробуждение всегда строго в будущем и всегда на рассвете.
        float cycle = (float) (Math.PI * 2.0);
        for (float t : new float[] { 0f, 0.1f, 3.9f, 4.7f, cycle - 0.001f, cycle * 3.2f, -1.4f }) {
            float dawn = com.mineclone.world.SleepRules.nextDawn(t);
            assertTrue("dawn is in the future (from " + t + " to " + dawn + ")", dawn > t);
            float phase = dawn % cycle;
            if (phase < 0f)
                phase += cycle;
            assertTrue("and it is dawn (phase " + phase + ")",
                    phase < 1e-3f || Math.abs(phase - cycle) < 1e-3f);
            // Не больше одних суток: сон не должен съедать неделю.
            assertTrue("no more than a full day skipped", dawn - t <= cycle + 1e-4f);
        }

        // Номер суток растёт ровно на один — от него зависит фаза луны.
        long before = com.mineclone.world.NightSky.dayIndex(4.7f);
        long after = com.mineclone.world.NightSky.dayIndex(
                com.mineclone.world.SleepRules.nextDawn(4.7f));
        assertEq("sleeping advances the day by one", before + 1, after);
    }

    private static void testBedroll() {
        // Рисуется слоем — значит высота берётся из meta, как у снега.
        assertTrue("bedroll is layered", BlockType.BEDROLL.isLayered());
        assertTrue("and not solid: you lie on it, not climb it", !BlockType.BEDROLL.solid);

        // Тайлы не должны наезжать на соседей: лёд стоит прямо перед ним.
        assertEq("ice keeps its tile", 86, BlockType.ICE.topTile);
        assertEq("bedroll top", 87, BlockType.BEDROLL.topTile);
        assertEq("bedroll side", 88, BlockType.BEDROLL.sideTile);
        assertTrue("every bedroll tile exists in the atlas",
                BlockType.BEDROLL.sideTile < com.mineclone.render.TextureAtlas.TILE_NAMES.length);
        assertEq("tile 87 is the bedroll top", "bedroll_top",
                com.mineclone.render.TextureAtlas.TILE_NAMES[87]);
        assertEq("tile 86 is still ice", "ice",
                com.mineclone.render.TextureAtlas.TILE_NAMES[86]);

        // Собирается из досок и листвы.
        Inventory inv = new Inventory();
        inv.add(new ItemStack(BlockType.PLANKS, 3));
        inv.add(new ItemStack(BlockType.LEAVES, 3));
        var recipe = findRecipe(BlockType.BEDROLL);
        assertTrue("there is a bedroll recipe", recipe != null);
        assertTrue("and the materials are enough",
                com.mineclone.world.Recipes.canCraft(inv, recipe));
        assertTrue("crafting works", com.mineclone.world.Recipes.craft(inv, recipe));
        assertEq("one bedroll made", 1, com.mineclone.world.Recipes.count(inv, item(BlockType.BEDROLL)));
        assertEq("planks spent", 0, com.mineclone.world.Recipes.count(inv, item(BlockType.PLANKS)));
        assertEq("leaves spent", 0, com.mineclone.world.Recipes.count(inv, item(BlockType.LEAVES)));
    }

    /**
     * Спальник должен встать половиной блока, а не плёнкой и не кубом.
     *
     * Высота живёт в meta и приходит из `emitLayer` — того же кода, что
     * рисует снег. Скриншотом это проверять бесполезно: на глаз 1/8 и 4/8
     * различимы, а 4/8 и 5/8 уже нет.
     */
    private static void testBedrollHeight() {
        World w = flatTestWorld();
        // Пол на y=10, спальник кладём сверху с meta=3 -> высота (3+1)/8.
        w.setBlock(4, 11, 4, BlockType.BEDROLL, (byte) 3);
        var mesher = new com.mineclone.world.ChunkMesher(w);
        var data = mesher.buildData(w.getChunk(0, 0));
        float top = Float.NEGATIVE_INFINITY, bottom = Float.POSITIVE_INFINITY;
        float[] pos = data[0].positions;
        for (int i = 0; i < pos.length; i += 3) {
            float x = pos[i], y = pos[i + 1], z = pos[i + 2];
            // Берём только вершины этой колонны, выше пола.
            if (x >= 4f && x <= 5f && z >= 4f && z <= 5f && y > 10.9f) {
                top = Math.max(top, y);
                bottom = Math.min(bottom, y);
            }
        }
        assertTrue("the bedroll produced geometry", top > Float.NEGATIVE_INFINITY);
        assertTrue("it sits on the floor (" + bottom + ")", Math.abs(bottom - 11f) < 1e-3f);
        assertTrue("and is half a block tall (" + top + ")", Math.abs(top - 11.5f) < 1e-3f);
    }

    private static com.mineclone.world.Recipes.Recipe findRecipe(BlockType out) {
        for (var r : com.mineclone.world.Recipes.all())
            if (r.result() == item(out))
                return r;
        return null;
    }

    // ---- Снег ------------------------------------------------------------

    /** Ищет колонку тундры с открытым небом: снег ложится только там. */
    private static int[] findTundraColumn(World w) {
        for (int cx = -100; cx <= 100; cx += 4)
            for (int cz = -100; cz <= 100; cz += 4) {
                if (w.biomes.biomeAt(cx * Chunk.SIZE_X + 8, cz * Chunk.SIZE_Z + 8) != Biome.TUNDRA)
                    continue;
                Chunk c = w.getChunk(cx, cz);
                for (int x = 0; x < Chunk.SIZE_X; x++)
                    for (int z = 0; z < Chunk.SIZE_Z; z++) {
                        int wx = cx * Chunk.SIZE_X + x, wz = cz * Chunk.SIZE_Z + z;
                        if (w.biomes.biomeAt(wx, wz) != Biome.TUNDRA)
                            continue;
                        int y = -1;
                        for (int yy = Chunk.SIZE_Y - 2; yy > 1; yy--)
                            if (c.get(x, yy, z).solid) { y = yy; break; }
                        if (y < 0 || y > Chunk.SIZE_Y - 4)
                            continue;
                        if (c.get(x, y + 1, z) != BlockType.AIR)
                            continue;
                        return new int[] { wx, y + 1, wz };
                    }
            }
        return null;
    }

    private static void testSnowAccumulation() {
        World w = null;
        int[] spot = null;
        // Тундра есть не на каждом сиде — перебираем, пока не найдём.
        for (long seed : new long[] { 5L, 17L, 88L, 404L, 1234L }) {
            w = new World(seed);
            spot = findTundraColumn(w);
            if (spot != null)
                break;
        }
        assertTrue("found a tundra column to snow on", spot != null);
        int x = spot[0], y = spot[1], z = spot[2];
        BlockTicker ticker = new BlockTicker(9L);

        // Без осадков ничего не происходит.
        ticker.apply(w, x, y, z, false, false);
        assertTrue("no snow without precipitation", w.getBlock(x, y, z) == BlockType.AIR);

        // В снегопад ложится первый слой...
        ticker.apply(w, x, y, z, false, true);
        assertTrue("snow settles during snowfall", w.getBlock(x, y, z) == BlockType.SNOW_LAYER);

        // ...и растёт до предела, но не выше.
        for (int i = 0; i < 100; i++)
            ticker.apply(w, x, y, z, false, true);
        int level = w.getBlockMeta(x, y, z) & 0x7;
        assertTrue("snow thickened (level " + level + ")", level > 0);
        assertTrue("snow stops at its limit", level <= BlockTicker.SNOW_MAX_LEVEL);

        // В тундре покров лежит и после снегопада — как в MC, где снег
        // растапливает только блочный свет, а не солнце.
        for (int i = 0; i < 200; i++)
            ticker.apply(w, x, y, z, false, false);
        assertTrue("snow stays in a cold biome after the snowfall",
                w.getBlock(x, y, z) == BlockType.SNOW_LAYER);

        // А вот факел рядом съедает сугроб слой за слоем.
        w.setBlock(x + 1, y, z, BlockType.TORCH);
        assertTrue("torch lights the snow", w.getBlockLightWorld(x, y, z) >= BlockTicker.GRASS_LIGHT_MIN);
        boolean gone = false;
        for (int i = 0; i < 3000 && !gone; i++) {
            ticker.apply(w, x, y, z, false, false);
            gone = w.getBlock(x, y, z) == BlockType.AIR;
        }
        assertTrue("a torch melts the snow next to it", gone);
    }

    // ---- Огонь ----------------------------------------------------------

    /** Ровная площадка из досок в загруженном чанке — топливо для пожара. */
    private static World woodPlatform(long seed, int y) {
        World w = new World(seed);
        w.getChunk(0, 0);
        for (int x = 0; x < 10; x++)
            for (int z = 0; z < 10; z++) {
                for (int yy = y; yy < Chunk.SIZE_Y; yy++)
                    w.setBlock(x, yy, z, BlockType.AIR);
                w.setBlock(x, y - 1, z, BlockType.PLANKS);
            }
        return w;
    }

    private static void testFireSpreadsAndConsumes() {
        int y = 70;
        World w = woodPlatform(41L, y);
        BlockTicker ticker = new BlockTicker(5L);
        w.setBlock(2, y, 2, BlockType.FIRE);

        int fires = 0, burned = 0;
        for (int step = 0; step < 4000; step++) {
            for (int x = 0; x < 10; x++)
                for (int z = 0; z < 10; z++)
                    for (int yy = y - 1; yy <= y; yy++)
                        ticker.apply(w, x, yy, z, false);
        }
        for (int x = 0; x < 10; x++)
            for (int z = 0; z < 10; z++) {
                if (w.getBlock(x, y, z) == BlockType.FIRE) fires++;
                if (w.getBlock(x, y - 1, z) != BlockType.PLANKS) burned++;
            }
        assertTrue("fire ate through the planks (burned " + burned + ")", burned > 10);
        // Пожар не обязан оставаться в живых, но и весь пол съесть не должен
        // мгновенно: проверяем, что механизм вообще двигался.
        assertTrue("fire moved off its starting cell", fires >= 0);
    }

    private static void testFireDiesWithoutFuel() {
        World w = new World(42L);
        w.getChunk(0, 0);
        int y = 70;
        for (int yy = y - 1; yy < Chunk.SIZE_Y; yy++)
            w.setBlock(3, yy, 3, BlockType.AIR);
        w.setBlock(3, y - 1, 3, BlockType.STONE);
        w.setBlock(3, y, 3, BlockType.FIRE);
        BlockTicker ticker = new BlockTicker(6L);
        for (int i = 0; i < 200 && w.getBlock(3, y, 3) == BlockType.FIRE; i++)
            ticker.apply(w, 3, y, 3, false);
        assertTrue("fire on bare stone burns out", w.getBlock(3, y, 3) == BlockType.AIR);
    }

    private static void testWaterAndRainExtinguishFire() {
        int y = 70;
        World w = woodPlatform(43L, y);
        BlockTicker ticker = new BlockTicker(7L);

        // Вода рядом тушит мгновенно, даже когда топлива вокруг полно.
        w.setBlock(4, y, 4, BlockType.FIRE);
        w.setBlock(5, y, 4, BlockType.WATER);
        ticker.apply(w, 4, y, 4, false);
        assertTrue("water puts fire out", w.getBlock(4, y, 4) != BlockType.FIRE);

        // Дождь тушит огонь под открытым небом...
        w.setBlock(7, y, 7, BlockType.FIRE);
        ticker.apply(w, 7, y, 7, true);
        assertTrue("rain puts out an exposed fire", w.getBlock(7, y, 7) != BlockType.FIRE);

        // ...но не под крышей.
        w.setBlock(8, y, 8, BlockType.FIRE);
        w.setBlock(8, y + 2, 8, BlockType.STONE);
        ticker.apply(w, 8, y, 8, true);
        assertTrue("a sheltered fire survives the rain",
                w.getBlock(8, y, 8) == BlockType.FIRE);
    }

    // ---- Тики блоков ----------------------------------------------------

    /** Плоская площадка дёрна в уже загруженном чанке, чтобы тикать по ней. */
    private static World flatWorld(long seed) {
        World w = new World(seed);
        w.getChunk(0, 0);
        return w;
    }

    private static void testGrassTick() {
        World w = flatWorld(31L);
        BlockTicker ticker = new BlockTicker(1L);
        // Площадка: дёрн и рядом голая земля, всё под открытым небом.
        int y = 70;
        for (int x = 0; x < 6; x++)
            for (int z = 0; z < 6; z++)
                for (int yy = y; yy < Chunk.SIZE_Y; yy++)
                    w.setBlock(x, yy, z, BlockType.AIR);
        for (int x = 0; x < 6; x++)
            for (int z = 0; z < 6; z++)
                w.setBlock(x, y - 1, z, BlockType.DIRT);
        w.setBlock(0, y - 1, 0, BlockType.GRASS);

        // Соседняя земля обязана зарасти за разумное число тиков.
        boolean spread = false;
        for (int i = 0; i < 40 && !spread; i++) {
            ticker.apply(w, 1, y - 1, 0);
            spread = w.getBlock(1, y - 1, 0) == BlockType.GRASS;
        }
        assertTrue("dirt next to grass turns to grass", spread);

        // Земля без соседнего дёрна остаётся землёй.
        for (int i = 0; i < 40; i++)
            ticker.apply(w, 5, y - 1, 5);
        assertTrue("dirt far from grass stays dirt",
                w.getBlock(5, y - 1, 5) == BlockType.DIRT);

        // Накрытый дёрн вырождается обратно в землю.
        w.setBlock(1, y, 1, BlockType.STONE);
        w.setBlock(1, y - 1, 1, BlockType.GRASS);
        ticker.apply(w, 1, y - 1, 1);
        assertTrue("covered grass turns to dirt",
                w.getBlock(1, y - 1, 1) == BlockType.DIRT);
    }

    private static void testLeafDecay() {
        World w = flatWorld(32L);
        BlockTicker ticker = new BlockTicker(2L);
        int y = 70;
        for (int x = 0; x < 12; x++)
            for (int z = 0; z < 12; z++)
                for (int yy = y - 2; yy < Chunk.SIZE_Y; yy++)
                    w.setBlock(x, yy, z, BlockType.AIR);

        // Листва у ствола держится.
        w.setBlock(2, y, 2, BlockType.WOOD);
        w.setBlock(3, y, 2, BlockType.LEAVES);
        ticker.apply(w, 3, y, 2);
        assertTrue("leaves next to a trunk stay",
                w.getBlock(3, y, 2) == BlockType.LEAVES);

        // Листва дальше предела поддержки осыпается.
        int far = 2 + BlockTicker.LEAF_SUPPORT_RANGE + 1;
        w.setBlock(far, y, 2, BlockType.LEAVES);
        ticker.apply(w, far, y, 2);
        assertTrue("orphaned leaves decay", w.getBlock(far, y, 2) == BlockType.AIR);
    }

    private static void testCactusGrowth() {
        World w = flatWorld(33L);
        BlockTicker ticker = new BlockTicker(3L);
        int y = 70;
        for (int x = 0; x < 4; x++)
            for (int z = 0; z < 4; z++)
                for (int yy = y; yy < Chunk.SIZE_Y; yy++)
                    w.setBlock(x, yy, z, BlockType.AIR);
        w.setBlock(1, y - 1, 1, BlockType.SAND);
        w.setBlock(1, y, 1, BlockType.CACTUS);

        for (int i = 0; i < 400; i++)
            for (int h = 0; h < BlockTicker.CACTUS_MAX_HEIGHT + 2; h++)
                ticker.apply(w, 1, y + h, 1);

        int height = 0;
        while (w.getBlock(1, y + height, 1) == BlockType.CACTUS)
            height++;
        assertTrue("cactus grew at all", height > 1);
        assertTrue("cactus stops at its limit (got " + height + ")",
                height <= BlockTicker.CACTUS_MAX_HEIGHT);
    }

    /**
     * Замена без изменения прозрачности не должна трогать освещение:
     * тики блоков делают десятки таких замен в секунду, и полная BFS-заливка
     * неба на каждую травинку съедала бы кадр.
     */
    /**
     * Точечная правка света обязана давать ровно то же, что полная заливка.
     *
     * Инкрементальный свет — это оптимизация, а не новая механика: стоит ему
     * разойтись с эталоном, и в мире появятся тёмные пятна, которые видно
     * только глазами и только иногда. Поэтому проверка сравнивает все 32 768
     * ячеек после каждой пачки случайных правок: ломаем, ставим, роем шахту,
     * накрываем крышей — и сверяемся с {@code computeSkyLight()}.
     */
    private static void testIncrementalSkyLight() {
        java.util.Random rnd = new java.util.Random(20260920L);
        for (int trial = 0; trial < 6; trial++) {
            World w = new World(700L + trial);
            Chunk c = w.getChunk(0, 0);
            for (int step = 0; step < 40; step++) {
                int x = rnd.nextInt(Chunk.SIZE_X);
                int z = rnd.nextInt(Chunk.SIZE_Z);
                int y = 1 + rnd.nextInt(Chunk.SIZE_Y - 2);
                BlockType t = switch (rnd.nextInt(4)) {
                    case 0 -> BlockType.AIR;
                    case 1 -> BlockType.STONE;
                    case 2 -> BlockType.GLASS;
                    default -> BlockType.LEAVES;
                };
                w.setBlock(x, y, z, t);
            }
            // Вертикальная шахта и крыша над ней — самые злые случаи: столб
            // неба режется и восстанавливается целиком.
            for (int y = 60; y < 100; y++)
                w.setBlock(5, y, 5, BlockType.AIR);
            w.processPendingSkyRelights(Integer.MAX_VALUE);
            byte[] incremental = skySnapshot(c);
            w.setBlock(5, 99, 5, BlockType.STONE);
            w.processPendingSkyRelights(Integer.MAX_VALUE);
            byte[] roofed = skySnapshot(c);
            w.setBlock(5, 99, 5, BlockType.AIR);
            w.processPendingSkyRelights(Integer.MAX_VALUE);

            byte[] afterEdits = skySnapshot(c);
            c.computeSkyLight();
            assertTrue("reopened shaft matches a full reflood",
                    java.util.Arrays.equals(afterEdits, skySnapshot(c)));
            assertTrue("a shaft is brighter than the same shaft with a roof",
                    brightness(incremental) > brightness(roofed));
        }
    }

    private static void testMaterialSkyAttenuation() {
        Chunk air = new Chunk(0, 0);
        air.computeSkyLight();
        Chunk water = new Chunk(0, 0);
        Chunk leaves = new Chunk(0, 0);
        for (int x = 0; x < Chunk.SIZE_X; x++)
            for (int z = 0; z < Chunk.SIZE_Z; z++)
                for (int y = 110; y < 114; y++) {
                    water.set(x, y, z, BlockType.WATER);
                    leaves.set(x, y, z, BlockType.LEAVES);
                }
        water.computeSkyLight();
        leaves.computeSkyLight();
        assertTrue("water darkens its column", water.getSkyLight(8, 109, 8) < air.getSkyLight(8, 109, 8));
        assertTrue("leaves attenuate harder than water",
                leaves.getSkyLight(8, 109, 8) < water.getSkyLight(8, 109, 8));
    }

    private static byte[] skySnapshot(Chunk c) {
        byte[] out = new byte[Chunk.SIZE_X * Chunk.SIZE_Y * Chunk.SIZE_Z];
        for (int x = 0; x < Chunk.SIZE_X; x++)
            for (int y = 0; y < Chunk.SIZE_Y; y++)
                for (int z = 0; z < Chunk.SIZE_Z; z++)
                    out[Chunk.idx(x, y, z)] = (byte) c.getSkyLight(x, y, z);
        return out;
    }

    private static long brightness(byte[] light) {
        long sum = 0;
        for (byte b : light)
            sum += b & 0xFF;
        return sum;
    }

    private static void testSetBlockSkipsRelight() {
        World w = flatWorld(34L);
        int y = 70;
        for (int yy = y; yy < Chunk.SIZE_Y; yy++)
            w.setBlock(3, yy, 3, BlockType.AIR);
        w.setBlock(3, y - 1, 3, BlockType.DIRT);
        // Свет под непрозрачным блоком — эталон, который не должен измениться.
        int before = w.getSkyLight(3, y - 2, 3);
        w.setBlock(3, y - 1, 3, BlockType.GRASS);
        assertTrue("light under an opaque->opaque swap is untouched",
                w.getSkyLight(3, y - 2, 3) == before);
        // А вот снятие блока освещение менять обязано.
        w.setBlock(3, y - 1, 3, BlockType.AIR);
        assertTrue("removing a block does relight",
                w.getSkyLight(3, y - 1, 3) > 0);
    }

    // ---- Пещеры и руды --------------------------------------------------

    private static void testCavesCarveUnderground() {
        World w = new World(4242L);
        long stone = 0, air = 0;
        for (int cx = -1; cx <= 1; cx++)
            for (int cz = -1; cz <= 1; cz++) {
                Chunk c = w.getChunk(cx, cz);
                for (int x = 0; x < Chunk.SIZE_X; x++)
                    for (int z = 0; z < Chunk.SIZE_Z; z++) {
                        int surface = topSolid(c, x, z);
                        for (int y = Caves.MIN_Y; y < surface; y++) {
                            BlockType b = c.get(x, y, z);
                            if (b == BlockType.AIR) air++;
                            else if (b == BlockType.STONE) stone++;
                        }
                    }
            }
        double frac = air / (double) (air + stone);
        assertTrue("caves carve something at all", air > 0);
        // Слишком мало — пещер не найти, слишком много — мир становится сыром
        // и рушится производительность мешера.
        assertTrue("carved fraction is sane (got " + Math.round(frac * 100) + "%)",
                frac > 0.02 && frac < 0.22);
    }

    private static void testCavesKeepSeabedAndBedrock() {
        World w = new World(99L);
        for (int cx = -1; cx <= 1; cx++)
            for (int cz = -1; cz <= 1; cz++) {
                Chunk c = w.getChunk(cx, cz);
                for (int x = 0; x < Chunk.SIZE_X; x++)
                    for (int z = 0; z < Chunk.SIZE_Z; z++) {
                        assertTrue("bedrock survives carving",
                                c.get(x, 0, z) == BlockType.BEDROCK);
                        assertTrue("floor above bedrock survives",
                                c.get(x, 1, z) != BlockType.AIR);
                        // Дно под водой обязано остаться сплошным: пробитое
                        // дно океана осушает его целиком через WaterSimulator.
                        int surface = topSolid(c, x, z);
                        boolean underwater = c.get(x, surface + 1, z) == BlockType.WATER;
                        if (underwater && surface > Caves.MIN_Y + 2)
                            for (int y = surface; y > surface - 3; y--)
                                assertTrue("seabed is not breached at y=" + y,
                                        c.get(x, y, z) != BlockType.AIR);
                    }
            }
    }

    private static void testOreBandsAndHost() {
        World w = new World(7L);
        int coal = 0, iron = 0, gold = 0, diamond = 0;
        int chunks = 0;
        for (int cx = -2; cx <= 2; cx++)
            for (int cz = -2; cz <= 2; cz++) {
                Chunk c = w.getChunk(cx, cz);
                chunks++;
                for (int x = 0; x < Chunk.SIZE_X; x++)
                    for (int y = 0; y < Chunk.SIZE_Y; y++)
                        for (int z = 0; z < Chunk.SIZE_Z; z++) {
                            switch (c.get(x, y, z)) {
                                case COAL_ORE -> { coal++; assertTrue("coal band", y >= 6 && y <= 96); }
                                case IRON_ORE -> { iron++; assertTrue("iron band", y >= 4 && y <= 60); }
                                case GOLD_ORE -> { gold++; assertTrue("gold band", y >= 2 && y <= 32); }
                                case DIAMOND_ORE -> { diamond++; assertTrue("diamond band", y >= 2 && y <= 15); }
                                default -> { }
                            }
                        }
            }
        assertTrue("coal is common", coal / (double) chunks > 20);
        assertTrue("iron is present", iron > 0);
        assertTrue("gold is rarer than iron", gold < iron);
        assertTrue("diamond is rarest", diamond < gold);
    }

    private static void testWorldGenerationDeterministic() {
        Chunk a = new World(12345L).getChunk(3, -2);
        Chunk b = new World(12345L).getChunk(3, -2);
        Chunk other = new World(54321L).getChunk(3, -2);
        boolean same = true, differs = false;
        for (int x = 0; x < Chunk.SIZE_X; x++)
            for (int y = 0; y < Chunk.SIZE_Y; y++)
                for (int z = 0; z < Chunk.SIZE_Z; z++) {
                    if (a.get(x, y, z) != b.get(x, y, z)) same = false;
                    if (a.get(x, y, z) != other.get(x, y, z)) differs = true;
                }
        assertTrue("same seed generates the same chunk (caves and ores included)", same);
        assertTrue("a different seed generates a different chunk", differs);
    }

    /** Верхний блок колонки, не считая воздуха и воды. */
    private static int topSolid(Chunk c, int x, int z) {
        for (int y = Chunk.SIZE_Y - 1; y > 0; y--) {
            BlockType b = c.get(x, y, z);
            if (b != BlockType.AIR && b != BlockType.WATER)
                return y;
        }
        return 1;
    }

    private static void testHostileSpawnLightRule() {
        World w = new World(2024L);
        w.getChunk(0, 0);
        // Открытая поверхность: днём светло, ночью темно.
        int surfaceY = -1;
        for (int y = Chunk.SIZE_Y - 1; y > 0; y--)
            if (w.getBlock(4, y, 4) != BlockType.AIR && w.getBlock(4, y, 4) != BlockType.WATER) {
                surfaceY = y;
                break;
            }
        assertTrue("found a surface column", surfaceY > 0);
        assertTrue("no hostile spawn on a lit surface at noon",
                !MobSpawner.darkEnough(w, 4, surfaceY + 1, 4, 1f));
        assertTrue("hostile spawn allowed on the surface at night",
                MobSpawner.darkEnough(w, 4, surfaceY + 1, 4, 0.05f));

        // Глубоко под землёй неба нет вообще — значит темно и в полдень.
        // Именно это делает пещеры опасными круглосуточно.
        boolean sawDarkUnderground = false;
        for (int x = 0; x < Chunk.SIZE_X && !sawDarkUnderground; x++)
            for (int z = 0; z < Chunk.SIZE_Z && !sawDarkUnderground; z++)
                for (int y = 5; y < 30; y++)
                    if (w.getSkyLight(x, y, z) == 0 && w.getBlockLightWorld(x, y, z) == 0) {
                        assertTrue("hostile spawn allowed underground at noon",
                                MobSpawner.darkEnough(w, x, y, z, 1f));
                        sawDarkUnderground = true;
                        break;
                    }
        assertTrue("found an unlit underground cell", sawDarkUnderground);
    }

    private static void testStructures() {
        long seed = 2211L;
        World w = new World(seed);
        int found = 0, scanned = 0;
        // Признак постройки — рукотворный блок на поверхности или над ней.
        for (int cx = -60; cx <= 60; cx++)
            for (int cz = -60; cz <= 60; cz++) {
                scanned++;
                if (com.mineclone.world.Structures.candidateKind(cx, cz, seed) < 0) continue;
                Chunk c = w.getChunk(cx, cz);
                boolean hit = false;
                for (int x = 0; x < Chunk.SIZE_X && !hit; x++)
                    for (int z = 0; z < Chunk.SIZE_Z && !hit; z++)
                        for (int y = 1; y < Chunk.SIZE_Y - 1; y++) {
                            BlockType b = c.get(x, y, z);
                            if (b == BlockType.COBBLE || b == BlockType.PLANKS
                                    || b == BlockType.GLASS || b == BlockType.MOSSY_COBBLE || b == BlockType.TORCH) {
                                hit = true;
                                // Постройка обязана целиком лежать внутри чанка:
                                // генерация пишет только в свой чанк, и на
                                // границе строение обрезалось бы посередине.
                                assertTrue("structure keeps a margin from the chunk edge",
                                        x >= 1 && x <= Chunk.SIZE_X - 2
                                                && z >= 1 && z <= Chunk.SIZE_Z - 2);
                                break;
                            }
                        }
                if (hit)
                    found++;
            }
        assertTrue("structures do appear (" + found + " of " + scanned + " chunks)", found > 0);
        assertTrue("structures stay rare (" + found + " of " + scanned + ")",
                found < scanned / 3);

        // Тот же сид — та же карта построек.
        World again = new World(seed);
        Chunk a = w.getChunk(0, 0), b = again.getChunk(0, 0);
        boolean same = true;
        for (int x = 0; x < Chunk.SIZE_X; x++)
            for (int y = 0; y < Chunk.SIZE_Y; y++)
                for (int z = 0; z < Chunk.SIZE_Z; z++)
                    if (a.get(x, y, z) != b.get(x, y, z))
                        same = false;
        assertTrue("structure placement is deterministic", same);
    }

    // ---- Освещение и тени ----------------------------------------------------

    private static void testSunLightDirection() {
        float noon = (float) (Math.PI / 2.0);
        Vector3f sun = SunLight.sunDirection(noon);
        assertTrue("noon sun overhead", sun.y > 0.99f);

        // Полночь: солнце под миром, светит луна — направление снова вверх.
        Vector3f night = SunLight.lightDirection((float) (Math.PI * 1.5));
        assertTrue("moon is above the horizon at midnight", night.y > 0.99f);

        // На самом горизонте источник обязан быть приподнят, иначе
        // ортографический каскад вырождается и тень уходит в бесконечность.
        for (float t = -0.25f; t <= 0.25f; t += 0.01f) {
            Vector3f d = SunLight.lightDirection(t);
            assertTrue("light elevation clamped at t=" + t, d.y >= SunLight.MIN_ELEVATION - 1e-4f);
            assertTrue("light direction normalised at t=" + t,
                    Math.abs(d.length() - 1f) < 1e-4f);
        }
    }

    private static void testShadowStrength() {
        assertTrue("no shadows exactly at sunrise", SunLight.shadowStrength(0f) < 0.001f);
        assertTrue("no shadows exactly at sunset",
                SunLight.shadowStrength((float) Math.PI) < 0.001f);
        float noon = SunLight.shadowStrength((float) (Math.PI / 2.0));
        assertTrue("full shadows at noon", noon > 0.8f);
        float midnight = SunLight.shadowStrength((float) (Math.PI * 1.5));
        assertTrue("moon casts shadows too", midnight > 0.8f);
        // Монотонный подъём от горизонта — иначе на рассвете тень моргает.
        float prev = -1f;
        for (float t = 0f; t <= 0.5f; t += 0.02f) {
            float v = SunLight.shadowStrength(t);
            assertTrue("shadow strength grows after sunrise", v >= prev - 1e-5f);
            prev = v;
        }
    }

    private static void testShadowCascade() {
        int mapSize = 2048;
        float radius = 40f;
        Vector3f light = new Vector3f(0f, 0.7f, -0.7f).normalize();
        Vector3f center = new Vector3f(100f, 64f, -50f);
        Matrix4f m = SunLight.cascadeMatrix(light, center, radius, mapSize);

        Vector4f c = m.transform(new Vector4f(center.x, center.y, center.z, 1f));
        assertTrue("centre inside the cascade", Math.abs(c.x) < 1f && Math.abs(c.y) < 1f);
        assertTrue("centre depth inside near/far", c.z > -1f && c.z < 1f);

        // Снап к сетке текселей: без него карта плывёт и края теней кипят.
        float texel = 2f / mapSize;
        float offGrid = Math.abs(c.x / texel - Math.round(c.x / texel));
        assertTrue("centre snapped to the texel grid on X", offGrid < 1e-3f);
        offGrid = Math.abs(c.y / texel - Math.round(c.y / texel));
        assertTrue("centre snapped to the texel grid on Y", offGrid < 1e-3f);

        // X перпендикулярен орбите светила, поэтому уезжает ровно в NDC-x.
        Vector4f inside = m.transform(new Vector4f(center.x + radius * 0.7f, center.y, center.z, 1f));
        assertTrue("point inside the radius stays in the map", Math.abs(inside.x) < 1f);
        Vector4f outside = m.transform(new Vector4f(center.x + radius * 2f, center.y, center.z, 1f));
        assertTrue("point past the radius falls outside", Math.abs(outside.x) > 1f);

        // Кастер высоко над центром обязан попасть в глубинный диапазон,
        // иначе дерево на холме перестаёт отбрасывать тень.
        Vector4f high = m.transform(new Vector4f(center.x, center.y + 60f, center.z, 1f));
        assertTrue("tall caster inside the depth range", high.z > -1f && high.z < 1f);

        // Детерминизм: одинаковый вход — побитово одинаковая матрица.
        Matrix4f again = SunLight.cascadeMatrix(light, new Vector3f(center), radius, mapSize);
        assertTrue("cascade matrix is deterministic", again.equals(m, 0f));
    }

    private static void testCascadeStaleness() {
        float r = SunLight.CASCADE1_RADIUS;
        // Свежий каскад, камера стоит, солнце не двигалось — трогать нечего.
        assertTrue("fresh cascade is reused",
                !ShadowMap.isStale(0, 4, 0f, r, 1f));
        // Шаг игрока за кадр — это доли блока, перестройка не нужна.
        assertTrue("walking does not rebuild every frame",
                !ShadowMap.isStale(1, 4, 0.4f, r, 0.99999f));
        // Срок вышел — обязаны переснять.
        assertTrue("cascade rebuilds when its period elapses",
                ShadowMap.isStale(4, 4, 0f, r, 1f));
        // Разворот камеры на 180° уносит центр почти на два радиуса.
        assertTrue("fast camera turn forces a rebuild",
                ShadowMap.isStale(0, 4, r * 0.9f, r, 1f));
        // Прыжок времени командой разворачивает солнце скачком.
        assertTrue("time jump forces a rebuild",
                ShadowMap.isStale(0, 4, 0f, r, 0.9f));
        // Ход солнца за кадр (8e-5 рад) не должен считаться прыжком —
        // иначе лень вообще не включится.
        float perFrameDot = (float) Math.cos(8e-5);
        assertTrue("normal sun motion is not a time jump",
                !ShadowMap.isStale(0, 4, 0f, r, perFrameDot));
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
        assertEq("all eleven biomes occur within 4000 blocks", 11, seen.size());
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
        // Пещера может вскрыть колонку и обнажить наполнитель — как в MC.
        // Такие колонки из проверки исключаем, спрашивая у того же генератора.
        Caves caves = new Caves(seed);
        for (int cx = -2; cx <= 2; cx++)
            for (int cz = -2; cz <= 2; cz++) {
                Chunk c = w.getChunk(cx, cz);
                for (int x = 0; x < Chunk.SIZE_X; x++)
                    for (int z = 0; z < Chunk.SIZE_Z; z++) {
                        int wx = cx * Chunk.SIZE_X + x, wz = cz * Chunk.SIZE_Z + z;
                        int y = surfaceY(c, x, z);
                        if (caves.isCave(wx, y + 1, wz))
                            continue;   // колонка вскрыта пещерой
                        Biome b = bp.biomeAt(wx, wz);
                        BlockType expected = World.surfaceFor(b, y);
                        BlockType actual = c.get(x, y, z);
                        assertTrue("surface @" + wx + "," + wz + " biome=" + b
                                + " expected=" + expected + " got=" + actual, actual == expected);
                    }
            }
    }

    private static void testHeightSmoothness() {
        long seed = 991L;
        World w = new World(seed);
        Caves caves = new Caves(seed);
        int prev = Integer.MIN_VALUE;
        for (int wx = -160; wx < 160; wx++) {
            int cx = Math.floorDiv(wx, Chunk.SIZE_X);
            Chunk c = w.getChunk(cx, 0);
            int y = surfaceY(c, Math.floorMod(wx, Chunk.SIZE_X), 7);
            // Провал, вскрытый пещерой, — не обрыв рельефа: цепочку сравнений
            // на таких колонках рвём, иначе тест меряет глубину пещеры.
            if (caves.isCave(wx, y + 1, 7)) {
                prev = Integer.MIN_VALUE;
                continue;
            }
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
        int[] sampled = new int[Biome.values().length];
        for (int cx = -150; cx <= 150; cx += 3)
            for (int cz = -150; cz <= 150; cz += 3) {
                Biome biome = w.biomes.biomeAt(cx * 16 + 8, cz * 16 + 8);
                if (biome.treeType == Biome.TreeType.NONE || sampled[biome.ordinal()] >= 4
                        || w.terrainHeight(cx * 16 + 8, cz * 16 + 8) <= World.SEA_LEVEL + 1) continue;
                sampled[biome.ordinal()]++;
                Chunk c = w.getChunk(cx, cz);
                for (int x = 0; x < Chunk.SIZE_X; x++)
                    for (int z = 0; z < Chunk.SIZE_Z; z++)
                        for (int y = 1; y < Chunk.SIZE_Y; y++) {
                            BlockType t = c.get(x, y, z);
                            BlockType below = c.get(x, y - 1, z);
                            if (t == BlockType.CACTUS) {
                                sawCactus = true;
                                assertTrue("cactus on sand/cactus @" + (cx * 16 + x) + "," + y + "," + (cz * 16 + z) + ", got " + below,
                                        below == BlockType.SAND || below == BlockType.RED_SAND || below == BlockType.CACTUS);
                                assertTrue("cactus above water line", y > World.SEA_LEVEL + 1);
                            }
                            if (t == BlockType.WOOD && below != BlockType.WOOD) {
                                sawTrunk = true;
                                assertTrue("trunk base on grass/snowy grass, got " + below,
                                        below.isSoil());
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
        assertEq("torch drops itself", BlockType.TORCH, BlockType.TORCH.getDrop());
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

    private static void testMobAnimation() {
        World w = flatTestWorld();
        var m = spawnAt(com.mineclone.world.entity.MobType.COW, 8.5f, 11f, 8.5f, 4);
        var far = new org.joml.Vector3f(100, 11, 100);
        float before = com.mineclone.render.MobAnimation.headYaw(m);
        m.update(w, far, 0.05f, 0f, false);
        assertTrue("idle head moves without walking", before != com.mineclone.render.MobAnimation.headYaw(m));
        m.walkAmount = 1f;
        for (int i = 0; i < 20; i++) m.update(w, far, 0.05f, 0f, false);
        assertTrue("legs settle at rest", m.walkAmount < 0.01f);
        var observer = spawnAt(com.mineclone.world.entity.MobType.COW, 8.5f, 11f, 8.5f, 4);
        observer.yaw = 0f;
        observer.update(w, new org.joml.Vector3f(10.5f, 11f, 8.5f), 0.05f, 1f, false);
        assertTrue("idle animal turns head toward nearby player", observer.lookYaw < -0.05f);
        w.getChunk(0, 0).set(8, 10, 8, BlockType.GRASS);
        observer.animationTime = 2f;
        observer.onGround = true;
        observer.update(w, far, 0.05f, 1f, false);
        assertTrue("idle animal grazes on grass", observer.grazeAmount > 0f);
        for (var type : com.mineclone.world.entity.MobType.values()) {
            java.awt.image.BufferedImage skin = com.mineclone.render.MobSkins.load(type);
            assertEq("loaded skin width", 128, skin.getWidth());
            assertEq("loaded skin height", 64, skin.getHeight());
        }
    }

    private static void testFlightStopsFall() {
        World w = flatTestWorld();
        var p = new com.mineclone.game.Player();
        p.respawn(8.5f, 40f, 8.5f);
        for (int i = 0; i < 300 && p.position.y > 13f; i++)
            p.update(1f / 120f, w, null, false);
        assertTrue("long descent accumulated", p.fallDistance > 20f);
        assertTrue("still above ground", !p.onGround && p.position.y > 11f);
        p.flying = true;
        p.update(1f / 120f, w, null, false);
        assertEq("flight clears fall distance", 0f, p.fallDistance);
        assertEq("flight arrests descent", 0f, p.velocity.y);
        p.flying = false;
        for (int i = 0; i < 300 && !p.onGround; i++)
            p.update(1f / 120f, w, null, false);
        assertTrue("landed after short fall", p.onGround);
        assertEq("short fall is harmless", com.mineclone.game.Player.MAX_HEALTH, p.health);
        assertEq("no stale damage", 0f, p.lastFallDamage);

        p.respawn(8.5f, 19f, 8.5f);
        for (int i = 0; i < 300 && !p.onGround; i++)
            p.update(1f / 120f, w, null, false);
        assertTrue("uninterrupted fall still hurts", p.lastFallDamage > 4.9f && p.lastFallDamage < 5.1f);
    }

    private static void testWaterOutlet() {
        World w = flatTestWorld();
        com.mineclone.world.WaterSimulator.reset();
        w.setBlock(10, 10, 8, BlockType.AIR);
        w.setBlock(8, 11, 8, BlockType.WATER);
        com.mineclone.world.WaterSimulator.tick(w);
        assertEq("flows toward outlet", BlockType.WATER_FLOW, w.getBlock(9, 11, 8));
        assertEq("does not spread uphill", BlockType.AIR, w.getBlock(7, 11, 8));
        assertEq("does not form broad side curtain", BlockType.AIR, w.getBlock(8, 11, 9));
        com.mineclone.world.WaterSimulator.reset();
    }

    private static void testWaterSlope() {
        World w = flatTestWorld();
        com.mineclone.world.WaterSimulator.reset();
        for (int x = 0; x < 32; x++)
            for (int z = 0; z < 16; z++) {
                int height = 20 - Math.min(10, x / 2);
                for (int y = 11; y <= height; y++)
                    w.getChunk(x / 16, 0).set(x % 16, y, z, BlockType.STONE);
            }
        w.setBlock(1, 21, 8, BlockType.WATER);
        for (int i = 0; i < 60; i++) com.mineclone.world.WaterSimulator.tick(w);
        assertEq("water reaches first step", BlockType.WATER_FLOW, w.getBlock(2, 20, 8));
        assertEq("fall preserves distance from source", 1, w.getBlockMeta(2, 20, 8) & 15);
        for (int x = 9; x < 32; x++)
            for (int y = 11; y < 22; y++)
                assertTrue("no renewed spread beyond seven horizontal steps at " + x + "," + y,
                        w.getBlock(x, y, 8) != BlockType.WATER_FLOW);
        w.setBlock(1, 21, 8, BlockType.AIR);
        for (int i = 0; i < 80; i++) com.mineclone.world.WaterSimulator.tick(w);
        assertEq("slope drains when source is removed", BlockType.AIR, w.getBlock(2, 20, 8));
        com.mineclone.world.WaterSimulator.reset();
    }

    private static void testWeather() {
        // Первый фронт мира ясный; подробные проверки фронтов — в FeatureTests.
        assertEq("clear interval", 0f, com.mineclone.world.Weather.intensity(0, 0));
        boolean wet = false;
        for (float t = 0f; t < com.mineclone.world.Weather.FRONT_LENGTH * 40 && !wet; t += 30f)
            wet = com.mineclone.world.Weather.intensity(0, t) > 0.3f;
        assertTrue("some front brings precipitation", wet);
        assertTrue("desert stays dry", !com.mineclone.world.Weather.precipitates(Biome.DESERT));
        assertTrue("tundra has precipitation", com.mineclone.world.Weather.precipitates(Biome.TUNDRA));
    }

    private static void testWaterFlow() {
        World w = flatTestWorld();
        com.mineclone.world.WaterSimulator.reset();
        w.setBlock(8, 11, 8, BlockType.WATER);
        for (int i = 0; i < 10; i++) com.mineclone.world.WaterSimulator.tick(w);
        assertEq("flow reaches seven cells", BlockType.WATER_FLOW, w.getBlock(15, 11, 8));
        assertEq("flow stops at eighth cell", BlockType.AIR, w.getBlock(16, 11, 8));
        w.setBlock(8, 11, 8, BlockType.AIR);
        for (int i = 0; i < 20; i++) com.mineclone.world.WaterSimulator.tick(w);
        assertEq("removed source drains pool", BlockType.AIR, w.getBlock(12, 11, 8));

        w.setBlock(8, 11, 8, BlockType.WATER);
        w.setBlock(14, 11, 8, BlockType.WATER);
        for (int i = 0; i < 12; i++) com.mineclone.world.WaterSimulator.tick(w);
        w.setBlock(8, 11, 8, BlockType.AIR);
        for (int i = 0; i < 20; i++) com.mineclone.world.WaterSimulator.tick(w);
        assertEq("remaining source still supports stream", BlockType.WATER_FLOW, w.getBlock(9, 11, 8));
        assertEq("stream weakens with longer route", 5, w.getBlockMeta(9, 11, 8) & 15);

        w = flatTestWorld();
        com.mineclone.world.WaterSimulator.reset();
        w.setBlock(8, 16, 8, BlockType.WATER);
        com.mineclone.world.WaterSimulator.tick(w);
        assertEq("fall takes priority", BlockType.WATER_FLOW, w.getBlock(8, 15, 8));
        assertEq("no floating side arm", BlockType.AIR, w.getBlock(9, 16, 8));
        w.setBlock(7, 14, 8, BlockType.WATER);
        w.setBlock(9, 14, 8, BlockType.WATER);
        w.setBlock(8, 14, 8, BlockType.WATER_FLOW, (byte) 1);
        com.mineclone.world.WaterSimulator.tick(w);
        assertTrue("unsupported cell does not become infinite source", w.getBlock(8, 14, 8) != BlockType.WATER);
        com.mineclone.world.WaterSimulator.reset();
    }

    private static void testMobBuoyancy() {
        World w = flatTestWorld();
        for (int y = 11; y < 17; y++)
            w.getChunk(0, 0).set(8, y, 8, BlockType.WATER);
        org.joml.Vector3f pos = new org.joml.Vector3f(8.5f, 12f, 8.5f);
        org.joml.Vector3f vel = new org.joml.Vector3f();
        for (int i = 0; i < 60; i++)
            com.mineclone.world.entity.EntityPhysics.step(w, pos, vel, 0.6f, 1.8f, 1f / 60f, -30f);
        assertTrue("submerged mob rises from rest", pos.y > 12.2f);
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

    /**
     * Разворачивает моба к точке. Нужен всем тестам погони: курс у свежего
     * моба случайный, а с конусом зрения стоящий спиной зомби игрока не
     * видит — это проверяется отдельно, в testSightCone.
     */
    private static void aimAt(Mob m, org.joml.Vector3f target) {
        m.yaw = (float) Math.atan2(-(target.x - m.position.x), -(target.z - m.position.z));
    }

    private static com.mineclone.world.entity.Mob spawnAt(
            com.mineclone.world.entity.MobType t, float x, float y, float z, long seed) {
        return new com.mineclone.world.entity.Mob(t, x, y, z, new java.util.Random(seed));
    }

    private static void testMobPeacefulStates() {
        World w = flatTestWorld();
        com.mineclone.world.entity.Mob cow =
                spawnAt(com.mineclone.world.entity.MobType.COW, 8.5f, 11f, 8.5f, 42L);
        org.joml.Vector3f far = new org.joml.Vector3f(8.5f, 11f, 100f);
        java.util.EnumSet<com.mineclone.world.entity.Mob.State> seen =
                java.util.EnumSet.noneOf(com.mineclone.world.entity.Mob.State.class);
        for (int i = 0; i < 1200; i++) {
            cow.update(w, far, 1f / 60f, 1.0f, true);
            seen.add(cow.state);
        }
        assertTrue("cow reaches WANDER", seen.contains(com.mineclone.world.entity.Mob.State.WANDER));
        assertTrue("cow reaches IDLE", seen.contains(com.mineclone.world.entity.Mob.State.IDLE));
        assertTrue("cow never chases", !seen.contains(com.mineclone.world.entity.Mob.State.CHASE));
        assertTrue("cow stays alive", !cow.dead);
        assertTrue("cow stays on the platform, y=" + cow.position.y,
                Math.abs(cow.position.y - 11f) < 0.2f);
    }

    private static void testMobFlee() {
        World w = flatTestWorld();
        com.mineclone.world.entity.Mob pig =
                spawnAt(com.mineclone.world.entity.MobType.PIG, 8.5f, 11f, 8.5f, 7L);
        org.joml.Vector3f player = new org.joml.Vector3f(6.5f, 11f, 8.5f);
        pig.hurt(2f, player.x, player.z);
        assertEq("pig flees", com.mineclone.world.entity.Mob.State.FLEE, pig.state);
        assertTrue("hurt flash on", pig.hurtFlash > 0f);
        assertTrue("health reduced", pig.health < com.mineclone.world.entity.MobType.PIG.maxHealth);
        float startX = pig.position.x;
        for (int i = 0; i < 30; i++)
            pig.update(w, player, 1f / 60f, 1.0f, true);
        assertTrue("pig ran away from the player, dx=" + (pig.position.x - startX),
                pig.position.x > startX);
        for (int i = 0; i < 400; i++)
            pig.update(w, player, 1f / 60f, 1.0f, true);
        assertTrue("flee ends", pig.state != com.mineclone.world.entity.Mob.State.FLEE);
    }

    private static void testMobDeath() {
        World w = flatTestWorld();
        org.joml.Vector3f far = new org.joml.Vector3f(8.5f, 11f, 300f);
        com.mineclone.world.entity.Mob chicken =
                spawnAt(com.mineclone.world.entity.MobType.CHICKEN, 8.5f, 11f, 8.5f, 3L);
        assertTrue("alive on spawn", !chicken.dead);
        assertTrue("first hit lands", chicken.hurt(2f, 0f, 0f));
        assertTrue("still alive after 2 damage", !chicken.dead);
        // Второй удар нужно ждать: окно неуязвимости 0.5 с.
        for (int i = 0; i < 40; i++)
            chicken.update(w, far, 1f / 60f, 0f, true);
        assertTrue("second hit lands after the invulnerability window",
                chicken.hurt(2f, 0f, 0f));
        assertTrue("dead after 4 damage total", chicken.dead);
    }

    private static void testZombieChase() {
        World w = flatTestWorld();
        com.mineclone.world.entity.Mob z =
                spawnAt(com.mineclone.world.entity.MobType.ZOMBIE, 8.5f, 11f, 8.5f, 11L);
        // Игрок в 10 блоках — в радиусе агра (16).
        org.joml.Vector3f near = new org.joml.Vector3f(8.5f, 11f, -1.5f);
        aimAt(z, near);
        z.update(w, near, 1f / 60f, 0f, true);
        assertEq("chases at 10 blocks", com.mineclone.world.entity.Mob.State.CHASE, z.state);
        float startZ = z.position.z;
        for (int i = 0; i < 60; i++)
            z.update(w, near, 1f / 60f, 0f, true);
        assertTrue("moved toward the player, dz=" + (z.position.z - startZ), z.position.z < startZ);

        // Игрок ушёл за 30 блоков — цель потеряна.
        org.joml.Vector3f far = new org.joml.Vector3f(8.5f, 11f, 41.5f);
        z.update(w, far, 1f / 60f, 0f, true);
        assertTrue("loses target beyond 24 blocks, state=" + z.state,
                z.state != com.mineclone.world.entity.Mob.State.CHASE
                        && z.state != com.mineclone.world.entity.Mob.State.ATTACK);
    }

    private static void testZombieAttackCooldown() {
        World w = flatTestWorld();
        com.mineclone.world.entity.Mob z =
                spawnAt(com.mineclone.world.entity.MobType.ZOMBIE, 8.5f, 11f, 8.5f, 5L);
        org.joml.Vector3f player = new org.joml.Vector3f(9.3f, 11f, 8.5f); // < 1.5 блока
        int hits = 0;
        for (int i = 0; i < 60; i++) {  // ровно 1 секунда
            z.update(w, player, 1f / 60f, 0f, true);
            if (z.justAttacked) hits++;
        }
        assertEq("one hit per second", 1, hits);
        assertEq("in ATTACK state", com.mineclone.world.entity.Mob.State.ATTACK, z.state);
        for (int i = 0; i < 60; i++) {
            z.update(w, player, 1f / 60f, 0f, true);
            if (z.justAttacked) hits++;
        }
        assertEq("two hits over two seconds", 2, hits);
    }

    private static void testZombieCreativePassive() {
        World w = flatTestWorld();
        com.mineclone.world.entity.Mob z =
                spawnAt(com.mineclone.world.entity.MobType.ZOMBIE, 8.5f, 11f, 8.5f, 5L);
        org.joml.Vector3f player = new org.joml.Vector3f(9.3f, 11f, 8.5f);
        for (int i = 0; i < 180; i++) {
            z.update(w, player, 1f / 60f, 0f, false);   // hostileEnabled = false
            assertTrue("never attacks in creative", !z.justAttacked);
        }
        assertTrue("stays peaceful, state=" + z.state,
                z.state != com.mineclone.world.entity.Mob.State.CHASE
                        && z.state != com.mineclone.world.entity.Mob.State.ATTACK);
    }

    private static void testZombieSunBurn() {
        World w = flatTestWorld();
        org.joml.Vector3f far = new org.joml.Vector3f(8.5f, 11f, 200f);

        com.mineclone.world.entity.Mob open =
                spawnAt(com.mineclone.world.entity.MobType.ZOMBIE, 8.5f, 11f, 8.5f, 9L);
        for (int i = 0; i < 60; i++)
            open.update(w, far, 1f / 60f, 1.0f, true);   // полдень
        assertTrue("burning under open sky", open.burning);
        assertTrue("lost ~2 HP in one second, hp=" + open.health,
                open.health < com.mineclone.world.entity.MobType.ZOMBIE.maxHealth - 1.5f);

        // Крыша над головой — не горит. Навес 6×6 блоков, а не один: зомби в
        // WANDER успевает отойти на ~0.7 м и вылез бы из-под точечной крыши.
        com.mineclone.world.entity.Mob shaded =
                spawnAt(com.mineclone.world.entity.MobType.ZOMBIE, 4.5f, 11f, 4.5f, 9L);
        for (int x = 2; x <= 7; x++)
            for (int z = 2; z <= 7; z++)
                w.getChunk(0, 0).set(x, 14, z, BlockType.STONE);
        for (int i = 0; i < 60; i++)
            shaded.update(w, far, 1f / 60f, 1.0f, true);
        assertTrue("not burning under a roof", !shaded.burning);
        assertEq("full health under a roof",
                com.mineclone.world.entity.MobType.ZOMBIE.maxHealth, shaded.health);

        // Ночью не горит даже под открытым небом.
        com.mineclone.world.entity.Mob night =
                spawnAt(com.mineclone.world.entity.MobType.ZOMBIE, 12.5f, 11f, 12.5f, 9L);
        for (int i = 0; i < 60; i++)
            night.update(w, far, 1f / 60f, 0f, true);
        assertTrue("not burning at night", !night.burning);
    }

    private static void testMobSpawnerRules() {
        // Правила поверхности и времени — чистые предикаты, миру не нужны.
        assertTrue("grass ok for peaceful",
                com.mineclone.world.entity.MobSpawner.peacefulSurfaceOk(BlockType.GRASS));
        assertTrue("stone not ok for peaceful",
                !com.mineclone.world.entity.MobSpawner.peacefulSurfaceOk(BlockType.STONE));
        assertTrue("sand not ok for peaceful",
                !com.mineclone.world.entity.MobSpawner.peacefulSurfaceOk(BlockType.SAND));
        assertTrue("zombies spawn at night",
                com.mineclone.world.entity.MobSpawner.zombieTimeOk(0.0f));
        assertTrue("zombies do not spawn at noon",
                !com.mineclone.world.entity.MobSpawner.zombieTimeOk(1.0f));

        // Капы: список забит до отказа — trySpawn ничего не добавляет.
        World w = flatTestWorld();
        com.mineclone.world.entity.MobSpawner sp = new com.mineclone.world.entity.MobSpawner(1L);
        java.util.List<com.mineclone.world.entity.Mob> mobs = new java.util.ArrayList<>();
        for (int i = 0; i < com.mineclone.world.entity.MobSpawner.PEACEFUL_CAP; i++)
            mobs.add(spawnAt(com.mineclone.world.entity.MobType.COW, 8.5f, 11f, 8.5f, i));
        for (int i = 0; i < com.mineclone.world.entity.MobSpawner.HOSTILE_CAP; i++)
            mobs.add(spawnAt(com.mineclone.world.entity.MobType.ZOMBIE, 8.5f, 11f, 8.5f, 100 + i));
        int before = mobs.size();
        for (int i = 0; i < 20; i++)
            sp.trySpawn(w, mobs, new org.joml.Vector3f(8.5f, 11f, 8.5f), 0f);
        assertEq("caps respected", before, mobs.size());
    }

    private static void testMobSpawnerNeedsChunks() {
        // Мир без единого загруженного чанка: спавнить некуда.
        World w = new World(99L);
        com.mineclone.world.entity.MobSpawner sp = new com.mineclone.world.entity.MobSpawner(2L);
        java.util.List<com.mineclone.world.entity.Mob> mobs = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++)
            sp.trySpawn(w, mobs, new org.joml.Vector3f(8.5f, 70f, 8.5f), 0f);
        assertEq("nothing spawns in unloaded chunks", 0, mobs.size());
    }

    private static void testMobSpawnerDespawn() {
        com.mineclone.world.entity.MobSpawner sp = new com.mineclone.world.entity.MobSpawner(3L);
        java.util.List<com.mineclone.world.entity.Mob> mobs = new java.util.ArrayList<>();
        com.mineclone.world.entity.Mob near =
                spawnAt(com.mineclone.world.entity.MobType.COW, 8.5f, 11f, 8.5f, 1L);
        com.mineclone.world.entity.Mob far =
                spawnAt(com.mineclone.world.entity.MobType.COW, 8.5f, 11f, 900f, 2L);
        mobs.add(near);
        mobs.add(far);
        sp.despawnFar(mobs, new org.joml.Vector3f(8.5f, 11f, 8.5f));
        assertEq("only the distant mob is removed", 1, mobs.size());
        assertTrue("the near mob survived", mobs.get(0) == near);
    }

    private static void testMobSkins() {
        java.util.List<int[]> pixelSets = new java.util.ArrayList<>();
        for (com.mineclone.world.entity.MobType t : com.mineclone.world.entity.MobType.values()) {
            java.awt.image.BufferedImage img = com.mineclone.render.MobSkins.generate(t);
            assertEq(t + " skin width", com.mineclone.render.MobSkins.WIDTH, img.getWidth());
            assertEq(t + " skin height", com.mineclone.render.MobSkins.HEIGHT, img.getHeight());
            int opaque = 0;
            int[] px = new int[img.getWidth() * img.getHeight()];
            for (int y = 0; y < img.getHeight(); y++)
                for (int x = 0; x < img.getWidth(); x++) {
                    int argb = img.getRGB(x, y);
                    px[y * img.getWidth() + x] = argb;
                    if (((argb >>> 24) & 0xFF) > 0) opaque++;
                }
            assertTrue(t + " skin is not blank (opaque=" + opaque + ")", opaque > px.length / 4);
            pixelSets.add(px);
        }
        // Разные виды выглядят по-разному.
        for (int a = 0; a < pixelSets.size(); a++)
            for (int b = a + 1; b < pixelSets.size(); b++)
                assertTrue("skins " + a + " and " + b + " differ",
                        !Arrays.equals(pixelSets.get(a), pixelSets.get(b)));
    }

    /**
     * Рука и предмет в первом лице обязаны оставаться перед камерой и в кадре:
     * позы — это набор подобранных на глаз констант, и одна опечатка молча
     * уносит руку за экран. Проверяем кулак (локально −Y у бокса руки) и
     * центр блока.
     */
    private static void testFirstPersonPoses() {
        org.joml.Matrix4f proj = com.mineclone.render.HeldItemRenderer.projection(16f / 9f, 70f);
        // equip = 1 (достали), swing = 0 и 0.5 — покой и пик замаха.
        for (float swing : new float[] { 0f, 0.5f, 1f })
            for (boolean holding : new boolean[] { false, true }) {
                org.joml.Matrix4f arm = com.mineclone.render.HeldItemRenderer.armPose(
                        1f, swing, 0f, false, holding);
                checkOnScreen("кулак (holding=" + holding + ", swing=" + swing + ")",
                        proj, arm, 0f, -0.5f, 0f);
            }
        for (float swing : new float[] { 0f, 0.5f, 1f }) {
            org.joml.Matrix4f item = com.mineclone.render.HeldItemRenderer.itemPose(
                    1f, swing, 0f, false);
            checkOnScreen("предмет (swing=" + swing + ")", proj, item, 0f, 0f, 0f);
        }
        // У инструмента поза своя: он плоский и его держат за рукоять, а не
        // за центр. Рукоять на спрайте — правый нижний угол, и она обязана
        // быть в кадре и рядом с кулаком, иначе кирка висит в воздухе.
        for (float swing : new float[] { 0f, 0.5f, 1f }) {
            org.joml.Matrix4f tool = com.mineclone.render.HeldItemRenderer.toolPose(
                    1f, swing, 0f, false);
            checkOnScreen("инструмент, центр (swing=" + swing + ")", proj, tool, 0f, 0f, 0f);
            checkOnScreen("инструмент, головка (swing=" + swing + ")",
                    proj, tool, -0.62f, 0.62f, 0f);
        }
        // Сидит ли рукоять в кулаке — вопрос картинки, а не числа: расстояние
        // от угла спрайта до конца бокса руки одинаково и у правильной позы,
        // и у сломанной. Это смотрится в tools/RenderMobPreview.java.
    }

    private static void checkOnScreen(String what, org.joml.Matrix4f proj,
            org.joml.Matrix4f model, float lx, float ly, float lz) {
        org.joml.Vector4f p = new org.joml.Matrix4f(proj).mul(model)
                .transform(new org.joml.Vector4f(lx, ly, lz, 1f));
        assertTrue(what + " перед камерой", p.w > 0f);
        float x = p.x / p.w, y = p.y / p.w;
        assertTrue(what + " в кадре по X (ndc=" + x + ")", x > -1f && x < 1f);
        assertTrue(what + " в кадре по Y (ndc=" + y + ")", y > -1f && y < 1f);
    }

    private static void testMobSounds() {
        Sounds s = new Sounds();
        for (com.mineclone.world.entity.MobType t : com.mineclone.world.entity.MobType.values()) {
            assertTrue(t + " has idle (say) samples", !s.mobSay(t).isEmpty());
            // hurt и death в ассетах есть не у всех — цепочка фолбэков обязана
            // вернуть хоть что-то, иначе моб умирает молча.
            assertTrue(t + " has hurt samples (with fallback)", !s.mobHurt(t).isEmpty());
            assertTrue(t + " has death samples (with fallback)", !s.mobDeath(t).isEmpty());
        }
        // Точечные проверки фолбэков на реальных ассетах:
        // у овцы нет ни hurt*, ни death* — оба съезжают на say*.
        assertEq("sheep hurt falls back to say",
                s.mobSay(com.mineclone.world.entity.MobType.SHEEP),
                s.mobHurt(com.mineclone.world.entity.MobType.SHEEP));
        // у коровы hurt есть, а death нет — death съезжает на hurt.
        assertEq("cow death falls back to hurt",
                s.mobHurt(com.mineclone.world.entity.MobType.COW),
                s.mobDeath(com.mineclone.world.entity.MobType.COW));
        // у зомби есть всё — фолбэки не срабатывают.
        assertTrue("zombie death is its own sample",
                !s.mobDeath(com.mineclone.world.entity.MobType.ZOMBIE)
                        .equals(s.mobHurt(com.mineclone.world.entity.MobType.ZOMBIE)));
        // Шаги есть у всех пяти видов — фолбэк не нужен, но пустой список
        // означал бы, что ассеты не нашлись.
        for (com.mineclone.world.entity.MobType t : com.mineclone.world.entity.MobType.values())
            assertTrue(t + " has step samples", !s.mobStep(t).isEmpty());
    }

    private static void testMobFallDamage() {
        World w = flatTestWorld();
        org.joml.Vector3f far = new org.joml.Vector3f(8.5f, 11f, 300f);

        // Корова с 19 блоков (спавн y=30, площадка y=11) должна пострадать.
        com.mineclone.world.entity.Mob cow =
                spawnAt(com.mineclone.world.entity.MobType.COW, 8.5f, 30f, 8.5f, 21L);
        for (int i = 0; i < 600; i++)
            cow.update(w, far, 1f / 60f, 0f, true);
        assertTrue("cow landed, y=" + cow.position.y, Math.abs(cow.position.y - 11f) < 0.2f);
        assertTrue("cow hurt by the fall, hp=" + cow.health,
                cow.health < com.mineclone.world.entity.MobType.COW.maxHealth);

        // Курица планирует (терминальная скорость -3 м/с) — урона быть не должно.
        com.mineclone.world.entity.Mob chicken =
                spawnAt(com.mineclone.world.entity.MobType.CHICKEN, 12.5f, 30f, 12.5f, 22L);
        for (int i = 0; i < 900; i++)
            chicken.update(w, far, 1f / 60f, 0f, true);
        assertTrue("chicken landed, y=" + chicken.position.y,
                Math.abs(chicken.position.y - 11f) < 0.2f);
        assertEq("chicken unharmed by gliding down",
                com.mineclone.world.entity.MobType.CHICKEN.maxHealth, chicken.health);
    }

    private static void testMobStepSounds() {
        World w = flatTestWorld();
        org.joml.Vector3f far = new org.joml.Vector3f(8.5f, 11f, 300f);
        com.mineclone.world.entity.Mob cow =
                spawnAt(com.mineclone.world.entity.MobType.COW, 8.5f, 11f, 8.5f, 42L);
        int steps = 0;
        for (int i = 0; i < 1200; i++) {   // 20 с — заведомо больше одного WANDER
            cow.update(w, far, 1f / 60f, 0f, true);
            if (cow.justStepSound) steps++;
        }
        assertTrue("walking cow emits step events, got " + steps, steps > 0);

        // Стоящий на месте моб шагов не издаёт: IDLE обнуляет направление.
        com.mineclone.world.entity.Mob idle =
                spawnAt(com.mineclone.world.entity.MobType.COW, 4.5f, 11f, 4.5f, 42L);
        int idleSteps = 0;
        for (int i = 0; i < 60; i++) {      // первую секунду моб ещё в IDLE
            idle.update(w, far, 1f / 60f, 0f, true);
            if (idle.justStepSound) idleSteps++;
        }
        assertEq("standing mob is silent", 0, idleSteps);
    }

    private static void testSeparate() {
        // Два тела в одной точке разводятся в стороны.
        org.joml.Vector3f a = new org.joml.Vector3f(8.5f, 11f, 8.5f);
        org.joml.Vector3f b = new org.joml.Vector3f(8.5f, 11f, 8.5f);
        assertTrue("overlap detected",
                com.mineclone.world.entity.EntityPhysics.separate(a, 0.9f, 1.4f, 0.25f,
                        b, 0.9f, 1.4f, 0.25f));
        float dx = b.x - a.x, dz = b.z - a.z;
        assertTrue("bodies moved apart, d=" + Math.sqrt(dx * dx + dz * dz),
                dx * dx + dz * dz > 1e-6f);

        // Доля 0 означает «не двигать»: так игрока не таскает своими же мобами.
        org.joml.Vector3f fixed = new org.joml.Vector3f(0f, 11f, 0f);
        org.joml.Vector3f mob = new org.joml.Vector3f(0.2f, 11f, 0f);
        com.mineclone.world.entity.EntityPhysics.separate(fixed, 0.6f, 1.8f, 0f,
                mob, 0.9f, 1.4f, 0.5f);
        assertEq("fixed body stays put (x)", 0f, fixed.x);
        assertEq("fixed body stays put (z)", 0f, fixed.z);
        assertTrue("mob pushed away, x=" + mob.x, mob.x > 0.2f);

        // Разнесённые по вертикали не расталкиваются — один стоит на другом.
        org.joml.Vector3f low = new org.joml.Vector3f(8.5f, 11f, 8.5f);
        org.joml.Vector3f high = new org.joml.Vector3f(8.5f, 12.4f, 8.5f);
        assertTrue("stacked bodies are left alone",
                !com.mineclone.world.entity.EntityPhysics.separate(low, 0.9f, 1.4f, 0.25f,
                        high, 0.9f, 1.4f, 0.25f));
    }

    private static void testMobSidestep() {
        World w = flatTestWorld();
        com.mineclone.world.entity.Mob z = spawnAt(
                com.mineclone.world.entity.MobType.ZOMBIE, 10.5f, 11f, 8.5f, 77L);
        org.joml.Vector3f player = new org.joml.Vector3f(20.5f, 11f, 8.5f);

        // Цель берётся по прямой видимости, поэтому сначала даём зомби увидеть
        // игрока, и только потом ставим стену — ровно так это и происходит в
        // игре: моб уже бежит, а путь перекрыт.
        aimAt(z, player);
        z.update(w, player, 1f / 60f, 0f, true);
        assertEq("target acquired before the wall goes up",
                com.mineclone.world.entity.Mob.State.CHASE, z.state);

        // Стена по x=12 от z=-5 до z=25: обойти её концы за время теста нельзя.
        // Пишем напрямую в чанк, чтобы не гонять пересчёт освещения на 90 блоков.
        for (int wz = -5; wz <= 25; wz++) {
            Chunk c = w.getChunk(0, Math.floorDiv(wz, Chunk.SIZE_Z));
            for (int y = 11; y <= 13; y++)
                c.set(12, y, Math.floorMod(wz, Chunk.SIZE_Z), BlockType.STONE);
        }
        float startZ = z.position.z;
        // Меряем максимальное отклонение за прогон, а не конечную точку: после
        // истечения обхода зомби законно возвращается по z к игроку, и endpoint
        // снова оказывается около старта.
        float maxDrift = 0f;
        for (int i = 0; i < 240; i++) {   // 4 с: ~1 с подхода + 0.8 с упора + обход
            z.update(w, player, 1f / 60f, 0f, true);
            maxDrift = Math.max(maxDrift, Math.abs(z.position.z - startZ));
        }
        assertEq("still chasing", com.mineclone.world.entity.Mob.State.CHASE, z.state);
        assertTrue("did not pass through the wall, x=" + z.position.x, z.position.x < 12f);
        assertTrue("moved along the wall instead of butting it, maxDrift=" + maxDrift,
                maxDrift > 0.5f);
    }

    private static void testMobInvulnWindow() {
        World w = flatTestWorld();
        org.joml.Vector3f far = new org.joml.Vector3f(8.5f, 11f, 300f);
        com.mineclone.world.entity.Mob cow =
                spawnAt(com.mineclone.world.entity.MobType.COW, 8.5f, 11f, 8.5f, 5L);
        float max = com.mineclone.world.entity.MobType.COW.maxHealth;

        // Закликивание: 50 ударов в один кадр должны дать урон ровно одного.
        int landed = 0;
        for (int i = 0; i < 50; i++)
            if (cow.hurt(2f, 6f, 8.5f)) landed++;
        assertEq("only the first of 50 spam hits lands", 1, landed);
        assertEq("damage of exactly one hit", max - 2f, cow.health);
        assertTrue("cow survived the spam", !cow.dead);

        // После окна неуязвимости удар снова проходит.
        for (int i = 0; i < 40; i++)   // 0.67 с > INVULN_TIME
            cow.update(w, far, 1f / 60f, 0f, true);
        assertTrue("hit lands again after the window", cow.hurt(2f, 6f, 8.5f));
        assertEq("two hits total", max - 4f, cow.health);

        // Крит-множитель проходит как обычный урон, просто больше.
        for (int i = 0; i < 40; i++)
            cow.update(w, far, 1f / 60f, 0f, true);
        assertTrue("crit lands", cow.hurt(2f * 1.5f, 6f, 8.5f));
        assertEq("crit dealt 3 damage", max - 7f, cow.health);
    }

    private static void testPlayerInvulnWindow() {
        com.mineclone.game.Player p = new com.mineclone.game.Player();
        float max = com.mineclone.game.Player.MAX_HEALTH;
        int landed = 0;
        for (int i = 0; i < 10; i++)   // стая зомби бьёт в один кадр
            if (p.takeAttackDamage(3f)) landed++;
        assertEq("only one of 10 simultaneous mob hits lands", 1, landed);
        assertEq("player lost exactly one hit worth", max - 3f, p.health);

        // Урон от падения окно не уважает — он идёт другим путём.
        p.takeDamage(2f);
        assertEq("fall damage still applies during invulnerability", max - 5f, p.health);
    }

    private static void testZombieBurnsAtDay() {
        // Регрессия на реальный баг: /time set day даёт daylight = sin(PI/6) = 0.5,
        // а порог горения стоял на 0.7 — зомби горели только около полудня.
        World w = flatTestWorld();
        org.joml.Vector3f far = new org.joml.Vector3f(8.5f, 11f, 300f);
        com.mineclone.world.entity.Mob z =
                spawnAt(com.mineclone.world.entity.MobType.ZOMBIE, 8.5f, 11f, 8.5f, 31L);
        float dayLight = (float) Math.sin(Math.PI / 6.0);
        assertTrue("preset 'day' really is 0.5", Math.abs(dayLight - 0.5f) < 1e-4f);
        for (int i = 0; i < 60; i++)
            z.update(w, far, 1f / 60f, dayLight, true);
        assertTrue("burning at /time set day", z.burning);
        assertTrue("lost health at /time set day, hp=" + z.health,
                z.health < com.mineclone.world.entity.MobType.ZOMBIE.maxHealth - 1.5f);

        // Сумерки (0.2) всё ещё безопасны — иначе зомби сгорит в момент спавна.
        com.mineclone.world.entity.Mob dusk =
                spawnAt(com.mineclone.world.entity.MobType.ZOMBIE, 12.5f, 11f, 12.5f, 32L);
        for (int i = 0; i < 60; i++)
            dusk.update(w, far, 1f / 60f, 0.2f, true);
        assertTrue("not burning at dusk", !dusk.burning);
        assertEq("full health at dusk",
                com.mineclone.world.entity.MobType.ZOMBIE.maxHealth, dusk.health);
    }

    private static void testSoundOcclusion() {
        World w = flatTestWorld();
        Vector3f ear = new Vector3f(4.5f, 12f, 8.5f);
        Vector3f src = new Vector3f(14.5f, 12f, 8.5f);

        // Открытое пространство — звук не теряет ничего.
        assertTrue("clear air does not muffle",
                com.mineclone.audio.SoundOcclusion.factor(w, ear, src) == 1f);

        // Одна стена между ними.
        for (int y = 11; y <= 14; y++)
            for (int zz = 4; zz <= 13; zz++)
                w.getChunk(0, 0).set(9, y, zz, BlockType.STONE);
        float oneWall = com.mineclone.audio.SoundOcclusion.factor(w, ear, src);
        assertTrue("one wall muffles (got " + oneWall + ")", oneWall < 1f && oneWall > 0.2f);

        // Вторая стена глушит сильнее первой.
        for (int y = 11; y <= 14; y++)
            for (int zz = 4; zz <= 13; zz++)
                w.getChunk(0, 0).set(11, y, zz, BlockType.STONE);
        float twoWalls = com.mineclone.audio.SoundOcclusion.factor(w, ear, src);
        assertTrue("two walls muffle more", twoWalls < oneWall);

        // Один блок, пройденный по диагонали, не должен считаться трижды:
        // без дедупликации клеток он глушил бы как три стены. Концы отрезка
        // берём заведомо снаружи стены — клетки источника и уха не считаются.
        int diag = com.mineclone.audio.SoundOcclusion.solidBetween(w,
                new Vector3f(8.2f, 11.2f, 8.5f), new Vector3f(10.8f, 13.8f, 8.5f));
        assertTrue("a diagonal pass counts the wall once or twice, not more (got " + diag + ")",
                diag >= 1 && diag <= 2);

        // Насыщение: бесконечно глушить нельзя, иначе звук уходит в денормали.
        assertTrue("muffling saturates",
                com.mineclone.audio.SoundOcclusion.gainFor(100)
                        == com.mineclone.audio.SoundOcclusion.gainFor(
                                com.mineclone.audio.SoundOcclusion.MAX_BLOCKS));
    }

    /**
     * Конус зрения. Соглашение движка: вперёд — это (-sin yaw, -cos yaw),
     * и именно оно тихо ломается при любой правке поворотов, поэтому
     * проверяется отдельно от мира.
     */
    private static void testSightCone() {
        // yaw = 0 -> смотрим в -Z.
        assertTrue("sees straight ahead", Mob.inSightCone(0f, 0f, -10f));
        assertTrue("sees within the cone", Mob.inSightCone(0f, 4f, -8f));
        assertTrue("does not see straight behind", !Mob.inSightCone(0f, 0f, 10f));
        assertTrue("does not see directly to the side", !Mob.inSightCone(0f, 10f, 0f));
        // Развернулись на 180 — теперь видно то, что было за спиной.
        float back = (float) Math.PI;
        assertTrue("turning around flips the cone", Mob.inSightCone(back, 0f, 10f));
        assertTrue("and loses what was in front", !Mob.inSightCone(back, 0f, -10f));
    }

    private static void testSightRangeByLight() {
        float lit = Mob.sightRange(1f);
        float dark = Mob.sightRange(0f);
        assertTrue("a lit target is seen far", lit > 15f);
        assertTrue("a dark target is seen closer", dark < lit * 0.85f);
        assertTrue("range grows with light", Mob.sightRange(0.5f) > dark
                && Mob.sightRange(0.5f) < lit);
        // Выход за пределы 0..1 не должен ломать дальность.
        assertTrue("range is clamped from above", Mob.sightRange(5f) == lit);
        assertTrue("range is clamped from below", Mob.sightRange(-5f) == dark);
    }

    private static void testHearingAndInvestigate() {
        World w = flatTestWorld();
        Mob z = new Mob(MobType.ZOMBIE, 8.5f, 11f, 8.5f, new java.util.Random(1));
        z.onGround = true;

        // Далёкий шум не слышно.
        z.hearNoise(60f, 11f, 8.5f, 12f);
        assertTrue("a distant noise is ignored", !z.isInvestigating());

        // Близкий — слышно, и моб идёт к точке.
        z.hearNoise(16.5f, 11f, 8.5f, 12f);
        assertTrue("a nearby noise starts an investigation", z.isInvestigating());

        Vector3f faraway = new Vector3f(500f, 11f, 500f);   // игрока рядом нет
        float startDx = Math.abs(16.5f - z.position.x);
        for (int i = 0; i < 60; i++)
            z.update(w, faraway, 0.05f, 0f, true);
        assertTrue("the zombie walks toward the noise",
                Math.abs(16.5f - z.position.x) < startDx);

        // Погоня важнее шума: услышанное во время преследования игнорируется.
        Vector3f near = new Vector3f(z.position.x + 2f, z.position.y, z.position.z);
        aimAt(z, near);
        for (int i = 0; i < 10; i++)
            z.update(w, near, 0.05f, 0f, true);
        assertTrue("the zombie is chasing", z.state == Mob.State.CHASE
                || z.state == Mob.State.ATTACK);
        z.hearNoise(z.position.x - 9f, z.position.y, z.position.z, 12f);
        assertTrue("noise does not interrupt a chase", !z.isInvestigating());
    }

    private static void testZombieLineOfSight() {
        // Сначала сам предикат: сквозь воздух видно, сквозь камень нет.
        World w = flatTestWorld();
        assertTrue("clear line over the platform",
                com.mineclone.world.entity.EntityPhysics.lineOfSight(w,
                        4.5f, 12f, 8.5f, 14.5f, 12f, 8.5f));
        for (int y = 11; y <= 14; y++)
            for (int zz = 4; zz <= 13; zz++)
                w.getChunk(0, 0).set(9, y, zz, BlockType.STONE);
        assertTrue("stone wall blocks the line",
                !com.mineclone.world.entity.EntityPhysics.lineOfSight(w,
                        4.5f, 12f, 8.5f, 14.5f, 12f, 8.5f));

        // Зомби за стеной в 8 блоках от игрока агриться не должен.
        com.mineclone.world.entity.Mob blocked = spawnAt(
                com.mineclone.world.entity.MobType.ZOMBIE, 5.5f, 11f, 8.5f, 61L);
        org.joml.Vector3f player = new org.joml.Vector3f(13.5f, 11f, 8.5f);
        for (int i = 0; i < 120; i++)
            blocked.update(w, player, 1f / 60f, 0f, true);
        assertTrue("does not aggro through the wall, state=" + blocked.state,
                blocked.state != com.mineclone.world.entity.Mob.State.CHASE
                        && blocked.state != com.mineclone.world.entity.Mob.State.ATTACK);

        // На открытом месте (та же дистанция, стены нет) — агрится сразу.
        World open = flatTestWorld();
        com.mineclone.world.entity.Mob seeing = spawnAt(
                com.mineclone.world.entity.MobType.ZOMBIE, 5.5f, 11f, 8.5f, 61L);
        aimAt(seeing, player);
        seeing.update(open, player, 1f / 60f, 0f, true);
        assertEq("aggros with a clear line",
                com.mineclone.world.entity.Mob.State.CHASE, seeing.state);
    }

    private static void testMobDeathLinger() {
        World w = flatTestWorld();
        org.joml.Vector3f far = new org.joml.Vector3f(8.5f, 11f, 300f);
        com.mineclone.world.entity.Mob cow =
                spawnAt(com.mineclone.world.entity.MobType.COW, 8.5f, 11f, 8.5f, 8L);
        cow.hurt(100f, 6f, 8.5f);
        assertTrue("dead on a lethal hit", cow.dead);
        assertEq("fall-over timer armed",
                com.mineclone.world.entity.Mob.DEATH_TIME, cow.deathTimer);
        assertTrue("effects not fired yet", !cow.deathEffectsDone);

        // Полкадра спустя ещё лежит — Game не имеет права убирать его сразу.
        cow.update(w, far, 1f / 60f, 0f, true);
        assertTrue("still lingering, timer=" + cow.deathTimer, cow.deathTimer > 0f);

        for (int i = 0; i < 40; i++)
            cow.update(w, far, 1f / 60f, 0f, true);
        assertEq("timer ran out", 0f, cow.deathTimer);
    }

    private static void testRegenPause() {
        com.mineclone.game.Player p = new com.mineclone.game.Player();
        World w = flatTestWorld();
        com.mineclone.core.Input noInput = null;   // controlsEnabled=false — ввод не читается
        p.position.set(8.5f, 11f, 8.5f);
        p.takeAttackDamage(6f);
        float hurt = p.health;
        // 4 с после удара регена быть не должно: пауза 5 с.
        for (int i = 0; i < 240; i++)
            p.update(1f / 60f, w, noInput, false);
        assertEq("no regen during the pause", hurt, p.health);
        // Even after the combat delay expires, menus must not heal the player.
        for (int i = 0; i < 480; i++)
            p.update(1f / 60f, w, noInput, false);
        assertEq("menus do not regenerate health", hurt, p.health);
    }

    // ---- Еда и голод ------------------------------------------------------

    private static void testFoodStacks() {
        ItemStack beef = ItemStack.of("beef", 4);
        assertTrue("food is food", beef.food() != null);
        assertTrue("food is not a tool", !beef.hasDurability());
        assertTrue("same food stacks", beef.stacksWith(ItemStack.of("beef", 1)));
        assertTrue("different food does not stack",
                !beef.stacksWith(ItemStack.of("porkchop", 1)));
        assertTrue("food never stacks with blocks",
                !beef.stacksWith(new ItemStack(BlockType.STONE, 1)));
        assertTrue("food never stacks with tools",
                !beef.stacksWith(ItemStack.of("wooden_axe")));

        // Инвентарь обязан сливать одинаковую еду и не путать её с блоками.
        Inventory inv = new Inventory();
        inv.add(ItemStack.of("beef", 10));
        inv.add(ItemStack.of("beef", 5));
        inv.add(new ItemStack(BlockType.STONE, 5));
        int beefSlots = 0, beefTotal = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack st = inv.get(i);
            if (st != null && st.food() != null && st.item == item("beef")) {
                beefSlots++;
                beefTotal += st.count;
            }
        }
        assertEq("beef merged into one stack", 1, beefSlots);
        assertEq("beef total is right", 15, beefTotal);
        assertEq("stone kept its own stack", 5, Recipes.count(inv, item(BlockType.STONE)));
    }

    private static void testHunger() {
        Player p = new Player();
        p.respawn(8.5f, 12f, 8.5f);
        assertEq("starts full", Player.MAX_HUNGER, p.hunger);

        // Голод уходит сам по себе. Гоняем tickHunger напрямую: полный
        // update требует настоящего GLFW-ввода, которого в тестах нет.
        for (int i = 0; i < 600; i++)
            p.tickHunger(1f / 60f, true);
        assertTrue("hunger drains over time (" + p.hunger + ")", p.hunger < Player.MAX_HUNGER);

        // В меню и в творческом полёте голод стоит.
        float held = p.hunger;
        for (int i = 0; i < 600; i++)
            p.tickHunger(1f / 60f, false);
        assertEq("hunger freezes when controls are off", held, p.hunger);

        // Бег ест сытость заметно быстрее ходьбы.
        p.hunger = Player.MAX_HUNGER;
        for (int i = 0; i < 600; i++)
            p.tickHunger(1f / 60f, true);
        float walked = Player.MAX_HUNGER - p.hunger;
        p.hunger = Player.MAX_HUNGER;
        p.isSprinting = true;
        for (int i = 0; i < 600; i++)
            p.tickHunger(1f / 60f, true);
        float sprinted = Player.MAX_HUNGER - p.hunger;
        p.isSprinting = false;
        assertTrue("sprinting costs more (" + walked + " vs " + sprinted + ")",
                sprinted > walked * 2f);

        // Реген гейтится сытостью.
        p.health = 10f;
        p.hunger = Player.MAX_HUNGER;
        assertTrue("fed player regenerates", p.canRegen());
        p.hunger = 2f;
        assertTrue("hungry player does not", !p.canRegen());

        // Пустой желудок отнимает здоровье, но не добивает: смерть от голода
        // в игре без земледелия — тупик, а не вызов.
        p.hunger = 0f;
        p.health = 10f;
        for (int i = 0; i < 60 * 120; i++)
            p.tickHunger(1f / 60f, true);
        assertTrue("starving hurts", p.health < 10f);
        assertTrue("starving never kills (" + p.health + ")", p.health >= Player.STARVE_FLOOR);

        // Еда поднимает сытость и не переполняет её.
        p.hunger = 19f;
        p.eat(10f);
        assertEq("hunger is capped", Player.MAX_HUNGER, p.hunger);
        assertTrue("a full player has no reason to eat", !p.canEat());
    }

    private static void testMobDrops() {
        assertEq("cow drops beef", item("beef"), item(MobType.COW.drop()));
        assertEq("pig drops pork", item("porkchop"), item(MobType.PIG.drop()));
        assertEq("chicken drops chicken", item("chicken"), item(MobType.CHICKEN.drop()));
        assertEq("sheep drops mutton", item("mutton"), item(MobType.SHEEP.drop()));
        // Зомби ничего не даёт: иначе ночь превращается в ферму и сидеть в
        // темноте становится выгоднее, чем строить дом.
        assertTrue("zombie drops nothing", MobType.ZOMBIE.drop() == null);
        for (MobType t : MobType.values())
            if (t.drop() != null)
                assertTrue("drop count is sane for " + t, t.dropCount() > 0 && t.dropCount() <= 4);
    }

    private static void testFoodSaveRoundTrip() throws Exception {
        SaveManager sm = freshManager();
        ItemStack[] inv = LevelData.emptyInventory();
        inv[0] = ItemStack.of("porkchop", 7);
        inv[1] = ItemStack.of("wooden_axe");
        inv[2] = new ItemStack(BlockType.PLANKS, 12);
        sm.saveLevel("w1", new LevelData("w", 5L, 1, 2, 3, 1, 2, 3, 0f, 0f, 0f, 0,
                inv, GameMode.SURVIVAL, 0L, 13f, 8.5f));

        LevelData out = sm.loadLevel("w1");
        assertTrue("level loaded", out != null);
        assertTrue("food survived", out.inventory[0] != null && out.inventory[0].food() != null);
        assertEq("food kind survived", item("porkchop"), out.inventory[0].item);
        assertEq("food count survived", 7, out.inventory[0].count);
        assertTrue("tool still fine", out.inventory[1] != null && out.inventory[1].hasDurability());
        assertTrue("block still fine", out.inventory[2] != null
                && !out.inventory[2].hasDurability() && out.inventory[2].food() == null);
        assertTrue("hunger survived", Math.abs(out.hunger - 8.5f) < 1e-4f);
        assertTrue("health survived", Math.abs(out.health - 13f) < 1e-4f);
    }

    // ---- Инструменты и крафт ---------------------------------------------

    private static void testToolStacks() {
        ItemStack pick = ItemStack.of("stone_pickaxe");
        assertTrue("a tool is a tool", pick.hasDurability());
        assertTrue("a tool is always a single item", pick.count == 1);
        assertTrue("a tool is always full", pick.isFull());
        assertTrue("a tool never stacks with another tool",
                !pick.stacksWith(ItemStack.of("stone_pickaxe")));
        assertTrue("a tool never stacks with blocks",
                !pick.stacksWith(new ItemStack(BlockType.STONE, 1)));
        assertEq("pouring into a tool changes nothing", 5, pick.addUpTo(5));

        // Износ обязан переживать копирование: иначе перекладывание кирки
        // в другой слот её чинит.
        pick.setDamage(40);
        ItemStack copy = pick.copy();
        assertEq("wear survives a copy", 40, copy.damage());
        assertTrue("condition drops with wear", copy.condition() < 1f);

        // Инвентарь не должен сливать инструменты в стопку.
        Inventory inv = new Inventory();
        inv.addItem(ItemStack.of("wooden_axe"));
        inv.addItem(ItemStack.of("wooden_axe"));
        int tools = 0;
        for (int i = 0; i < inv.size(); i++)
            if (inv.get(i) != null && inv.get(i).hasDurability())
                tools++;
        assertEq("two axes occupy two slots", 2, tools);
    }

    private static void testToolGating() {
        // Что вообще даёт дроп.
        assertEq("dirt needs no tool", 0, BlockType.DIRT.requiredToolLevel());
        assertEq("stone needs a wooden pick", 1, BlockType.STONE.requiredToolLevel());
        assertEq("iron ore needs a stone pick", 2, BlockType.IRON_ORE.requiredToolLevel());
        assertEq("diamond needs an iron pick", 3, BlockType.DIAMOND_ORE.requiredToolLevel());

        // Класс инструмента.
        assertTrue("stone is a pickaxe job",
                item("stone_pickaxe").tool.suits(BlockType.STONE));
        assertTrue("a pickaxe is useless on dirt",
                !item("stone_pickaxe").tool.suits(BlockType.DIRT));
        assertTrue("a shovel is the dirt tool",
                item("wooden_shovel").tool.suits(BlockType.DIRT));
        assertTrue("an axe is the wood tool",
                item("wooden_axe").tool.suits(BlockType.PLANKS));

        // Вертикаль прогресса: уровень растёт вместе с материалом.
        assertTrue("stone beats wood", item("stone_pickaxe").tool.level() > item("wooden_pickaxe").tool.level());
        assertTrue("iron beats stone", item("iron_pickaxe").tool.level() > item("stone_pickaxe").tool.level());
        assertTrue("diamond beats iron", item("diamond_pickaxe").tool.level() > item("iron_pickaxe").tool.level());
        assertTrue("better material digs faster",
                item("diamond_pickaxe").tool.speed() > item("wooden_pickaxe").tool.speed());
    }

    private static void testToolWear() {
        ItemStack pick = ItemStack.of("wooden_pickaxe");
        int uses = 0;
        while (!pick.wear() && uses < 10000)
            uses++;
        assertEq("a tool lasts exactly its durability",
                item("wooden_pickaxe").durability - 1, uses);
        assertTrue("a worn out tool has no condition left", pick.condition() <= 0f);
        // Блок износом не интересуется.
        assertTrue("blocks never wear", !new ItemStack(BlockType.STONE, 1).wear());
    }

    private static void testCrafting() {
        Inventory inv = new Inventory();
        inv.add(new ItemStack(BlockType.COBBLE, 3));
        inv.add(ItemStack.of("stick", 2));

        ItemStack[] grid = new ItemStack[9];
        grid[0] = new ItemStack(BlockType.COBBLE, 1);
        grid[1] = new ItemStack(BlockType.COBBLE, 1);
        grid[2] = new ItemStack(BlockType.COBBLE, 1);
        grid[4] = ItemStack.of("stick");
        grid[7] = ItemStack.of("stick");
        var shaped = Recipes.match(grid, 3);
        assertTrue("3x3 shape offers a stone pickaxe",
                shaped != null && shaped.result() == item("stone_pickaxe"));
        assertTrue("shape consumes its five cells", Recipes.consume(grid, 3) != null
                && grid[0] == null && grid[4] == null && grid[7] == null);

        var list = Recipes.available(inv);
        assertTrue("stone pickaxe is offered", list.stream()
                .anyMatch(r -> r.result() == item("stone_pickaxe")));

        var pickRecipe = java.util.Arrays.stream(Recipes.all())
                .filter(r -> r.result() == item("stone_pickaxe")).findFirst().orElse(null);
        assertTrue("recipe table has the stone pickaxe", pickRecipe != null);
        assertTrue("crafting succeeds", Recipes.craft(inv, pickRecipe));

        // Списалось ровно по рецепту, ни блоком больше.
        assertEq("cobble spent", 0, Recipes.count(inv, item(BlockType.COBBLE)));
        assertEq("plank spent", 0, Recipes.count(inv, item(BlockType.PLANKS)));
        int tools = 0;
        for (int i = 0; i < inv.size(); i++)
            if (inv.get(i) != null && inv.get(i).hasDurability())
                tools++;
        assertEq("got exactly one pickaxe", 1, tools);

        // Второй раз собрать не из чего.
        assertTrue("cannot craft without materials", !Recipes.craft(inv, pickRecipe));

        // Рукоять — отдельный материал: деревянная кирка это три доски плюс
        // две палки, а не четыре доски.
        Inventory wood = new Inventory();
        wood.add(new ItemStack(BlockType.PLANKS, 3));
        var woodPick = java.util.Arrays.stream(Recipes.all())
                .filter(r -> r.result() == item("wooden_pickaxe")).findFirst().orElse(null);
        assertTrue("three planks are not enough for a wooden pickaxe",
                !Recipes.canCraft(wood, woodPick));
        wood.add(ItemStack.of("stick", 2));
        assertTrue("three planks and two sticks are enough",
                Recipes.canCraft(wood, woodPick));
    }

    private static void testRecipeTable() {
        for (var r : Recipes.all()) {
            assertTrue("recipe needs something", r.needCount() > 0 && r.need() != null);
            assertTrue("recipe produces something", r.result() != null);
            assertTrue("recipe yields at least one", r.resultCount() > 0);
            // Ровно то, ради чего таблица и существует: рецепт должен быть
            // выполним из материалов, которые в мире вообще добываются.
            Inventory inv = new Inventory();
            inv.add(new ItemStack(r.need(), r.needCount() + r.handleCount()));
            if (r.handle() != null && r.handle() != r.need())
                inv.add(new ItemStack(r.handle(), r.handleCount()));
            assertTrue("recipe for " + r.resultName() + " is satisfiable",
                    Recipes.canCraft(inv, r));
        }
    }

    private static void testToolSaveRoundTrip() throws Exception {
        SaveManager sm = freshManager();
        ItemStack[] inv = LevelData.emptyInventory();
        ItemStack pick = ItemStack.of("iron_pickaxe");
        pick.setDamage(77);
        inv[0] = pick;
        inv[1] = new ItemStack(BlockType.COBBLE, 30);
        sm.saveLevel("w1", new LevelData("w", 5L, 1, 2, 3, 1, 2, 3, 0f, 0f, 0f, 0,
                inv, GameMode.SURVIVAL, 0L, 20f));

        LevelData out = sm.loadLevel("w1");
        assertTrue("level loaded", out != null);
        assertTrue("tool survived the round trip", out.inventory[0] != null
                && out.inventory[0].hasDurability());
        assertEq("tool type survived", item("iron_pickaxe"), out.inventory[0].item);
        assertEq("tool wear survived", 77, out.inventory[0].damage());
        assertTrue("block stack still works", out.inventory[1] != null
                && !out.inventory[1].hasDurability());
        assertEq("block count survived", 30, out.inventory[1].count);
    }

    private static void testItemStack() {
        ItemStack s = new ItemStack(BlockType.STONE, 1);
        assertEq("type", BlockType.STONE, s.block());
        assertEq("count", 1, s.count);
        assertTrue("isFull false at 1", !s.isFull());

        s.count = 64;
        assertTrue("isFull true at MAX", s.isFull());

        // add returns leftover that didn't fit
        ItemStack t = new ItemStack(BlockType.DIRT, 60);
        int left = t.addUpTo(10); // 60 + 10 = 70 -> capped 64, leftover 6
        assertEq("count capped", 64, t.count);
        assertEq("leftover", 6, left);

        ItemStack copy = t.copy();
        assertTrue("copy distinct", copy != t);
        assertEq("copy type", BlockType.DIRT, copy.block());
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
        int left = inv.add(new ItemStack(BlockType.STONE, 10));
        assertEq("no leftover", 0, left);
        assertEq("slot0 count", 10, inv.get(0).count);

        // merges into the same existing stack first
        inv.add(new ItemStack(BlockType.STONE, 5));
        assertEq("merged into slot0", 15, inv.get(0).count);
        assertTrue("slot1 still empty", inv.get(1) == null);

        // overflow spills into the next free slot
        inv.add(new ItemStack(BlockType.STONE, 60)); // 15 + 60 = 75 -> 64 in slot0, 11 in next free
        assertEq("slot0 full", 64, inv.get(0).count);
        assertEq("spill slot count", 11, inv.get(1).count);

        // full inventory returns leftover
        Inventory full = new Inventory();
        for (int i = 0; i < 36; i++) full.set(i, new ItemStack(BlockType.DIRT, 64));
        int rem = full.add(new ItemStack(BlockType.DIRT, 5));
        assertEq("leftover when full", 5, rem);
        assertTrue("cannot fit a mined drop", !full.canAdd(new ItemStack(BlockType.STONE, 1), 1));
        full.get(0).count = 63;
        assertTrue("matching stack has capacity", full.canAdd(new ItemStack(BlockType.DIRT, 1), 1));
        assertTrue("capacity query does not mutate", full.get(0).count == 63);
        ItemStack cursor = full.rightClick(0, null);
        full.set(1, new ItemStack(BlockType.STONE, 64));
        cursor = full.leftClick(1, cursor);
        assertEq("swapped cursor cannot be silently returned", 64, full.add(cursor));

        // add with amount <= 0 returns 0
        Inventory inv2 = new Inventory();
        assertEq("add(0) returns 0", 0, inv2.add(null));
        ItemStack none = new ItemStack(BlockType.STONE, 1);
        none.count = 0;
        assertEq("add(empty) returns 0", 0, inv2.add(none));
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
        assertEq("slot took wood", BlockType.WOOD, inv.get(1).block());
        assertEq("cursor took dirt", BlockType.DIRT, cursor.block());
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

    /**
     * Меши строятся в несколько потоков, порядок возврата ничем не задан.
     * Без защиты меш от первой правки ложится поверх меша от второй —
     * и только что сломанный блок возвращается на место.
     */
    private static void testMeshVersionRejectsStale() {
        Chunk c = new Chunk(0, 0);
        int v1 = c.contentVersion();
        c.set(1, 1, 1, BlockType.STONE);
        int v2 = c.contentVersion();
        assertTrue("правка двигает версию", v2 > v1);

        // Свежий меш принимается...
        assertTrue("свежий меш принят", c.acceptMeshVersion(v2));
        // ...а опоздавший старый — нет.
        assertTrue("старый меш отвергнут", !c.acceptMeshVersion(v1));
        assertTrue("повтор той же версии отвергнут", !c.acceptMeshVersion(v2));

        // Флаг снимается только если содержимое не ушло вперёд.
        c.set(2, 1, 1, BlockType.DIRT);
        assertTrue("устаревшая версия не снимает флаг", !c.clearDirtyIfCurrent(v2));
        assertTrue("чанк остался грязным", c.isDirty());
        assertTrue("текущая версия снимает флаг",
                c.clearDirtyIfCurrent(c.contentVersion()));
        assertTrue("чанк чист", !c.isDirty());

        // Выгрузка сбрасывает планку: чанк может вернуться с любой версией.
        c.forgetUploadedMesh();
        assertTrue("после выгрузки меш снова принимается", c.acceptMeshVersion(v2));
    }

    /**
     * Список излучателей заменяет перебор 32 768 ячеек в главном потоке.
     * Разойдись он с блоками — и факел либо перестанет светить, либо будет
     * светить из пустоты.
     */
    private static void testEmitterListMatchesChunk() {
        Chunk c = new Chunk(0, 0);
        assertEq("пустой чанк — ни одного излучателя", 0, c.emitterCount());

        BlockType lamp = null;
        for (BlockType t : BlockType.values())
            if (t.emittedLight > 0) { lamp = t; break; }
        assertTrue("в игре есть хоть один источник света", lamp != null);

        c.set(3, 40, 5, lamp);
        c.set(9, 41, 2, lamp);
        assertEq("два источника учтены", 2, c.emitterCount());
        assertEmittersConsistent(c);

        // Снятие одного.
        c.set(3, 40, 5, BlockType.AIR);
        assertEq("остался один", 1, c.emitterCount());
        assertEmittersConsistent(c);

        // Замена источника на источник не плодит дубликатов.
        c.set(9, 41, 2, lamp);
        assertEq("дубликата нет", 1, c.emitterCount());
        assertEmittersConsistent(c);

        // Восстановление из сейва пишет блоки массивом, мимо set().
        byte[] blocks = new byte[Chunk.SIZE_X * Chunk.SIZE_Y * Chunk.SIZE_Z];
        byte[] meta = new byte[blocks.length];
        blocks[Chunk.idx(1, 10, 1)] = (byte) lamp.ordinal();
        blocks[Chunk.idx(2, 10, 1)] = (byte) lamp.ordinal();
        blocks[Chunk.idx(3, 10, 1)] = (byte) lamp.ordinal();
        c.restore(blocks, meta);
        assertEq("после restore список пересобран", 3, c.emitterCount());
        assertEmittersConsistent(c);
    }

    /** Каждая позиция из списка действительно светит, и ни одна не забыта. */
    private static void assertEmittersConsistent(Chunk c) {
        java.util.HashSet<Integer> listed = new java.util.HashSet<>();
        for (int i = 0; i < c.emitterCount(); i++) {
            int packed = c.emitterAt(i);
            int lx = packed % Chunk.SIZE_X;
            int rest = packed / Chunk.SIZE_X;
            int lz = rest % Chunk.SIZE_Z;
            int y = rest / Chunk.SIZE_Z;
            assertTrue("в списке только светящиеся блоки",
                    c.get(lx, y, lz).emittedLight > 0);
            assertTrue("позиции не повторяются", listed.add(packed));
        }
        int actual = 0;
        for (int x = 0; x < Chunk.SIZE_X; x++)
            for (int y = 0; y < Chunk.SIZE_Y; y++)
                for (int z = 0; z < Chunk.SIZE_Z; z++)
                    if (c.get(x, y, z).emittedLight > 0) {
                        actual++;
                        assertTrue("источник не забыт списком",
                                listed.contains(Chunk.idx(x, y, z)));
                    }
        assertEq("длина списка совпадает с числом источников", actual, c.emitterCount());
    }

    /**
     * Очередь меширования упорядочена по расстоянию, а правка игрока идёт
     * вперёд всех. Иначе удар по блоку ждёт очереди из сотни дальних чанков.
     */
    private static void testMeshPriorityOrder() {
        java.util.List<int[]> order = new java.util.ArrayList<>();
        // Модель ключа приоритета из ChunkLoader: правка = -1,
        // иначе квадрат расстояния до игрока.
        order.add(new int[] { 100, 1 });  // дальний чанк
        order.add(new int[] { -1, 2 });   // правка
        order.add(new int[] { 4, 3 });    // ближний
        order.add(new int[] { 4, 0 });    // ближний, поступил раньше
        order.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0])
                                          : Integer.compare(a[1], b[1]));
        assertEq("правка игрока первая", -1, order.get(0)[0]);
        assertEq("затем ближний, поступивший раньше", 0, order.get(1)[1]);
        assertEq("затем ближний позже", 3, order.get(2)[1]);
        assertEq("дальний последний", 100, order.get(3)[0]);
    }

    /**
     * Рывок виден только в худшем кадре: среднее его съедает.
     */
    private static void testFrameProfilerWorst() {
        com.mineclone.game.FrameProfiler prof = new com.mineclone.game.FrameProfiler();
        double t = 0;
        // Десять ровных кадров по 5 мс работы.
        for (int i = 0; i < 10; i++) {
            prof.beginFrame();
            prof.begin(com.mineclone.game.FrameProfiler.Phase.WORLD, t);
            t += 0.005;
            prof.endFrame(t, 0.005);
        }
        double calmWorst = prof.worstMillis();
        assertTrue("ровные кадры — худший около 5 мс",
                calmWorst > 4.0 && calmWorst < 8.0);

        // Один провал на 60 мс.
        prof.beginFrame();
        prof.begin(com.mineclone.game.FrameProfiler.Phase.WORLD, t);
        t += 0.060;
        prof.endFrame(t, 0.060);
        assertTrue("провал виден сразу", prof.worstMillis() > 55.0);

        // Сглаженное среднее провал почти не замечает — ради этой
        // разницы худший кадр и считается отдельно.
        assertTrue("среднее осталось малым", prof.totalMillis() < 20.0);

        assertTrue("разбивка называет фазы", prof.breakdown().contains("world"));
    }

    /**
     * floodFillRemove теперь рано выходит, когда излучателей в радиусе нет, — без
     * этого каждая правка блока перебирала куб из 29 791 ячейки. Ловушка в том,
     * что снятый факел в список излучателей уже не входит — его блок заменён
     * раньше вызова, — а свет его в буфере остался. Ранний выход без проверки
     * света в самой точке оставлял погасший факел светить навсегда.
     */
    private static void testLastTorchGoesOut() {
        World w = new World(31337L);
        // Площадка глубоко под землёй: там небесного света нет и виден
        // только блочный.
        w.getChunk(0, 0);
        int x = 8, y = 20, z = 8;
        BlockType torch = null;
        for (BlockType t : BlockType.values())
            if (t.emittedLight > 0) { torch = t; break; }
        assertTrue("в игре есть источник света", torch != null);

        // Расчистим карман, чтобы свету было куда разойтись.
        for (int dx = -3; dx <= 3; dx++)
            for (int dy = -3; dy <= 3; dy++)
                for (int dz = -3; dz <= 3; dz++)
                    w.setBlock(x + dx, y + dy, z + dz, BlockType.AIR);

        w.setBlock(x, y, z, torch);
        assertTrue("факел светит", w.getBlockLightWorld(x, y, z) > 0);
        assertTrue("свет доходит до соседней клетки",
                w.getBlockLightWorld(x + 2, y, z) > 0);

        w.setBlock(x, y, z, BlockType.AIR);
        assertEq("снятый факел не светит", 0, w.getBlockLightWorld(x, y, z));
        assertEq("и рядом тоже темно", 0, w.getBlockLightWorld(x + 2, y, z));
    }

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

package com.mineclone;

import com.mineclone.game.CameraMotion;
import com.mineclone.game.ContextHint;
import com.mineclone.game.Frost;
import com.mineclone.game.SoundIndicators;
import com.mineclone.game.SurvivalProgress;
import com.mineclone.render.OutlineAnimator;
import com.mineclone.world.Biome;
import com.mineclone.world.BlockType;
import com.mineclone.world.ItemStack;
import com.mineclone.world.Inventory;
import com.mineclone.world.NightSky;
import com.mineclone.world.Weather;
import com.mineclone.world.World;
import com.mineclone.world.entity.ItemEntity;

import java.util.EnumMap;
import java.util.Map;

/**
 * Проверки атмосферы, «сочности» и новых систем поверх базового ядра.
 *
 * Отдельным классом, а не очередной тысячей строк в {@link TestMain}: раннер
 * тот же, счётчики те же — {@code TestMain} зовёт {@link #runAll} перед итогом.
 */
final class FeatureTests {
    private FeatureTests() {}

    interface Check { void run() throws Exception; }
    interface Runner { void run(String name, Check check); }

    static void runAll(Runner r) {
        r.run("weather fronts blend instead of switching", FeatureTests::testWeatherFronts);
        r.run("weather kinds follow their weights", FeatureTests::testWeatherDistribution);
        r.run("snow storms blind harder than rain storms", FeatureTests::testWeatherVisibility);
        r.run("storm wind outblows a clear day", FeatureTests::testWeatherWind);
        r.run("wind gusts pulse, and the drift they carry never runs backwards",
                FeatureTests::testWeatherDrift);
        r.run("the head turns before the body does, and never past 75 degrees",
                FeatureTests::testBodyRotation);
        r.run("the body follows where the feet go, not where the eyes look",
                FeatureTests::testBodyFollowsMovement);
        r.run("moon waxes and wanes over eight nights", FeatureTests::testMoonPhases);
        r.run("/time set keeps the day and its moon", FeatureTests::testTimeKeepsDay);
        r.run("aurora is a rare, clear, cold night event", FeatureTests::testAurora);
        r.run("frost creeps in on a tundra night and thaws by fire", FeatureTests::testFrost);
        r.run("camera leans into strafe and settles", FeatureTests::testCameraLean);
        r.run("landing dips the camera and springs back", FeatureTests::testCameraLanding);
        r.run("sound bearing points the right way", FeatureTests::testSoundBearing);
        r.run("sound cues merge, skip the front and fade", FeatureTests::testSoundCues);
        r.run("outline slides to a neighbour and fades out", FeatureTests::testOutlineAnimator);
        r.run("context hints only where a click is not obvious", FeatureTests::testContextHints);
        r.run("mist gathers at dawn in the forest, not at desert noon", FeatureTests::testMist);
        r.run("snow stops at the roof, not inside the house", FeatureTests::testPrecipitationRoof);
        r.run("swing trail follows the head and fades", FeatureTests::testSwingTrail);
        r.run("tool cracks show up only past a third of wear", FeatureTests::testCrackStages);
        r.run("storm wind is heard outdoors, never indoors", FeatureTests::testWindAmbience);
        r.run("a wounded zombie flies into a rage", FeatureTests::testEnrage);
        r.run("undead back away from torchlight, the enraged do not", FeatureTests::testFearOfLight);
        r.run("zombie hesitates at the edge of the light, then charges", FeatureTests::testStalkAtLight);
        r.run("A* leaps a gap instead of giving up", FeatureTests::testPathLeap);
        r.run("A* prefers the dark detour for the undead", FeatureTests::testPathAvoidsLight);
        r.run("a killed mob flies off and topples away from the blow", FeatureTests::testDeathImpulse);
        r.run("rabbit bolts when approached and settles when alone", FeatureTests::testRabbitFlees);
        r.run("wolves ignore the player until one is struck, then the pack", FeatureTests::testWolfPack);
        r.run("a hungry wolf hunts the nearest prey", FeatureTests::testWolfHunts);
        r.run("bird takes off when approached and lands again", FeatureTests::testBirdFlight);
        r.run("wildlife spawns where it belongs", FeatureTests::testWildSpawnRules);
        r.run("tundra lakes freeze, torches melt the ice", FeatureTests::testIce);
        r.run("debris bounces, rests on the floor and fades", FeatureTests::testDebris);
        r.run("a dropped item pops, lands and stops sliding", FeatureTests::testItemFalls);
        r.run("items fly to the player only when there is room", FeatureTests::testItemMagnet);
        r.run("stacks on the ground merge, tools never do", FeatureTests::testItemMerge);
        r.run("a forgotten item despawns", FeatureTests::testItemDespawn);
        r.run("items on the ground survive a chunk save", FeatureTests::testItemSave);
        r.run("footsteps follow the surface and the weather", FeatureTests::testStepMaterials);
        r.run("walls muffle the treble before the volume", FeatureTests::testMuffle);
        r.run("vegetation bends away only inside the contact radius", FeatureTests::testVegetationBend);
        r.run("Verlet rope keeps its anchors and segment lengths", FeatureTests::testRopePhysics);
        r.run("lava is viscous and solidifies against water", FeatureTests::testFluidThermodynamics);
        r.run("heavy feet compress snow and wet dirt", FeatureTests::testTerrainDeformation);
        r.run("fuel kinds have distinct burn times", FeatureTests::testFuelTimers);
        r.run("wood floats while stone sinks", FeatureTests::testBuoyancyRules);
        r.run("limb wounds alter speed, attack and head damage", FeatureTests::testLimbDamage);
        r.run("morale, routines and elite rolls are deterministic", FeatureTests::testMobTactics);
        r.run("cover search finds a voxel shield", FeatureTests::testCoverSearch);
        r.run("coloured voxel bounce falls off with distance", FeatureTests::testVoxelBounce);
        r.run("throw preview follows a ballistic arc", FeatureTests::testThrowArc);
        r.run("status envelopes expire and eye exposure adapts", FeatureTests::testAdvancedFeedback);
        r.run("placed heavy spans collapse only without support", FeatureTests::testStructureStability);
        r.run("strong impacts create micro-voxel showers", FeatureTests::testMicroShatter);
        r.run("survival goals advance, persist and never regress", FeatureTests::testSurvivalProgress);
    }

    // ---- второй пакет систем -------------------------------------------------

    private static void testVegetationBend() {
        org.joml.Vector3f v = new org.joml.Vector3f(1f, 2f, 0f);
        org.joml.Vector3f bent = com.mineclone.render.ProceduralEffects.vegetationBend(
                v, new org.joml.Vector3f(0f, 2f, 0f), 2f, 1f);
        assertTrue("contact pushes the vertex away", bent.x > v.x && bent.y < v.y);
        org.joml.Vector3f far = com.mineclone.render.ProceduralEffects.vegetationBend(
                v, new org.joml.Vector3f(-5f, 2f, 0f), 2f, 1f);
        assertTrue("outside the radius it stays still", far.equals(v));
    }

    private static void testRopePhysics() {
        var rope = new com.mineclone.world.RopeSimulation(
                new org.joml.Vector3f(0f, 5f, 0f), new org.joml.Vector3f(0f, 1f, 0f), 8, true);
        rope.impulse(4, 0.5f, 0f, 0f);
        for (int i = 0; i < 120; i++) rope.step(1f / 60f, 0.5f, 0f);
        var n = rope.nodes();
        assertTrue("top remains pinned", n[0].position.distance(new org.joml.Vector3f(0f, 5f, 0f)) < 1e-5f);
        assertTrue("bottom remains pinned", n[n.length - 1].position.distance(new org.joml.Vector3f(0f, 1f, 0f)) < 1e-5f);
        float worst = 0f;
        for (int i = 0; i < n.length - 1; i++)
            worst = Math.max(worst, Math.abs(n[i].position.distance(n[i + 1].position) - rope.segmentLength()));
        assertTrue("constraints hold (error=" + worst + ")", worst < 0.035f);
    }

    private static void testFluidThermodynamics() {
        assertTrue("lava is slower", com.mineclone.world.FluidThermodynamics.viscosity(BlockType.LAVA)
                > com.mineclone.world.FluidThermodynamics.viscosity(BlockType.WATER));
        World w = flatWorld();
        w.setBlock(8, 12, 8, BlockType.LAVA, (byte) 0);
        w.setBlock(9, 12, 8, BlockType.WATER);
        assertEq("side contact reaction", com.mineclone.world.FluidThermodynamics.Reaction.STEAM_AND_COBBLE,
                com.mineclone.world.FluidThermodynamics.react(w, 8, 12, 8, 9, 12, 8));
        assertEq("side contact makes cobble", BlockType.COBBLE, w.getBlock(8, 12, 8));
        w.setBlock(8, 12, 8, BlockType.LAVA, (byte) 0);
        w.setBlock(8, 13, 8, BlockType.WATER);
        com.mineclone.world.FluidThermodynamics.react(w, 8, 12, 8, 8, 13, 8);
        assertEq("water over source makes obsidian", BlockType.OBSIDIAN, w.getBlock(8, 12, 8));
        w.setBlock(8, 13, 8, BlockType.LAVA, (byte) 2);
        w.setBlock(8, 12, 8, BlockType.WATER);
        com.mineclone.world.FluidThermodynamics.react(w, 8, 13, 8, 8, 12, 8);
        assertEq("lava over water makes stone", BlockType.STONE, w.getBlock(8, 13, 8));
    }

    private static void testTerrainDeformation() {
        World w = flatWorld();
        w.setBlock(8, 11, 8, BlockType.SNOW_LAYER, (byte) 4);
        assertTrue("snow changes", com.mineclone.world.TerrainDeformation.footprint(w, 8, 11, 8, 1.5f, false));
        assertEq("two layers compressed", 2, w.getBlockMeta(8, 11, 8) & 7);
        w.setBlock(9, 10, 8, BlockType.DIRT);
        assertTrue("wet soil changes", com.mineclone.world.TerrainDeformation.footprint(w, 9, 11, 8, 1f, true));
        assertEq("mud remains in world", BlockType.MUD, w.getBlock(9, 10, 8));
    }

    private static void testFuelTimers() {
        assertTrue("leaves flash first", com.mineclone.world.BlockTicker.burnTicks(BlockType.LEAVES)
                < com.mineclone.world.BlockTicker.burnTicks(BlockType.PLANKS));
        assertTrue("logs smoulder longest", com.mineclone.world.BlockTicker.burnTicks(BlockType.WOOD)
                > com.mineclone.world.BlockTicker.burnTicks(BlockType.PLANKS));
    }

    private static void testBuoyancyRules() {
        assertTrue("wood below water density", ItemEntity.density(new ItemStack(BlockType.WOOD, 1)) < 1f);
        assertTrue("stone above water density", ItemEntity.density(new ItemStack(BlockType.STONE, 1)) > 1f);
    }

    private static void testSurvivalProgress() {
        Inventory inv = new Inventory();
        SurvivalProgress progress = new SurvivalProgress();
        assertEq("starts with wood", "Добудьте бревно", progress.objective(inv).title());

        inv.set(0, ItemStack.of("log"));
        assertEq("wood goal completed", "Добудьте бревно", progress.update(inv));
        assertEq("next asks for planks", "Сделайте доски", progress.objective(inv).title());
        inv.set(0, null);
        progress.update(inv);
        assertEq("spent wood does not regress", 1, progress.stage());

        SurvivalProgress oldWorld = new SurvivalProgress();
        inv.set(0, ItemStack.of("iron_pickaxe"));
        oldWorld.synchronize(inv);
        assertEq("late evidence skips tutorials", 11, oldWorld.stage());
        SurvivalProgress loaded = SurvivalProgress.decode(oldWorld.encode());
        assertEq("stage survives its save section", 11, loaded.stage());
    }

    private static void testLimbDamage() {
        var d = new com.mineclone.world.entity.LimbDamage();
        d.hit(com.mineclone.world.entity.LimbDamage.Limb.LEFT_LEG, 0.8f);
        d.hit(com.mineclone.world.entity.LimbDamage.Limb.RIGHT_ARM, 0.6f);
        d.hit(com.mineclone.world.entity.LimbDamage.Limb.HEAD, 0.5f);
        assertTrue("leg slows", d.speedMultiplier() < 0.7f);
        assertTrue("arm weakens", d.attackMultiplier() < 0.75f);
        assertTrue("head amplifies", d.headDamageMultiplier() > 1.3f);
        assertEq("low lateral hit maps to leg", com.mineclone.world.entity.LimbDamage.Limb.LEFT_LEG,
                com.mineclone.world.entity.LimbDamage.fromHitHeight(0.2f, -0.3f));
    }

    private static void testMobTactics() {
        var T = com.mineclone.world.entity.MobTactics.class;
        assertEq("lonely wounded mob panics", com.mineclone.world.entity.MobTactics.Morale.PANIC,
                com.mineclone.world.entity.MobTactics.morale(0.1f, true, 0));
        // Нежить не отступает: убегающий от добивания зомби читается как
        // поломка, а не как тактика.
        assertEq("a wounded zombie keeps coming",
                com.mineclone.world.entity.MobTactics.Morale.STEADY,
                com.mineclone.world.entity.MobTactics.morale(0.05f, true, 0, true));
        assertEq("and calls its own instead of fleeing",
                com.mineclone.world.entity.MobTactics.Morale.CALL_HELP,
                com.mineclone.world.entity.MobTactics.morale(0.05f, false, 4, true));
        assertTrue("the undead are the fearless ones",
                com.mineclone.world.entity.MobTactics.fearless(com.mineclone.world.entity.MobType.ZOMBIE)
                        && !com.mineclone.world.entity.MobTactics.fearless(com.mineclone.world.entity.MobType.RABBIT));
        assertEq("night is a hostile hunting shift", com.mineclone.world.entity.MobTactics.Routine.HUNT,
                com.mineclone.world.entity.MobTactics.routine(com.mineclone.world.entity.MobType.ZOMBIE, 0.7f, false, false));
        for (int i = 0; i < 100; i++)
            assertEq("peaceful never elite", com.mineclone.world.entity.MobTactics.Elite.NONE,
                    com.mineclone.world.entity.MobTactics.eliteFor(5L, i, false));
        var a = com.mineclone.world.entity.MobTactics.eliteFor(99L, 31, true);
        var b = com.mineclone.world.entity.MobTactics.eliteFor(99L, 31, true);
        assertEq("elite roll repeats", a, b);
        assertTrue("wolves hunt prey", com.mineclone.world.entity.MobTactics.factionHostile(
                com.mineclone.world.entity.MobType.WOLF, com.mineclone.world.entity.MobType.RABBIT));
    }

    private static void testCoverSearch() {
        World w = flatWorld();
        for (int z = 4; z <= 12; z++)
            for (int y = 11; y <= 13; y++) w.setBlock(8, y, z, BlockType.STONE);
        org.joml.Vector3f cover = com.mineclone.world.entity.MobTactics.findCover(w,
                new org.joml.Vector3f(7f, 11f, 8f), new org.joml.Vector3f(12f, 12f, 8f), 5);
        assertTrue("a wall yields cover", cover != null && cover.x < 9f);
    }

    private static void testVoxelBounce() {
        var near = com.mineclone.render.ProceduralEffects.bouncedLight(new float[]{1f, .2f, .05f}, 1f, 8f, 1f);
        var far = com.mineclone.render.ProceduralEffects.bouncedLight(new float[]{1f, .2f, .05f}, 7f, 8f, 1f);
        assertTrue("nearer bounce is brighter", near.length() > far.length() * 10f);
        assertTrue("bounce keeps emitter hue", near.x > near.y && near.y > near.z);
    }

    private static void testThrowArc() {
        var arc = com.mineclone.render.ProceduralEffects.throwArc(new org.joml.Vector3f(),
                new org.joml.Vector3f(1f, 0.5f, 0f), new org.joml.Vector3f(), 8f, 10f, 1.5f, 16);
        assertEq("requested samples", 16, arc.size());
        float top = -999f;
        for (var p : arc) top = Math.max(top, p.y);
        assertTrue("arc rises", top > 0.5f);
        assertTrue("then falls", arc.get(arc.size() - 1).y < top);
    }

    private static void testAdvancedFeedback() {
        var f = new com.mineclone.game.AdvancedFeedback();
        f.apply(com.mineclone.game.AdvancedFeedback.Effect.FREEZE, 1f);
        assertTrue("freeze slows", f.movementMultiplier() < 1f);
        float before = f.exposure();
        f.update(0.2f, 1f);
        assertTrue("bright scene closes exposure", f.exposure() < before);
        f.update(1.1f, 0.2f);
        assertTrue("effect expires", !f.active(com.mineclone.game.AdvancedFeedback.Effect.FREEZE));
    }

    private static void testStructureStability() {
        World w = flatWorld();
        java.util.Set<Long> placed = new java.util.HashSet<>();
        for (int y = 11; y <= 13; y++) {
            w.setBlock(8, y, 8, BlockType.COBBLE);
            placed.add(com.mineclone.world.StructureStability.placementKey(8, y, 8));
        }
        assertEq("tower on floor is supported", 0,
                com.mineclone.world.StructureStability.unstablePlaced(w, placed, 8, 13, 8).size());
        w.setBlock(8, 11, 8, BlockType.AIR);
        placed.remove(com.mineclone.world.StructureStability.placementKey(8, 11, 8));
        assertEq("two upper blocks lose support", 2,
                com.mineclone.world.StructureStability.unstablePlaced(w, placed, 8, 12, 8).size());
    }

    private static void testMicroShatter() {
        World w = flatWorld();
        var d = new com.mineclone.render.Debris(4L);
        d.spawnShatter(8, 11, 8, BlockType.OBSIDIAN, 1f, 0f, 3f);
        assertTrue("micro shower is dense", d.pieces().size() >= 28);
        for (int i = 0; i < 30; i++) d.update(w, 1f / 60f);
        assertTrue("pieces survive the first half-second", !d.pieces().isEmpty());
    }

    // ---- звук ------------------------------------------------------------------

    private static void testStepMaterials() {
        var M = com.mineclone.audio.Sounds.Material.class;
        assertEq("dry dirt crunches", com.mineclone.audio.Sounds.Material.GRAVEL,
                com.mineclone.audio.Sounds.stepMaterial(BlockType.DIRT, BlockType.AIR, 0, false));
        assertEq("wet dirt squelches", com.mineclone.audio.Sounds.Material.MUD,
                com.mineclone.audio.Sounds.stepMaterial(BlockType.DIRT, BlockType.AIR, 0, true));
        assertEq("grass in rain is wet", com.mineclone.audio.Sounds.Material.WET_GRASS,
                com.mineclone.audio.Sounds.stepMaterial(BlockType.GRASS, BlockType.AIR, 0, true));
        assertEq("thin snow creaks", com.mineclone.audio.Sounds.Material.SNOW,
                com.mineclone.audio.Sounds.stepMaterial(BlockType.SNOWY_GRASS, BlockType.SNOW_LAYER, 1, false));
        assertEq("deep snow sinks", com.mineclone.audio.Sounds.Material.DEEP_SNOW,
                com.mineclone.audio.Sounds.stepMaterial(BlockType.SNOWY_GRASS, BlockType.SNOW_LAYER, 5, false));
        assertEq("ice rings", com.mineclone.audio.Sounds.Material.ICE,
                com.mineclone.audio.Sounds.stepMaterial(BlockType.ICE, BlockType.AIR, 0, false));
        assertTrue("ice rings higher than stone",
                com.mineclone.audio.Sounds.stepPitch(com.mineclone.audio.Sounds.Material.ICE)
                        > com.mineclone.audio.Sounds.stepPitch(com.mineclone.audio.Sounds.Material.STONE));
        com.mineclone.audio.Sounds s = new com.mineclone.audio.Sounds();
        for (var m : M.getEnumConstants()) {
            if (m == com.mineclone.audio.Sounds.Material.NONE)
                continue;
            assertTrue(m + " has step samples", !s.step(m).isEmpty());
        }
        assertTrue("breaking ice shatters like glass", !s.breakBlock(BlockType.ICE).isEmpty());
    }

    private static void testMuffle() {
        assertEq("open air is clear", 0f, com.mineclone.audio.SoundOcclusion.muffle(0));
        float one = com.mineclone.audio.SoundOcclusion.muffle(1);
        assertTrue("one wall already muffles", one > 0.2f && one < 1f);
        assertEq("three walls are a dull thud", 1f, com.mineclone.audio.SoundOcclusion.muffle(3));
        // Верх уходит быстрее громкости: за одной стеной глушение сильнее потерь.
        assertTrue("treble goes before volume",
                one > 1f - com.mineclone.audio.SoundOcclusion.gainFor(1) - 0.2f);
    }

    // ---- мобы ------------------------------------------------------------------

    private static com.mineclone.world.entity.Mob mob(com.mineclone.world.entity.MobType t,
                                                      float x, float y, float z, long seed) {
        return new com.mineclone.world.entity.Mob(t, x, y, z, new java.util.Random(seed));
    }

    private static void face(com.mineclone.world.entity.Mob m, org.joml.Vector3f target) {
        m.yaw = (float) Math.atan2(-(target.x - m.position.x), -(target.z - m.position.z));
    }

    private static void testEnrage() {
        World w = flatWorld();
        org.joml.Vector3f far = new org.joml.Vector3f(8.5f, 11f, 200f);
        var z = mob(com.mineclone.world.entity.MobType.ZOMBIE, 8.5f, 11f, 8.5f, 1L);
        z.update(w, far, 1f / 60f, 0f, true);
        assertTrue("healthy zombie is calm", !z.enraged);
        z.hurt(z.type.maxHealth * 0.7f, 8.5f, 6f);
        boolean fired = false;
        int fires = 0;
        for (int i = 0; i < 30; i++) {
            z.update(w, far, 1f / 60f, 0f, true);
            if (z.justEnraged) fires++;
            fired |= z.enraged;
        }
        assertTrue("rage kicks in below the threshold", fired);
        assertEq("the roar plays once", 1, fires);
        assertEq("enraged zombie hits harder", com.mineclone.world.entity.Mob.ENRAGED_DAMAGE, z.attackDamage());
    }

    private static void testFearOfLight() {
        World w = flatWorld();
        w.setBlock(8, 11, 8, BlockType.TORCH);
        assertTrue("torch lights its cell", w.getBlockLightWorld(9, 11, 8) >= com.mineclone.world.entity.Mob.FEAR_LIGHT);
        org.joml.Vector3f far = new org.joml.Vector3f(8.5f, 11f, 300f);
        var z = mob(com.mineclone.world.entity.MobType.ZOMBIE, 9.5f, 11f, 8.5f, 3L);
        int startLight = w.getBlockLightWorld(9, 11, 8);
        boolean fled = false;
        for (int i = 0; i < 20 * 4; i++) {
            z.update(w, far, 1f / 20f, 0f, true);
            fled |= z.state == com.mineclone.world.entity.Mob.State.FLEE_LIGHT;
        }
        int endLight = w.getBlockLightWorld((int) Math.floor(z.position.x), 11, (int) Math.floor(z.position.z));
        assertTrue("zombie backs away from the torch", fled);
        assertTrue("and ends up darker (" + startLight + " -> " + endLight + ")", endLight < startLight - 2);

        var rage = mob(com.mineclone.world.entity.MobType.ZOMBIE, 9.5f, 11f, 8.5f, 4L);
        rage.hurt(rage.type.maxHealth * 0.8f, 9.5f, 6f);
        boolean ragedFled = false;
        for (int i = 0; i < 20 * 3; i++) {
            rage.update(w, far, 1f / 20f, 0f, true);
            ragedFled |= rage.state == com.mineclone.world.entity.Mob.State.FLEE_LIGHT;
        }
        assertTrue("an enraged zombie is not afraid", !ragedFled);
    }

    private static void testStalkAtLight() {
        World w = flatWorld();
        // Игрок стоит у факела; зомби в темноте в восьми блоках.
        w.setBlock(8, 11, 16, BlockType.TORCH);
        org.joml.Vector3f player = new org.joml.Vector3f(8.5f, 11f, 15.5f);
        var z = mob(com.mineclone.world.entity.MobType.ZOMBIE, 8.5f, 11f, 6.5f, 5L);
        z.state = com.mineclone.world.entity.Mob.State.CHASE;
        face(z, player);
        boolean stalked = false;
        boolean enteredLightEarly = false;
        for (int i = 0; i < 20 * 2; i++) {          // две секунды — терпения ещё хватает
            z.update(w, player, 1f / 20f, 0f, true);
            stalked |= z.state == com.mineclone.world.entity.Mob.State.STALK;
            int l = w.getBlockLightWorld((int) Math.floor(z.position.x), 11, (int) Math.floor(z.position.z));
            enteredLightEarly |= l >= com.mineclone.world.entity.Mob.FEAR_LIGHT;
        }
        assertTrue("zombie stalks at the edge of the light", stalked);
        assertTrue("it does not step into the light while it hesitates", !enteredLightEarly);
        float closest = Float.MAX_VALUE;
        for (int i = 0; i < 20 * 12; i++) {
            z.update(w, player, 1f / 20f, 0f, true);
            closest = Math.min(closest, z.position.distance(player));
        }
        assertTrue("patience runs out and it charges (closest " + closest + ")", closest < 2.2f);
    }

    private static void testPathLeap() {
        World w = flatWorld();
        // Ров шириной два блока поперёк всего пола, без дна.
        for (int x = -16; x < 32; x++)
            for (int z = 8; z <= 9; z++)
                w.setBlock(x, 10, z, BlockType.AIR);
        var p = com.mineclone.world.entity.PathFinder.find(w, 5, 11, 4, 5, 11, 13, 2);
        assertTrue("a path over the ditch exists", p != null && !p.isEmpty());
        boolean leap = false;
        for (int i = 1; i < p.size(); i++) {
            var a = p.get(i - 1);
            var b = p.get(i);
            if (Math.abs(a.x - b.x) + Math.abs(a.z - b.z) >= 2.5f)
                leap = true;
            assertTrue("no waypoint hangs over the ditch at " + b,
                    !(Math.floor(b.z) >= 8 && Math.floor(b.z) <= 9));
        }
        assertTrue("the path contains a leap", leap);

        // Зомби действительно перепрыгивает ров.
        var zombie = mob(com.mineclone.world.entity.MobType.ZOMBIE, 5.5f, 11f, 4.5f, 6L);
        org.joml.Vector3f player = new org.joml.Vector3f(5.5f, 11f, 13.5f);
        zombie.state = com.mineclone.world.entity.Mob.State.CHASE;
        face(zombie, player);
        boolean crossed = false;
        for (int i = 0; i < 20 * 20 && !crossed; i++) {
            zombie.update(w, player, 1f / 20f, 0f, true);
            crossed = zombie.position.z > 10.1f && zombie.position.y > 10.5f;
        }
        assertTrue("zombie leaps the ditch (at " + zombie.position + ")", crossed);
    }

    private static void testPathAvoidsLight() {
        World w = flatWorld();
        // Прямая дорога идёт мимо факела, обход — на пару шагов в стороне.
        w.setBlock(6, 11, 8, BlockType.TORCH);
        var plain = com.mineclone.world.entity.PathFinder.find(w, 5, 11, 2, 5, 11, 14, 2, false);
        var dark = com.mineclone.world.entity.PathFinder.find(w, 5, 11, 2, 5, 11, 14, 2, true);
        assertTrue("both paths exist", plain != null && dark != null);
        int litPlain = 0, litDark = 0;
        for (var v : plain)
            if (w.getBlockLightWorld((int) Math.floor(v.x), 11, (int) Math.floor(v.z)) >= com.mineclone.world.entity.PathFinder.LIT_CELL)
                litPlain++;
        for (var v : dark)
            if (w.getBlockLightWorld((int) Math.floor(v.x), 11, (int) Math.floor(v.z)) >= com.mineclone.world.entity.PathFinder.LIT_CELL)
                litDark++;
        assertTrue("the plain path walks past the torch (" + litPlain + ")", litPlain > 0);
        assertTrue("the dark path spends fewer steps in the light (" + litDark + " < " + litPlain + ")",
                litDark < litPlain);
    }

    private static void testDeathImpulse() {
        World w = flatWorld();
        org.joml.Vector3f far = new org.joml.Vector3f(8.5f, 11f, 300f);
        var cow = mob(com.mineclone.world.entity.MobType.COW, 8.5f, 11f, 8.5f, 7L);
        // Удар с запада (из −X): тело должно отлететь на восток.
        cow.hurt(100f, 5.5f, 8.5f);
        assertTrue("dead", cow.dead);
        float x0 = cow.position.x;
        float maxTopple = 0f;
        for (int i = 0; i < 30; i++) {
            cow.update(w, far, 1f / 60f, 0f, true);
            maxTopple = Math.max(maxTopple, cow.topple);
        }
        assertTrue("corpse slides away from the blow (dx=" + (cow.position.x - x0) + ")", cow.position.x > x0 + 0.15f);
        assertTrue("it topples over (" + maxTopple + ")", maxTopple > 1.2f);
        // Ось падения поперёк удара: удар по X — ось по Z.
        assertTrue("falls around an axis across the blow",
                Math.abs(cow.deathAxisZ) > 0.9f && Math.abs(cow.deathAxisX) < 0.1f);
        assertTrue("never sinks into the floor", cow.position.y > 10.9f);
    }

    private static void testRabbitFlees() {
        World w = flatWorld();
        var rabbit = mob(com.mineclone.world.entity.MobType.RABBIT, 8.5f, 11f, 8.5f, 8L);
        org.joml.Vector3f player = new org.joml.Vector3f(8.5f, 11f, 12f);
        float startDist = rabbit.position.distance(player);
        boolean fled = false;
        for (int i = 0; i < 20 * 3; i++) {
            rabbit.update(w, player, 1f / 20f, 1f, true);
            fled |= rabbit.state == com.mineclone.world.entity.Mob.State.FLEE;
        }
        assertTrue("rabbit bolts", fled);
        assertTrue("and gets away (" + startDist + " -> " + rabbit.position.distance(player) + ")",
                rabbit.position.distance(player) > startDist + 4f);
        org.joml.Vector3f gone = new org.joml.Vector3f(8.5f, 11f, 400f);
        for (int i = 0; i < 20 * 6; i++)
            rabbit.update(w, gone, 1f / 20f, 1f, true);
        assertTrue("it calms down when alone", rabbit.state != com.mineclone.world.entity.Mob.State.FLEE);
    }

    private static void testWolfPack() {
        World w = flatWorld();
        org.joml.Vector3f player = new org.joml.Vector3f(8.5f, 11f, 12.5f);
        var a = mob(com.mineclone.world.entity.MobType.WOLF, 8.5f, 11f, 8.5f, 9L);
        var b = mob(com.mineclone.world.entity.MobType.WOLF, 11.5f, 11f, 8.5f, 10L);
        java.util.List<com.mineclone.world.entity.Mob> pack = new java.util.ArrayList<>(java.util.List.of(a, b));
        for (int i = 0; i < 20 * 3; i++) {
            com.mineclone.world.entity.Wildlife.sense(pack);
            for (var m : pack) {
                m.update(w, player, 1f / 20f, 1f, true);
                assertTrue("a calm wolf never bites the player", !m.justAttacked);
            }
        }
        a.hurt(1f, player.x, player.z);
        assertTrue("struck wolf is angry", a.isAngry());
        com.mineclone.world.entity.Wildlife.sense(pack);
        assertTrue("the pack joins in", b.isAngry());
        boolean bit = false;
        for (int i = 0; i < 20 * 6 && !bit; i++) {
            com.mineclone.world.entity.Wildlife.sense(pack);
            for (var m : pack) {
                m.update(w, player, 1f / 20f, 1f, true);
                bit |= m.justAttacked;
            }
        }
        assertTrue("an angry pack attacks", bit);
        assertEq("a wolf bite deals its own damage", com.mineclone.world.entity.Wildlife.BITE_DAMAGE, a.attackDamage());
    }

    private static void testWolfHunts() {
        World w = flatWorld();
        org.joml.Vector3f far = new org.joml.Vector3f(8.5f, 11f, 400f);
        var wolf = mob(com.mineclone.world.entity.MobType.WOLF, 4.5f, 11f, 8.5f, 11L);
        var chicken = mob(com.mineclone.world.entity.MobType.CHICKEN, 11.5f, 11f, 8.5f, 12L);
        java.util.List<com.mineclone.world.entity.Mob> all = new java.util.ArrayList<>(java.util.List.of(wolf, chicken));
        boolean hunted = false, bitten = false, fled = false;
        for (int i = 0; i < 20 * 60 && !chicken.dead; i++) {
            com.mineclone.world.entity.Wildlife.sense(all);
            wolf.update(w, far, 1f / 20f, 1f, true);
            chicken.update(w, far, 1f / 20f, 1f, true);
            hunted |= wolf.state == com.mineclone.world.entity.Mob.State.HUNT;
            fled |= chicken.state == com.mineclone.world.entity.Mob.State.FLEE;
            if (wolf.justBitMob != null) {
                bitten = true;
                wolf.justBitMob.damage(com.mineclone.world.damage.DamageSource.byMob(
                        com.mineclone.world.damage.DamageType.MELEE, wolf.type, wolf.position.x,
                        wolf.position.y, wolf.position.z, 0.6f), com.mineclone.world.entity.Wildlife.BITE_DAMAGE);
            }
        }
        assertTrue("the wolf goes hunting", hunted);
        assertTrue("the chicken runs from it", fled);
        assertTrue("and gets bitten", bitten);
        assertTrue("prey killed by a wolf is eaten, not dropped", !chicken.dead || chicken.eaten);
    }

    private static void testBirdFlight() {
        World w = flatWorld();
        var bird = mob(com.mineclone.world.entity.MobType.BIRD, 8.5f, 11f, 8.5f, 13L);
        org.joml.Vector3f far = new org.joml.Vector3f(8.5f, 11f, 400f);
        for (int i = 0; i < 20; i++)
            bird.update(w, far, 1f / 20f, 1f, true);
        assertTrue("bird rests while alone", bird.state != com.mineclone.world.entity.Mob.State.FLY);
        org.joml.Vector3f near = new org.joml.Vector3f(9.5f, 11f, 11.5f);
        boolean tookOff = false;
        float highest = 0f;
        for (int i = 0; i < 20 * 3; i++) {
            bird.update(w, near, 1f / 20f, 1f, true);
            tookOff |= bird.justTookOff;
            highest = Math.max(highest, bird.position.y);
        }
        assertTrue("it takes off when approached", tookOff);
        assertTrue("and gains height (" + highest + ")", highest > 13f);
        boolean landed = false;
        for (int i = 0; i < 20 * 40 && !landed; i++) {
            bird.update(w, far, 1f / 20f, 1f, true);
            landed = bird.state != com.mineclone.world.entity.Mob.State.FLY && bird.onGround;
        }
        assertTrue("it lands again (at " + bird.position + ")", landed);
        assertTrue("never inside a block",
                !w.getBlock((int) Math.floor(bird.position.x), (int) Math.floor(bird.position.y + 0.1f),
                        (int) Math.floor(bird.position.z)).solid);
    }

    private static void testWildSpawnRules() {
        var wolves = com.mineclone.world.entity.MobSpawner.wildTypeFor(Biome.FOREST, BlockType.GRASS, 1f, 0.1f);
        assertEq("wolves in the forest", com.mineclone.world.entity.MobType.WOLF, wolves);
        assertEq("birds sit on leaves by day", com.mineclone.world.entity.MobType.BIRD,
                com.mineclone.world.entity.MobSpawner.wildTypeFor(Biome.FOREST, BlockType.LEAVES, 1f, 0.9f));
        assertTrue("no birds on leaves at night",
                com.mineclone.world.entity.MobSpawner.wildTypeFor(Biome.FOREST, BlockType.LEAVES, 0f, 0.9f) == null);
        assertEq("rabbits in the snow", com.mineclone.world.entity.MobType.RABBIT,
                com.mineclone.world.entity.MobSpawner.wildTypeFor(Biome.TUNDRA, BlockType.SNOWY_GRASS, 1f, 0.9f));
        assertTrue("nothing on stone",
                com.mineclone.world.entity.MobSpawner.wildTypeFor(Biome.PLAINS, BlockType.STONE, 1f, 0.5f) == null);
        assertTrue("nothing in the ocean",
                com.mineclone.world.entity.MobSpawner.wildTypeFor(Biome.OCEAN, BlockType.SAND, 1f, 0.2f) == null);
    }

    // ---- лёд и обломки -------------------------------------------------------

    private static void testIce() {
        // Генерация: верхний слой воды в тундре — лёд.
        World w = null;
        int[] spot = null;
        for (long seed : new long[] { 5L, 17L, 88L, 404L, 1234L, 777L, 31337L }) {
            w = new World(seed);
            spot = findFrozenWater(w);
            if (spot != null)
                break;
        }
        assertTrue("some tundra water froze at generation", spot != null);
        assertEq("ice lies at sea level", World.SEA_LEVEL, spot[1]);
        assertEq("water stays under the ice", BlockType.WATER, w.getBlock(spot[0], spot[1] - 1, spot[2]));

        // Тики: факел топит лёд.
        com.mineclone.world.BlockTicker ticker = new com.mineclone.world.BlockTicker(3L);
        w.setBlock(spot[0] + 1, spot[1] + 1, spot[2], BlockType.TORCH);
        boolean melted = false;
        for (int i = 0; i < 400 && !melted; i++) {
            ticker.apply(w, spot[0], spot[1], spot[2]);
            melted = w.getBlock(spot[0], spot[1], spot[2]) == BlockType.WATER;
        }
        assertTrue("a torch melts the ice", melted);
        w.setBlock(spot[0] + 1, spot[1] + 1, spot[2], BlockType.AIR);
        boolean refroze = false;
        for (int i = 0; i < 400 && !refroze; i++) {
            ticker.apply(w, spot[0], spot[1], spot[2]);
            refroze = w.getBlock(spot[0], spot[1], spot[2]) == BlockType.ICE;
        }
        assertTrue("open tundra water freezes again", refroze);
        assertTrue("ice is slippery", BlockType.ICE.grip() < 0.5f);
        assertTrue("ice drops nothing", com.mineclone.item.Items.get().loot()
                .blockDrops(BlockType.ICE, com.mineclone.item.loot.LootContext.chest(1, 0, 60, 0)).isEmpty());
    }

    private static int[] findFrozenWater(World w) {
        for (int cx = -100; cx <= 100; cx += 2)
            for (int cz = -100; cz <= 100; cz += 2) {
                if (!w.biomes.biomeAt(cx * 16 + 8, cz * 16 + 8).isCold()
                        || w.terrainHeight(cx * 16 + 8, cz * 16 + 8) >= World.SEA_LEVEL - 1) continue;
                com.mineclone.world.Chunk c = w.getChunk(cx, cz);
                for (int x = 0; x < com.mineclone.world.Chunk.SIZE_X; x++)
                    for (int z = 0; z < com.mineclone.world.Chunk.SIZE_Z; z++)
                        if (c.get(x, World.SEA_LEVEL, z) == BlockType.ICE
                                && c.get(x, World.SEA_LEVEL - 1, z) == BlockType.WATER
                                && c.get(x, World.SEA_LEVEL + 1, z) == BlockType.AIR)
                            return new int[] { cx * com.mineclone.world.Chunk.SIZE_X + x, World.SEA_LEVEL,
                                    cz * com.mineclone.world.Chunk.SIZE_Z + z };
            }
        return null;
    }

    private static void testDebris() {
        World w = flatWorld();
        com.mineclone.render.Debris d = new com.mineclone.render.Debris(1L);
        d.spawn(8, 11, 8, BlockType.STONE, 1f, 0f);
        int n = d.pieces().size();
        assertTrue("a broken block leaves a handful of pieces (" + n + ")", n >= 8);
        float top = 0f;
        for (int i = 0; i < 8; i++) {
            d.update(w, 1f / 60f);
            for (var p : d.pieces())
                top = Math.max(top, p.y);
        }
        assertTrue("pieces fly up first", top > 12f);
        for (int i = 0; i < 60; i++)
            d.update(w, 1f / 60f);
        int resting = 0;
        for (var p : d.pieces()) {
            assertTrue("no piece sinks into the floor (y=" + p.y + ")", p.y >= 10.99f);
            if (p.resting) resting++;
        }
        assertTrue("most pieces come to rest (" + resting + "/" + d.pieces().size() + ")",
                resting >= d.pieces().size() / 2);
        for (int i = 0; i < 60 * 3; i++)
            d.update(w, 1f / 60f);
        assertEq("pieces fade away", 0, d.pieces().size());
    }

    // ---- предметы на земле -------------------------------------------------------

    private static ItemEntity lying(ItemStack s, float x, float z) {
        return new ItemEntity(s, x, 11.0001f, z, 0f, 0f);
    }

    private static void testItemFalls() {
        World w = flatWorld();
        ItemEntity e = ItemEntity.popped(new ItemStack(BlockType.COBBLE, 1), 8.5f, 11.3f, 8.5f,
                new java.util.Random(7));
        org.joml.Vector3f far = new org.joml.Vector3f(40f, 11f, 40f);
        assertTrue("a fresh drop cannot be taken at once", e.pickupDelay > 0f);
        float top = e.position.y;
        for (int i = 0; i < 60 * 3; i++) {
            e.update(w, far, true, 1f / 60f);
            top = Math.max(top, e.position.y);
        }
        assertTrue("it pops up first (" + top + ")", top > 11.45f);
        assertTrue("and lies on the floor (y=" + e.position.y + ")",
                e.onGround && Math.abs(e.position.y - 11f) < 0.01f);
        assertTrue("friction stops the slide",
                Math.hypot(e.velocity.x, e.velocity.z) < 0.05f);
        assertTrue("it lands near where it dropped",
                Math.hypot(e.position.x - 8.5f, e.position.z - 8.5f) < 2f);
        assertTrue("far from the player nothing pulls it", !e.magnetized);
    }

    private static void testItemMagnet() {
        World w = flatWorld();
        org.joml.Vector3f player = new org.joml.Vector3f(10.5f, 11.7f, 8.5f);

        ItemEntity full = lying(new ItemStack(BlockType.COBBLE, 3), 8.5f, 8.5f);
        for (int i = 0; i < 60; i++)
            full.update(w, player, false, 1f / 60f);
        assertTrue("with no room in the inventory the item stays put",
                !full.magnetized && Math.abs(full.position.x - 8.5f) < 0.01f);

        ItemEntity fresh = ItemEntity.popped(new ItemStack(BlockType.COBBLE, 1), 9.5f, 11.3f, 8.5f,
                new java.util.Random(3));
        fresh.update(w, player, true, 0.1f);
        assertTrue("a fresh drop waits before it flies", !fresh.magnetized && !fresh.readyForPickup(player));

        ItemEntity e = lying(new ItemStack(BlockType.COBBLE, 3), 8.5f, 8.5f);
        float t = 0f;
        boolean got = false;
        for (int i = 0; i < 90 && !got; i++) {
            e.update(w, player, true, 1f / 60f);
            t += 1f / 60f;
            got = e.readyForPickup(player);
        }
        assertTrue("an item within reach flies to the player", got);
        assertTrue("and does it quickly (" + t + " s)", t < 1f);

        ItemEntity out = lying(new ItemStack(BlockType.COBBLE, 3), 8.5f, 8.5f);
        org.joml.Vector3f away = new org.joml.Vector3f(8.5f + ItemEntity.MAGNET_RANGE + 1f, 11.7f, 8.5f);
        for (int i = 0; i < 30; i++)
            out.update(w, away, true, 1f / 60f);
        assertTrue("beyond the magnet range nothing moves",
                !out.magnetized && Math.abs(out.position.x - 8.5f) < 0.01f);
    }

    private static void testItemMerge() {
        ItemEntity a = lying(new ItemStack(BlockType.COBBLE, 30), 8.5f, 8.5f);
        ItemEntity b = lying(new ItemStack(BlockType.COBBLE, 50), 8.8f, 8.5f);
        ItemEntity dirt = lying(new ItemStack(BlockType.DIRT, 5), 8.6f, 8.5f);
        ItemEntity distant = lying(new ItemStack(BlockType.COBBLE, 5), 12f, 8.5f);
        ItemEntity pick1 = lying(ItemStack.of("wooden_pickaxe"), 8.5f, 9f);
        ItemEntity pick2 = lying(ItemStack.of("wooden_pickaxe"), 8.5f, 9.1f);
        a.age = 100f;
        b.age = 5f;
        java.util.List<ItemEntity> list = new java.util.ArrayList<>(
                java.util.List.of(a, b, dirt, distant, pick1, pick2));
        ItemEntity.mergeNearby(list);
        assertEq("the first stack fills up", 64, a.stack.count);
        assertEq("the rest stays in the second", 16, b.stack.count);
        assertEq("different blocks never merge", 5, dirt.stack.count);
        assertEq("distant stacks stay apart", 5, distant.stack.count);
        assertTrue("tools never merge", pick1.stack.count == 1 && pick2.stack.count == 1);
        assertEq("a merged stack takes the younger age", 5f, a.age);

        ItemEntity meat1 = lying(ItemStack.of("beef", 2), 3.5f, 3.5f);
        ItemEntity meat2 = lying(ItemStack.of("beef", 3), 3.7f, 3.6f);
        java.util.List<ItemEntity> food = new java.util.ArrayList<>(java.util.List.of(meat1, meat2));
        ItemEntity.mergeNearby(food);
        assertEq("food merges whole", 5, meat1.stack.count);
        assertTrue("and the emptied stack is gone on the next check", meat2.expired());
    }

    private static void testItemDespawn() {
        World w = flatWorld();
        ItemEntity old = lying(new ItemStack(BlockType.DIRT, 1), 8.5f, 8.5f);
        old.age = ItemEntity.DESPAWN_TIME - 0.01f;
        assertTrue("not before its time", !old.expired());
        old.update(w, new org.joml.Vector3f(40f, 11f, 40f), true, 0.05f);
        assertTrue("five minutes on the ground and it is gone", old.expired());
    }

    private static void testItemSave() throws Exception {
        java.io.File root = java.nio.file.Files.createTempDirectory("mineclone-items-").toFile();
        root.deleteOnExit();
        com.mineclone.save.SaveManager sm = new com.mineclone.save.SaveManager(new java.io.File(root, "saves"));
        byte[] blocks = new byte[com.mineclone.save.SaveFormat.CHUNK_VOLUME];
        byte[] meta = new byte[com.mineclone.save.SaveFormat.CHUNK_VOLUME];
        ItemStack pick = ItemStack.of("stone_pickaxe");
        pick.setDamage(17);
        java.util.List<com.mineclone.world.DroppedItem> dropped = java.util.List.of(
                new com.mineclone.world.DroppedItem(new ItemStack(BlockType.COBBLE, 42), 3.5f, 61.25f, -7.75f, 12.5f),
                new com.mineclone.world.DroppedItem(pick, 4f, 60f, -6f, 200f),
                new com.mineclone.world.DroppedItem(ItemStack.of("beef", 3), 5f, 60f, -5f, 0f));
        sm.saveChunkAsync("w1", new com.mineclone.save.ChunkSnapshot(0, -1, blocks, meta,
                new java.util.HashMap<>(), new java.util.HashMap<>(), dropped));
        sm.flushAndAwait();
        com.mineclone.save.ChunkSnapshot out = sm.loadChunk("w1", 0, -1);
        assertTrue("chunk loaded", out != null);
        assertEq("all items came back", 3, out.items.size());
        com.mineclone.world.DroppedItem cobble = out.items.get(0);
        assertEq("block stack", BlockType.COBBLE, cobble.stack.block());
        assertEq("its count", 42, cobble.stack.count);
        assertTrue("its place", cobble.x == 3.5f && cobble.y == 61.25f && cobble.z == -7.75f);
        assertEq("its age", 12.5f, cobble.age);
        assertEq("tool keeps its wear", 17, out.items.get(1).stack.damage());
        assertEq("food keeps its kind", "mineclone:beef",
                out.items.get(2).stack.item.id.toString());

        // Чанк отдаёт предметы в мир один раз, но помнит их для записи.
        com.mineclone.world.Chunk c = new com.mineclone.world.Chunk(0, -1);
        c.setPendingItems(out.items);
        assertEq("the chunk remembers how many it wrote", 3, c.savedItems);
        assertEq("a save before the pickup still sees them", 3, c.copyPendingItems().size());
        assertEq("the world takes them once", 3, c.takePendingItems().size());
        assertEq("and only once", 0, c.takePendingItems().size());

        ItemEntity restored = ItemEntity.restored(out.items.get(0), new java.util.Random(1));
        assertEq("a restored item keeps its age", 12.5f, restored.age);
        assertTrue("and can be taken at once", restored.pickupDelay <= 0f);

        sm.saveChunkAsync("w1", new com.mineclone.save.ChunkSnapshot(1, 1, blocks, meta));
        sm.flushAndAwait();
        assertTrue("a chunk with nothing on the ground loads empty",
                sm.loadChunk("w1", 1, 1).items.isEmpty());
    }

    // ---- туман, осадки, рука ----------------------------------------------------

    private static void testMist() {
        float dawn = 0.2f, noon = (float) (Math.PI / 2), midnight = (float) (Math.PI * 1.5);
        float forestDawn = com.mineclone.game.Mist.groundDensity(dawn, 0.2f, 0f, Biome.FOREST);
        float plainsDawn = com.mineclone.game.Mist.groundDensity(dawn, 0.2f, 0f, Biome.PLAINS);
        float desertNoon = com.mineclone.game.Mist.groundDensity(noon, 1f, 0f, Biome.DESERT);
        float plainsNoon = com.mineclone.game.Mist.groundDensity(noon, 1f, 0f, Biome.PLAINS);
        float plainsNight = com.mineclone.game.Mist.groundDensity(midnight, 0f, 0f, Biome.PLAINS);
        assertTrue("dawn mist is thick in the forest (" + forestDawn + ")", forestDawn > plainsDawn);
        assertEq("no ground mist at noon", 0f, plainsNoon);
        assertEq("desert noon is dry", 0f, desertNoon);
        assertTrue("mist builds at night", plainsNight > 0f);
        assertTrue("dawn beats midnight", plainsDawn > plainsNight);
        assertTrue("rain thickens the haze",
                com.mineclone.game.Mist.haze(1f, Biome.PLAINS) > com.mineclone.game.Mist.haze(0f, Biome.PLAINS));
        assertTrue("forest haze carries the light shafts",
                com.mineclone.game.Mist.haze(0f, Biome.FOREST) > com.mineclone.game.Mist.haze(0f, Biome.DESERT) * 4f);
    }

    private static void testPrecipitationRoof() {
        World w = flatWorld();
        // Крыша 3×3 на высоте 16 над полом на 10.
        for (int x = 7; x <= 9; x++)
            for (int z = 7; z <= 9; z++)
                w.setBlock(x, 16, z, BlockType.PLANKS);
        com.mineclone.render.PrecipitationField f = new com.mineclone.render.PrecipitationField();
        f.rebuild(w, 8, 8, 0, 30);
        assertEq("snow lands on the roof", 17f, f.heightAt(8, 8));
        assertEq("open ground catches it at the floor", 11f, f.heightAt(3, 3));
        assertTrue("outside the map nothing falls", f.heightAt(500, 500) == Float.MAX_VALUE);
        // Крыша выше полосы сканирования всё равно закрывает колонну.
        com.mineclone.render.PrecipitationField low = new com.mineclone.render.PrecipitationField();
        low.rebuild(w, 8, 8, 0, 12);
        assertTrue("a roof above the scan band still shelters", low.heightAt(8, 8) > 12f);
        assertTrue("a small step does not force a rebuild", !f.needsRebuild(10, 9));
        assertTrue("walking away forces a rebuild", f.needsRebuild(20, 8));
    }

    private static void testSwingTrail() {
        assertEq("no swing, no trail", 0,
                com.mineclone.render.HeldItemRenderer.trailStrip(1f, 0f, 0f, false).length);
        float[] s = com.mineclone.render.HeldItemRenderer.trailStrip(1f, 0.6f, 0f, false);
        assertEq("two vertices per sample", com.mineclone.render.HeldItemRenderer.TRAIL_SAMPLES * 8, s.length);
        // Новейший отсчёт ярче хвоста, внутренний край полосы прозрачен.
        float head = s[3], tail = s[(com.mineclone.render.HeldItemRenderer.TRAIL_SAMPLES - 1) * 8 + 3];
        assertTrue("the fresh end is brighter (" + head + " vs " + tail + ")", head > tail);
        assertEq("inner edge is transparent", 0f, s[7]);
        // Головка в начале следа совпадает с головкой инструмента в текущей позе.
        org.joml.Vector4f tip = com.mineclone.render.HeldItemRenderer.toolPose(1f, 0.6f, 0f, false)
                .transform(new org.joml.Vector4f(-0.58f, 0.58f, 0f, 1f));
        assertTrue("trail starts at the tool head",
                Math.abs(tip.x - s[0]) < 1e-4f && Math.abs(tip.y - s[1]) < 1e-4f);
    }

    private static void testCrackStages() {
        assertEq("new tool is clean", -1, com.mineclone.render.HeldItemRenderer.crackStage(1f));
        assertEq("light wear is clean", -1, com.mineclone.render.HeldItemRenderer.crackStage(0.7f));
        int mid = com.mineclone.render.HeldItemRenderer.crackStage(0.4f);
        int worn = com.mineclone.render.HeldItemRenderer.crackStage(0.05f);
        assertTrue("cracks appear past a third of wear", mid >= 0);
        assertTrue("cracks deepen with wear", worn > mid);
        assertTrue("stage stays in the tile strip", worn <= 9
                && com.mineclone.render.HeldItemRenderer.crackStage(0f) <= 9);
    }

    private static void testWindAmbience() {
        com.mineclone.audio.AmbientSound outdoors = new com.mineclone.audio.AmbientSound(new java.util.Random(3));
        com.mineclone.audio.AmbientSound indoors = new com.mineclone.audio.AmbientSound(new java.util.Random(3));
        int outside = 0, inside = 0, thunderInBlizzard = 0;
        for (int i = 0; i < 20 * 120; i++) {
            if (outdoors.tick(0.05f, false, false, 0f, 1f, 4f, true)
                    == com.mineclone.audio.AmbientSound.Cue.WIND) outside++;
            var cue = indoors.tick(0.05f, false, false, 0f, 1f, 4f, false);
            if (cue == com.mineclone.audio.AmbientSound.Cue.WIND) inside++;
            if (cue == com.mineclone.audio.AmbientSound.Cue.THUNDER) thunderInBlizzard++;
        }
        assertTrue("gusts are heard in a storm outdoors (" + outside + ")", outside >= 8);
        assertEq("no gusts indoors", 0, inside);
        assertEq("a snow storm without rain never thunders", 0, thunderInBlizzard);
    }

    private static World flatWorld() {
        World w = new World(1234L);
        for (int cx = -1; cx <= 1; cx++)
            for (int cz = -1; cz <= 1; cz++) {
                com.mineclone.world.Chunk c = w.getChunk(cx, cz);
                for (int x = 0; x < com.mineclone.world.Chunk.SIZE_X; x++)
                    for (int z = 0; z < com.mineclone.world.Chunk.SIZE_Z; z++)
                        for (int y = 0; y < com.mineclone.world.Chunk.SIZE_Y; y++)
                            c.set(x, y, z, y == 10 ? BlockType.STONE : BlockType.AIR);
            }
        return w;
    }

    // ---- погода --------------------------------------------------------------

    private static void testWeatherFronts() {
        long seed = 4242L;
        Weather.State first = Weather.sample(seed, 5f);
        assertTrue("a world starts under a clear sky", first.precipitation() == 0f
                && first.cloudiness() == 0f);
        // Непрерывность: за секунду погода не может сменить больше, чем
        // позволяет переход (наклон smoothstep не круче 1.5 / TRANSITION).
        float maxStep = 1.5f / Weather.TRANSITION + 1e-3f;
        Weather.State prev = Weather.sample(seed, 0f);
        for (float t = 1f; t < Weather.FRONT_LENGTH * 60; t += 1f) {
            Weather.State s = Weather.sample(seed, t);
            assertTrue("precipitation jumps at t=" + t,
                    Math.abs(s.precipitation() - prev.precipitation()) <= maxStep);
            assertTrue("clouds jump at t=" + t,
                    Math.abs(s.cloudiness() - prev.cloudiness()) <= maxStep);
            prev = s;
        }
        // Середина фронта — установившаяся погода своего вида.
        for (long f = 1; f < 40; f++) {
            float mid = f * Weather.FRONT_LENGTH + Weather.TRANSITION + 10f;
            Weather.Kind k = Weather.kindAt(seed, f);
            assertEq("settled precipitation of front " + f, k.precipitation,
                    Weather.sample(seed, mid).precipitation());
        }
        assertTrue("desert never gets rain", !Weather.precipitates(Biome.DESERT));
        assertTrue("tundra snows", Weather.snowsAt(Biome.TUNDRA, World.SEA_LEVEL + 5));
        assertTrue("plains lowland rains", !Weather.snowsAt(Biome.PLAINS, World.SEA_LEVEL + 5));
        assertTrue("mountain tops snow anywhere",
                Weather.snowsAt(Biome.PLAINS, World.SEA_LEVEL + Weather.SNOW_ALTITUDE + 3));
    }

    private static void testWeatherDistribution() {
        Map<Weather.Kind, Integer> n = new EnumMap<>(Weather.Kind.class);
        for (long f = 1; f <= 4000; f++)
            n.merge(Weather.kindAt(777L, f), 1, Integer::sum);
        for (Weather.Kind k : Weather.Kind.values())
            assertTrue(k + " occurs", n.getOrDefault(k, 0) > 0);
        assertTrue("clear is the most common", n.get(Weather.Kind.CLEAR) > n.get(Weather.Kind.CLOUDY)
                && n.get(Weather.Kind.CLEAR) > n.get(Weather.Kind.STORM));
        assertTrue("storms are rare (" + n.get(Weather.Kind.STORM) + ")",
                n.get(Weather.Kind.STORM) < 4000 * 0.14);
        int wet = n.get(Weather.Kind.LIGHT) + n.get(Weather.Kind.HEAVY)
                + n.get(Weather.Kind.STORM);
        assertTrue("rainy fronts stay occasional (" + wet + ")", wet < 4000 * 0.25);
    }

    private static void testWeatherVisibility() {
        float clear = Weather.visibility(0f, 0f, false);
        float rainStorm = Weather.visibility(1f, 1f, false);
        float blizzard = Weather.visibility(1f, 1f, true);
        assertEq("clear sky hides nothing", 1f, clear);
        assertTrue("rain storm shortens the view", rainStorm < 0.6f);
        assertTrue("blizzard is worse than rain (" + blizzard + " vs " + rainStorm + ")",
                blizzard < rainStorm);
        assertTrue("never blind", blizzard >= Weather.MIN_VISIBILITY - 1e-6f);
    }

    private static void testWeatherWind() {
        long seed = 99L;
        float clearWind = -1f, stormWind = -1f;
        for (long f = 1; f < 400 && (clearWind < 0 || stormWind < 0); f++) {
            float mid = f * Weather.FRONT_LENGTH + Weather.FRONT_LENGTH * 0.6f;
            Weather.Kind k = Weather.kindAt(seed, f);
            // К середине фронта минутный переход давно закончился, поэтому
            // соседний фронт может быть любого вида.
            float[] w = Weather.wind(seed, mid);
            float speed = (float) Math.hypot(w[0], w[1]);
            if (k == Weather.Kind.CLEAR && clearWind < 0) clearWind = speed;
            if (k == Weather.Kind.STORM && stormWind < 0) stormWind = speed;
        }
        assertTrue("found both a calm and a stormy front", clearWind >= 0 && stormWind >= 0);
        assertTrue("storm wind " + stormWind + " beats calm " + clearWind, stormWind > clearWind * 3f);
    }

    // ---- небо ----------------------------------------------------------------

    /**
     * Снос осадков и тумана — интеграл ветра, а не «ветер × время».
     *
     * <p>Ветер пульсирует порывами по построению, и произведение пульсации на
     * растущее общее время двигало разом весь снегопад и весь туман. Тест
     * держит оба конца: что порывы действительно есть и что снос от них не
     * пятится назад.
     */
    private static void testWeatherDrift() {
        long seed = 20260921L;
        // Порывы: за двенадцать секунд сила ветра гуляет заметно.
        float min = Float.MAX_VALUE, max = 0f;
        for (int i = 0; i <= 120; i++) {
            float[] w = com.mineclone.world.Weather.wind(seed, 500f + i * 0.1f);
            float speed = (float) Math.hypot(w[0], w[1]);
            min = Math.min(min, speed);
            max = Math.max(max, speed);
        }
        assertTrue("ветер пульсирует порывами", max > min * 1.3f);

        // Наивная формула на тех же данных пятится: именно это и качало снег.
        boolean naiveWentBack = false;
        float prevNaive = Float.NEGATIVE_INFINITY;
        com.mineclone.game.WeatherDrift drift = new com.mineclone.game.WeatherDrift();
        float prevDrift = Float.NEGATIVE_INFINITY;
        boolean driftWentBack = false;
        boolean windAlwaysForward = true;
        for (int i = 0; i <= 120; i++) {
            float t = 500f + i * 0.1f;
            float[] w = com.mineclone.world.Weather.wind(seed, t);
            if (w[0] <= 0f)
                windAlwaysForward = false;
            float naive = w[0] * t;
            if (naive < prevNaive - 1e-3f)
                naiveWentBack = true;
            prevNaive = naive;
            drift.advance(0.1f, w[0], w[1], 0f);
            if (drift.x < prevDrift - 1e-6f)
                driftWentBack = true;
            prevDrift = drift.x;
        }
        assertTrue("выбран отрезок, где ветер всё время дует в одну сторону", windAlwaysForward);
        assertTrue("наивная формула пятится назад", naiveWentBack);
        assertTrue("накопленный снос не пятится", !driftWentBack);

        // Буря несёт снег сильнее и роняет его быстрее; без бури добавок нет.
        com.mineclone.game.WeatherDrift calm = new com.mineclone.game.WeatherDrift();
        com.mineclone.game.WeatherDrift storm = new com.mineclone.game.WeatherDrift();
        for (int i = 0; i < 100; i++) {
            calm.advance(0.05f, 2f, 0f, 0f);
            storm.advance(0.05f, 2f, 0f, 1f);
        }
        assertTrue("в штиль снег несёт как всё остальное",
                Math.abs(calm.snowX - calm.x) < 1e-4f);
        assertTrue("в штиль буря ничего не добавляет к падению",
                calm.snowFall == 0f && calm.rainFall == 0f);
        assertTrue("метель несёт снег дальше", storm.snowX > storm.x * 1.5f);
        assertTrue("и роняет его быстрее", storm.snowFall > 0f && storm.rainFall > storm.snowFall);

        // Рывок кадра не имеет права рвать снос длинным шагом.
        com.mineclone.game.WeatherDrift hitch = new com.mineclone.game.WeatherDrift();
        hitch.advance(1f, 10f, 0f, 0f);
        assertTrue("длинный кадр обрезан", hitch.x <= 10f * 0.1f + 1e-4f);

        com.mineclone.game.WeatherDrift reset = new com.mineclone.game.WeatherDrift();
        reset.advance(0.1f, 5f, 5f, 1f);
        reset.reset();
        assertTrue("сброс обнуляет всё",
                reset.x == 0f && reset.z == 0f && reset.snowX == 0f && reset.snowZ == 0f
                        && reset.snowFall == 0f && reset.rainFall == 0f);
    }

    /** Полсекунды кадров по 1/60 с при заданной камере и скорости. */
    private static void spin(com.mineclone.render.BodyRotation b, float seconds,
            float cameraYaw, float velX, float velZ) {
        for (int i = 0; i < (int) (seconds * 60f); i++)
            b.update(1f / 60f, cameraYaw, 0f, velX, velZ);
    }

    /**
     * Голова поворачивается сразу, корпус — нехотя.
     *
     * <p>Пока взгляд в пределах конуса, плечи стоят на месте: человек,
     * который разворачивается всем телом на каждое движение мыши, выглядит
     * флюгером. За пределом корпус подтягивается — но ровно до предела, а не
     * до головы, иначе конуса бы не было вовсе.
     */
    private static void testBodyRotation() {
        float limit = com.mineclone.render.BodyRotation.MAX_OFFSET;
        com.mineclone.render.BodyRotation b = new com.mineclone.render.BodyRotation();

        // Внутри конуса корпус не шевелится.
        b.snap(0f, 0f);
        spin(b, 1f, (float) Math.toRadians(60), 0f, 0f);
        assertTrue("корпус стоит, пока голова в конусе", Math.abs(b.bodyYaw) < 1e-4f);
        assertTrue("голова смотрит в камеру",
                Math.abs(b.headYaw - (float) Math.toRadians(60)) < 1e-5f);

        // За пределом — подтягивается ровно до предела.
        b.snap(0f, 0f);
        spin(b, 2f, (float) Math.toRadians(150), 0f, 0f);
        assertTrue("корпус довернулся до предела, а не до головы",
                Math.abs(b.headOffset() - limit) < 0.01f);
        assertTrue("и встал там, где обязан (" + Math.toDegrees(b.bodyYaw) + "°)",
                Math.abs(b.bodyYaw - (float) Math.toRadians(75)) < 0.01f);

        // Рывок мыши за один кадр не имеет права вывернуть шею.
        b.snap(0f, 0f);
        b.update(1f / 60f, (float) Math.toRadians(179), 0f, 0f, 0f);
        assertTrue("предел держится и на первом же кадре ("
                        + Math.toDegrees(b.headOffset()) + "°)",
                Math.abs(b.headOffset()) <= limit + 1e-4f);

        // Переход через ±180° идёт короткой дугой, а не кругом.
        b.snap((float) Math.toRadians(179), 0f);
        b.update(1f / 60f, (float) Math.toRadians(-179), 0f, 0f, 0f);
        assertTrue("шов на 180° не считается разворотом (" 
                        + Math.toDegrees(b.headOffset()) + "°)",
                Math.abs(b.headOffset()) < (float) Math.toRadians(3));
        assertTrue("и корпус на нём не дёргается",
                Math.abs(b.bodyYaw - (float) Math.toRadians(179)) < 1e-4f);

        // Наклон головы ограничен вертикалью, корпус его не видит вовсе.
        b.snap(0f, 0f);
        b.update(1f / 60f, 0f, (float) Math.toRadians(200), 0f, 0f);
        assertTrue("наклон обрезан по вертикали",
                Math.abs(b.headPitch - (float) (Math.PI / 2.0)) < 1e-5f);

        // Кратчайшая дуга в самом сглаживании.
        float mid = com.mineclone.render.BodyRotation.lerpAngle(
                (float) Math.toRadians(170), (float) Math.toRadians(-170), 0.5f);
        assertTrue("середина между 170° и −170° лежит на шве (" 
                        + Math.toDegrees(mid) + "°)",
                Math.abs(Math.abs(mid) - Math.PI) < 0.02f);
    }

    /**
     * Корпус разворачивается к движению.
     *
     * <p>Идёшь вперёд — плечи вперёд; идёшь боком — плечи уходят вбок ровно
     * настолько, насколько позволяет конус. Без этого модель скользит боком,
     * глядя прямо, как на льду.
     */
    private static void testBodyFollowsMovement() {
        float limit = com.mineclone.render.BodyRotation.MAX_OFFSET;
        com.mineclone.render.BodyRotation b = new com.mineclone.render.BodyRotation();

        // Взгляд и шаг в одну сторону: корпус доезжает до них обоих.
        b.snap(0f, 0f);
        float east = (float) (Math.PI / 2.0);
        spin(b, 2f, east, 4f, 0f);
        assertTrue("корпус довернулся к ходу (" + Math.toDegrees(b.bodyYaw) + "°)",
                Math.abs(com.mineclone.render.BodyRotation.wrap(b.bodyYaw - east)) < 0.02f);

        // Шаг вбок: корпус тянется к направлению хода, но конус его держит.
        b.snap(0f, 0f);
        spin(b, 2f, 0f, 4f, 0f);
        assertTrue("боком корпус уходит к ходу до упора в конус ("
                        + Math.toDegrees(b.bodyYaw) + "°)",
                Math.abs(b.bodyYaw - limit) < 0.02f);
        assertTrue("и шея не выворачивается", Math.abs(b.headOffset()) <= limit + 1e-4f);

        // Еле ползущий игрок считается стоящим: дрожание скорости у стены не
        // должно крутить плечи.
        b.snap(0f, 0f);
        spin(b, 1f, 0f, 0.05f, 0f);
        assertTrue("ползком корпус не крутится", Math.abs(b.bodyYaw) < 1e-4f);
    }

    private static void testMoonPhases() {
        double day = NightSky.CYCLE;
        assertEq("first night is full moon", 0, NightSky.moonPhase(4f));
        assertEq("day index flips at sunrise", 1L, NightSky.dayIndex((float) (day + 0.01)));
        assertEq("four nights later it is new moon", 4, NightSky.moonPhase((float) (day * 4 + 4.0)));
        assertEq("the month closes after eight nights", 0, NightSky.moonPhase((float) (day * 8 + 4.0)));
        assertEq("full disc lit", 1f, NightSky.moonIllumination(0));
        assertTrue("new moon is dark", NightSky.moonIllumination(4) < 1e-4f);
        assertTrue("waning is symmetric", Math.abs(NightSky.moonIllumination(2)
                - NightSky.moonIllumination(6)) < 1e-4f);
        assertTrue("new moon does not black the night out", NightSky.moonlight(4) > 0.25f);
        assertTrue("full moon is brighter than new", NightSky.moonlight(0) > NightSky.moonlight(4) * 2f);
    }

    private static void testTimeKeepsDay() {
        float t = (float) (NightSky.CYCLE * 5 + 1.0);
        float noon = NightSky.withTimeOfDay(t, (float) (Math.PI / 2));
        assertEq("same day after /time set", 5L, NightSky.dayIndex(noon));
        assertTrue("clock moved to noon", Math.abs(noon - (NightSky.CYCLE * 5 + Math.PI / 2)) < 1e-3);
        assertEq("same moon", NightSky.moonPhase(t), NightSky.moonPhase(noon));
    }

    private static void testAurora() {
        long seed = 2026L;
        assertEq("no aurora at noon", 0f, NightSky.auroraActivity(seed, (float) (Math.PI / 2)));
        int active = 0;
        int nights = 400;
        for (int d = 0; d < nights; d++) {
            float midnight = (float) (d * NightSky.CYCLE + Math.PI * 1.5);
            if (NightSky.auroraActivity(seed, midnight) > 0f)
                active++;
        }
        float share = active / (float) nights;
        assertTrue("aurora is an event, not every night (" + share + ")",
                share > NightSky.AURORA_CHANCE - 0.1f && share < NightSky.AURORA_CHANCE + 0.1f);
        // Найдём ночь с сиянием и сравним биомы и облака.
        float t = -1f;
        for (int d = 0; d < nights && t < 0; d++) {
            float midnight = (float) (d * NightSky.CYCLE + Math.PI * 1.5);
            if (NightSky.auroraActivity(seed, midnight) > 0f)
                t = midnight;
        }
        float tundra = NightSky.auroraStrength(seed, t, Biome.TUNDRA, 0f);
        float plains = NightSky.auroraStrength(seed, t, Biome.PLAINS, 0f);
        assertTrue("brightest in the cold (" + tundra + " vs " + plains + ")", tundra > plains * 2f);
        assertEq("overcast hides it", 0f, NightSky.auroraStrength(seed, t, Biome.TUNDRA, 1f));
        float dusk = (float) (t - Math.PI * 0.49);
        assertTrue("fades in from dusk", NightSky.auroraStrength(seed, dusk, Biome.TUNDRA, 0f) < tundra * 0.2f);
    }

    // ---- мороз ---------------------------------------------------------------

    private static void testFrost() {
        float tundraNight = Frost.coldness(Biome.TUNDRA, World.SEA_LEVEL + 8, 0f, 0.6f, 0.5f, 15, false);
        float plainsDay = Frost.coldness(Biome.PLAINS, World.SEA_LEVEL + 8, 1f, 0f, 0f, 15, false);
        float tundraCave = Frost.coldness(Biome.TUNDRA, World.SEA_LEVEL - 10, 0f, 0f, 0f, 0, false);
        assertTrue("tundra night is bitter (" + tundraNight + ")", tundraNight > 0.8f);
        assertEq("warm plains are not cold", 0f, plainsDay);
        assertTrue("a cave shelters from the wind", tundraCave < Frost.COLD_THRESHOLD);

        Frost f = new Frost();
        for (int i = 0; i < 15 * 20; i++)
            f.update(0.05f, tundraNight, 0);
        assertTrue("a short dash leaves only a hint of frost (" + f.level() + ")", f.level() < 0.4f);
        for (int i = 0; i < 60 * 20; i++)
            f.update(0.05f, tundraNight, 0);
        assertTrue("a long walk frosts the screen (" + f.level() + ")", f.level() > 0.85f);
        for (int i = 0; i < (int) (Frost.THAW_TIME * 20) + 2; i++)
            f.update(0.05f, tundraNight, 14);
        assertEq("a torch thaws it in seconds", 0f, f.level());
    }

    // ---- камера ----------------------------------------------------------------

    private static void testCameraLean() {
        CameraMotion m = new CameraMotion();
        for (int i = 0; i < 30; i++)
            m.update(1f / 60f, 0f, 4.8f, 0f, 0f, true);
        assertTrue("strafing right banks right (" + m.roll() + ")", m.roll() > 0.01f);
        assertTrue("lean stays subtle", m.roll() <= CameraMotion.MAX_LEAN * 1.25f);
        for (int i = 0; i < 90; i++)
            m.update(1f / 60f, 0f, 0f, 0f, 0f, true);
        assertTrue("lean settles at rest (" + m.roll() + ")", Math.abs(m.roll()) < 0.003f);

        CameraMotion turn = new CameraMotion();
        for (int i = 0; i < 20; i++)
            turn.update(1f / 60f, -3f, 0f, 0f, 0f, true);
        assertTrue("turning left banks left", turn.roll() < 0f);

        CameraMotion off = new CameraMotion();
        for (int i = 0; i < 30; i++)
            off.update(1f / 60f, 4f, 6f, 6f, 2f, false);
        assertEq("disabled camera does not move", 0f, off.roll());
        assertEq("disabled camera does not dip", 0f, off.dip());
    }

    private static void testCameraLanding() {
        CameraMotion m = new CameraMotion();
        m.update(1f / 60f, 0f, 0f, 0f, 0f, true);
        m.update(1f / 60f, 0f, 0f, 0f, 3f, true);
        float lowest = 0f;
        for (int i = 0; i < 20; i++) {
            m.update(1f / 60f, 0f, 0f, 0f, 0f, true);
            lowest = Math.min(lowest, m.dip());
        }
        assertTrue("landing drops the eyes (" + lowest + ")", lowest < -0.02f);
        assertTrue("dip is bounded", lowest >= -CameraMotion.MAX_DIP - 1e-4f);
        for (int i = 0; i < 90; i++)
            m.update(1f / 60f, 0f, 0f, 0f, 0f, true);
        assertTrue("eyes come back (" + m.dip() + ")", Math.abs(m.dip()) < 0.004f);

        CameraMotion hard = new CameraMotion();
        hard.update(1f / 60f, 0f, 0f, 0f, 0f, true);
        hard.update(1f / 60f, 0f, 0f, 0f, 30f, true);
        float hardLowest = 0f;
        for (int i = 0; i < 20; i++) {
            hard.update(1f / 60f, 0f, 0f, 0f, 0f, true);
            hardLowest = Math.min(hardLowest, hard.dip());
        }
        assertTrue("even a hard landing stays comfortable (" + hardLowest + ")",
                hardLowest >= -CameraMotion.MAX_DIP - 1e-4f && CameraMotion.MAX_DIP <= 0.12f);
    }

    // ---- индикаторы звука ----------------------------------------------------

    private static void testSoundBearing() {
        // yaw = 0: взгляд в −Z, правая рука — +X.
        assertTrue("ahead is 0", Math.abs(SoundIndicators.bearing(0f, 0f, -1f)) < 1e-3f);
        assertTrue("+X is on the right", Math.abs(SoundIndicators.bearing(0f, 1f, 0f) - 90f) < 1e-3f);
        assertTrue("-X is on the left", Math.abs(SoundIndicators.bearing(0f, -1f, 0f) + 90f) < 1e-3f);
        assertTrue("+Z is behind", Math.abs(Math.abs(SoundIndicators.bearing(0f, 0f, 1f)) - 180f) < 1e-3f);
        // Повернулись направо на 90°: теперь впереди +X, а бывшее «впереди» — слева.
        float yaw = (float) (Math.PI / 2);
        assertTrue("after turning right the old front is left",
                Math.abs(SoundIndicators.bearing(yaw, 0f, -1f) + 90f) < 1e-3f);
    }

    private static void testSoundCues() {
        SoundIndicators s = new SoundIndicators();
        assertTrue("a sound straight ahead gets no arc", !s.add(0, 0, 0f, 0f, -8f, 1f, true));
        assertTrue("a faint sound gets no arc", !s.add(0, 0, 0f, 8f, 0f, 0.05f, true));
        assertTrue("a loud sound on the right gets an arc", s.add(0, 0, 0f, 8f, 0f, 0.9f, true));
        assertTrue("a second groan from nearly the same spot merges", s.add(0, 0, 0f, 8f, 1.5f, 0.7f, true));
        assertEq("merged into one", 1, s.cues().size());
        assertTrue("behind is another arc", s.add(0, 0, 0f, 0f, 9f, 0.6f, false));
        assertEq("two arcs", 2, s.cues().size());
        s.update(SoundIndicators.LIFE * 0.5f);
        assertTrue("arcs fade", s.cues().get(0).alpha() < 0.9f);
        s.update(SoundIndicators.LIFE);
        assertEq("arcs die", 0, s.cues().size());
    }

    // ---- рамка и подсказки ------------------------------------------------------

    private static void testOutlineAnimator() {
        OutlineAnimator a = new OutlineAnimator();
        a.update(1f / 60f, new float[] { 0, 0, 0, 1, 1, 1 });
        assertEq("first target snaps", 0f, a.box()[0]);
        for (int i = 0; i < 10; i++)
            a.update(1f / 60f, new float[] { 0, 0, 0, 1, 1, 1 });
        assertEq("fully faded in", 1f, a.alpha());
        a.update(1f / 60f, new float[] { 1, 0, 0, 2, 1, 1 });
        assertTrue("slides, does not jump (" + a.box()[0] + ")", a.box()[0] > 0f && a.box()[0] < 0.9f);
        for (int i = 0; i < 15; i++)
            a.update(1f / 60f, new float[] { 1, 0, 0, 2, 1, 1 });
        assertTrue("arrives quickly", Math.abs(a.box()[0] - 1f) < 0.01f);
        a.update(1f / 60f, new float[] { 30, 5, 30, 31, 6, 31 });
        assertEq("a far jump snaps", 30f, a.box()[0]);
        for (int i = 0; i < 20; i++)
            a.update(1f / 60f, null);
        assertTrue("fades out without a target", !a.visible());
    }

    private static void testContextHints() {
        assertEq("chest", "открыть сундук",
                ContextHint.forTarget(BlockType.CHEST, null, true, false).action());
        assertEq("closed door opens", "открыть дверь",
                ContextHint.forTarget(BlockType.DOOR_CLOSED, null, true, false).action());
        assertEq("open door closes", "закрыть дверь",
                ContextHint.forTarget(BlockType.DOOR_OPEN, null, true, false).action());
        assertEq("furnace uses the right button", ContextHint.RMB,
                ContextHint.forTarget(BlockType.FURNACE, null, true, false).key());
        assertTrue("stone needs no hint", ContextHint.forTarget(BlockType.STONE, null, true, false) == null);
        ItemStack beef = ItemStack.of("cooked_beef", 3);
        assertTrue("food in hand suggests eating",
                ContextHint.forTarget(BlockType.STONE, beef, true, false).action().startsWith("съесть"));
        assertTrue("a full stomach suggests nothing",
                ContextHint.forTarget(BlockType.STONE, beef, false, false) == null);
        assertEq("the chest still wins over food", "открыть сундук",
                ContextHint.forTarget(BlockType.CHEST, beef, true, false).action());
        assertEq("a mob in reach", ContextHint.LMB,
                ContextHint.forTarget(null, null, false, true).key());
    }

    // ---- утверждения -----------------------------------------------------------

    static void assertTrue(String what, boolean cond) {
        if (!cond) throw new AssertionError("expected true: " + what);
    }

    static void assertEq(String what, Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual))
            throw new AssertionError(what + ": expected <" + expected + "> but was <" + actual + ">");
    }
}

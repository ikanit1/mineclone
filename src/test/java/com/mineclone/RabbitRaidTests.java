package com.mineclone;

import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.World;
import com.mineclone.world.entity.Mob;
import com.mineclone.world.entity.MobType;
import com.mineclone.world.entity.MobTactics;
import com.mineclone.world.entity.Wildlife;
import org.joml.Vector3f;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Random;

/** Physical crop raids, collision barriers and higher-priority rabbit reactions. */
public final class RabbitRaidTests {
    private static final int FLOOR = 40, PLANT = 41;
    private static final Vector3f PLAYER_FAR = new Vector3f(22.5f, PLANT, 23.5f);

    public static void runAll(TestMain.Runner runner) {
        runner.run("hungry rabbits reach all three maturing crops, eat without loot and schedule regrowth", RabbitRaidTests::eatAndRegrow);
        runner.run("closed fences and gates retain crops under repeated physical rabbit hops", RabbitRaidTests::closedBarrier);
        runner.run("a rabbit follows the actual route through a narrow open gate or fence gap", RabbitRaidTests::openBarrier);
        runner.run("player fear, wolves, existing flight and held-food temptation override crop raids", RabbitRaidTests::priorities);
        runner.run("Living World senses height while legacy wildlife retains horizontal threats", RabbitRaidTests::verticalSensing);
        runner.run("rabbit raids preserve young crops and protected chunks and never generate terrain", RabbitRaidTests::protectedAndLoadedOnly);
        runner.run("an inaccessible nearer plant does not starve a reachable crop search", RabbitRaidTests::reachableAlternative);
        runner.run("a dense closed field does not starve a farther reachable crop search", RabbitRaidTests::denseReachableAlternative);
        runner.run("disabling Living World cancels an active raid before crop mutation", RabbitRaidTests::disabled);
    }

    private static World blank() {
        World world = new World(71);
        for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) world.publish(new Chunk(cx, cz));
        ground(world);
        world.simulate((stack, x, y, z) -> { throw new AssertionError("a rabbit produced harvest loot"); }, 0);
        return world;
    }

    private static void ground(World world) {
        for (int x = -16; x < 32; x++) for (int z = -16; z < 32; z++)
            if (world.getChunkIfExists(x >> 4, z >> 4) != null) world.setBlock(x, FLOOR, z, BlockType.GRASS);
    }

    private static void plant(World world, int x, int z, BlockType crop, int stage) {
        world.setBlock(x, FLOOR, z, BlockType.FARMLAND, (byte) 7);
        world.setBlock(x, PLANT, z, crop, (byte) stage);
    }

    private static Mob rabbit(float x, float z, int salt) throws Exception {
        Mob mob = new Mob(MobType.RABBIT, x, PLANT + 0.001f, z, new Random(71 + salt));
        mob.id = salt + 1L;
        mob.onGround = true;
        mob.cropRaidsEnabled = true;
        hunger(mob, 0f);
        return mob;
    }

    private static void hunger(Mob mob, float value) throws Exception {
        Field field = Mob.class.getDeclaredField("hungerTimer");
        field.setAccessible(true);
        field.setFloat(mob, value);
    }

    private static void step(World world, Mob mob, int ticks) {
        for (int n = 0; n < ticks; n++) mob.update(world, PLAYER_FAR, 0.05f, 1f, false);
    }

    private static void reach(World world, Mob mob, int x, int z) {
        for (int n = 0; n < 800 && (world.getBlockMeta(x, PLANT, z) & 7) >= 4; n++) {
            mob.update(world, PLAYER_FAR, 0.05f, 1f, false);
            check(!mob.dead, "test rabbit died while approaching crop");
        }
        check(world.getBlockMeta(x, PLANT, z) == 0, "rabbit failed to reach and eat crop at " + x + "," + z + " from " + mob.position);
        check(mob.position.distanceSquared(new Vector3f(x + .5f, FLOOR + 15f / 16, z + .5f)) < 1.2f,
                "rabbit ate without physical proximity");
    }

    private static void eatAndRegrow() throws Exception {
        int salt = 1;
        for (BlockType crop : new BlockType[]{BlockType.WHEAT, BlockType.CARROTS, BlockType.POTATOES}) {
            World world = blank();
            plant(world, 8, 8, crop, crop == BlockType.WHEAT ? 4 : 7);
            for (Chunk chunk : world.getLoadedChunks()) chunk.computeSkyLight();
            world.setSimulationDaylight(1f);
            Mob mob = rabbit(3.5f, 8.5f, salt++);
            reach(world, mob, 8, 8);
            check(world.getBlock(8, PLANT, 8) == crop, "eating removed the plant");
            check(world.pendingScheduledTicks() > 0, "eaten crop has no regrowth tick");
            world.runScheduledTicks(3400, 512, 48000);
            check(world.getBlockMeta(8, PLANT, 8) == 1, "eaten crop did not regrow normally");
            plant(world, 9, 8, crop, 7);
            step(world, mob, 200);
            check(world.getBlockMeta(9, PLANT, 8) == 7, "sated rabbit immediately ate again");
        }
    }

    private static void enclosure(World world, boolean gate, boolean open) {
        for (int x = 5; x <= 9; x++) { world.setBlock(x, PLANT, 5, BlockType.FENCE); world.setBlock(x, PLANT, 9, BlockType.FENCE); }
        for (int z = 6; z < 9; z++) { world.setBlock(5, PLANT, z, BlockType.FENCE); world.setBlock(9, PLANT, z, BlockType.FENCE); }
        if (gate) world.setBlock(5, PLANT, 7, BlockType.FENCE_GATE, (byte) (open ? 5 : 1));
        else if (open) world.setBlock(5, PLANT, 7, BlockType.AIR);
        plant(world, 7, 7, BlockType.CARROTS, 7);
    }

    private static void closedBarrier() throws Exception {
        for (boolean gate : new boolean[]{false, true}) {
            World world = blank(); enclosure(world, gate, false);
            Mob mob = rabbit(4.05f, 7.5f, gate ? 5 : 4);
            float highest = mob.position.y;
            for (int n = 0; n < 800; n++) {
                mob.update(world, PLAYER_FAR, .05f, 1f, false);
                highest = Math.max(highest, mob.position.y);
                check(world.getBlockMeta(7, PLANT, 7) == 7, "rabbit ate through a closed barrier");
            }
            check(highest < PLANT + 1.5f, "rabbit climbed over a tall fence");
        }
    }

    private static void openBarrier() throws Exception {
        for (boolean gate : new boolean[]{false, true}) {
            World world = blank(); enclosure(world, gate, true);
            Mob mob = rabbit(3.5f, 6.5f, gate ? 7 : 6);
            reach(world, mob, 7, 7);
            check(mob.position.x > 5.5f, "rabbit ate without passing the gap");
        }
    }

    private static void priorities() throws Exception {
        World world = blank(); plant(world, 8, 8, BlockType.POTATOES, 7);
        Mob rabbit = rabbit(8.5f, 8.5f, 8);
        rabbit.update(world, new Vector3f(10, PLANT, 8), .05f, 1f, false);
        check(rabbit.state == Mob.State.FLEE && world.getBlockMeta(8, PLANT, 8) == 7, "player fear lost to food");

        rabbit = rabbit(8.5f, 8.5f, 9);
        Mob wolf = new Mob(MobType.WOLF, 10.5f, PLANT, 8.5f, new Random(42));
        Wildlife.sense(List.of(rabbit, wolf));
        rabbit.update(world, PLAYER_FAR, .05f, 1f, false);
        check(rabbit.state == Mob.State.FLEE && world.getBlockMeta(8, PLANT, 8) == 7, "wolf fear lost to food");

        rabbit = rabbit(8.5f, 8.5f, 10); rabbit.state = Mob.State.FLEE;
        step(world, rabbit, 1);
        check(world.getBlockMeta(8, PLANT, 8) == 7, "existing flight ate a crop");

        rabbit = rabbit(8.5f, 8.5f, 11); rabbit.foodTarget = new Vector3f(15.5f, PLANT, 8.5f);
        step(world, rabbit, 80);
        check(world.getBlockMeta(8, PLANT, 8) == 7 && rabbit.position.x > 9f, "held-food temptation lost to crop food");

        world = blank(); plant(world, 12, 8, BlockType.CARROTS, 7);
        rabbit = rabbit(3.5f, 8.5f, 17);
        for (int n = 0; n < 35 && !rabbit.hasPath(); n++) step(world, rabbit, 1);
        check(rabbit.hasPath(), "sleeping temptation fixture never began a raid");
        rabbit.routine = MobTactics.Routine.SLEEP;
        rabbit.foodTarget = new Vector3f(rabbit.position);
        step(world, rabbit, 1);
        check(!rabbit.hasPath() && world.getBlockMeta(12, PLANT, 8) == 7,
                "temptation while sleeping retained the previous crop route");
    }

    private static void protectedAndLoadedOnly() throws Exception {
        World world = blank(); plant(world, 8, 8, BlockType.WHEAT, 3);
        Mob mob = rabbit(8.5f, 8.5f, 12);
        step(world, mob, 100);
        check(world.getBlockMeta(8, PLANT, 8) == 3, "rabbit ate immature plants");

        world = blank(); plant(world, 8, 8, BlockType.CARROTS, 7);
        world.getChunkIfExists(0, 0).markReadOnly();
        mob = rabbit(8.5f, 8.5f, 13); step(world, mob, 100);
        check(world.getBlockMeta(8, PLANT, 8) == 7, "raid changed a protected chunk");

        class LoadedOnlyWorld extends World {
            LoadedOnlyWorld() { super(71); }
            @Override public Chunk generateDetached(int cx, int cz) { throw new AssertionError("raid generated unloaded terrain"); }
        }
        world = new LoadedOnlyWorld(); world.publish(new Chunk(0, 0)); ground(world);
        world.simulate((stack, x, y, z) -> { throw new AssertionError("raid dropped loot"); }, 0);
        plant(world, 14, 8, BlockType.POTATOES, 7);
        mob = rabbit(9.5f, 8.5f, 14); reach(world, mob, 14, 8);
        int loaded = 0;
        for (Chunk ignored : world.getLoadedChunks()) loaded++;
        check(loaded == 1, "crop search published chunks");
    }

    private static void verticalSensing() throws Exception {
        World world = blank(); plant(world, 8, 8, BlockType.CARROTS, 7);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
            world.setBlock(x, FLOOR - 20, z, BlockType.GRASS);
        Mob rabbit = rabbit(3.5f, 8.5f, 19);
        Mob below = new Mob(MobType.ZOMBIE, 8.5f, PLANT - 20 + .001f, 8.5f, new Random(71));
        List<Mob> shared = List.of(rabbit, below);
        for (int n = 0; n < 800 && world.getBlockMeta(8, PLANT, 8) == 7; n++) {
            Wildlife.sense(shared, true);
            rabbit.update(world, PLAYER_FAR, .05f, 1f, false);
            below.update(world, PLAYER_FAR, .05f, 1f, true);
            check(rabbit.state != Mob.State.FLEE, "hostile twenty blocks below scared the elevated rabbit");
        }
        check(world.getBlockMeta(8, PLANT, 8) == 0,
                "rabbit did not physically eat its crop above the lower hostile");
        check(rabbit.position.distanceSquared(new Vector3f(8.5f, FLOOR + 15f / 16, 8.5f)) < 1.2f,
                "elevated crop was eaten without physical approach");

        plant(world, 8, 8, BlockType.CARROTS, 7);
        rabbit = rabbit(8.5f, 8.5f, 20);
        Mob near = new Mob(MobType.ZOMBIE, 10.5f, PLANT + .001f, 8.5f, new Random(72));
        Wildlife.sense(List.of(rabbit, near), true);
        step(world, rabbit, 1);
        check(rabbit.state == Mob.State.FLEE && world.getBlockMeta(8, PLANT, 8) == 7,
                "nearby same-level hostile fear lost to crop food");

        rabbit = rabbit(8.5f, 8.5f, 21);
        below.position.set(8.5f, PLANT - 20 + .001f, 8.5f);
        Wildlife.sense(List.of(rabbit, below));
        step(world, rabbit, 1);
        check(rabbit.state == Mob.State.FLEE && world.getBlockMeta(8, PLANT, 8) == 7,
                "legacy wildlife no longer uses its horizontal threat distance");

        Mob wolf = new Mob(MobType.WOLF, 8.5f, PLANT + .001f, 8.5f, new Random(73));
        Mob prey = rabbit(8.5f, 8.5f, 22); prey.position.y -= 20;
        hunger(wolf, 0f);
        Wildlife.sense(List.of(wolf, prey), true);
        step(world, wolf, 1);
        check(wolf.state != Mob.State.HUNT, "Living World wolf selected prey twenty blocks below");
        Wildlife.sense(List.of(wolf, prey));
        step(world, wolf, 1);
        check(wolf.state == Mob.State.HUNT, "legacy wolf prey sensing changed");

        wolf = new Mob(MobType.WOLF, 8.5f, PLANT + .001f, 8.5f, new Random(74));
        Mob lowerWolf = new Mob(MobType.WOLF, 8.5f, PLANT - 20 + .001f, 8.5f, new Random(75));
        wolf.hurt(1f, 9f, 8f);
        Wildlife.sense(List.of(wolf, lowerWolf), true);
        check(!lowerWolf.isAngry(), "Living World anger jumped to a pack twenty blocks below");
        Wildlife.sense(List.of(wolf, lowerWolf));
        check(lowerWolf.isAngry(), "legacy horizontal pack anger changed");

        wolf = new Mob(MobType.WOLF, 8.5f, PLANT + .001f, 8.5f, new Random(76));
        wolf.cropRaidsEnabled = true;
        hunger(wolf, 0f);
        Mob lowerPrey = rabbit(8.5f, 8.5f, 23); lowerPrey.position.y -= 4;
        world.setBlock(8, FLOOR - 4, 8, BlockType.GRASS);
        Wildlife.sense(List.of(wolf, lowerPrey), true);
        step(world, wolf, 1);
        check(wolf.state == Mob.State.HUNT && wolf.justBitMob == null,
                "Living World wolf bit sensed prey four blocks below through the floor");
        Mob reachablePrey = rabbit(9.5f, 8.5f, 24);
        Wildlife.sense(List.of(wolf, reachablePrey), true);
        step(world, wolf, 1);
        check(wolf.justBitMob == reachablePrey, "Living World wolf failed a physically close same-level bite");

        wolf = new Mob(MobType.WOLF, 8.5f, PLANT + .001f, 8.5f, new Random(77));
        hunger(wolf, 0f);
        Wildlife.sense(List.of(wolf, lowerPrey));
        step(world, wolf, 1);
        check(wolf.justBitMob == lowerPrey, "legacy horizontal wolf bite behavior changed");
    }

    private static void reachableAlternative() throws Exception {
        World world = blank(); enclosure(world, true, false);
        plant(world, 1, 11, BlockType.WHEAT, 7);
        Mob mob = rabbit(4.5f, 7.5f, 15);
        reach(world, mob, 1, 11);
        check(world.getBlockMeta(7, PLANT, 7) == 7, "alternative search breached the closed field");
    }

    private static void denseReachableAlternative() throws Exception {
        denseReachableAlternative(4, 60f);
        // More plants than the rejection cache also require forward progress.
        denseReachableAlternative(8, 150f);
    }

    private static void denseReachableAlternative(int side, float timeout) throws Exception {
        World world = blank();
        int minX = 6, minZ = 4, maxX = minX + side - 1, maxZ = minZ + side - 1;
        for (int x = minX - 1; x <= maxX + 1; x++) {
            world.setBlock(x, PLANT, minZ - 1, BlockType.FENCE);
            world.setBlock(x, PLANT, maxZ + 1, BlockType.FENCE);
        }
        for (int z = minZ; z <= maxZ; z++) {
            world.setBlock(minX - 1, PLANT, z, BlockType.FENCE);
            world.setBlock(maxX + 1, PLANT, z, BlockType.FENCE);
        }
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++)
            plant(world, x, z, BlockType.CARROTS, 7);
        int openX = maxX + 4;
        plant(world, openX, 7, BlockType.WHEAT, 7);
        Mob mob = rabbit(3.5f, 7.5f, 18);
        // Keep the search's distance ordering stable until a real route starts.
        // Otherwise random wandering could carry the rabbit past the closed field.
        Field idleTimer = Mob.class.getDeclaredField("stateTimer");
        idleTimer.setAccessible(true);
        idleTimer.setFloat(mob, timeout + 60f);
        for (int n = 0; n < (int) (timeout / .05f) && world.getBlockMeta(openX, PLANT, 7) == 7; n++) {
            mob.update(world, PLAYER_FAR, .05f, 1f, false);
            check(!mob.dead, "dense-field search rabbit died");
        }
        check(world.getBlockMeta(openX, PLANT, 7) == 0,
                "dense unreachable field starved the farther reachable crop from " + mob.position);
        check(mob.position.distanceSquared(new Vector3f(openX + .5f, FLOOR + 15f / 16, 7.5f)) < 1.2f,
                "dense-field alternative was eaten without physically following its route");
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++)
            check(world.getBlockMeta(x, PLANT, z) == 7, "dense-field search breached the closed fence");
    }

    private static void disabled() throws Exception {
        World world = blank(); plant(world, 12, 8, BlockType.CARROTS, 7);
        Mob mob = rabbit(3.5f, 8.5f, 16);
        for (int n = 0; n < 35 && !mob.hasPath(); n++) step(world, mob, 1);
        check(mob.hasPath(), "fixture never began the crop path");
        mob.routine = MobTactics.Routine.SLEEP;
        mob.cropRaidsEnabled = false;
        step(world, mob, 1);
        check(!mob.hasPath(), "disabled raid retained its navigation");
        mob.routine = MobTactics.Routine.PATROL;
        step(world, mob, 800);
        check(world.getBlockMeta(12, PLANT, 8) == 7, "disabled ecology allowed a rabbit crop raid");
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

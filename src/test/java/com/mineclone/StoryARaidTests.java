package com.mineclone;

import com.mineclone.data.ResourceId;
import com.mineclone.game.LocalParticipant;
import com.mineclone.game.Player;
import com.mineclone.sim.EntityStore;
import com.mineclone.sim.Participants;
import com.mineclone.sim.WorldClock;
import com.mineclone.sim.WorldEvents;
import com.mineclone.sim.WorldSession;
import com.mineclone.sim.ecology.Ecology;
import com.mineclone.sim.ecology.EcologyModel;
import com.mineclone.sim.ecology.EcologyRegion;
import com.mineclone.sim.ecology.SpeciesTable;
import com.mineclone.world.Biome;
import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.GameMode;
import com.mineclone.world.World;
import com.mineclone.world.behavior.Behaviors;
import com.mineclone.world.damage.DamageSource;
import com.mineclone.world.damage.DamageType;
import com.mineclone.world.entity.Mob;
import com.mineclone.world.entity.MobSpawner;
import com.mineclone.world.entity.MobType;
import java.util.List;
import java.util.Random;
import org.joml.Vector3f;

/** Focused LW-06 story A: seven-day predator release and real session raids through a gate. */
public final class StoryARaidTests {
    private static final long SEED = 20261002L;
    private static final ResourceId RABBIT = ResourceId.of("rabbit"), WOLF = ResourceId.of("wolf");
    private static final int FLOOR = 40, CROP = FLOOR + 1, OPEN_X = 8, CLOSED_X = 32, PLOT_Z = 14;

    public static void runAll(TestMain.Runner runner) {
        runner.run("story A: four of five wolves removed produces more rabbits after exactly seven days", StoryARaidTests::sevenDays);
        runner.run("story A: normal session rabbits eat an open field, preserve a closed field and enter its opened gate", StoryARaidTests::fields);
    }

    private static void check(boolean okay, String why) { if (!okay) throw new AssertionError(why); }

    private static void sevenDays() {
        SpeciesTable table = SpeciesTable.get();
        EcologyRegion released = EcologyRegion.initial(0, 0, 0, table, Biome.FOREST);
        released.population(RABBIT, 20); released.population(WOLF, 1);
        released.foodBonus(RABBIT, 16);
        EcologyRegion intact = released.copy(); intact.population(WOLF, 5);
        List<EcologyRegion> a = List.of(released), b = List.of(intact);
        for (int day = 1; day <= 7; day++) {
            a = EcologyModel.step(a, table, day, SEED);
            b = EcologyModel.step(b, table, day, SEED);
        }
        check(a.get(0).lastDay() == 7 && b.get(0).lastDay() == 7, "story skipped the seven-day boundary");
        check(a.get(0).population(RABBIT) > 20, "rabbit population failed to recover after wolf removal");
        check(a.get(0).population(RABBIT) > b.get(0).population(RABBIT), "removing four wolves did not increase rabbit pressure");
        check(released.population(RABBIT) == 20 && intact.population(WOLF) == 5, "daily model changed its initial fixtures");
    }

    private static final class Scenario {
        final World world = new World(SEED);
        final WorldClock clock = new WorldClock(Math.PI / 2);
        final EntityStore entities = new EntityStore();
        final Participants participants = new Participants();
        final Vector3f focus = new Vector3f(22.5f, FLOOR + 14f, 35.5f);
        final WorldSession session;
        final Mob[] wolves = new Mob[5];
        Mob openRabbit, closedRabbit;

        Scenario() throws Exception {
            for (int cx = 0; cx < 4; cx++) for (int cz = 0; cz < 3; cz++) world.publish(new Chunk(cx, cz));
            Player player = new Player(); player.position.set(focus); player.setGameMode(GameMode.CREATIVE);
            participants.put(new LocalParticipant(player, () -> 0));
            session = new WorldSession(world, clock, new MobSpawner(SEED), entities, participants,
                    new WorldSession.Host() {
                        public Vector3f mobFocus() { return focus; }
                        public boolean spawnMobs() { return false; }
                    }, WorldEvents.NONE);
            // Only crop growth is frozen: the asserted stage decreases must be caused by the rabbit brain.
            world.setScheduledTickHandler((w, x, y, z, block, meta, late) -> {
                if (block != BlockType.WHEAT && block != BlockType.CARROTS && block != BlockType.POTATOES)
                    World.ScheduledTickHandler.BEHAVIOURS.tick(w, x, y, z, block, meta, late);
            });
            for (int x = 0; x < 64; x++) for (int z = 0; z < 40; z++) world.setBlock(x, FLOOR, z, BlockType.GRASS);
            plot(OPEN_X); plot(CLOSED_X);
            // A small outer approach pen retains the test animal near the closed gate.
            for (int z = 6; z <= 19; z++) { fence(30, z); fence(37, z); }
            for (int x = 30; x <= 37; x++) { fence(x, 6); fence(x, 12); fence(x, 19); }
            world.setBlock(33, CROP, 12, BlockType.FENCE_GATE, (byte) 0);
            for (int x = 53; x <= 62; x++) for (int z = 24; z <= 31; z++)
                if (x == 53 || x == 62 || z == 24 || z == 31)
                    for (int y = CROP; y <= CROP + 2; y++) world.setBlock(x, y, z, BlockType.STONE);
            for (int i = 0; i < wolves.length; i++) {
                wolves[i] = new Mob(MobType.WOLF, 55.5f + i, CROP + .001f, 27.5f, new Random(SEED + i));
                wolves[i].onGround = true; entities.mobs.add(wolves[i]);
            }
            for (Chunk chunk : world.getLoadedChunks()) chunk.computeSkyLight();
            world.setSimulationDaylight(1f);
            session.ecology().setEnabled(true);
            EcologyRegion region = session.ecology().regionAt(8, 14);
            region.population(RABBIT, 20); region.population(WOLF, 5);
        }

        void plot(int left) {
            for (int x = left; x < left + 4; x++) for (int z = PLOT_Z; z < PLOT_Z + 4; z++) {
                world.setBlock(x, FLOOR, z, BlockType.FARMLAND, (byte) 7);
                world.setBlock(x, CROP, z, BlockType.CARROTS, (byte) 7);
                Behaviors.of(BlockType.CARROTS).onPlaced(world, x, CROP, z, (byte) 7);
            }
        }
        void fence(int x, int z) { world.setBlock(x, CROP, z, BlockType.FENCE); }
        void step(int count) {
            for (int i = 0; i < count; i++) {
                clock.advance(.05); session.tickMobs(.05f); session.tickScheduled();
            }
        }
        Mob rabbit(float x, float z, int salt) throws Exception {
            Mob body = new Mob(MobType.RABBIT, x, CROP + .001f, z, new Random(SEED + salt));
            body.onGround = true;
            var hunger = Mob.class.getDeclaredField("hungerTimer"); hunger.setAccessible(true); hunger.setFloat(body, 0f);
            check(session.ecology().claim(body), "fixture rabbit did not consume a finite population reservation");
            entities.mobs.add(body); return body;
        }
        int damaged(int left) {
            int result = 0;
            for (int x = left; x < left + 4; x++) for (int z = PLOT_Z; z < PLOT_Z + 4; z++) {
                check(world.getBlock(x, FLOOR, z) == BlockType.FARMLAND, "ordinary trampling changed the controlled field");
                check(world.getBlock(x, CROP, z) == BlockType.CARROTS, "a crop disappeared instead of being eaten");
                check(world.plantLight(x, CROP, z) >= 9, "crop fixture is too dark");
                if ((world.getBlockMeta(x, CROP, z) & 7) < 7) result++;
            }
            return result;
        }
    }

    private static void fields() throws Exception {
        Scenario f = new Scenario();
        check(f.session.ecology().regionAt(8, 14).population(WOLF) == 5, "story did not begin with five wolves");
        for (int i = 0; i < 4; i++) check(f.wolves[i].damage(DamageSource.byPlayer(DamageType.MELEE, 0,
                f.focus.x, f.focus.y, f.focus.z, 0f), 100f), "player wolf removal was refused");
        f.step(20);
        check(f.session.ecology().regionAt(8, 14).population(WOLF) == 1, "session did not account for four actual wolf deaths");
        check(f.session.ecology().regionAt(8, 14).lossCount(EcologyRegion.LossCause.PLAYER) == 4, "wolf deaths were not recorded once each");
        long before = Ecology.day(f.clock.gameTime()); f.session.ecology().advanceDays(7);
        check(Ecology.day(f.clock.gameTime()) == before + 7, "story advance did not pass exactly seven days");
        check(f.session.ecology().regionAt(8, 14).population(RABBIT) > 20, "seven days did not recover the regional rabbits");
        f.openRabbit = f.rabbit(9.5f, 11.5f, 20); f.closedRabbit = f.rabbit(33.5f, 10.5f, 21);
        f.step(400);
        check(f.damaged(OPEN_X) > 0, "normal session rabbits never damaged the open field");
        check(f.damaged(CLOSED_X) == 0, "a fully closed fence and gate failed to protect the equivalent field");
        check(f.closedRabbit.position.z < 12.5f, "rabbit crossed the closed gate");
        f.world.setBlock(33, CROP, 12, BlockType.FENCE_GATE, (byte) 4);
        f.step(600);
        check(f.damaged(CLOSED_X) > 0, "opening the gate did not grant the rabbit access to its crop");
        check(!f.openRabbit.dead && !f.closedRabbit.dead && f.openRabbit.id != f.closedRabbit.id,
                "the story lost or duplicated its finite rabbit bodies");
    }
}

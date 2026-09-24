package com.mineclone;

import com.mineclone.sim.Participant;
import com.mineclone.sim.Participants;
import com.mineclone.sim.TargetSelector;
import com.mineclone.world.BlockType;
import com.mineclone.world.GameMode;
import com.mineclone.world.GenProfile;
import com.mineclone.world.World;
import com.mineclone.world.damage.ArmorView;
import com.mineclone.world.damage.DamageSource;
import com.mineclone.world.entity.Mob;
import com.mineclone.world.entity.MobType;
import com.mineclone.world.gen.GenPolicy;
import com.mineclone.world.gen.WorldGenVersion;
import java.util.Random;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * SIM-07: who a mob goes after when more than one player is there. The world
 * is the flat debug profile — ground at 64, air above — with walls built where
 * a case needs one.
 */
final class TargetSelectionTests {
    static void runAll(TestMain.Runner r) {
        r.run("a new target is the nearest one the mob can see", TargetSelectionTests::nearestVisible);
        r.run("creative and dead players are never targets", TargetSelectionTests::creativeIgnored);
        r.run("a target out of sight for three seconds is given up", TargetSelectionTests::lostSight);
        r.run("a mob keeps its target unless another is markedly nearer", TargetSelectionTests::hysteresis);
        r.run("whoever hit the mob comes first while it holds the grudge", TargetSelectionTests::revenge);
        r.run("a lone candidate is always the target, seen or not", TargetSelectionTests::lone);
    }

    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    private static final float GROUND = 65f;

    /** A participant standing where the test puts it. */
    private static final class Player implements Participant {
        final int id;
        final Vector3f at = new Vector3f();
        private final Vector3f eye = new Vector3f();
        GameMode mode = GameMode.SURVIVAL;
        boolean alive = true;

        Player(int id, float x, float z) {
            this.id = id;
            at.set(x, GROUND, z);
        }

        @Override public int id() { return id; }
        @Override public Vector3fc position() { return at; }
        @Override public Vector3fc eye() { return eye.set(at.x, at.y + 1.62f, at.z); }
        @Override public GameMode mode() { return mode; }
        @Override public boolean alive() { return alive; }
        @Override public ArmorView armor() { return ArmorView.NONE; }
        @Override public boolean local() { return false; }
        @Override public boolean damage(DamageSource source, float amount) { return true; }
    }

    private static World flat() {
        World world = new World(3L, GenProfile.FLAT, GenPolicy.fixed(WorldGenVersion.V1));
        for (int cx = -2; cx <= 2; cx++)
            for (int cz = -2; cz <= 2; cz++)
                world.getChunk(cx, cz);
        return world;
    }

    /** A stone wall across z from x0 to x1, three blocks tall. */
    private static void wall(World world, int x0, int x1, int z) {
        for (int x = x0; x <= x1; x++)
            for (int y = 65; y < 68; y++)
                world.setBlock(x, y, z, BlockType.STONE);
    }

    private static Mob zombie(float x, float z) {
        return new Mob(MobType.ZOMBIE, x, GROUND, z, new Random(7));
    }

    private static Participants of(Participant... players) {
        Participants all = new Participants();
        for (Participant p : players) all.put(p);
        return all;
    }

    private static void nearestVisible() {
        World world = flat();
        wall(world, -3, 3, 3);
        Mob mob = zombie(0.5f, 0.5f);
        Player hidden = new Player(1, 0.5f, 6.5f);   // nearer, behind the wall
        Player open = new Player(2, 9.5f, 0.5f);     // farther, in the open
        check(TargetSelector.select(mob, of(hidden, open), world, 0.05f) == open,
                "the mob chose someone it cannot see over someone it can");
    }

    private static void creativeIgnored() {
        World world = flat();
        Mob mob = zombie(0.5f, 0.5f);
        Player builder = new Player(1, 2.5f, 0.5f);
        Player survivor = new Player(2, 8.5f, 0.5f);
        builder.mode = GameMode.CREATIVE;
        check(TargetSelector.select(mob, of(builder, survivor), world, 0.05f) == survivor, "creative player targeted");
        survivor.alive = false;
        check(TargetSelector.select(mob, of(builder, survivor), world, 0.05f) == null,
                "a mob found a target among the creative and the dead");
        check(mob.targetId == DamageSource.NO_ATTACKER, "the mob remembers a target it no longer has");
    }

    private static void lostSight() {
        World world = flat();
        Mob mob = zombie(0.5f, 0.5f);
        Player first = new Player(1, 4.5f, 0.5f);
        Player second = new Player(2, 0.5f, 7.5f);
        Participants all = of(first, second);
        check(TargetSelector.select(mob, all, world, 0.05f) == first, "the nearer visible player was not chosen");
        // The first ducks behind a wall; the second, farther, stays in view.
        for (int z = -2; z <= 2; z++)
            for (int y = 65; y < 68; y++)
                world.setBlock(3, y, z, BlockType.STONE);
        float waited = 0f;
        while (waited < TargetSelector.LOST_TIME - 0.1f) {
            check(TargetSelector.select(mob, all, world, 0.1f) == first,
                    "gave up the target after only " + waited + " s out of sight");
            waited += 0.1f;
        }
        Participant after = null;
        for (int i = 0; i < 5; i++) after = TargetSelector.select(mob, all, world, 0.1f);
        check(after == second, "a target lost for more than three seconds was kept");
    }

    private static void hysteresis() {
        World world = flat();
        Mob mob = zombie(0.5f, 0.5f);
        Player current = new Player(1, 10.5f, 0.5f);
        Player other = new Player(2, 0.5f, 12.5f);
        Participants all = of(current, other);
        check(TargetSelector.select(mob, all, world, 0.05f) == current, "the nearest was not the first target");
        other.at.set(0.5f, GROUND, 8.5f);            // 20 % nearer than the current target
        check(TargetSelector.select(mob, all, world, 0.05f) == current, "switched for a 20 % difference");
        other.at.set(0.5f, GROUND, 6.5f);            // 40 % nearer
        check(TargetSelector.select(mob, all, world, 0.05f) == other, "kept a target 40 % farther than another");
    }

    private static void revenge() {
        World world = flat();
        Mob wolf = new Mob(MobType.WOLF, 0.5f, GROUND, 0.5f, new Random(3));
        Player near = new Player(1, 2.5f, 0.5f);
        Player archer = new Player(2, 0.5f, 12.5f);
        Participants all = of(near, archer);
        check(TargetSelector.select(wolf, all, world, 0.05f) == near, "the nearest was not the first target");
        wolf.hurtBy(1f, archer.at.x, archer.at.z, 0f, archer.id);
        check(TargetSelector.select(wolf, all, world, 0.05f) == archer, "the wolf ignored who hit it");
        for (float t = 0f; t < Mob.REVENGE_TIME - 1f; t += 1f)
            check(TargetSelector.select(wolf, all, world, 1f) == archer, "the grudge faded after " + t + " s");
        for (int i = 0; i < 3; i++) TargetSelector.select(wolf, all, world, 1f);
        // The grudge is spent: the archer is kept only while no one is markedly nearer.
        check(TargetSelector.select(wolf, all, world, 0.05f) == near, "the grudge outlived its time");
    }

    /** The property the single-player parity hash rests on. */
    private static void lone() {
        World world = flat();
        wall(world, -5, 5, 3);
        Mob mob = zombie(0.5f, 0.5f);
        Player alone = new Player(1, 0.5f, 60.5f);   // far away and behind a wall
        for (int i = 0; i < 100; i++)
            check(TargetSelector.select(mob, of(alone), world, 0.1f) == alone, "a lone survivor was not the target");
    }
}

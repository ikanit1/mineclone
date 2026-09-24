package com.mineclone;

import com.mineclone.game.Player;
import com.mineclone.sim.WorldClock;
import com.mineclone.world.Chunk;
import com.mineclone.world.GenProfile;
import com.mineclone.world.World;
import com.mineclone.world.entity.EntityPhysics;
import com.mineclone.world.entity.Mob;
import com.mineclone.world.entity.MobHerd;
import com.mineclone.world.entity.MobSpatialGrid;
import com.mineclone.world.entity.MobSpawner;
import com.mineclone.world.entity.MobTactics;
import com.mineclone.world.entity.MobType;
import com.mineclone.world.entity.Projectile;
import com.mineclone.world.entity.Wildlife;
import com.mineclone.world.gen.GenPolicy;
import com.mineclone.world.gen.WorldGenVersion;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import org.joml.Vector3f;

/**
 * SIM-03: the mob loop keeps behaving exactly as it did in {@code Game.updateMobs}.
 *
 * <p>One scenario — seed 20260922 at night, nine placed mobs of every temper and
 * attack style, some standing in each other and in the way, a participant
 * walking a fixed path, natural spawning, 600 ticks of 50 ms — is hashed tick by
 * tick: every mob's position, yaw, health, state, fuse and fire, every blow,
 * explosion, drop and arrow. The expected hash was
 * recorded before the loop moved, by {@link LegacyMobLoop}: {@code Game.updateMobs}
 * at a8ec5d1 with only the sound and particle calls taken out.
 */
final class SessionParityTests {
    static void runAll(TestMain.Runner r) {
        r.run("the mob loop reproduces the hash recorded from Game.updateMobs", SessionParityTests::etalon);
        r.run("the parity scenario exercises blows, arrows, explosions, drops and spawning", SessionParityTests::coverage);
    }

    /**
     * Recorded from {@link LegacyMobLoop}; see the class comment. The run holds two
     * blows, one explosion, two deaths with drops, 22 spawns (29 mobs at the peak),
     * two arrows and six strikes by the participant. Pushing each pair twice, or
     * never out of the participant, changes it.
     */
    static final String ETALON = "162d54b2d69799238388ec79ba2117b38d079d75a8140e6d0feabcd1a2fc8e4f";

    static final long SEED = 20260922L;
    static final int TICKS = 600;
    static final float DT = 0.05f;
    /** Deep night: hostiles hunt and spawn on the surface. */
    static final double NIGHT = 4.0;
    /**
     * Every temper and attack style, plus a pair of cows standing in one another
     * on the participant's path and a pig where it stops: pushing mobs apart and
     * out of the participant's body is part of what is pinned.
     */
    private static final MobType[] PLACED = {
            MobType.ZOMBIE, MobType.SKELETON, MobType.SPIDER, MobType.CREEPER, MobType.COW, MobType.WOLF,
            MobType.COW, MobType.COW, MobType.PIG };
    private static final float[][] OFFSETS = { { 6, 3 }, { 17, 7 }, { 10, 10 }, { 22, 2 }, { -5, 5 }, { 4, -6 },
            { 4.5f, 2.35f }, { 4.6f, 2.4f }, { 15, 0.42f } };
    private static final int WALK_TICKS = 200;
    private static final int HIT_EVERY = 40;
    private static final float HIT_REACH = 4f, HIT_DAMAGE = 7f;

    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    /** Everything a mob loop reads and writes, and a record of what it did. */
    static final class Scenario {
        final World world = new World(SEED, GenProfile.NORMAL, GenPolicy.fixed(WorldGenVersion.V1));
        final WorldClock clock = new WorldClock(NIGHT);
        final MobSpawner spawner = new MobSpawner(SEED ^ 0x51E7B0BL);
        final List<Mob> mobs = new ArrayList<>();
        final Vector3f focus = new Vector3f();
        final List<Projectile> shots = new ArrayList<>();
        final MessageDigest digest;
        final DataOutputStream log;
        int struck, exploded, loot, spawned, maxMobs, hits, arrows;

        Scenario() throws Exception {
            digest = MessageDigest.getInstance("SHA-256");
            log = new DataOutputStream(new java.security.DigestOutputStream(OutputStreamNull.INSTANCE, digest));
            for (int cx = -4; cx <= 8; cx++)
                for (int cz = -4; cz <= 4; cz++)
                    world.getChunk(cx, cz);
            path(0);
            for (int i = 0; i < PLACED.length; i++) {
                float x = focus.x + OFFSETS[i][0], z = focus.z + OFFSETS[i][1];
                mobs.add(new Mob(PLACED[i], x, surface(x, z) + 0.05f, z, new Random(SEED + i)));
            }
        }

        /** The participant walks east for ten seconds, then stands and lets the night come to it. */
        void path(int tick) {
            float t = Math.min(tick, WALK_TICKS) * DT;
            float x = 8.5f + 1.5f * t;
            float z = 8.5f + 3f * (float) Math.sin(t * 0.3);
            focus.set(x, surface(x, z), z);
        }

        /**
         * Every {@link #HIT_EVERY} ticks the participant strikes the nearest living
         * mob within reach, as a player's click would between two frames: deaths,
         * drops and the pack's reaction are then part of every run.
         */
        void strike(int tick) {
            if (tick % HIT_EVERY != 0) return;
            Mob nearest = null;
            float best = HIT_REACH * HIT_REACH;
            for (Mob m : mobs) {
                float d = m.position.distanceSquared(focus);
                if (!m.dead && d < best) { best = d; nearest = m; }
            }
            if (nearest == null) return;
            hits++;
            nearest.hurt(HIT_DAMAGE, focus.x, focus.z, 1f, true);
            write('H', nearest);
        }

        float surface(float x, float z) {
            int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
            for (int y = Chunk.SIZE_Y - 1; y > 0; y--)
                if (world.getBlock(bx, y, bz).solid) return y + 1f;
            return 1f;
        }

        void struck(Mob m) {
            struck++;
            write('S', m);
            writeFloat(m.attackDamage());
        }

        void exploded(Mob m) {
            exploded++;
            write('E', m);
        }

        void loot(Mob m) {
            loot++;
            write('L', m);
            writeInt((m.eaten ? 1 : 0) | (m.killedByParticipant ? 2 : 0));
        }

        private void write(char kind, Mob m) {
            writeInt(kind);
            writeInt(m.type.ordinal());
            writeFloat(m.position.x); writeFloat(m.position.y); writeFloat(m.position.z);
        }

        void endTick(int tick) {
            writeInt(tick);
            writeInt(mobs.size());
            maxMobs = Math.max(maxMobs, mobs.size());
            for (Mob m : mobs) {
                writeInt(m.type.ordinal());
                writeFloat(m.position.x); writeFloat(m.position.y); writeFloat(m.position.z);
                writeFloat(m.yaw); writeFloat(m.health); writeFloat(m.fuse);
                writeInt(m.state.ordinal() | (m.dead ? 1 << 8 : 0) | (m.burning ? 1 << 9 : 0));
            }
            writeInt(shots.size());
            arrows += shots.size();
            for (Projectile p : shots) {
                writeFloat(p.velocity.x); writeFloat(p.velocity.y); writeFloat(p.velocity.z);
            }
            shots.clear();
        }

        private void writeInt(int value) {
            try { log.writeInt(value); } catch (IOException e) { throw new AssertionError(e); }
        }

        private void writeFloat(float value) { writeInt(Float.floatToIntBits(value)); }

        String hash() { return HexFormat.of().formatHex(digest.digest()); }
    }

    /** A sink that only feeds the digest. */
    private static final class OutputStreamNull extends java.io.OutputStream {
        static final OutputStreamNull INSTANCE = new OutputStreamNull();
        @Override public void write(int b) {}
        @Override public void write(byte[] b, int off, int len) {}
    }

    interface Loop { void tick(float dt); }

    static Scenario run(java.util.function.Function<Scenario, Loop> factory) throws Exception {
        Scenario s = new Scenario();
        Loop loop = factory.apply(s);
        for (int tick = 1; tick <= TICKS; tick++) {
            s.clock.advance(DT);
            s.path(tick);
            s.strike(tick);
            int before = s.mobs.size();
            loop.tick(DT);
            if (s.mobs.size() > before) s.spawned += s.mobs.size() - before;
            s.endTick(tick);
        }
        return s;
    }

    private static void etalon() throws Exception {
        String legacy = run(LegacyMobLoop::new).hash();
        check(legacy.equals(run(LegacyMobLoop::new).hash()), "the scenario is not deterministic");
        check(legacy.equals(ETALON), "mob loop hash " + legacy + " differs from the recorded " + ETALON);
    }

    private static void coverage() throws Exception {
        Scenario s = run(LegacyMobLoop::new);
        check(s.struck > 0 && s.loot > 0 && s.spawned > 0 && s.maxMobs > PLACED.length && s.hits > 0
                        && s.arrows > 0 && s.exploded > 0,
                "scenario too quiet: struck=" + s.struck + " loot=" + s.loot + " spawned=" + s.spawned
                        + " max=" + s.maxMobs + " exploded=" + s.exploded + " hits=" + s.hits + " arrows=" + s.arrows);
    }

    /**
     * {@code Game.updateMobs} at a8ec5d1, simulation only. Sound, particles,
     * footprints and sound cues are gone; the blow, the explosion and the drop
     * call the scenario where the game called {@code takeAttackDamage},
     * {@code detonate} and {@code giveMobDrop}. The game was in survival, with
     * no torch in hand and no benchmark running.
     */
    static final class LegacyMobLoop implements Loop {
        private final Scenario s;
        private final MobSpatialGrid collisionGrid = new MobSpatialGrid();
        private float mobSenseTimer;
        private float mobSpawnTimer;

        LegacyMobLoop(Scenario s) { this.s = s; }

        @Override
        public void tick(float dt) {
            List<Mob> mobs = s.mobs;
            float daylight = s.clock.daylight();
            Vector3f playerPosition = s.focus;
            boolean hostileEnabled = true;
            mobSenseTimer -= dt;
            if (mobSenseTimer <= 0f) {
                mobSenseTimer = 0.20f;
                MobHerd.update(mobs);
                Wildlife.sense(mobs);
            }
            float dayPhase = s.clock.dayPhase();
            MobTactics.updateGroup(mobs, dayPhase, dt);
            boolean torchInHand = false;

            List<Mob> killedByWolves = null;
            Iterator<Mob> it = mobs.iterator();
            while (it.hasNext()) {
                Mob m = it.next();
                m.setPlayerTorch(torchInHand);
                m.shotSink = s.shots::add;
                if (!m.updateLod(s.world, playerPosition, dt, daylight, hostileEnabled)) continue;
                if (m.justBitMob != null) {
                    Mob prey = m.justBitMob;
                    if (prey.hurt(Wildlife.BITE_DAMAGE, m.position.x, m.position.z, 0.6f, false)) {
                        if (prey.dead) {
                            if (killedByWolves == null)
                                killedByWolves = new ArrayList<>();
                            killedByWolves.add(m);
                        }
                    }
                }
                if (m.justAttacked)
                    s.struck(m);
                if (m.justExploded)
                    s.exploded(m);
                if (m.dead && !m.deathEffectsDone) {
                    m.deathEffectsDone = true;
                    MobTactics.leaderFell(mobs, m);
                    s.loot(m);
                }
                if (m.dead && m.deathTimer <= 0f)
                    it.remove();
            }
            if (killedByWolves != null)
                for (Mob wolf : killedByWolves)
                    Wildlife.sate(wolf);

            collisionGrid.rebuild(mobs);
            java.util.IdentityHashMap<Mob, Integer> collisionOrder = new java.util.IdentityHashMap<>();
            for (int i = 0; i < mobs.size(); i++) collisionOrder.put(mobs.get(i), i);
            for (int i = 0; i < mobs.size(); i++) {
                Mob a = mobs.get(i);
                for (Mob b : collisionGrid.nearby(a, 2.0f)) {
                    if (b == a || collisionOrder.getOrDefault(b, Integer.MAX_VALUE) <= i)
                        continue;
                    EntityPhysics.separate(a.position, a.type.width, a.type.height, 0.25f,
                            b.position, b.type.width, b.type.height, 0.25f);
                }
                EntityPhysics.separate(playerPosition, Player.WIDTH, Player.HEIGHT, 0f,
                        a.position, a.type.width, a.type.height, 0.5f);
            }

            s.spawner.despawnFar(mobs, playerPosition);
            mobSpawnTimer -= dt;
            if (mobSpawnTimer <= 0f) {
                mobSpawnTimer = MobSpawner.TICK_INTERVAL;
                s.spawner.trySpawn(s.world, mobs, playerPosition, daylight);
            }
        }
    }
}

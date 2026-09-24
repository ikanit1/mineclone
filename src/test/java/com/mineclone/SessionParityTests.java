package com.mineclone;

import com.mineclone.sim.EntityStore;
import com.mineclone.sim.Participant;
import com.mineclone.sim.Participants;
import com.mineclone.sim.WorldClock;
import com.mineclone.sim.WorldEvents;
import com.mineclone.sim.WorldSession;
import com.mineclone.world.Chunk;
import com.mineclone.world.GameMode;
import com.mineclone.world.damage.ArmorView;
import com.mineclone.world.damage.DamageSource;
import com.mineclone.world.GenProfile;
import com.mineclone.world.World;
import com.mineclone.world.entity.Mob;
import com.mineclone.world.entity.MobSpawner;
import com.mineclone.world.entity.MobType;
import com.mineclone.world.entity.Projectile;
import com.mineclone.world.gen.GenPolicy;
import com.mineclone.world.gen.WorldGenVersion;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * SIM-03/SIM-04: the mob loop keeps behaving exactly as it did in {@code Game}.
 *
 * <p>One scenario — seed 20260922 at night, nine placed mobs of every temper and
 * attack style, some standing in each other and in the way, a participant
 * walking a fixed path, natural spawning, 600 ticks of 50 ms — is hashed tick by
 * tick: every mob's position, yaw, health, state, fuse and fire, every blow,
 * arrow and blast, and every block the run changes. The loop was recorded
 * before it moved (79318ba) by a transcription of {@code Game.updateMobs} at
 * a8ec5d1 with only the sound and particle calls taken out;
 * {@link WorldSession} reproduced it. The hash was recorded again, before
 * explosions and drops moved, with {@link Scenario#exploded} transcribing
 * {@code Game.detonate} at 701706f.
 */
final class SessionParityTests {
    static void runAll(TestMain.Runner r) {
        r.run("the session reproduces the hash recorded from Game.updateMobs", SessionParityTests::etalon);
        r.run("the parity scenario exercises blows, arrows, explosions, drops and spawning", SessionParityTests::coverage);
        r.run("what the game shows does not change what the server simulates", SessionParityTests::presentation);
        r.run("the dedicated server has no mob loop of its own", SessionParityTests::serverUsesSession);
    }

    /**
     * Recorded before the move; see the class comment. The run holds two blows,
     * one explosion with its crater, two deaths with drops, 22 spawns (29 mobs at
     * the peak), two arrows and six strikes by the participant. Pushing each pair
     * twice, or never out of the participant, changes it.
     */
    static final String ETALON = "fcc8d937381db87b2cfde58a2b0d766324ba9511cc78d87187252339dc5f2a91";

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
    /** The participant's number, as a guest's actor would be. */
    private static final int PARTICIPANT = 0;

    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    /** Everything a mob loop reads and writes, and a record of what it did. */
    static final class Scenario {
        final World world = new World(SEED, GenProfile.NORMAL, GenPolicy.fixed(WorldGenVersion.V1));
        final WorldClock clock = new WorldClock(NIGHT);
        final MobSpawner spawner = new MobSpawner(SEED ^ 0x51E7B0BL);
        final EntityStore entities = new EntityStore();
        final List<Mob> mobs = entities.mobs;
        final Vector3f focus = new Vector3f();
        final List<Projectile> shots = entities.projectiles;
        /** The participant as the session sees it: a body on the path that records every blast. */
        final Participants participants = new Participants();
        /** Every mob the run has seen; a creeper that blows up leaves the list within its tick. */
        final java.util.Set<Mob> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        final MessageDigest digest;
        final DataOutputStream log;
        int struck, spawned, maxMobs, hits, arrows, blasts, blocksChanged;

        Scenario() throws Exception {
            digest = MessageDigest.getInstance("SHA-256");
            log = new DataOutputStream(new java.security.DigestOutputStream(OutputStreamNull.INSTANCE, digest));
            for (int cx = -4; cx <= 8; cx++)
                for (int cz = -4; cz <= 4; cz++)
                    world.getChunk(cx, cz);
            // Every block the run changes — a blast crater — is part of the hash.
            world.setBlockObserver((x, y, z, old, now, meta) -> {
                blocksChanged++;
                writeInt('W'); writeInt(x); writeInt(y); writeInt(z); writeInt(now.ordinal()); writeInt(meta);
            });
            path(0);
            participants.put(new Walker());
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
            nearest.hurtBy(HIT_DAMAGE, focus.x, focus.z, 1f, PARTICIPANT);
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

        /** The blast reached the participant; what it does to a player is the player's business. */
        void blast(float damage) {
            if (damage <= 0f) return;
            blasts++;
            writeInt('B');
            writeFloat(damage);
        }


        private void write(char kind, Mob m) {
            writeInt(kind);
            writeInt(m.type.ordinal());
            writeFloat(m.position.x); writeFloat(m.position.y); writeFloat(m.position.z);
        }

        /** The participant on its fixed path: the blast is recorded, the path does not change. */
        private final class Walker implements Participant {
            @Override public int id() { return PARTICIPANT; }
            @Override public Vector3fc position() { return focus; }
            @Override public Vector3fc eye() { return focus; }
            @Override public GameMode mode() { return GameMode.SURVIVAL; }
            @Override public boolean alive() { return true; }
            @Override public ArmorView armor() { return ArmorView.NONE; }
            @Override public boolean local() { return true; }

            @Override
            public boolean damage(DamageSource source, float amount) {
                blast(amount);
                return true;
            }
        }

        void endTick(int tick) {
            writeInt(tick);
            writeInt(mobs.size());
            maxMobs = Math.max(maxMobs, mobs.size());
            for (Mob m : mobs) {
                seen.add(m);
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

        int deaths() { return (int) seen.stream().filter(m -> m.dead).count(); }
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

    /** The session as the game runs it: around its own player, who takes the blows and the blasts. */
    static Loop session(Scenario s, WorldEvents events) {
        WorldSession.Host host = new WorldSession.Host() {
            @Override public Vector3f mobFocus() { return s.focus; }
            @Override public boolean hostileMobs() { return true; }
            @Override public boolean focusIsBody() { return true; }
            @Override public void mobStruck(Mob m) { s.struck(m); }
        };
        return new WorldSession(s.world, s.clock, s.spawner, s.entities, s.participants, host, events)::tickMobs;
    }

    private static void etalon() throws Exception {
        String hash = run(s -> session(s, WorldEvents.NONE)).hash();
        check(hash.equals(run(s -> session(s, WorldEvents.NONE)).hash()), "the scenario is not deterministic");
        check(hash.equals(ETALON), "mob loop hash " + hash + " differs from the recorded " + ETALON);
    }

    private static void coverage() throws Exception {
        Scenario s = run(x -> session(x, WorldEvents.NONE));
        check(s.struck > 0 && s.deaths() >= 2 && s.spawned > 0 && s.maxMobs > PLACED.length && s.hits > 0
                        && s.arrows > 0 && s.blasts > 0 && s.blocksChanged > 0,
                "scenario too quiet: struck=" + s.struck + " deaths=" + s.deaths() + " spawned=" + s.spawned
                        + " max=" + s.maxMobs + " crater=" + s.blocksChanged
                        + " blasts=" + s.blasts + " hits=" + s.hits + " arrows=" + s.arrows);
    }

    /** Counts what would be shown; a presentation reads and never writes. */
    private static final class Shown implements WorldEvents {
        int voices, splashes, rages, takeoffs, bites, steps, burning, deaths;
        @Override public void mobVoice(Mob m) { voices++; }
        @Override public void mobSplash(Mob m) { splashes++; }
        @Override public void mobEnraged(Mob m) { rages++; }
        @Override public void mobTookOff(Mob m) { takeoffs++; }
        @Override public void mobBit(Mob predator, Mob prey) { bites++; }
        @Override public void mobStep(Mob m) { steps++; }
        @Override public void mobBurning(Mob m) { burning++; }
        @Override public void mobDied(Mob m) { deaths++; }
    }

    private static void presentation() throws Exception {
        Shown shown = new Shown();
        Scenario s = run(x -> session(x, shown));
        check(s.hash().equals(ETALON), "presenting the mobs changed the simulation");
        check(shown.voices > 0 && shown.steps > 0 && shown.rages > 0, "events never reached the presentation: voices="
                + shown.voices + " steps=" + shown.steps + " rages=" + shown.rages);
        check(shown.deaths == s.deaths(), "each death is shown once: " + shown.deaths + " vs " + s.deaths());
    }

    private static void serverUsesSession() throws Exception {
        String source = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/com/mineclone/server/DedicatedServer.java"));
        for (String rule : new String[] { "updateLod(", "MobHerd", "Wildlife", "MobTactics", "despawnFar", "trySpawn" })
            check(!source.contains(rule), "DedicatedServer runs mob rules itself again: " + rule);
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("mineclone-session-server-");
        com.mineclone.server.DedicatedServer server = null;
        try {
            java.nio.file.Path config = root.resolve("server.properties");
            java.nio.file.Files.writeString(config, "saves-dir=" + root.toString().replace('\\', '/')
                    + "\nworld=mobs\nseed=" + SEED + "\ndirect=false\nphoton=false\nupnp=false\n");
            server = new com.mineclone.server.DedicatedServer(com.mineclone.server.ServerConfig.load(config.toFile()));
            var open = com.mineclone.server.DedicatedServer.class.getDeclaredMethod("openWorld");
            open.setAccessible(true);
            check((boolean) open.invoke(server), "server refused to create a world");
            WorldSession session = (WorldSession) field(server, "session");
            check(session != null && session.entities().mobs == server.mobs(),
                    "the server's mobs are not the session's");
        } finally {
            if (server != null) {
                var loader = (com.mineclone.world.ChunkLoader) field(server, "loader");
                if (loader != null) loader.shutdown();
                ((com.mineclone.save.SaveManager) field(server, "save")).flushAndAwait();
            }
            try (var paths = java.nio.file.Files.walk(root)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
                    java.nio.file.Files.deleteIfExists(path);
            }
        }
    }

    private static Object field(Object owner, String name) throws Exception {
        var f = owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(owner);
    }
}

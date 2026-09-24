package com.mineclone;

import com.mineclone.sim.EntityStore;
import com.mineclone.sim.Participant;
import com.mineclone.sim.Participants;
import com.mineclone.sim.WorldClock;
import com.mineclone.sim.WorldEvents;
import com.mineclone.sim.WorldSession;
import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.Furnace;
import com.mineclone.world.GameMode;
import com.mineclone.world.GenProfile;
import com.mineclone.world.ItemStack;
import com.mineclone.world.Weather;
import com.mineclone.world.World;
import com.mineclone.world.WorldSimulation;
import com.mineclone.world.damage.ArmorView;
import com.mineclone.world.damage.DamageSource;
import com.mineclone.world.entity.Mob;
import com.mineclone.world.entity.MobSpawner;
import com.mineclone.world.entity.MobType;
import com.mineclone.world.gen.GenPolicy;
import com.mineclone.world.gen.WorldGenVersion;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * SIM-05: block ticks, furnaces, spawning and despawning live around every
 * player, not around the first one — and a lone player's world is unchanged.
 */
final class WorldAroundEveryoneTests {
    static void runAll(TestMain.Runner r) {
        r.run("a furnace by a second player 300 blocks away smelts", WorldAroundEveryoneTests::farFurnace);
        r.run("a chunk near two players ticks once", WorldAroundEveryoneTests::tickedOnce);
        r.run("mobs spawn around both players and despawn by the nearest", WorldAroundEveryoneTests::mobsAroundBoth);
        r.run("the server's block ticks get the weather front's precipitation", WorldAroundEveryoneTests::serverWeather);
    }

    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    private static final float FAR = 300f;

    /** A player standing where the test puts it. */
    private static final class Player implements Participant {
        final int id;
        final Vector3f at;
        private final Vector3f eye = new Vector3f();

        Player(int id, float x, float y, float z) {
            this.id = id;
            this.at = new Vector3f(x, y, z);
        }

        @Override public int id() { return id; }
        @Override public Vector3fc position() { return at; }
        @Override public Vector3fc eye() { return eye.set(at.x, at.y + 1.62f, at.z); }
        @Override public GameMode mode() { return GameMode.SURVIVAL; }
        @Override public boolean alive() { return true; }
        @Override public ArmorView armor() { return ArmorView.NONE; }
        @Override public boolean local() { return false; }
        @Override public boolean damage(DamageSource source, float amount) { return true; }
    }

    private static World flat(float... centresX) {
        World world = new World(11L, GenProfile.FLAT, GenPolicy.fixed(WorldGenVersion.V1));
        for (float x : centresX) {
            int cx = (int) Math.floor(x / Chunk.SIZE_X);
            for (int dx = -5; dx <= 5; dx++)
                for (int cz = -5; cz <= 5; cz++)
                    world.getChunk(cx + dx, cz);
        }
        return world;
    }

    /** A lit furnace with coal and eight iron ore. */
    private static Furnace furnace(World world, int x, int z) {
        world.setBlock(x, 65, z, BlockType.FURNACE);
        Furnace f = world.createFurnace(x, 65, z);
        f.fuel = ItemStack.of("coal", 8);
        f.input = ItemStack.of("iron_ore", 8);
        return f;
    }

    private static void farFurnace() {
        World world = flat(0f, FAR);
        Furnace near = furnace(world, 3, 3);
        Furnace far = furnace(world, (int) FAR + 3, 3);
        WorldSimulation simulation = new WorldSimulation(world.seed);
        List<Vector3fc> both = List.of(new Vector3f(0.5f, 65f, 0.5f), new Vector3f(FAR + 0.5f, 65f, 0.5f));
        for (int i = 0; i < 20 * 30; i++)
            simulation.update(world, 0.05f, both, 0f, true);
        check(near.output != null && far.output != null,
                "a furnace smelted only by the first player: near=" + near.output + " far=" + far.output);

        World alone = flat(0f, FAR);
        Furnace unattended = furnace(alone, (int) FAR + 3, 3);
        WorldSimulation lone = new WorldSimulation(alone.seed);
        for (int i = 0; i < 20 * 30; i++)
            lone.update(alone, 0.05f, List.of(new Vector3f(0.5f, 65f, 0.5f)), 0f, true);
        check(unattended.output == null, "a furnace 300 blocks from everyone smelted");
    }

    /**
     * Two players in one chunk must not tick its neighbourhood twice: grass would
     * spread and furnaces cook at double speed. The union visits each chunk once
     * and in the lone order, so the world evolves exactly as for one player.
     */
    private static void tickedOnce() throws Exception {
        String lone = evolve(List.of(new Vector3f(8.5f, 65f, 8.5f)));
        String pair = evolve(List.of(new Vector3f(8.5f, 65f, 8.5f), new Vector3f(12.5f, 65f, 11.5f)));
        check(lone.equals(pair), "a second player next to the first changed how the world ticks");
    }

    /** Dirt beside grass and a furnace, ticked for a minute; the hash of what came of it. */
    private static String evolve(List<Vector3fc> around) throws Exception {
        World world = flat(0f);
        for (int x = -40; x < 40; x++)
            for (int z = -40; z < 40; z++)
                if (((x * 7 + z * 13) & 3) == 0)
                    world.setBlock(x, 64, z, BlockType.DIRT);
        Furnace f = furnace(world, 5, 5);
        WorldSimulation simulation = new WorldSimulation(world.seed);
        for (int i = 0; i < 20 * 60; i++)
            simulation.update(world, 0.05f, around, 0f, true);
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        for (int cx = -3; cx <= 3; cx++)
            for (int cz = -3; cz <= 3; cz++)
                sha.update(world.getChunk(cx, cz).copyBlocks());
        sha.update((f.output == null ? "none" : f.output.count + "").getBytes());
        sha.update(Float.toString(f.cookFraction()).getBytes());
        return HexFormat.of().formatHex(sha.digest());
    }

    private static void mobsAroundBoth() {
        World world = new World(20260922L, GenProfile.NORMAL, GenPolicy.fixed(WorldGenVersion.V1));
        for (int cx = -5; cx <= 5; cx++)
            for (int cz = -5; cz <= 5; cz++) {
                world.getChunk(cx, cz);
                world.getChunk(cx + (int) (FAR / Chunk.SIZE_X), cz);
            }
        Participants players = new Participants();
        Player first = new Player(1, 8.5f, surface(world, 8.5f, 8.5f), 8.5f);
        Player second = new Player(2, FAR + 8.5f, surface(world, FAR + 8.5f, 8.5f), 8.5f);
        players.put(first);
        players.put(second);
        EntityStore entities = new EntityStore();
        WorldClock night = new WorldClock(4.0);
        WorldSession.Host host = () -> new Vector3f(8.5f, 70f, 8.5f);
        WorldSession session = new WorldSession(world, night, new MobSpawner(99L), entities, players, host,
                WorldEvents.NONE);
        // A mob by the second player is nobody's to despawn: it is near someone.
        Mob kept = new Mob(MobType.COW, second.at.x + 3f, second.at.y, second.at.z, new Random(1));
        entities.mobs.add(kept);
        for (int i = 0; i < 20 * 20; i++)
            session.tickMobs(0.05f);
        check(entities.mobs.contains(kept), "a mob beside the second player was despawned by distance from the first");
        int nearFirst = 0, nearSecond = 0;
        for (Mob m : entities.mobs) {
            if (m.position.distance(first.at) < MobSpawner.DESPAWN_RADIUS) nearFirst++;
            if (m.position.distance(second.at) < MobSpawner.DESPAWN_RADIUS && m != kept) nearSecond++;
        }
        check(nearFirst > 0 && nearSecond > 0,
                "mobs spawned around one player only: first=" + nearFirst + " second=" + nearSecond);
        check(entities.mobs.size() <= (MobSpawner.PEACEFUL_CAP + MobSpawner.HOSTILE_CAP + MobSpawner.WILDLIFE_CAP
                + MobSpawner.PACK_MAX) * 2 + 1, "caps ignored: " + entities.mobs.size());
    }

    private static float surface(World world, float x, float z) {
        for (int y = Chunk.SIZE_Y - 1; y > 0; y--)
            if (world.getBlock((int) Math.floor(x), y, (int) Math.floor(z)).solid) return y + 1f;
        return 1f;
    }

    /** Regression: the server passed zero, so snow never settled and rain never put a fire out there. */
    private static void serverWeather() throws Exception {
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("mineclone-server-weather-");
        try {
            java.nio.file.Path config = root.resolve("server.properties");
            java.nio.file.Files.writeString(config, "saves-dir=" + root.toString().replace('\\', '/')
                    + "\nworld=w\nseed=5\ndirect=false\nphoton=false\nupnp=false\n");
            var server = new com.mineclone.server.DedicatedServer(com.mineclone.server.ServerConfig.load(config.toFile()));
            var open = com.mineclone.server.DedicatedServer.class.getDeclaredMethod("openWorld");
            open.setAccessible(true);
            check((boolean) open.invoke(server), "server refused to open");
            var clockField = com.mineclone.server.DedicatedServer.class.getDeclaredField("worldClock");
            clockField.setAccessible(true);
            WorldClock clock = (WorldClock) clockField.get(server);
            var precipitation = com.mineclone.server.DedicatedServer.class.getDeclaredMethod("precipitation");
            precipitation.setAccessible(true);
            boolean wet = false;
            for (int front = 0; front < 400 && !wet; front++) {
                clock.setGameTime(front * 0.7);
                float expected = Weather.sample(server.world().seed, clock.frontSeconds()).precipitation();
                float actual = (float) precipitation.invoke(server);
                check(actual == expected, "server precipitation " + actual + " is not the front's " + expected);
                wet = expected > 0.3f;
            }
            check(wet, "no wet front found to compare against");
            var loaderField = com.mineclone.server.DedicatedServer.class.getDeclaredField("loader");
            loaderField.setAccessible(true);
            ((com.mineclone.world.ChunkLoader) loaderField.get(server)).shutdown();
            var saveField = com.mineclone.server.DedicatedServer.class.getDeclaredField("save");
            saveField.setAccessible(true);
            ((com.mineclone.save.SaveManager) saveField.get(server)).flushAndAwait();
        } finally {
            try (var paths = java.nio.file.Files.walk(root)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
                    java.nio.file.Files.deleteIfExists(path);
            }
        }
    }
}

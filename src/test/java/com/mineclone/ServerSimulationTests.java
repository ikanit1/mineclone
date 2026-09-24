package com.mineclone;

import com.mineclone.net.LoopbackTransport;
import com.mineclone.net.Multiplayer;
import com.mineclone.save.ChunkLoad;
import com.mineclone.save.ChunkSnapshot;
import com.mineclone.save.SaveManager;
import com.mineclone.server.DedicatedServer;
import com.mineclone.server.ServerConfig;
import com.mineclone.world.Chunk;
import com.mineclone.world.ChunkLoader;
import com.mineclone.world.DroppedItem;
import com.mineclone.world.GameMode;
import com.mineclone.world.ItemStack;
import com.mineclone.world.World;
import com.mineclone.world.entity.ItemEntity;
import com.mineclone.world.entity.Mob;
import com.mineclone.world.entity.MobType;
import com.mineclone.world.entity.Projectile;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The dedicated server's own tick, driven through its real private methods:
 * what a world without a local player does with the things in it.
 */
final class ServerSimulationTests {
    static void runAll(TestMain.Runner r) {
        r.run("a dedicated server ticks with items on the ground", ServerSimulationTests::itemsOnTheGround);
        r.run("items saved in a chunk survive a dedicated server session", ServerSimulationTests::chunkItemsSurvive);
        r.run("a guest's arrow flies and lands on the server", ServerSimulationTests::guestArrow);
        r.run("a creeper blows up on the server: blocks go and the guest is hurt", ServerSimulationTests::creeper);
        r.run("a cow killed by a guest drops beef on the server and at the guest", ServerSimulationTests::guestKillDrops);
        r.run("a creative killer gets no drop", ServerSimulationTests::creativeKiller);
        r.run("a zombie next to a guest strikes the guest on the server", ServerSimulationTests::zombieStrikesGuest);
        r.run("a guest breaking a block is heard by the server's mobs", ServerSimulationTests::guestNoise);
    }

    private static final long SEED = 20260922L;

    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    /** A temporary save root, deleted with everything in it. */
    private static final class TempRoot implements AutoCloseable {
        final Path path = Files.createTempDirectory("mineclone-server-sim-");

        TempRoot() throws Exception {}

        @Override
        public void close() throws Exception {
            try (var paths = Files.walk(path)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
    }

    /** A real server on a save root, its world open and no doors. */
    static final class Server implements AutoCloseable {
        final DedicatedServer server;
        private final Method tick;
        NetworkTests.TestContext guest;
        Multiplayer guestNet;

        Server(Path root, String properties) throws Exception {
            Path config = root.resolve("server.properties");
            Files.writeString(config, "saves-dir=" + root.toString().replace('\\', '/')
                    + "\nworld=sim\nseed=" + SEED + "\ndirect=false\nphoton=false\nupnp=false\n" + properties);
            server = new DedicatedServer(ServerConfig.load(config.toFile()));
            Method open = DedicatedServer.class.getDeclaredMethod("openWorld");
            open.setAccessible(true);
            check((boolean) open.invoke(server), "server refused to open its world");
            tick = DedicatedServer.class.getDeclaredMethod("tick", float.class);
            tick.setAccessible(true);
        }

        /** Ticks exactly as the server loop does; a failure surfaces as its own exception. */
        void tick(int count) throws Exception {
            for (int i = 0; i < count; i++) {
                try {
                    tick.invoke(server, 1f / DedicatedServer.TICK_RATE);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    if (e.getCause() instanceof Exception cause) throw cause;
                    throw e;
                }
            }
        }

        Object field(String name) throws Exception {
            var f = DedicatedServer.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(server);
        }

        World world() throws Exception { return (World) field("world"); }

        Multiplayer net() throws Exception { return (Multiplayer) field("net"); }

        /** A guest joins over the in-process loopback and stands at the spawn point. */
        void join() throws Exception {
            LoopbackTransport.reset();
            net().start(new LoopbackTransport(net()), "sim", true, "server");
            guest = new NetworkTests.TestContext(null, "guest");
            guestNet = new Multiplayer(guest);
            guestNet.start(new LoopbackTransport(guestNet), "sim", false, "guest");
            await(() -> guest.world != null && net().participants().size() == 1, 400, "the guest never joined");
        }

        /** Server ticks, with the guest's own updates in between. */
        void pump(int count) throws Exception {
            for (int i = 0; i < count; i++) {
                tick(1);
                if (guestNet != null) guestNet.update(1f / DedicatedServer.TICK_RATE);
            }
        }

        interface Condition { boolean holds() throws Exception; }

        void await(Condition condition, int ticks, String why) throws Exception {
            for (int i = 0; i < ticks && !condition.holds(); i++)
                pump(1);
            check(condition.holds(), why);
        }

        @Override
        public void close() throws Exception {
            if (guestNet != null) {
                guestNet.stop(null);
                net().update(.1f);
                net().stop(null);
                LoopbackTransport.reset();
            }
            ChunkLoader loader = (ChunkLoader) field("loader");
            if (loader != null) loader.shutdown();
            ((SaveManager) field("save")).flushAndAwait();
        }
    }

    /** Feet height on the column: one above the highest solid block. */
    private static float surface(World world, float x, float z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        for (int y = Chunk.SIZE_Y - 1; y > 0; y--)
            if (world.getBlock(bx, y, bz).solid) return y + 1f;
        return 1f;
    }

    private static Mob place(Server s, MobType type, float dx, float dz) throws Exception {
        World world = s.world();
        float x = 8.5f + dx, z = 8.5f + dz;
        Mob mob = new Mob(type, x, surface(world, x, z) + 0.05f, z, new Random(type.ordinal()));
        s.server.mobs().add(mob);
        return mob;
    }

    private static int count(List<ItemEntity> items, String item) {
        int total = 0;
        for (ItemEntity e : items)
            if (e.stack != null && e.stack.item.id.path().equals(item)) total += e.stack.count;
        return total;
    }

    private static int solidAround(World world, float x, float y, float z, int r) {
        int solid = 0;
        for (int dx = -r; dx <= r; dx++)
            for (int dy = -r; dy <= r; dy++)
                for (int dz = -r; dz <= r; dz++)
                    if (world.getBlock((int) Math.floor(x) + dx, (int) Math.floor(y) + dy, (int) Math.floor(z) + dz).solid)
                        solid++;
        return solid;
    }

    /**
     * Regression: the server passed no magnet target to {@link ItemEntity#update},
     * and the first item on the ground — a guest's throw, a broken chest, sand on
     * a torch — threw out of the tick and stopped the server without saving.
     */
    private static void itemsOnTheGround() throws Exception {
        try (TempRoot root = new TempRoot(); Server s = new Server(root.path, "")) {
            ItemEntity diamond = new ItemEntity(ItemStack.of("diamond", 3), 8.5f, 110f, 8.5f, 0f, 0f);
            s.server.groundItems().add(diamond);
            s.tick(40);
            check(s.server.groundItems().contains(diamond) && diamond.stack.count == 3,
                    "the item vanished instead of lying on the ground");
            check(diamond.position.y < 109f, "the item did not fall: " + diamond.position);
        }
    }

    /**
     * Regression: the server never took the items restored with a chunk, and its
     * save listed only items it had spawned itself — the first save rewrote the
     * chunk without what was lying in it.
     */
    private static void chunkItemsSurvive() throws Exception {
        try (TempRoot root = new TempRoot()) {
            float y;
            try (Server first = new Server(root.path, "")) {
                y = surface(first.world(), 8.5f, 8.5f) + 0.2f;
            }
            // Items lying in the spawn chunk, saved as a single-player session saves them.
            SaveManager saves = new SaveManager(root.path.toFile());
            Chunk fresh = new World(SEED).generateDetached(0, 0);
            saves.saveChunkAsync("sim", new ChunkSnapshot(0, 0, fresh.copyBlocks(), fresh.copyMeta(),
                    Map.of(), Map.of(), List.of(new DroppedItem(ItemStack.of("diamond", 5), 8.5f, y, 8.5f, 1f)),
                    Map.of()));
            saves.flushAndAwait();
            try (Server second = new Server(root.path, "")) {
                second.tick(20);
                check(count(second.server.groundItems(), "diamond") == 5,
                        "the server never put the saved items into its world");
                second.server.saveWorld();
            }
            ChunkLoad read = new SaveManager(root.path.toFile()).readChunk("sim", 0, 0);
            check(read instanceof ChunkLoad.Loaded loaded && loaded.snapshot().items.stream()
                            .filter(d -> d.stack.item.id.path().equals("diamond")).mapToInt(d -> d.stack.count).sum() == 5,
                    "the server's save erased the items lying in the chunk: " + read);
        }
    }

    /** Regression: guest arrows were added to the server's list and never flown, hanging in the air for everyone. */
    private static void guestArrow() throws Exception {
        try (TempRoot root = new TempRoot(); Server s = new Server(root.path, "")) {
            float y = surface(s.world(), 8.5f, 8.5f) + 1.5f;
            s.server.shootFor(7, 8.5f, y, 8.5f, 18f, 5f, 0f, 3f);
            Projectile arrow = s.server.projectiles().get(0);
            org.joml.Vector3f start = new org.joml.Vector3f(arrow.position);
            s.tick(40);
            check(arrow.position.distance(start) > 1f || !s.server.projectiles().contains(arrow),
                    "the arrow hangs where it was shot: " + arrow.position);
            s.await(() -> arrow.stuck || !s.server.projectiles().contains(arrow), 200, "the arrow never came down");
        }
    }

    /** Regression: on the server a creeper's fuse ran out and it simply died — no blast, no harm. */
    private static void creeper() throws Exception {
        try (TempRoot root = new TempRoot(); Server s = new Server(root.path, "")) {
            s.join();
            World world = s.world();
            float gx = s.guest.position.x, gy = s.guest.position.y, gz = s.guest.position.z;
            int before = solidAround(world, gx, gy, gz, 5);
            Mob creeper = place(s, MobType.CREEPER, 3, 0);
            s.await(() -> s.guest.hurtTaken > 0f, 600, "the creeper never hurt the guest");
            check(creeper.dead, "the creeper survived its own blast");
            check(solidAround(world, gx, gy, gz, 5) < before, "the blast left the ground whole");
        }
    }

    /** Regression: nothing the server's mobs died of ever dropped anything. */
    private static void guestKillDrops() throws Exception {
        try (TempRoot root = new TempRoot(); Server s = new Server(root.path, "")) {
            s.join();
            // Until a guest announces its mode the host reads it as creative, and a
            // creative killer takes no drop.
            s.await(() -> s.net().participants().get(0).mode() == GameMode.SURVIVAL, 100,
                    "the guest never announced survival");
            Mob cow = place(s, MobType.COW, 2, 1);
            Mob seen = mirrorOf(s, cow);
            s.guestNet.requestMobHit(seen, 50f, 0.4f, s.guest.position.x, s.guest.position.z);
            s.await(() -> cow.dead, 100, "the guest's hit never reached the cow");
            s.await(() -> count(s.server.groundItems(), "beef") == 2, 100, "the cow dropped nothing on the server");
            s.await(() -> count(s.guest.items, "beef") == 2, 100, "the guest never saw the beef");
        }
    }

    private static void creativeKiller() throws Exception {
        try (TempRoot root = new TempRoot(); Server s = new Server(root.path, "creative=true\n")) {
            s.join();
            Mob cow = place(s, MobType.COW, 2, 1);
            Mob seen = mirrorOf(s, cow);
            s.guestNet.requestMobHit(seen, 50f, 0.4f, s.guest.position.x, s.guest.position.z);
            s.await(() -> cow.dead, 100, "the guest's hit never reached the cow");
            s.pump(40);
            check(count(s.server.groundItems(), "beef") == 0, "a creative guest's kill dropped beef");
        }
    }

    /** Regression: on the server a zombie's blow landed on no one. */
    private static void zombieStrikesGuest() throws Exception {
        try (TempRoot root = new TempRoot(); Server s = new Server(root.path, "")) {
            s.join();
            s.await(() -> s.net().participants().get(0).mode() == GameMode.SURVIVAL, 100,
                    "the guest never announced survival");
            place(s, MobType.ZOMBIE, 1.2f, 0f);
            s.await(() -> s.guest.hurtTaken > 0f, 200, "the zombie never struck the guest");
        }
    }

    /** A guest's broken block sends a zombie to look, as the host's own would. */
    private static void guestNoise() throws Exception {
        try (TempRoot root = new TempRoot(); Server s = new Server(root.path, "")) {
            Mob zombie = place(s, MobType.ZOMBIE, 14f, 0f);
            check(!zombie.isInvestigating(), "the zombie was investigating before anything happened");
            s.server.remoteBlockAction(2, 10, (int) surface(s.world(), 10.5f, 8.5f) - 1, 8,
                    (byte) com.mineclone.world.BlockType.DIRT.ordinal(), true);
            check(zombie.isInvestigating(), "the zombie paid no attention to a block broken twelve blocks away");
        }
    }

    /** The guest's copy of a server mob: the mirror nearest to where the server holds it. */
    private static Mob mirrorOf(Server s, Mob original) throws Exception {
        Mob[] found = new Mob[1];
        s.await(() -> {
            float best = 4f;
            for (Mob m : s.guest.mobs) {
                float d = m.position.distance(original.position);
                if (m.type == original.type && d < best) { best = d; found[0] = m; }
            }
            return found[0] != null;
        }, 100, "the guest never saw the " + original.type);
        return found[0];
    }
}

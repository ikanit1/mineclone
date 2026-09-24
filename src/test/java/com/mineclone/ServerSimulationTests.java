package com.mineclone;

import com.mineclone.save.SaveManager;
import com.mineclone.server.DedicatedServer;
import com.mineclone.server.ServerConfig;
import com.mineclone.world.ChunkLoader;
import com.mineclone.world.ItemStack;
import com.mineclone.world.entity.ItemEntity;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * The dedicated server's own tick, driven through its real private methods:
 * what a world without a local player does with the things lying in it.
 */
final class ServerSimulationTests {
    static void runAll(TestMain.Runner r) {
        r.run("a dedicated server ticks with items on the ground", ServerSimulationTests::itemsOnTheGround);
    }

    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    /** A real server on a temporary save root, its world open and no doors. */
    static final class Server implements AutoCloseable {
        final Path root;
        final DedicatedServer server;
        private final Method tick;

        Server(String properties) throws Exception {
            root = Files.createTempDirectory("mineclone-server-sim-");
            Path config = root.resolve("server.properties");
            Files.writeString(config, "saves-dir=" + root.toString().replace('\\', '/')
                    + "\nworld=sim\nseed=20260922\ndirect=false\nphoton=false\nupnp=false\n" + properties);
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

        @Override
        public void close() throws Exception {
            ChunkLoader loader = (ChunkLoader) field("loader");
            if (loader != null) loader.shutdown();
            ((SaveManager) field("save")).flushAndAwait();
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    /**
     * Regression: the server passed no magnet target to {@link ItemEntity#update},
     * and the first item on the ground — a guest's throw, a broken chest, sand on
     * a torch — threw out of the tick and stopped the server without saving.
     */
    private static void itemsOnTheGround() throws Exception {
        try (Server s = new Server("")) {
            ItemEntity diamond = new ItemEntity(ItemStack.of("diamond", 3), 8.5f, 110f, 8.5f, 0f, 0f);
            s.server.groundItems().add(diamond);
            s.tick(40);
            check(s.server.groundItems().contains(diamond) && diamond.stack.count == 3,
                    "the item vanished instead of lying on the ground");
            check(diamond.position.y < 109f, "the item did not fall: " + diamond.position);
        }
    }
}

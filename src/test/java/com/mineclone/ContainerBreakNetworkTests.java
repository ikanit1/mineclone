package com.mineclone;

import com.mineclone.net.*;
import com.mineclone.server.DedicatedServer;
import com.mineclone.server.ServerConfig;
import com.mineclone.world.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/** Exercises the production headless host, including its S_ITEMS snapshots. */
final class ContainerBreakNetworkTests {
    static void runAll(NetworkTests.Runner runner) {
        runner.run("guest breaking a chest preserves its contents on host and guest", () -> chest(false));
        runner.run("guest replacing a furnace preserves all three slots", ContainerBreakNetworkTests::furnace);
        runner.run("guest chest destruction preserves items over TCP", () -> chest(true));
    }

    public static void main(String[] args) throws Exception { chest(false); }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }

    private static void chest(boolean sockets) throws Exception {
        try (Room room = new Room(sockets)) {
            room.host.world().setBlock(8, 100, 8, BlockType.CHEST);
            room.host.world().createChest(8, 100, 8)[0] = ItemStack.of("diamond", 16);
            room.await(() -> room.guest.world.getBlock(8, 100, 8) == BlockType.CHEST);
            room.edit(BlockType.CHEST, BlockType.AIR, (byte) 0);
            room.await(() -> room.host.world().getBlock(8, 100, 8) == BlockType.AIR);
            check(room.host.groundItems().stream().mapToInt(e -> e.stack.count).sum() == 16,
                    "host must spill 16 diamonds before deleting the chest");
            room.await(() -> room.guest.items.stream().mapToInt(e -> e.stack.count).sum() == 16);
            check(room.guest.items.get(0).stack.item.id.toString().equals("mineclone:diamond"),
                    "S_ITEMS preserves item identity");
            room.edit(BlockType.CHEST, BlockType.AIR, (byte) 0);
            room.pump(10);
            check(room.host.groundItems().stream().mapToInt(e -> e.stack.count).sum() == 16,
                    "duplicate block edit cannot spill contents twice");
        }
    }

    private static void furnace() throws Exception {
        try (Room room = new Room(false)) {
            room.host.world().setBlock(8, 100, 8, BlockType.FURNACE);
            Furnace furnace = room.host.world().createFurnace(8, 100, 8);
            furnace.input = ItemStack.of("iron_ore", 3);
            furnace.fuel = ItemStack.of("coal", 4);
            furnace.output = ItemStack.of("iron_ingot", 5);
            room.edit(BlockType.FURNACE, BlockType.FURNACE, (byte) 1);
            room.pump(4);
            check(room.host.groundItems().isEmpty() && furnace.input.count == 3,
                    "a metadata update must not spill contents");
            room.edit(BlockType.FURNACE, BlockType.STONE, (byte) 0);
            room.pump(10);
            check(room.host.world().getFurnace(8, 100, 8) == null, "furnace removed");
            check(room.host.groundItems().stream().mapToInt(e -> e.stack.count).sum() == 12,
                    "replacement without the broke flag preserves every furnace slot");
            check(room.guest.items.stream().mapToInt(e -> e.stack.count).sum() == 12,
                    "all furnace contents reach guest snapshots");
        }
    }

    private static final class Room implements AutoCloseable {
        final DedicatedServer host;
        final NetworkTests.TestContext guest = new NetworkTests.TestContext(null, "guest");
        final Multiplayer hostNet, guestNet = new Multiplayer(guest);
        final boolean sockets;

        Room(boolean sockets) throws Exception {
            this.sockets = sockets;
            Path folder = Files.createTempDirectory("mineclone-container-break-");
            Path config = folder.resolve("server.properties");
            Files.writeString(config, "photon=false\nupnp=false\nsaves-dir="
                    + folder.resolve("saves").toString().replace('\\', '/') + "\n");
            host = new DedicatedServer(ServerConfig.load(config.toFile()));
            Field worldField = DedicatedServer.class.getDeclaredField("world");
            worldField.setAccessible(true);
            World world = new World(42);
            world.getChunk(0, 0);
            worldField.set(host, world);
            Field netField = DedicatedServer.class.getDeclaredField("net");
            netField.setAccessible(true);
            hostNet = (Multiplayer) netField.get(host);
            world.setBlockObserver(hostNet::onWorldBlockChanged);
            guest.onWorldStarted = w -> w.getChunk(0, 0);
            LoopbackTransport.reset();
            if (sockets) {
                int port;
                try (var probe = new java.net.ServerSocket(0)) { port = probe.getLocalPort(); }
                hostNet.start(LanTransport.host(port, hostNet), "break", true, "host");
                guestNet.start(LanTransport.join("127.0.0.1:" + port, guestNet), "break", false, "guest");
            } else {
                hostNet.start(new LoopbackTransport(hostNet), "break", true, "host");
                guestNet.start(new LoopbackTransport(guestNet), "break", false, "guest");
            }
            await(() -> guest.world != null && !guestNet.inventoryBusy());
        }

        void edit(BlockType old, BlockType next, byte meta) {
            guestNet.onWorldBlockChanged(8, 100, 8, old, next, meta);
        }

        void pump(int count) throws InterruptedException {
            for (int i = 0; i < count; i++) {
                hostNet.update(.1f);
                guestNet.update(.1f);
                if (sockets) Thread.sleep(5);
            }
        }

        void await(BooleanSupplier predicate) throws InterruptedException {
            for (int i = 0; i < 600; i++) {
                pump(1);
                if (predicate.getAsBoolean()) return;
            }
            throw new AssertionError("timed out waiting for container block/item replication");
        }

        @Override public void close() {
            guestNet.stop(null);
            hostNet.update(.1f);
            hostNet.stop(null);
            LoopbackTransport.reset();
        }
    }
}

package com.mineclone;

import com.mineclone.save.LevelData;
import com.mineclone.save.SaveManager;
import com.mineclone.server.DedicatedServer;
import com.mineclone.server.ServerConfig;
import com.mineclone.sim.WorldClock;
import com.mineclone.world.ChunkLoader;
import com.mineclone.world.GameMode;
import com.mineclone.world.ItemStack;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;

final class DedicatedServerSaveTests {
    static void runAll(TestMain.Runner runner) {
        runner.run("dedicated world autosave preserves local owner checkpoint, unknown sections and clock", DedicatedServerSaveTests::ownerCheckpoint);
        runner.run("a new dedicated world records the generator it was made with", DedicatedServerSaveTests::newWorldGenerator);
    }

    /** GEN-01: the version is written with the first save, so no later default can reinterpret the land. */
    private static void newWorldGenerator() throws Exception {
        Path root = Files.createTempDirectory("mineclone-server-worldgen-");
        DedicatedServer server = null;
        try {
            Path configPath = root.resolve("server.properties");
            Files.writeString(configPath, "saves-dir=" + root.toString().replace('\\', '/')
                    + "\nworld=fresh\nseed=31337\ndirect=false\nphoton=false\nupnp=false\n");
            server = new DedicatedServer(ServerConfig.load(configPath.toFile()));
            var open = DedicatedServer.class.getDeclaredMethod("openWorld");
            open.setAccessible(true);
            check((boolean) open.invoke(server), "server refused to create a world");
            SaveManager saves = (SaveManager) get(server, "save");
            saves.flushAndAwait();
            LevelData level = saves.loadLevel("fresh");
            byte[] section = level.extraSections.get(com.mineclone.world.gen.WorldGenSettings.SAVE_SECTION);
            check(section != null && com.mineclone.world.gen.WorldGenSettings.decode(section)
                    .equals(com.mineclone.world.gen.WorldGenSettings.forNewWorld()), "new world did not record its generator");
            com.mineclone.world.World world = (com.mineclone.world.World) get(server, "world");
            check(world.genPolicy().versionAt(0, 0) == com.mineclone.world.gen.WorldGenVersion.LATEST,
                    "server generates new land with another version");
        } finally {
            if (server != null) {
                ChunkLoader loader = (ChunkLoader) get(server, "loader");
                if (loader != null) loader.shutdown();
                ((SaveManager) get(server, "save")).flushAndAwait();
            }
            try (var files = Files.walk(root)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void check(boolean value, String why) { if (!value) throw new AssertionError(why); }
    private static Object get(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static void ownerCheckpoint() throws Exception {
        Path root = Files.createTempDirectory("mineclone-server-owner-");
        SaveManager original = new SaveManager(root.toFile());
        DedicatedServer server = null;
        try {
            ItemStack[] inventory = LevelData.emptyInventory();
            inventory[0] = ItemStack.of("diamond", 17);
            inventory[1] = ItemStack.of("iron_pickaxe");
            inventory[1].set(com.mineclone.item.Components.DAMAGE, 42);
            byte[] opaque = { 9, 8, 7, 6 };
            WorldClock clock = new WorldClock(37.25);
            clock.advance(1.25);
            LevelData legacy = new LevelData("Owner world", 3232L, 3.25, 81.5, 4.75,
                    8.5, 85, 8.5, .33f, -.12f, clock.gameTimeFloat(), 5, inventory,
                    GameMode.SURVIVAL, 12345, 7.5f, 3.5f, new ItemStack[] { ItemStack.of("coal", 11) },
                    Map.of("future:owner", opaque, WorldClock.SAVE_SECTION, clock.encode()));
            // What only a record can hold must also survive a server that has no owner to recapture.
            ItemStack[] equipment = new ItemStack[com.mineclone.save.PlayerRecord.EQUIPMENT_SLOTS];
            equipment[0] = ItemStack.of("iron_ingot", 1);
            var ownerRecord = legacy.player.toBuilder().equipment(equipment)
                    .spawn(new com.mineclone.save.PlayerRecord.Spawn(1.5, 70, 2.5))
                    .effects(java.util.List.of(new com.mineclone.save.PlayerRecord.Effect("mineclone:speed", 80, 1)))
                    .section("future:owner_record", new byte[] { 3, 1, 4 }).build();
            LevelData before = new LevelData(legacy.name, legacy.seed, legacy.spawnX, legacy.spawnY, legacy.spawnZ,
                    legacy.timeOfDay, legacy.gameMode, legacy.lastPlayed, ownerRecord, legacy.extraSections);
            original.saveLevel("world", before);
            original.flushAndAwait();
            Path configPath = root.resolve("server.properties");
            Files.writeString(configPath, "saves-dir=" + root.toString().replace('\\', '/')
                    + "\nworld=world\ndirect=false\nphoton=false\nupnp=false\n");
            server = new DedicatedServer(ServerConfig.load(configPath.toFile()));
            var open = DedicatedServer.class.getDeclaredMethod("openWorld");
            open.setAccessible(true);
            check((boolean) open.invoke(server), "actual server world opening refused valid save");
            WorldClock serverClock = (WorldClock) get(server, "worldClock");
            serverClock.advance(2.5);
            server.saveWorld();
            SaveManager saves = (SaveManager) get(server, "save");
            saves.flushAndAwait();
            LevelData after = saves.loadLevel("world");
            check(after.inventory[0].count == 17 && after.inventory[1].damage() == 42, "server erased local owner inventory");
            check(after.pending.length == 1 && after.pending[0].count == 11, "server erased owner pending items");
            check(after.px == before.px && after.py == before.py && after.pz == before.pz
                    && after.yaw == before.yaw && after.pitch == before.pitch && after.selectedSlot == 5, "server reset owner pose");
            check(after.health == 7.5f && after.hunger == 3.5f, "server reset owner vitals");
            check(Arrays.equals(opaque, after.extraSections.get("future:owner")), "server discarded unknown section");
            PlayerRecordTests.same(before.player, after.player, "server autosave of the owner record");
            check(Arrays.equals(serverClock.encode(), after.extraSections.get(WorldClock.SAVE_SECTION)), "server clock did not persist exactly");
        } finally {
            if (server != null) {
                ChunkLoader loader = (ChunkLoader) get(server, "loader");
                if (loader != null) loader.shutdown();
                ((SaveManager) get(server, "save")).flushAndAwait();
            }
            original.flushAndAwait();
            try (var files = Files.walk(root)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}

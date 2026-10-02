package com.mineclone;

import com.mineclone.save.*;
import com.mineclone.ui.*;
import com.mineclone.world.*;
import com.mineclone.world.gen.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;

final class WorldGenOpeningTests {
    static void runAll(TestMain.Runner r) {
        r.run("worldgen opening keeps old worlds without an explicit upgrade", WorldGenOpeningTests::offer);
        r.run("worldgen upgrade backs up original bytes and preserves owner, guests and seen terrain", WorldGenOpeningTests::upgrade);
        r.run("worldgen upgrade refuses a failed backup without changing level or ledger", () -> failure("backups"));
        r.run("worldgen upgrade refuses a failed ledger write before changing level", () -> failure("chunks/ledger.dat"));
        r.run("worldgen upgrade refuses unreadable guest anchors", WorldGenOpeningTests::badGuest);
        r.run("worldgen server upgrade is opt-in and survives autosave and reopening", WorldGenOpeningTests::server);
        if (java.io.File.separatorChar == '/')
            r.run("worldgen failed level commit leaves durable V1 pins and the old generator", WorldGenOpeningTests::failedLevelCommit);
    }

    private static void offer() throws Exception {
        Path root = fixture();
        SaveManager save = new SaveManager(root.toFile());
        try {
            byte[] original = Files.readAllBytes(root.resolve("w/level.dat"));
            MenuAction action = MenuAction.play("w");
            MenuAction offered = WorldGenUpgradeScreen.offer(save, action);
            check(offered.screen instanceof WorldGenUpgradeScreen, "old world did not get a choice");
            WorldGenUpgradeScreen screen = (WorldGenUpgradeScreen) offered.screen;
            check(screen.escape().is(MenuAction.Kind.BACK), "Esc must return to the menu");
            MenuAction keep = screen.keepCurrent();
            check(keep.worldgenChecked && keep.is(MenuAction.Kind.PLAY_WORLD), "keep choice lost the opening action");
            check(WorldGenUpgradeScreen.offer(save, keep) == keep, "keep choice asked again in the same opening");
            check(Arrays.equals(original, Files.readAllBytes(root.resolve("w/level.dat"))), "declining changed the save");
            check(Files.notExists(root.resolve("w/chunks/ledger.dat")), "declining created a ledger");
            var net = new com.mineclone.net.NetSettings(com.mineclone.net.NetSettings.LAN, "owner", "", "", "", "", 25565);
            MenuAction host = MenuAction.netHost("w", net);
            MenuAction hostKeep = ((WorldGenUpgradeScreen) WorldGenUpgradeScreen.offer(save, host).screen).keepCurrent();
            check(hostKeep.net == net && hostKeep.is(MenuAction.Kind.NET_HOST), "host choice lost network settings");
            check(WorldGenUpgradeScreen.offer(save, MenuAction.play("missing")).is(MenuAction.Kind.PLAY_WORLD), "missing world offered upgrade");
            screen.requestUpgrade();
            save.flushAndAwait();
            check(screen.poll().worldgenChecked, "upgrade completed without an opening action");
            check(WorldGenUpgradeScreen.offer(save, action) == action, "V2 world offered upgrade again");
        } finally { save.flushAndAwait(); delete(root); }
    }

    private static void upgrade() throws Exception {
        Path root = fixture();
        SaveManager save = new SaveManager(root.toFile());
        try {
            LevelData before = save.loadLevel("w");
            String guestId = UUID.randomUUID().toString();
            PlayerRecord guest = PlayerRecord.builder().pose(-6408, 80, 8008, 0, 0, 0)
                    .spawn(new PlayerRecord.Spawn(9608, 80, -9608)).build();
            save.saveGuestRecord("w", guestId, guest); save.flushAndAwait();
            byte[] levelBytes = Files.readAllBytes(root.resolve("w/level.dat"));
            byte[] guestBytes = Files.readAllBytes(root.resolve("w/players/" + guestId + ".dat"));
            ChunkLedger known = new ChunkLedger(); known.record(World.key(150, 150), WorldGenVersion.V1);
            save.saveLedgerAsync("w", known.encode()); save.flushAndAwait();
            WorldGenSettings upgraded = save.upgradeWorldGen("w").get();
            SaveManager reopened = new SaveManager(root.toFile());
            LevelData after = reopened.loadLevel("w");
            check(upgraded.version() == WorldGenVersion.V2 && upgraded.upgradedAt() > 0, "upgrade settings");
            check(WorldGenSettings.decode(after.extraSections.get(WorldGenSettings.SAVE_SECTION)).equals(upgraded), "level generator not durable");
            PlayerRecordTests.same(before.player, after.player, "upgrade owner");
            check(before.seed == after.seed && before.lastPlayed == after.lastPlayed && before.timeOfDay == after.timeOfDay, "world state changed");
            for (var entry : before.extraSections.entrySet())
                if (!entry.getKey().equals(WorldGenSettings.SAVE_SECTION))
                    check(Arrays.equals(entry.getValue(), after.extraSections.get(entry.getKey())), "upgrade lost section " + entry.getKey());
            check(Arrays.equals(guestBytes, Files.readAllBytes(root.resolve("w/players/" + guestId + ".dat"))), "guest checkpoint changed");
            ChunkLedger ledger = ((LedgerLoad.Loaded) reopened.readLedger("w")).ledger();
            GenPolicy policy = upgraded.policy(ledger);
            check(policy.versionAt(150, 150) == WorldGenVersion.V1, "old recorded land forgotten");
            check(policy.versionAt(-401, 500) == WorldGenVersion.V1 && policy.versionAt(600, -601) == WorldGenVersion.V1, "guest pose or personal spawn not pinned");
            World old = new World(before.seed), now = new World(before.seed, policy);
            for (long key : save.savedChunkKeys("w")) {
                int x = (int) (key >> 32), z = (int) key;
                for (int d : new int[] {-12, 0, 12})
                    check(WorldGenGoldenTests.hash(old.generateDetached(x + d, z - d))
                            .equals(WorldGenGoldenTests.hash(now.generateDetached(x + d, z - d))), "seen terrain changed");
            }
            check(policy.versionAt(200, -200) == WorldGenVersion.V2, "new terrain stayed V1");
            check(!WorldGenGoldenTests.hash(old.generateDetached(200, -200)).equals(WorldGenGoldenTests.hash(now.generateDetached(200, -200))), "new terrain did not get copper");
            var backup = save.listBackups("w").get(0);
            try (var zip = new ZipFile(backup.path().toFile())) {
                check(Arrays.equals(levelBytes, zip.getInputStream(zip.getEntry("level.dat")).readAllBytes()), "backup is not pre-upgrade bytes");
                check(zip.getEntry("players/" + guestId + ".dat") != null, "backup lost guest");
            }
            byte[] upgradedBytes = Files.readAllBytes(root.resolve("w/level.dat"));
            check(save.upgradeWorldGen("w").get().equals(upgraded), "repeated upgrade changed timestamp");
            check(Arrays.equals(upgradedBytes, Files.readAllBytes(root.resolve("w/level.dat"))), "repeated upgrade rewrote level");
            Files.delete(root.resolve("w/chunks/ledger.dat"));
            var rebuilt = new SaveManager(root.toFile()).openLedger("w", upgraded, new float[0][]);
            check(upgraded.policy(rebuilt).versionAt(-401, 500) == WorldGenVersion.V1
                    && upgraded.policy(rebuilt).versionAt(600, -601) == WorldGenVersion.V1,
                    "lost ledger recovery forgot remote guest anchors");
        } finally { save.flushAndAwait(); delete(root); }
    }

    private static void failure(String obstruction) throws Exception {
        Path root = fixture();
        SaveManager save = new SaveManager(root.toFile());
        try {
            byte[] before = Files.readAllBytes(root.resolve("w/level.dat"));
            Path blocked = root.resolve("w/" + obstruction);
            if (obstruction.equals("backups")) Files.write(blocked, new byte[] {1});
            else { Files.createDirectories(blocked); Files.write(blocked.resolve("keep"), new byte[] {1}); }
            boolean failed = false;
            try { save.upgradeWorldGen("w").get(); } catch (java.util.concurrent.ExecutionException expected) { failed = true; }
            check(failed, "unsafe upgrade reported success");
            check(Arrays.equals(before, Files.readAllBytes(root.resolve("w/level.dat"))), "failed upgrade changed generator");
            if (obstruction.equals("backups")) check(Files.notExists(root.resolve("w/chunks/ledger.dat")), "ledger changed before backup");
            else check(Files.exists(blocked.resolve("keep")), "ledger obstruction overwritten");
        } finally { save.flushAndAwait(); delete(root); }
    }

    private static void badGuest() throws Exception {
        Path root = fixture();
        SaveManager save = new SaveManager(root.toFile());
        try {
            Path guest = root.resolve("w/players/" + UUID.randomUUID() + ".dat");
            Files.createDirectories(guest.getParent()); Files.write(guest, new byte[] {1, 2, 3});
            byte[] before = Files.readAllBytes(root.resolve("w/level.dat"));
            boolean failed = false;
            try { save.upgradeWorldGen("w").get(); } catch (java.util.concurrent.ExecutionException expected) { failed = true; }
            check(failed && Arrays.equals(before, Files.readAllBytes(root.resolve("w/level.dat"))), "unreadable guest allowed unsafe upgrade");
            check(Files.notExists(root.resolve("w/chunks/ledger.dat")), "failed anchor read initialized ledger");
            Path config = root.resolve("server.properties");
            Files.writeString(config, "saves-dir=" + root.toString().replace('\\', '/')
                    + "\nworld=w\ndirect=false\nphoton=false\nupnp=false\nupgrade-worldgen=true\n");
            var server = new com.mineclone.server.DedicatedServer(com.mineclone.server.ServerConfig.load(config.toFile()));
            check(server.run() == 2 && field(server, "world") == null, "server continued after a failed upgrade");
            check(Arrays.equals(before, Files.readAllBytes(root.resolve("w/level.dat"))), "server failure changed level");
        } finally { save.flushAndAwait(); delete(root); }
    }

    private static void server() throws Exception {
        Path root = fixture();
        try {
            for (boolean upgrade : new boolean[] {false, true, true}) {
                Path config = root.resolve("server.properties");
                Files.writeString(config, "saves-dir=" + root.toString().replace('\\', '/')
                        + "\nworld=w\ndirect=false\nphoton=false\nupnp=false\nupgrade-worldgen=" + upgrade + "\n");
                var server = new com.mineclone.server.DedicatedServer(com.mineclone.server.ServerConfig.load(config.toFile()));
                try {
                    var open = server.getClass().getDeclaredMethod("openWorld"); open.setAccessible(true);
                    check((boolean) open.invoke(server), "server refused valid upgrade");
                    World world = (World) field(server, "world");
                    check(world.genPolicy().versionAt(upgrade ? 201 : 200, -200) == (upgrade ? WorldGenVersion.V2 : WorldGenVersion.V1), "server ignored opt-in");
                    check(world.genPolicy().versionAt(200, -200) == WorldGenVersion.V1, "server changed previously observed distant land");
                    check(world.genPolicy().versionAt(0, 0) == WorldGenVersion.V1, "server changed old spawn terrain");
                    server.saveWorld();
                    SaveManager saves = (SaveManager) field(server, "save"); saves.flushAndAwait();
                    check(WorldGenSettings.decode(saves.loadLevel("w").extraSections.get(WorldGenSettings.SAVE_SECTION)).version()
                            == (upgrade ? WorldGenVersion.V2 : WorldGenVersion.V1), "autosave lost upgraded settings");
                } finally {
                    ChunkLoader loader = (ChunkLoader) field(server, "loader"); if (loader != null) loader.shutdown();
                    ((SaveManager) field(server, "save")).flushAndAwait();
                }
            }
        } finally { delete(root); }
    }

    private static Object field(Object target, String name) throws Exception {
        var f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target);
    }
    private static void failedLevelCommit() throws Exception {
        Path root = fixture();
        SaveManager save = new SaveManager(root.toFile());
        Path world = root.resolve("w");
        var permissions = Files.getPosixFilePermissions(world);
        try {
            Files.createDirectories(world.resolve("backups"));
            byte[] before = Files.readAllBytes(world.resolve("level.dat"));
            Files.setPosixFilePermissions(world, java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x"));
            boolean failed = false;
            try { save.upgradeWorldGen("w").get(); } catch (java.util.concurrent.ExecutionException expected) { failed = true; }
            check(failed, "failed level commit reported success");
            check(Arrays.equals(before, Files.readAllBytes(world.resolve("level.dat"))), "level changed after failed commit");
            check(new SaveManager(root.toFile()).readLedger("w") instanceof LedgerLoad.Loaded loaded
                    && loaded.ledger().versionAt(World.key(0, 0), WorldGenVersion.V2) == WorldGenVersion.V1,
                    "pins were not durable before level commit");
        } finally { save.flushAndAwait(); Files.setPosixFilePermissions(world, permissions); delete(root); }
    }
    private static Path fixture() throws Exception {
        Path root = Files.createTempDirectory("mineclone-gen-opening-");
        Path source = Path.of("src/test/resources/fixtures/saves/alpha-small");
        try (var files = Files.walk(source)) {
            for (Path p : files.toList()) {
                Path to = root.resolve("w").resolve(source.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(to); else Files.copy(p, to);
            }
        }
        return root;
    }
    private static void delete(Path root) throws Exception {
        try (var files = Files.walk(root)) { for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}

package com.mineclone;

import com.mineclone.data.VarLong;
import com.mineclone.save.LedgerLoad;
import com.mineclone.save.LevelData;
import com.mineclone.save.LevelLoad;
import com.mineclone.save.SaveFormat;
import com.mineclone.save.SaveManager;
import com.mineclone.world.GenProfile;
import com.mineclone.world.World;
import com.mineclone.world.gen.ChunkLedger;
import com.mineclone.world.gen.GenFeatures;
import com.mineclone.world.gen.GenPolicy;
import com.mineclone.world.gen.WorldGenSettings;
import com.mineclone.world.gen.WorldGenUpgrade;
import com.mineclone.world.gen.WorldGenVersion;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.ZipFile;

/**
 * GEN-02: the chunk ledger — which generator made each chunk — survives saving,
 * damage and backups, and an upgrade keeps every seen chunk on its old version.
 */
final class ChunkLedgerTests {
    static void runAll(TestMain.Runner r) {
        r.run("the ledger round-trips any keys and versions", ChunkLedgerTests::roundTrip);
        r.run("a damaged ledger is refused, not half-read", ChunkLedgerTests::damage);
        r.run("generating a chunk records its version; a recorded one keeps it", ChunkLedgerTests::policy);
        r.run("upgrading the 1.0 fixture world keeps every seen chunk on V1", ChunkLedgerTests::upgradeFixture);
        r.run("ledger saves are queued, read back at once and refused for protected worlds", ChunkLedgerTests::saving);
        r.run("a damaged ledger is kept as evidence and rebuilt conservatively", ChunkLedgerTests::quarantine);
        r.run("a damaged ledger that cannot be backed up stays in place, unwritten", ChunkLedgerTests::keptInPlace);
        r.run("the dedicated server records and saves the chunks it generates", ChunkLedgerTests::server);
    }

    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    private static void roundTrip() throws Exception {
        ChunkLedger empty = ChunkLedger.decode(new ChunkLedger().encode());
        check(empty.size() == 0, "empty ledger");
        ChunkLedger ledger = new ChunkLedger();
        Random random = new Random(5);
        int[][] extremes = { { Integer.MIN_VALUE, Integer.MIN_VALUE }, { Integer.MAX_VALUE, Integer.MAX_VALUE },
                { -1, -1 }, { 0, 0 }, { -1, 0 }, { 0, -1 }, { 1_875_000, -1_875_000 } };
        for (int[] c : extremes) ledger.record(World.key(c[0], c[1]), WorldGenVersion.V1);
        for (int i = 0; i < 5000; i++)
            ledger.record(World.key(random.nextInt(4001) - 2000, random.nextInt(4001) - 2000),
                    random.nextInt(3) == 0 ? WorldGenVersion.V2 : WorldGenVersion.V1);
        Map<Long, WorldGenVersion> before = ledger.snapshot();
        byte[] bytes = ledger.encode();
        check(ChunkLedger.decode(bytes).snapshot().equals(before), "ledger did not round-trip");
        check(bytes.length < before.size() * 6, "ledger encoding too large: " + bytes.length + " bytes");
        check(ledger.encodeIfDirty() != null && ledger.encodeIfDirty() == null, "dirty flag");
        check(!ledger.record(World.key(0, 0), WorldGenVersion.V2) && ledger.versionAt(World.key(0, 0), null)
                == WorldGenVersion.V1, "recording twice changed a chunk's version");
        check(VarLong.unzigzag(VarLong.zigzag(Long.MIN_VALUE)) == Long.MIN_VALUE, "zigzag");
    }

    private static void damage() throws Exception {
        byte[] good = sample().encode();
        byte[][] bad = {
                new byte[0], new byte[] { 1, 2, 3 },
                withInt(good, 0, 0x12345678),               // magic
                withInt(good, 4, 99),                       // format
                truncated(good, good.length - 1),
                appended(good, (byte) 0),
                manual(2, new long[] { VarLong.zigzag(5), 0 }, new int[] { 2, 1 }),        // repeated key
                manual(1, new long[] { VarLong.zigzag(5) }, new int[] { 1, 42 }),          // unknown version
                manual(2, new long[] { VarLong.zigzag(5), 1 }, new int[] { 3, 1 }),        // run past the end
        };
        for (byte[] b : bad) {
            boolean refused = false;
            try { ChunkLedger.decode(b); }
            catch (java.io.IOException expected) { refused = true; }
            check(refused, "damaged ledger accepted: " + java.util.HexFormat.of().formatHex(b));
        }
    }

    private static ChunkLedger sample() {
        ChunkLedger ledger = new ChunkLedger();
        for (int i = -3; i <= 3; i++) ledger.record(World.key(i, -i), i < 0 ? WorldGenVersion.V1 : WorldGenVersion.V2);
        return ledger;
    }

    private static byte[] withInt(byte[] bytes, int at, int value) {
        byte[] copy = bytes.clone();
        java.nio.ByteBuffer.wrap(copy).putInt(at, value);
        return copy;
    }

    private static byte[] truncated(byte[] bytes, int length) { return java.util.Arrays.copyOf(bytes, length); }

    private static byte[] appended(byte[] bytes, byte extra) {
        byte[] copy = java.util.Arrays.copyOf(bytes, bytes.length + 1);
        copy[bytes.length] = extra;
        return copy;
    }

    private static byte[] manual(int count, long[] keys, int[] runs) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(ChunkLedger.MAGIC);
            out.writeInt(ChunkLedger.FORMAT);
            com.mineclone.data.VarInt.write(out, count);
            for (long k : keys) VarLong.write(out, k);
            for (int r : runs) com.mineclone.data.VarInt.write(out, r);
        }
        return bytes.toByteArray();
    }

    private static void policy() {
        ChunkLedger ledger = new ChunkLedger();
        ledger.record(World.key(4, 4), WorldGenVersion.V1);
        GenPolicy v2 = new WorldGenSettings(WorldGenVersion.V2, GenFeatures.V2, 0).policy(ledger);
        check(v2.versionAt(4, 4) == WorldGenVersion.V1, "a recorded V1 chunk regenerated as V2");
        check(v2.featuresAt(4, 4).equals(GenFeatures.V1), "a V1 chunk got V2 features");
        check(v2.versionAt(9, -9) == WorldGenVersion.V2 && ledger.contains(World.key(9, -9)),
                "a new chunk was not recorded with the world's version");
        World world = new World(3L, GenProfile.NORMAL, WorldGenSettings.LEGACY.policy(new ChunkLedger()));
        world.getChunk(-2, 7);
        GenPolicy recorded = world.genPolicy();
        check(recorded.versionAt(-2, 7) == WorldGenVersion.V1, "V1 world");
        check(world.blankTwin().genPolicy() == recorded, "the network baseline generates with another ledger");
    }

    /** The acceptance of GEN-02: an upgrade changes no block anywhere a player has been. */
    private static void upgradeFixture() throws Exception {
        Path root = Files.createTempDirectory("mineclone-ledger-upgrade-");
        try {
            copyTree(Path.of("src/test/resources/fixtures/saves/alpha-small"), root.resolve("world"));
            SaveManager saves = new SaveManager(root.toFile());
            LevelData level = ((LevelLoad.Loaded) saves.readLevel("world")).data();
            WorldGenSettings before = WorldGenSettings.decode(level.extraSections.get(WorldGenSettings.SAVE_SECTION));
            check(before.equals(WorldGenSettings.LEGACY), "the 1.0 fixture is not a V1 world: " + before);
            float[][] anchors = { { (float) level.spawnX, (float) level.spawnZ }, { (float) level.px, (float) level.pz } };
            ChunkLedger ledger = saves.openLedger("world", before, anchors);
            check(ledger.size() == 0, "a never-upgraded 1.0 world got pins it does not need");
            List<Long> saved = saves.savedChunkKeys("world");
            check(saved.size() == 5, "fixture chunks: " + saved);
            WorldGenSettings after = WorldGenUpgrade.upgrade(before, WorldGenVersion.V2, ledger, saved, anchors, 1234L);
            check(after.version() == WorldGenVersion.V2 && after.upgradedAt() == 1234L, "upgrade settings");
            check(WorldGenUpgrade.seams(ledger, WorldGenVersion.V1).isEmpty(), "seams in a world of one version");
            GenPolicy upgraded = after.policy(ledger);
            World v1 = new World(level.seed, GenProfile.NORMAL, GenPolicy.fixed(WorldGenVersion.V1));
            World now = new World(level.seed, GenProfile.NORMAL, upgraded);
            int compared = 0;
            for (long key : saved) {
                int cx = (int) (key >> 32), cz = (int) key;
                // The roadmap's ring: twelve chunks around everything saved.
                for (int d = -12; d <= 12; d += 12) {
                    check(upgraded.versionAt(cx + d, cz - d) == WorldGenVersion.V1,
                            "seen chunk " + (cx + d) + "," + (cz - d) + " moved to the new generator");
                    check(WorldGenGoldenTests.hash(now.generateDetached(cx + d, cz - d))
                            .equals(WorldGenGoldenTests.hash(v1.generateDetached(cx + d, cz - d))), "a seen chunk changed");
                    compared++;
                }
            }
            check(compared == 15, "compared " + compared);
            check(upgraded.versionAt(200, -200) == WorldGenVersion.V2, "unseen land kept the old generator");
            // Seams ring the pinned land: each borders land left to the new generator.
            List<Long> seams = WorldGenUpgrade.seams(ledger, WorldGenVersion.V2);
            check(!seams.isEmpty() && !seams.contains(World.key(0, 0)) && !seams.contains(World.key(200, -200)),
                    "seams: " + seams.size());
            for (long key : seams) {
                int cx = (int) (key >> 32), cz = (int) key;
                boolean borders = !ledger.contains(World.key(cx + 1, cz)) || !ledger.contains(World.key(cx - 1, cz))
                        || !ledger.contains(World.key(cx, cz + 1)) || !ledger.contains(World.key(cx, cz - 1));
                check(ledger.versionAt(key, null) == WorldGenVersion.V1 && borders, "chunk " + cx + "," + cz + " is no seam");
            }
            saves.saveLedgerAsync("world", ledger.encodeIfDirty());
            saves.flushAndAwait();
            check(new SaveManager(root.toFile()).openLedger("world", after, anchors).snapshot().equals(ledger.snapshot()),
                    "the upgraded ledger did not survive saving");
        } finally {
            deleteTree(root);
        }
    }

    private static void saving() throws Exception {
        Path root = Files.createTempDirectory("mineclone-ledger-save-");
        try {
            SaveManager saves = new SaveManager(root.toFile());
            saves.saveLevel("w", new LevelData("w", 9L, 1, 70, 1, 8.5, 80, 8.5, 0, 0, 1, 0,
                    LevelData.emptyInventory(), com.mineclone.world.GameMode.SURVIVAL, 1, 20, 20, null, Map.of()));
            ChunkLedger ledger = sample();
            saves.saveLedgerAsync("w", ledger.encode());
            check(saves.readLedger("w") instanceof LedgerLoad.Loaded loaded
                    && loaded.ledger().snapshot().equals(ledger.snapshot()), "queued ledger not visible");
            saves.flushAndAwait();
            Path file = root.resolve("w").resolve(SaveFormat.CHUNKS_DIR).resolve(SaveFormat.LEDGER_FILE);
            check(Files.isRegularFile(file), "ledger never reached the disk");
            check(new SaveManager(root.toFile()).readLedger("w") instanceof LedgerLoad.Loaded disk
                    && disk.ledger().snapshot().equals(ledger.snapshot()), "ledger on disk differs");
            check(!saves.savedChunkKeys("w").contains(0L) && saves.savedChunkKeys("w").isEmpty(),
                    "the ledger file was taken for a chunk");
            // The backup of the world carries the ledger with the chunks.
            var backup = saves.backupWorld("w", "manual").get();
            try (var zip = new ZipFile(backup.path().toFile())) {
                check(zip.getEntry(SaveFormat.CHUNKS_DIR + "/" + SaveFormat.LEDGER_FILE) != null, "backup lost the ledger");
            }
            // A world that failed its read check is never written, ledger included.
            byte[] onDisk = Files.readAllBytes(file);
            Files.write(root.resolve("w").resolve(SaveFormat.LEVEL_FILE), new byte[] { 1, 2, 3 });
            SaveManager guarded = new SaveManager(root.toFile());
            check(guarded.readLevel("w") instanceof LevelLoad.Unreadable, "damaged level read");
            ChunkLedger other = new ChunkLedger();
            other.record(World.key(77, 77), WorldGenVersion.V2);
            guarded.saveLedgerAsync("w", other.encode());
            guarded.flushAndAwait();
            check(java.util.Arrays.equals(onDisk, Files.readAllBytes(file)), "protected world's ledger overwritten");
        } finally {
            deleteTree(root);
        }
    }

    private static void quarantine() throws Exception {
        Path root = Files.createTempDirectory("mineclone-ledger-damage-");
        try {
            copyTree(Path.of("src/test/resources/fixtures/saves/alpha-small"), root.resolve("world"));
            Path file = root.resolve("world").resolve(SaveFormat.CHUNKS_DIR).resolve(SaveFormat.LEDGER_FILE);
            byte[] damaged = { 0x1f, (byte) 0x8b, 8, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3 };
            Files.write(file, damaged);
            SaveManager saves = new SaveManager(root.toFile());
            check(saves.readLedger("world") instanceof LedgerLoad.Unreadable, "damaged ledger read");
            // A world that was never upgraded has nothing to lose: everything is V1.
            ChunkLedger plain = saves.openLedger("world", WorldGenSettings.LEGACY, new float[0][]);
            check(plain.size() == 0, "a V1 world was given pins");
            Path evidence;
            try (var files = Files.list(file.getParent())) {
                evidence = files.filter(p -> p.getFileName().toString().startsWith(SaveFormat.LEDGER_FILE + ".corrupt-"))
                        .findFirst().orElse(null);
            }
            check(evidence != null && java.util.Arrays.equals(damaged, Files.readAllBytes(evidence)),
                    "the damaged ledger was not kept as evidence");
            check(Files.notExists(file), "the damaged ledger stayed in place");
            // An upgraded world whose ledger is gone pins what it saved and the land around it.
            var upgraded = new WorldGenSettings(WorldGenVersion.V2, GenFeatures.V2, 99L);
            ChunkLedger rebuilt = saves.openLedger("world", upgraded, new float[][] { { 500f, 500f } });
            GenPolicy policy = upgraded.policy(rebuilt);
            check(policy.versionAt(0, 0) == WorldGenVersion.V1 && policy.versionAt(-2 - WorldGenUpgrade.SEEN_RADIUS, 0)
                    == WorldGenVersion.V1 && policy.versionAt(31, 31) == WorldGenVersion.V1,
                    "a rebuilt ledger forgot seen land");
            check(policy.versionAt(-100, 100) == WorldGenVersion.V2, "a rebuilt ledger pinned unseen land");
        } finally {
            deleteTree(root);
        }
    }

    /** Without a backup the damaged file may not move; the world still opens and never writes over it. */
    private static void keptInPlace() throws Exception {
        Path root = Files.createTempDirectory("mineclone-ledger-kept-");
        try {
            copyTree(Path.of("src/test/resources/fixtures/saves/alpha-small"), root.resolve("world"));
            Path file = root.resolve("world").resolve(SaveFormat.CHUNKS_DIR).resolve(SaveFormat.LEDGER_FILE);
            byte[] damaged = { 0x1f, (byte) 0x8b, 8, 0, 0, 0, 0, 0, 0, 0, 9, 9, 9 };
            Files.write(file, damaged);
            // A file where the backups directory belongs: the session backup fails.
            Files.write(root.resolve("world").resolve("backups"), new byte[] { 0 });
            SaveManager saves = new SaveManager(root.toFile());
            var upgraded = new WorldGenSettings(WorldGenVersion.V2, GenFeatures.V2, 99L);
            ChunkLedger ledger = saves.openLedger("world", upgraded, new float[0][]);
            check(upgraded.policy(ledger).versionAt(0, 0) == WorldGenVersion.V1,
                    "a ledger kept in place was not rebuilt for the session");
            check(Files.exists(file) && java.util.Arrays.equals(damaged, Files.readAllBytes(file)),
                    "the damaged ledger moved without a backup");
            ledger.record(World.key(40, 40), WorldGenVersion.V2);
            saves.saveLedgerAsync("world", ledger.encodeIfDirty());
            saves.flushAndAwait();
            check(java.util.Arrays.equals(damaged, Files.readAllBytes(file)), "the damaged ledger was overwritten");
        } finally {
            deleteTree(root);
        }
    }

    private static void server() throws Exception {
        Path root = Files.createTempDirectory("mineclone-ledger-server-");
        try {
            Path config = root.resolve("server.properties");
            Files.writeString(config, "saves-dir=" + root.toString().replace('\\', '/')
                    + "\nworld=ledger\nseed=7\ndirect=false\nphoton=false\nupnp=false\n");
            var server = new com.mineclone.server.DedicatedServer(com.mineclone.server.ServerConfig.load(config.toFile()));
            var open = com.mineclone.server.DedicatedServer.class.getDeclaredMethod("openWorld");
            open.setAccessible(true);
            check((boolean) open.invoke(server), "server refused to open");
            server.saveWorld();
            var saveField = com.mineclone.server.DedicatedServer.class.getDeclaredField("save");
            saveField.setAccessible(true);
            SaveManager saves = (SaveManager) saveField.get(server);
            var loaderField = com.mineclone.server.DedicatedServer.class.getDeclaredField("loader");
            loaderField.setAccessible(true);
            ((com.mineclone.world.ChunkLoader) loaderField.get(server)).shutdown();
            saves.flushAndAwait();
            LedgerLoad read = new SaveManager(root.toFile()).readLedger("ledger");
            check(read instanceof LedgerLoad.Loaded loaded && loaded.ledger().contains(World.key(0, 0))
                    && loaded.ledger().contains(World.key(-1, 1)) && loaded.ledger().versionAt(World.key(0, 0), null)
                    == WorldGenVersion.LATEST, "the server did not save the chunks it generated: " + read);
        } finally {
            deleteTree(root);
        }
    }

    private static void copyTree(Path from, Path to) throws Exception {
        try (var paths = Files.walk(from)) {
            for (Path p : paths.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else Files.copy(p, target);
            }
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}

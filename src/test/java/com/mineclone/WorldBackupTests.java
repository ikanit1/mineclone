package com.mineclone;

import com.mineclone.save.ChunkSnapshot;
import com.mineclone.save.LevelData;
import com.mineclone.save.LevelLoad;
import com.mineclone.save.SaveManager;
import com.mineclone.save.WorldBackups;
import com.mineclone.world.Chunk;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

final class WorldBackupTests {
    static void runAll(TestMain.Runner r) {
        r.run("session backup precedes writes and occurs once per opening", WorldBackupTests::sessionOrdering);
        r.run("backup rotation keeps recent copies plus migration archives for seven days", WorldBackupTests::rotation);
        r.run("restore creates a renamed copy with identical saved content and no source changes", WorldBackupTests::restore);
        r.run("failed session backup blocks every subsequent world write", WorldBackupTests::failedBackup);
        r.run("unsafe or invalid archive cannot publish a restored world", WorldBackupTests::invalidArchive);
    }

    private static void check(boolean value, String why) { if (!value) throw new AssertionError(why); }
    private static LevelData level(long seed) { return new LevelData(seed, 8.5, 80, 8.5, 0, 0, 0, 0); }
    private static ChunkSnapshot chunk(byte value) {
        byte[] blocks = new byte[Chunk.SIZE_X * Chunk.SIZE_Y * Chunk.SIZE_Z];
        blocks[0] = value;
        return new ChunkSnapshot(0, 0, blocks, new byte[blocks.length]);
    }

    private static void sessionOrdering() throws Exception {
        try (Fixture f = new Fixture()) {
            Map<String, byte[]> original = f.content();
            SaveManager opened = new SaveManager(f.root.toFile());
            var backup = opened.beginWorldSession("world");
            opened.saveChunkAsync("world", chunk((byte) 4));
            opened.saveLevel("world", level(999));
            opened.saveChunkAsync("world", chunk((byte) 5));
            opened.flushAndAwait();
            check(opened.listBackups("world").size() == 1, "multiple writes made multiple session backups");
            assertZip(backup.join().path(), original);
            check(((LevelLoad.Loaded) opened.readLevel("world")).data().seed == 999, "write did not finish after backup");
            opened.beginWorldSession("world").join();
            check(opened.listBackups("world").size() == 2, "next opening did not get a new snapshot");
        }
    }

    private static void rotation() throws Exception {
        try (Fixture f = new Fixture()) {
            Instant first = Instant.parse("2026-01-01T00:00:00Z");
            WorldBackups initial = new WorldBackups(f.root, Clock.fixed(first, ZoneOffset.UTC));
            initial.setRetention(2);
            var migration = initial.snapshot("world", "migration");
            for (int i = 0; i < 5; i++) initial.snapshot("world", "session");
            check(initial.list("world").size() == 3, "migration archive consumed regular retention slot");
            check(Files.exists(migration.path()), "young migration backup was rotated away");
            WorldBackups later = new WorldBackups(f.root, Clock.fixed(first.plusSeconds(8 * 86400), ZoneOffset.UTC));
            later.setRetention(2);
            later.snapshot("world", "manual");
            later.snapshot("world", "manual");
            check(later.list("world").size() == 2, "expired migration archive prevented rotation");
            check(!Files.exists(migration.path()), "expired old migration archive should rotate normally");
            check(later.list("world").get(0).path().toString().endsWith("manual.zip"), "newest first listing");
        }
    }

    private static void restore() throws Exception {
        try (Fixture f = new Fixture()) {
            Map<String, byte[]> original = f.content();
            var backup = f.save.backupWorld("world", "manual").join();
            byte[] archiveBytes = Files.readAllBytes(backup.path());
            f.save.saveLevel("world", level(54321));
            f.save.saveChunkAsync("world", chunk((byte) 7));
            f.save.flushAndAwait();
            Map<String, byte[]> changedSource = f.content();
            String restoredId = f.save.restoreBackup("world", backup).join();
            check(!restoredId.equals("world"), "restored over original world");
            assertFiles(f.world, changedSource);
            check(Arrays.equals(archiveBytes, Files.readAllBytes(backup.path())), "restore changed archive");
            assertZip(backup.path(), original);
            Path restored = f.root.resolve(restoredId);
            for (var entry : original.entrySet()) {
                if (!entry.getKey().equals("level.dat"))
                    check(Arrays.equals(entry.getValue(), Files.readAllBytes(restored.resolve(entry.getKey()))),
                            "restored bytes differ: " + entry.getKey());
            }
            LevelData data = ((LevelLoad.Loaded) f.save.readLevel(restoredId)).data();
            check(data.seed == 12345 && data.name.contains("(бэкап "), "restored identity/content incorrect");
            check(f.save.listWorlds(false).stream().anyMatch(w -> w.id.equals(restoredId) && w.playable()), "restored world cannot open");
        }
    }

    private static void failedBackup() throws Exception {
        try (Fixture f = new Fixture()) {
            Map<String, byte[]> original = f.content();
            Files.writeString(f.world.resolve("backups"), "do not overwrite");
            SaveManager opened = new SaveManager(f.root.toFile());
            boolean failed = false;
            try { opened.beginWorldSession("world").join(); }
            catch (CompletionException expected) { failed = true; }
            check(failed, "backup error did not reach caller");
            opened.saveLevel("world", level(999));
            opened.saveChunkAsync("world", chunk((byte) 4));
            opened.saveIconAsync("world", 1, 1, new int[] { 0xff112233 });
            opened.flushAndAwait();
            assertFiles(f.world, original);
            check(Files.readString(f.world.resolve("backups")).equals("do not overwrite"), "backup obstruction overwritten");
        }
    }

    private static void invalidArchive() throws Exception {
        try (Fixture f = new Fixture()) {
            Path backupDir = Files.createDirectories(f.world.resolve("backups"));
            Path malicious = backupDir.resolve("bad-manual.zip");
            try (var out = new ZipOutputStream(Files.newOutputStream(malicious))) {
                out.putNextEntry(new ZipEntry("chunks/../../escaped.txt"));
                out.write(new byte[] { 1 });
                out.closeEntry();
            }
            var archive = new WorldBackups.Backup(malicious, System.currentTimeMillis(), "manual", Files.size(malicious));
            boolean failed = false;
            try { f.save.restoreBackup("world", archive).join(); }
            catch (CompletionException expected) { failed = true; }
            check(failed, "archive traversal was accepted");
            check(!Files.exists(f.root.resolve("escaped.txt")), "archive escaped staging directory");
            check(f.save.listWorlds(false).size() == 1, "failed restore left a playable partial copy");
            Path invalid = backupDir.resolve("invalid-manual.zip");
            try (var out = new ZipOutputStream(Files.newOutputStream(invalid))) {
                out.putNextEntry(new ZipEntry("level.dat"));
                out.write(new byte[] { 4, 3, 2, 1 });
                out.closeEntry();
            }
            failed = false;
            try { f.save.restoreBackup("world", new WorldBackups.Backup(invalid, 0, "manual", Files.size(invalid))).join(); }
            catch (CompletionException expected) { failed = true; }
            check(failed && f.save.listWorlds(false).size() == 1, "invalid level published from archive");
        }
    }

    private static void assertZip(Path zip, Map<String, byte[]> expected) throws Exception {
        try (ZipFile file = new ZipFile(zip.toFile())) {
            check(file.size() == expected.size(), "snapshot copied unexpected files or nested backups");
            for (var entry : expected.entrySet()) {
                ZipEntry archived = file.getEntry(entry.getKey());
                check(archived != null, "missing backup entry " + entry.getKey());
                try (var in = file.getInputStream(archived)) {
                    check(Arrays.equals(entry.getValue(), in.readAllBytes()), "backup changed bytes " + entry.getKey());
                }
            }
        }
    }

    private static void assertFiles(Path root, Map<String, byte[]> expected) throws IOException {
        for (var entry : expected.entrySet())
            check(Arrays.equals(entry.getValue(), Files.readAllBytes(root.resolve(entry.getKey()))), "source changed: " + entry.getKey());
    }

    private static final class Fixture implements AutoCloseable {
        final Path root = Files.createTempDirectory("mineclone-backup-test-");
        final Path world = root.resolve("world");
        final SaveManager save = new SaveManager(root.toFile());
        Fixture() throws Exception {
            save.saveLevel("world", level(12345));
            save.saveChunkAsync("world", chunk((byte) 3));
            save.flushAndAwait();
            Files.createDirectories(world.resolve("players"));
            Files.write(world.resolve("players/player.dat"), new byte[] { 5, 6, 7 });
            Files.write(world.resolve("chunks/ledger.0"), new byte[] { 8, 9, 10 });
            Files.write(world.resolve("chunks/c.1.1.dat.corrupt-123"), new byte[] { 11, 12 });
            Files.write(world.resolve("icon.png"), new byte[] { 13, 14 });
        }
        Map<String, byte[]> content() throws IOException {
            Map<String, byte[]> result = new LinkedHashMap<>();
            try (var paths = Files.walk(world)) {
                for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                    String relative = world.relativize(path).toString().replace('\\', '/');
                    if (!relative.startsWith("backups/")) result.put(relative, Files.readAllBytes(path));
                }
            }
            return result;
        }
        @Override public void close() throws Exception {
            save.flushAndAwait();
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}

package com.mineclone;

import com.mineclone.data.SectionCodec;
import com.mineclone.data.VarInt;
import com.mineclone.save.LevelData;
import com.mineclone.save.LevelLoad;
import com.mineclone.save.SaveFormat;
import com.mineclone.save.SaveManager;
import com.mineclone.world.BlockType;
import com.mineclone.world.GameMode;
import com.mineclone.world.ItemStack;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

final class LevelFormatTests {
    static void runAll(TestMain.Runner r) {
        r.run("level v11 guards old readers and stores named state sections", LevelFormatTests::headerAndSections);
        r.run("additive v12 with minimum reader11 preserves unknown sections over five saves", LevelFormatTests::additiveFuture);
        r.run("v12 requiring reader12 refuses writes and preserves original bytes", LevelFormatTests::incompatibleFuture);
        r.run("v10 migration creates original-byte migration backup before v11 write", LevelFormatTests::migrationBackup);
        r.run("level snapshot is immutable and queues behind blocked writer without blocking caller", LevelFormatTests::queuedSnapshot);
        r.run("invalid clock bytes fail closed before opening", LevelFormatTests::invalidClock);
        r.run("section codec rejects duplicate and oversized payloads", LevelFormatTests::sectionBounds);
    }

    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private static LevelData level(Map<String, byte[]> extra) {
        ItemStack[] inventory = LevelData.emptyInventory();
        inventory[0] = new ItemStack(BlockType.STONE, 23);
        return new LevelData("v11 fixture", 9876, 1.5, 72, 2.5, 8.5, 80, 8.5,
                .5f, .25f, 1.2f, 4, inventory, GameMode.SURVIVAL, 1234567, 9.5f, 6.5f, null, extra);
    }

    private static void headerAndSections() throws Exception {
        try (Fixture f = new Fixture()) {
            f.save.saveLevel("world", level(Map.of()));
            LevelData data = ((LevelLoad.Loaded) f.save.readLevel("world")).data();
            check(data.seed == 9876 && data.inventory[0].count == 23 && data.health == 9.5f, "round-trip state");
            try (var in = new DataInputStream(new GZIPInputStream(Files.newInputStream(f.level())))) {
                check(in.readInt() == SaveFormat.MAGIC, "magic");
                int version = in.readInt();
                check(version == 11 && version > 10, "old reader guard");
                check(in.readInt() == 11 && !in.readUTF().isBlank(), "minimum reader/writtenBy");
                var sections = SectionCodec.read(in);
                for (String name : new String[] { "world", "player", "player_format", "world_spawn", "clock", "rules", "worldgen" })
                    check(sections.containsKey(name), "missing " + name);
                check(com.mineclone.save.PlayerRecordCodec.decode(sections.get("player")).inventory()[0].count==23,
                        "inventory is nested in the canonical player record");
                check(in.read() == -1, "unexpected trailing data");
            }
        }
    }

    private static void additiveFuture() throws Exception {
        try (Fixture f = new Fixture()) {
            byte[] opaque = { 77, 0, -1, 32 };
            f.save.saveLevel("world", level(Map.of("future:payload", opaque)));
            f.save.flushAndAwait();
            rewriteHeader(f.level(), 12, 11);
            for (int i = 0; i < 5; i++) {
                SaveManager current = new SaveManager(f.root.toFile());
                var result = current.readLevel("world");
                check(result instanceof LevelLoad.Loaded, "additive future refused");
                LevelData data = ((LevelLoad.Loaded) result).data();
                check(Arrays.equals(opaque, data.extraSections.get("future:payload")), "opaque payload changed");
                current.saveLevel("world", data);
                current.flushAndAwait();
            }
        }
    }

    private static void incompatibleFuture() throws Exception {
        try (Fixture f = new Fixture()) {
            f.save.saveLevel("world", level(Map.of()));
            f.save.flushAndAwait();
            rewriteHeader(f.level(), 12, 12);
            byte[] original = Files.readAllBytes(f.level());
            SaveManager reader = new SaveManager(f.root.toFile());
            check(reader.readLevel("world") instanceof LevelLoad.TooNew, "minimum reader guard ignored");
            reader.saveLevel("world", level(Map.of()));
            reader.flushAndAwait();
            check(Arrays.equals(original, Files.readAllBytes(f.level())), "future file overwritten");
        }
    }

    private static void migrationBackup() throws Exception {
        try (Fixture f = new Fixture()) {
            Files.createDirectories(f.level().getParent());
            try (var out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(f.level())))) {
                out.writeInt(SaveFormat.MAGIC); out.writeInt(10); out.writeUTF("legacy");
                out.writeLong(4422); out.writeLong(999); out.writeFloat(7); out.writeFloat(8);
                for (double value : new double[] { 1, 70, 2, 3, 71, 4 }) out.writeDouble(value);
                out.writeFloat(0); out.writeFloat(0); out.writeFloat(1); out.writeInt(0); out.writeInt(0);
                VarInt.write(out, 1); out.writeUTF("inventory"); VarInt.write(out, 1); out.writeByte(0);
            }
            byte[] original = Files.readAllBytes(f.level());
            LevelData old = f.save.loadLevel("world");
            check(old != null && old.seed == 4422, "legacy v10 fixture failed");
            f.save.saveLevel("world", old);
            f.save.flushAndAwait();
            var backups = f.save.listBackups("world");
            check(backups.size() == 1 && backups.get(0).migration(), "missing migration backup");
            try (var zip = new java.util.zip.ZipFile(backups.get(0).path().toFile());
                 var in = zip.getInputStream(zip.getEntry("level.dat"))) {
                check(Arrays.equals(original, in.readAllBytes()), "migration backup contains new data");
            }
            check(f.save.readLevel("world") instanceof LevelLoad.Loaded, "migrated v11 unreadable");
        }
    }

    private static void queuedSnapshot() throws Exception {
        try (Fixture f = new Fixture()) {
            var field = SaveManager.class.getDeclaredField("chunkWriter");
            field.setAccessible(true);
            ExecutorService writer = (ExecutorService) field.get(f.save);
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            writer.submit(() -> { entered.countDown(); try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
            check(entered.await(2, TimeUnit.SECONDS), "writer latch");
            ExecutorService callers = Executors.newFixedThreadPool(2);
            try {
                byte[] opaque = { 4, 5, 6 };
                LevelData input = level(Map.of("future:payload", opaque));
                callers.submit(() -> f.save.saveLevel("world", input)).get(1, TimeUnit.SECONDS);
                check(!Files.exists(f.level()), "saveLevel performed disk writes on caller");
                input.inventory[0].count = 1;
                opaque[0] = 99;
                var reading = callers.submit(() -> f.save.readLevel("world"));
                boolean waited = false;
                try { reading.get(100, TimeUnit.MILLISECONDS); }
                catch (java.util.concurrent.TimeoutException expected) { waited = true; }
                check(waited, "read did not wait for queued level write");
                release.countDown();
                LevelData restored = ((LevelLoad.Loaded) reading.get(3, TimeUnit.SECONDS)).data();
                check(restored.inventory[0].count == 23, "queued snapshot borrowed mutable stack");
                check(restored.extraSections.get("future:payload")[0] == 4, "queued snapshot borrowed section bytes");
            } finally { release.countDown(); callers.shutdownNow(); }
        }
    }

    private static void invalidClock() throws Exception {
        try (Fixture f = new Fixture()) {
            f.save.saveLevel("world", level(Map.of()));
            f.save.flushAndAwait();
            byte[] bytes = inflate(f.level());
            try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
                int magic = in.readInt(), version = in.readInt(), minimum = in.readInt();
                String writer = in.readUTF();
                Map<String, byte[]> sections = SectionCodec.read(in);
                sections.put("clock", new byte[] { 99 });
                try (var out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(f.level())))) {
                    out.writeInt(magic); out.writeInt(version); out.writeInt(minimum); out.writeUTF(writer);
                    SectionCodec.write(out, sections);
                }
            }
            check(f.save.readLevel("world") instanceof LevelLoad.Unreadable, "invalid clock accepted");
        }
    }

    private static void sectionBounds() throws Exception {
        for (boolean duplicate : new boolean[] { false, true }) {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                VarInt.write(out, duplicate ? 2 : 1);
                out.writeUTF("x"); VarInt.write(out, duplicate ? 0 : SectionCodec.MAX_SECTION_BYTES + 1);
                if (duplicate) { out.writeUTF("x"); VarInt.write(out, 0); }
            }
            boolean rejected = false;
            try { SectionCodec.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))); }
            catch (java.io.IOException expected) { rejected = true; }
            check(rejected, "malformed sections accepted");
        }
    }

    private static byte[] inflate(Path file) throws Exception {
        try (var in = new GZIPInputStream(Files.newInputStream(file))) { return in.readAllBytes(); }
    }
    private static void rewriteHeader(Path file, int version, int minimum) throws Exception {
        byte[] bytes = inflate(file);
        ByteBuffer.wrap(bytes).putInt(4, version).putInt(8, minimum);
        try (var out = new GZIPOutputStream(Files.newOutputStream(file))) { out.write(bytes); }
    }
    private static final class Fixture implements AutoCloseable {
        final Path root = Files.createTempDirectory("mineclone-level11-");
        final SaveManager save = new SaveManager(root.toFile());
        Fixture() throws Exception {}
        Path level() { return root.resolve("world/level.dat"); }
        @Override public void close() throws Exception {
            save.flushAndAwait();
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}

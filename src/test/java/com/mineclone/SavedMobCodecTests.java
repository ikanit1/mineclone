package com.mineclone;

import com.mineclone.data.VarInt;
import com.mineclone.data.VarLong;
import com.mineclone.save.*;
import com.mineclone.sim.*;
import com.mineclone.world.*;
import com.mineclone.world.entity.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.joml.Vector3f;

/** Save migration boundaries between the independent animal implementations. */
public final class SavedMobCodecTests {
    public static void runAll(TestMain.Runner runner) {
        runner.run("both historical entity layouts retain identity and available state", SavedMobCodecTests::legacyLayouts);
        runner.run("canonical animal records retain babies breeding clocks and opaque tails", SavedMobCodecTests::canonical);
        runner.run("future entity schema protects original chunk bytes and never quarantines", SavedMobCodecTests::futureProtected);
        runner.run("over-limit animal snapshots fail before bodies or pending records are lost", SavedMobCodecTests::overflow);
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }

    private static SavedMob animal() {
        return new SavedMob("COW", 9_000_000_000L, 4.25f, 70f, 6.5f, .75f, 3f,
                -850f, true, 290f, 22f, new byte[] { 17, 0, -1, 3 });
    }

    private static byte[] legacy(boolean generation) throws IOException {
        SavedMob mob = animal();
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            if (generation) {
                out.writeByte(1); out.writeInt(1); out.writeLong(mob.id()); out.writeUTF(mob.type());
            } else {
                VarInt.write(out, 1);
                var record = new ByteArrayOutputStream();
                try (var data = new DataOutputStream(record)) {
                    data.writeUTF(mob.type()); VarLong.write(data, mob.id());
                    position(data, mob); data.write(mob.tail());
                }
                VarInt.write(out, record.size()); record.writeTo(out);
                return bytes.toByteArray();
            }
            position(out, mob);
            out.writeFloat(mob.age()); out.writeBoolean(mob.persistent());
            out.writeFloat(mob.loveCooldown()); out.writeFloat(mob.love());
        }
        return bytes.toByteArray();
    }

    private static void position(DataOutputStream out, SavedMob mob) throws IOException {
        out.writeFloat(mob.x()); out.writeFloat(mob.y()); out.writeFloat(mob.z());
        out.writeFloat(mob.yaw()); out.writeFloat(mob.health());
    }

    private static void legacyLayouts() throws Exception {
        for (boolean generation : new boolean[] { false, true }) {
            SavedMob old = SavedMobCodec.decode(legacy(generation)).get(0);
            SavedMob migrated = SavedMobCodec.decode(SavedMobCodec.encode(List.of(old))).get(0);
            check(migrated.id() == animal().id() && migrated.health() == 3 && migrated.x() == 4.25f,
                    "historical identity/pose/health changed");
            if (generation) {
                check(migrated.age() == -850 && migrated.loveCooldown() == 290 && migrated.love() == 22,
                        "remote breeding state was lost");
            } else {
                check(migrated.age() == 0 && migrated.persistent() && migrated.loveCooldown() == 0,
                        "historical adult defaults changed");
                check(Arrays.equals(migrated.tail(), animal().tail()), "old opaque tail was reinterpreted");
            }
        }
        check(SavedMobCodec.decode(new byte[] { 0 }).isEmpty(), "headerless empty herd");
        check(SavedMobCodec.decode(new byte[] { 1, 0, 0, 0, 0 }).isEmpty(), "remote empty herd");
        // A remote 257-animal save is legitimate even though old headerless saves
        // had the stricter 256-record limit. Migration must not truncate that farm.
        var records = new ArrayList<SavedMob>();
        for (int i = 1; i <= 257; i++) records.add(new SavedMob("COW", i, 4, 70, 4, 0, 3, null));
        var largeRemote = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(largeRemote)) {
            out.writeByte(1); out.writeInt(records.size());
            for (SavedMob record : records) {
                out.writeLong(record.id()); out.writeUTF(record.type()); position(out, record);
                out.writeFloat(0); out.writeBoolean(true); out.writeFloat(0); out.writeFloat(0);
            }
        }
        var imported = SavedMobCodec.decode(largeRemote.toByteArray());
        check(SavedMobCodec.decode(SavedMobCodec.encode(imported)).size() == 257, "larger remote farm truncated");
    }

    private static void canonical() throws Exception {
        var unknown = new SavedMob("FUTURE_ANIMAL", 99, 5, 70, 5, .2f, 50,
                -600, true, 120, 8, new byte[] { 4, 1, -1 });
        List<SavedMob> records = List.of(animal(), unknown);
        for (int cycle = 0; cycle < 5; cycle++) {
            records = SavedMobCodec.decode(SavedMobCodec.encode(records));
            var child = records.get(0).toMob(new Random(1));
            check(child.id == animal().id() && child.age == -850 && child.loveCooldown == 290 && child.love == 22,
                    "baby or breeding clock changed");
            check(Arrays.equals(child.savedTail, animal().tail()), "materialized opaque tail changed");
            check(records.get(1).toMob(new Random(1)) == null, "unknown type materialized");
            check(records.get(1).age() == -600 && Arrays.equals(records.get(1).tail(), unknown.tail()),
                    "unknown state changed");
        }
        byte[] valid = SavedMobCodec.encode(records);
        try { SavedMobCodec.decode(Arrays.copyOf(valid, valid.length - 1));
            throw new AssertionError("truncated record accepted");
        } catch (IOException expected) {}
        try { SavedMobCodec.encode(List.of(animal(), animal()));
            throw new AssertionError("duplicate id accepted");
        } catch (IOException expected) {}
    }

    private static ChunkSnapshot snapshot(byte[] entities) {
        byte[] blocks = new byte[SaveFormat.CHUNK_VOLUME];
        return new ChunkSnapshot(0, 0, blocks, blocks.clone(), null, null, null,
                entities == null ? Map.of() : Map.of(EntityPersistence.SECTION, entities));
    }

    private static void futureProtected() throws Exception {
        Path root = Files.createTempDirectory("mineclone-animal-schema-");
        Path chunk = root.resolve("world/chunks/" + SaveFormat.chunkFileName(0, 0));
        try {
            Files.createDirectories(chunk.getParent());
            byte[] future = SavedMobCodec.encode(List.of(animal()));
            future[4] = 99;
            try (var out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(chunk)))) {
                out.writeInt(SaveFormat.MAGIC); out.writeInt(SaveFormat.CHUNK_VERSION);
                out.writeInt(SaveFormat.CHUNK_VERSION); ChunkSectionCodec.write(out, snapshot(future));
            }
            byte[] original = Files.readAllBytes(chunk);
            var save = new SaveManager(root.toFile());
            try {
                check(save.readChunk("world", 0, 0) instanceof ChunkLoad.TooNew, "future schema became corruption");
                World world = new World(1) {
                    @Override public Chunk generateDetached(int x, int z) { return new Chunk(x, z); }
                };
                var loader = new ChunkLoader(world, new ChunkMesher(world), save, "world");
                loader.setMeshing(false);
                try { check(loader.loadNow(0, 0).isReadOnly(), "future chunk was writable"); }
                finally { loader.shutdown(); }
                save.saveChunkAsync("world", snapshot(null));
                save.flushAndAwait();
                check(Arrays.equals(original, Files.readAllBytes(chunk)), "future raw bytes were overwritten");
                try (var files = Files.list(chunk.getParent())) {
                    check(files.noneMatch(path -> path.getFileName().toString().contains("corrupt")),
                            "future chunk was quarantined");
                }
            } finally { save.flushAndAwait(); }
        } finally {
            try (var files = Files.walk(root)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void overflow() {
        World world = new World(91);
        Chunk chunk = world.getChunk(0, 0);
        chunk.modified = true;
        var store = new EntityStore();
        var session = new WorldSession(world, new WorldClock(), new MobSpawner(1), store,
                new Participants(), () -> new Vector3f(0, 70, 0), WorldEvents.NONE);
        var pending = new SavedMob("FUTURE_ANIMAL", 100_000, 5, 70, 5, 0, 20, new byte[] { 9 });
        chunk.setPendingMobs(List.of(pending));
        for (int i = 0; i < SavedMobCodec.MAX_RECORDS; i++)
            store.mobs.add(new Mob(MobType.COW, 5, 70, 5, new Random(i)));
        try { session.snapshotChunk(chunk); throw new AssertionError("over-limit snapshot was partially written"); }
        catch (IllegalStateException expected) {}
        check(store.mobs.size() == SavedMobCodec.MAX_RECORDS && chunk.copyPendingMobs().size() == 1
                && chunk.modified, "failed snapshot discarded state");
        check(Arrays.equals(chunk.copyPendingMobs().get(0).tail(), new byte[] { 9 }), "pending opaque record changed");
    }
}

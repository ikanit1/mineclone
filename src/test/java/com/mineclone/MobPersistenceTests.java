package com.mineclone;

import com.mineclone.data.VarInt;
import com.mineclone.data.VarLong;
import com.mineclone.save.ChunkSectionCodec;
import com.mineclone.save.ChunkSnapshot;
import com.mineclone.save.SavedMob;
import com.mineclone.sim.EntityStore;
import com.mineclone.sim.Participants;
import com.mineclone.sim.WorldClock;
import com.mineclone.sim.WorldEvents;
import com.mineclone.sim.WorldSession;
import com.mineclone.world.Chunk;
import com.mineclone.world.World;
import com.mineclone.world.entity.Mob;
import com.mineclone.world.entity.MobSpawner;
import com.mineclone.world.entity.MobType;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.joml.Vector3f;

/**
 * Animals stay where they were (MOB-01) and keep one id for life (MOB-02).
 *
 * Mobs used to be written nowhere: {@code despawnFar} removed everything beyond 72
 * blocks, so a herd left behind was gone and a killed cow was simply replaced by the
 * spawner. Test names carry the scenario ids of
 * {@code docs/changes/mob-persistence/specs}.
 */
final class MobPersistenceTests {
    private static final long SEED = 91L;

    static void runAll(NetworkTests.Runner r) {
        r.run("MP-1 animals are written with their chunk, hostiles are not", MobPersistenceTests::animalsWritten);
        r.run("MP-2 a written sheep comes back with its id, place, yaw and health", MobPersistenceTests::roundTrip);
        r.run("MP-3 a cow that walked away leaves its old chunk's write", MobPersistenceTests::walkedAway);
        r.run("MP-4 an evicted herd comes back exactly once", MobPersistenceTests::evictAndReload);
        r.run("MP-5 despawn by distance spares animals", MobPersistenceTests::despawnSpares);
        r.run("MP-6 a mob from a newer build is kept byte for byte and never enters", MobPersistenceTests::unknownKept);
        r.run("MP-7 corrupt entity records fail the read", MobPersistenceTests::corrupt);
        r.run("MP-8 chunks without the section read with no mobs", MobPersistenceTests::legacy);
        r.run("MP-9 a server's herd survives a restart with its ids", MobPersistenceTests::serverRestart);
        r.run("ID-1 new mobs get increasing positive ids", MobPersistenceTests::allocation);
        r.run("ID-2 the id counter survives save and reopen", MobPersistenceTests::counterSaved);
        r.run("ID-3 adopting a saved mob moves the counter past its id", MobPersistenceTests::counterHeals);
        r.run("ID-4 a mob written into two chunks enters once", MobPersistenceTests::writtenTwice);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static WorldSession session(World w, EntityStore entities) {
        return new WorldSession(w, new WorldClock(0.5), new MobSpawner(1L), entities, new Participants(),
                () -> new Vector3f(8f, 70f, 8f), WorldEvents.NONE);
    }

    private static Mob mob(MobType type, float x, float y, float z) {
        return new Mob(type, x, y, z, new Random(7));
    }

    private static byte[] write(ChunkSnapshot snapshot) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            ChunkSectionCodec.write(out, snapshot);
        }
        return bytes.toByteArray();
    }

    private static ChunkSnapshot read(byte[] bytes, int cx, int cz) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            return ChunkSectionCodec.read(in, cx, cz);
        }
    }

    /** A written and re-read copy of a snapshot: what the disk gives back. */
    private static ChunkSnapshot throughDisk(ChunkSnapshot snapshot) throws IOException {
        return read(write(snapshot), snapshot.cx, snapshot.cz);
    }

    private static List<MobType> types(ChunkSnapshot snapshot) {
        List<MobType> out = new ArrayList<>();
        for (SavedMob m : snapshot.mobs) out.add(MobType.valueOf(m.type()));
        return out;
    }

    private static void animalsWritten() {
        World w = new World(SEED);
        Chunk c = w.getChunk(0, 0);
        EntityStore e = new EntityStore();
        WorldSession s = session(w, e);
        e.mobs.add(mob(MobType.COW, 4, 70, 4));
        e.mobs.add(mob(MobType.RABBIT, 5, 70, 5));
        e.mobs.add(mob(MobType.WOLF, 6, 70, 6));
        e.mobs.add(mob(MobType.ZOMBIE, 7, 70, 7));
        ChunkSnapshot snap = s.snapshotChunk(c);
        check(snap != null, "a chunk with animals must be written even when its blocks did not change");
        check(types(snap).equals(List.of(MobType.COW, MobType.RABBIT, MobType.WOLF)), "written: " + types(snap));
    }

    private static void roundTrip() throws IOException {
        World w = new World(SEED);
        EntityStore e = new EntityStore();
        WorldSession s = session(w, e);
        Mob sheep = mob(MobType.SHEEP, 3.25f, 71f, 9.5f);
        sheep.yaw = 1.25f;
        sheep.health = 5f;
        e.mobs.add(sheep);
        ChunkSnapshot snap = throughDisk(s.snapshotChunk(w.getChunk(0, 0)));
        check(sheep.id > 0, "the sheep had no id when it was written");

        World w2 = new World(SEED);
        Chunk c2 = w2.getChunk(0, 0);
        EntityStore e2 = new EntityStore();
        WorldSession s2 = session(w2, e2);
        c2.setPendingMobs(snap.mobs);
        s2.adoptChunkItems(c2);
        check(e2.mobs.size() == 1, "mobs after the load: " + e2.mobs.size());
        Mob back = e2.mobs.get(0);
        check(back.type == MobType.SHEEP && back.id == sheep.id && back.position.x == 3.25f && back.position.y == 71f
                && back.position.z == 9.5f && back.yaw == 1.25f && back.health == 5f,
                "came back as " + back.type + " #" + back.id + " at " + back.position + " yaw " + back.yaw + " hp " + back.health);
    }

    private static void walkedAway() {
        World w = new World(SEED);
        Chunk a = w.getChunk(0, 0), b = w.getChunk(1, 0);
        EntityStore e = new EntityStore();
        WorldSession s = session(w, e);
        Mob cow = mob(MobType.COW, 8, 70, 8);
        e.mobs.add(cow);
        check(types(s.snapshotChunk(a)).equals(List.of(MobType.COW)), "the cow is not in chunk A's first write");
        cow.position.x += Chunk.SIZE_X;
        ChunkSnapshot again = s.snapshotChunk(a);
        check(again != null && again.mobs.isEmpty(), "chunk A must be written again, without the cow");
        check(types(s.snapshotChunk(b)).equals(List.of(MobType.COW)), "chunk B's write must hold the cow");
    }

    private static void evictAndReload() throws IOException {
        World w = new World(SEED);
        Chunk c = w.getChunk(0, 0);
        EntityStore e = new EntityStore();
        WorldSession s = session(w, e);
        for (int i = 0; i < 4; i++)
            e.mobs.add(mob(MobType.COW, 2 + 3 * i, 70, 5));
        ChunkSnapshot snap = throughDisk(s.snapshotChunk(c));
        long[] ids = e.mobs.stream().mapToLong(m -> m.id).toArray();
        s.releaseEntitiesIn(Set.of(World.key(0, 0)));
        check(e.mobs.isEmpty(), "the evicted chunk's mobs must leave the world");
        w.removeChunk(0, 0);

        Chunk back = w.getChunk(0, 0);
        back.setPendingMobs(snap.mobs);
        s.adoptChunkItems(back);
        long[] after = e.mobs.stream().mapToLong(m -> m.id).toArray();
        check(Arrays.equals(ids, after), "herd ids before " + Arrays.toString(ids) + ", after " + Arrays.toString(after));

        // A reload that the eviction did not precede must not add a second herd.
        back.setPendingMobs(snap.mobs);
        s.adoptChunkItems(back);
        check(e.mobs.size() == 4, "the herd entered twice: " + e.mobs.size());
    }

    private static void despawnSpares() {
        List<Mob> mobs = new ArrayList<>(List.of(mob(MobType.COW, 100, 70, 0), mob(MobType.ZOMBIE, 100, 70, 5)));
        new MobSpawner(1L).despawnFar(mobs, new Vector3f(0, 70, 0));
        check(mobs.size() == 1 && mobs.get(0).type == MobType.COW, "left after despawn: " + mobs.size());
    }

    private static SavedMob mammoth() {
        return new SavedMob("MAMMOTH", 77L, 4f, 70f, 4f, 0.5f, 30f, new byte[] { 1, 2, 3 });
    }

    private static ChunkSnapshot withMobs(int cx, int cz, List<SavedMob> mobs) {
        byte[] blocks = new byte[com.mineclone.save.SaveFormat.CHUNK_VOLUME];
        return new ChunkSnapshot(cx, cz, blocks, blocks.clone(), null, null, null, Map.of(), 0, new long[0], mobs);
    }

    private static void unknownKept() throws IOException {
        byte[] first = write(withMobs(0, 0, List.of(mammoth())));
        ChunkSnapshot read = read(first, 0, 0);
        check(read.mobs.size() == 1 && read.mobs.get(0).type().equals("MAMMOTH")
                && Arrays.equals(read.mobs.get(0).tail(), new byte[] { 1, 2, 3 }), "the unknown record did not survive the read");

        World w = new World(SEED);
        Chunk c = w.getChunk(0, 0);
        EntityStore e = new EntityStore();
        WorldSession s = session(w, e);
        c.setPendingMobs(read.mobs);
        s.adoptChunkItems(c);
        check(e.mobs.isEmpty(), "a mob of an unknown type entered the world");
        ChunkSnapshot again = s.snapshotChunk(c);
        check(again != null && again.mobs.size() == 1, "the unknown record was not written back");
        SavedMob kept = again.mobs.get(0), original = mammoth();
        check(kept.type().equals(original.type()) && kept.id() == original.id() && kept.x() == original.x()
                && kept.health() == original.health() && Arrays.equals(kept.tail(), original.tail()),
                "the unknown record changed on the way back");
    }

    /** An {@code entities} section written by hand, to feed the reader what no writer would. */
    private static byte[] section(int count, byte[]... records) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            VarInt.write(out, count);
            for (byte[] record : records) {
                VarInt.write(out, record.length);
                out.write(record);
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] record(String type, long id, float x, float y, float z, float yaw, float health) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(type);
            VarLong.write(out, id);
            out.writeFloat(x); out.writeFloat(y); out.writeFloat(z); out.writeFloat(yaw); out.writeFloat(health);
        }
        return bytes.toByteArray();
    }

    private static boolean readFails(byte[] entities) throws IOException {
        byte[] blocks = new byte[com.mineclone.save.SaveFormat.CHUNK_VOLUME];
        byte[] chunk = write(new ChunkSnapshot(0, 0, blocks, blocks.clone(), null, null, null, Map.of("entities", entities)));
        try {
            read(chunk, 0, 0);
            return false;
        } catch (IOException expected) {
            return true;
        }
    }

    private static void corrupt() throws IOException {
        byte[] good = record("COW", 5, 4, 70, 4, 0, 10);
        check(!readFails(section(1, good)), "a valid hand-written record must read");
        byte[][] many = new byte[257][];
        Arrays.fill(many, good);
        check(readFails(section(257, many)), "257 records must fail");
        check(readFails(section(1, record("COW", 5, Float.NaN, 70, 4, 0, 10))), "a NaN position must fail");
        check(readFails(section(1, record("COW", 0, 4, 70, 4, 0, 10))), "a zero id must fail");
        check(readFails(section(1, record("COW", 5, 4, 70, 4, 0, 0))), "a zero health must fail");
        check(readFails(section(1, Arrays.copyOf(good, good.length - 3))), "a record shorter than its fields must fail");
        check(readFails(section(1, new byte[1025])), "a record over 1024 bytes must fail");
    }

    private static void legacy() throws IOException {
        byte[] blocks = new byte[com.mineclone.save.SaveFormat.CHUNK_VOLUME];
        ChunkSnapshot old = read(write(new ChunkSnapshot(0, 0, blocks, blocks.clone())), 0, 0);
        check(old.mobs.isEmpty(), "a chunk without the section brought mobs");
    }

    private static void serverRestart() throws IOException {
        World w = new World(SEED);
        EntityStore e = new EntityStore();
        WorldSession s = session(w, e);
        for (int i = 0; i < 3; i++)
            e.mobs.add(mob(MobType.SHEEP, 3 + 4 * i, 70, 11));
        ChunkSnapshot snap = throughDisk(s.snapshotChunk(w.getChunk(0, 0)));
        byte[] counter = e.encodeIds();
        long[] ids = e.mobs.stream().mapToLong(m -> m.id).toArray();

        World w2 = new World(SEED);
        Chunk c2 = w2.getChunk(0, 0);
        EntityStore e2 = new EntityStore();
        e2.restoreIds(counter);
        WorldSession s2 = session(w2, e2);
        c2.setPendingMobs(snap.mobs);
        s2.adoptChunkItems(c2);
        long[] after = e2.mobs.stream().mapToLong(m -> m.id).toArray();
        check(Arrays.equals(ids, after), "herd before " + Arrays.toString(ids) + ", after restart " + Arrays.toString(after));
    }

    private static void allocation() {
        EntityStore e = new EntityStore();
        Mob a = mob(MobType.PIG, 1, 70, 1), b = mob(MobType.PIG, 2, 70, 2);
        e.mobs.add(a);
        e.assignIds();
        e.mobs.add(b);
        e.assignIds();
        check(a.id > 0 && b.id > a.id && b.id <= Integer.MAX_VALUE, "ids " + a.id + ", " + b.id);
    }

    private static void counterSaved() {
        EntityStore e = new EntityStore();
        for (int i = 0; i < 40; i++) {
            e.mobs.add(mob(MobType.CHICKEN, i, 70, 0));
            e.assignIds();
        }
        long highest = e.mobs.stream().mapToLong(m -> m.id).max().orElse(0);
        EntityStore reopened = new EntityStore();
        reopened.restoreIds(e.encodeIds());
        Mob fresh = mob(MobType.CHICKEN, 0, 70, 0);
        reopened.mobs.add(fresh);
        reopened.assignIds();
        check(highest >= 40 && fresh.id > highest, "after reopening: " + fresh.id + ", highest before " + highest);
    }

    private static void counterHeals() {
        World w = new World(SEED);
        Chunk c = w.getChunk(0, 0);
        EntityStore e = new EntityStore();
        e.restoreIds(null);
        WorldSession s = session(w, e);
        c.setPendingMobs(List.of(new SavedMob("COW", 900L, 4f, 70f, 4f, 0f, 10f, new byte[0])));
        s.adoptChunkItems(c);
        Mob fresh = mob(MobType.COW, 6, 70, 6);
        e.mobs.add(fresh);
        e.assignIds();
        check(fresh.id > 900, "the counter did not move past an adopted id: " + fresh.id);
    }

    private static void writtenTwice() {
        World w = new World(SEED);
        Chunk a = w.getChunk(0, 0), b = w.getChunk(1, 0);
        EntityStore e = new EntityStore();
        WorldSession s = session(w, e);
        a.setPendingMobs(List.of(new SavedMob("COW", 7L, 4f, 70f, 4f, 0f, 10f, new byte[0])));
        b.setPendingMobs(List.of(new SavedMob("COW", 7L, 20f, 70f, 4f, 0f, 10f, new byte[0])));
        s.adoptChunkItems(a);
        s.adoptChunkItems(b);
        check(e.mobs.size() == 1 && e.mobs.get(0).id == 7, "mobs with id 7: " + e.mobs.size());
    }
}

package com.mineclone;

import com.mineclone.data.ResourceId;
import com.mineclone.save.ChunkSnapshot;
import com.mineclone.save.LevelData;
import com.mineclone.save.PlayerRecord;
import com.mineclone.save.SaveManager;
import com.mineclone.save.SavedMob;
import com.mineclone.sim.EntityStore;
import com.mineclone.sim.Participant;
import com.mineclone.sim.Participants;
import com.mineclone.sim.WorldClock;
import com.mineclone.sim.WorldEvents;
import com.mineclone.sim.WorldSession;
import com.mineclone.sim.ecology.Ecology;
import com.mineclone.sim.ecology.EcologyCodec;
import com.mineclone.sim.ecology.SpeciesTable;
import com.mineclone.world.Chunk;
import com.mineclone.world.ChunkLoader;
import com.mineclone.world.GameMode;
import com.mineclone.world.GenProfile;
import com.mineclone.world.World;
import com.mineclone.world.damage.ArmorView;
import com.mineclone.world.damage.DamageSource;
import com.mineclone.world.entity.Mob;
import com.mineclone.world.entity.MobSpawner;
import com.mineclone.world.entity.MobType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/** LW-03: server travel parks animals without freeing saved reservations or filling the active cap. */
public final class EcologyTravelTests {
    private static final long SEED = 20261002L;
    private static final ResourceId COW = ResourceId.of("cow");
    public static void runAll(TestMain.Runner r) {
        r.run("server travel frees active slots while saved herds retain ids and population", EcologyTravelTests::travel);
        r.run("parked animals survive real save reopening and reenter exactly once", EcologyTravelTests::saveReturn);
        r.run("turning Living World off wakes loaded parked animals once", EcologyTravelTests::disabled);
        r.run("parking keeps read-only, full and already-owned records without losing bodies", EcologyTravelTests::fallbacks);
    }
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private static final class Traveller implements Participant {
        final Vector3f at = new Vector3f(8.5f, 65.001f, 8.5f);
        @Override public int id() { return 1; }
        @Override public Vector3fc position() { return at; }
        @Override public Vector3fc eye() { return new Vector3f(at).add(0, EYE_HEIGHT, 0); }
        @Override public GameMode mode() { return GameMode.SURVIVAL; }
        @Override public boolean alive() { return true; }
        @Override public ArmorView armor() { return ArmorView.NONE; }
        @Override public boolean local() { return false; }
        @Override public boolean damage(DamageSource source, float amount) { return true; }
        @Override public Vector3f viewDirection(Vector3f into) { return into.set(0, 0, -1); }
    }
    private static final class Scenario {
        final World world = new World(SEED, GenProfile.FLAT);
        final WorldClock clock = new WorldClock(.5);
        final EntityStore entities = new EntityStore();
        final Participants participants = new Participants();
        final Traveller traveller = new Traveller();
        final WorldSession session;
        Scenario() {
            participants.put(traveller);
            session = new WorldSession(world, clock, new MobSpawner(SEED), entities, participants,
                    new WorldSession.Host() {
                        @Override public Vector3f mobFocus() { return traveller.at; }
                        @Override public boolean focusIsBody() { return false; }
                        @Override public boolean spawnMobs() { return false; }
                    }, WorldEvents.NONE);
        }
        void budget(float x, int count) {
            var region = session.ecology().regionAt(x, 8.5f);
            for (var species : SpeciesTable.get().definitions()) {
                region.population(species.id(), 0); region.capacity(species.id(), 0); region.protectedCount(species.id(), 0);
            }
            region.population(COW, count); region.capacity(COW, count);
        }
        Set<Long> herd(int count) {
            world.getChunk(0, 0); budget(8.5f, 30); session.ecology().setEnabled(true);
            for (int i = 0; i < count; i++) {
                Mob mob = new Mob(MobType.COW, 2.5f + i, 65.001f, 8.5f, new Random(SEED + i));
                mob.savedTail = new byte[] { (byte) i, 19, 27 };
                check(session.ecology().claim(mob), "cow budget claim failed"); entities.mobs.add(mob);
            }
            return ids(entities.mobs);
        }
        void leave() {
            traveller.at.x = 328.5f;
            session.tickMobs(2.1f);
        }
        Map<String, byte[]> sections() {
            Map<String, byte[]> into = new LinkedHashMap<>();
            session.ecology().writeSections(into); into.put(WorldClock.SAVE_SECTION, clock.encode()); into.put(EntityStore.IDS_SECTION, entities.encodeIds()); return into;
        }
    }
    private static Set<Long> ids(List<Mob> mobs) { return mobs.stream().map(mob -> mob.id).collect(Collectors.toSet()); }
    private static Map<Long, SavedMob> parked(World world) {
        Map<Long, SavedMob> records = new LinkedHashMap<>();
        for (Chunk chunk : world.getLoadedChunks())
            for (SavedMob mob : chunk.copyPendingMobs()) check(records.put(mob.id(), mob) == null, "duplicate parked id");
        return records;
    }
    private static void travel() throws Exception {
        Scenario f = new Scenario(); Set<Long> original = f.herd(12);
        f.leave();
        check(f.entities.mobs.isEmpty(), "far server herd kept the whole active cap");
        check(parked(f.world).keySet().equals(original), "parking lost old ids");
        check(f.session.ecology().regionAt(8, 8).population(COW) == 30
                && f.session.ecology().available(MobType.COW, 8, 8) == 18, "parking released or duplicated saved animals");
        for (int cx = 17; cx <= 23; cx++) for (int cz = -3; cz <= 3; cz++) f.world.getChunk(cx, cz);
        f.budget(328.5f, 30);
        int added = 0;
        for (int attempt = 0; attempt < 80 && added == 0; attempt++) added += f.session.ecology().materialize(1f);
        check(added > 0, "new region could not use slots freed by server travel");
        f.traveller.at.x = 8.5f; f.session.tickMobs(2.1f);
        check(ids(f.entities.mobs).equals(original), "return did not wake exact original herd");
        check(f.session.ecology().regionAt(8, 8).population(COW) == 30, "return increased population");
        for (Mob mob : f.entities.mobs) check(mob.savedTail != null && mob.savedTail.length == 3 && mob.savedTail[1] == 19, "opaque animal tail lost");
        f.session.tickMobs(.05f); f.session.adoptChunkItems(f.world.getChunkIfExists(0, 0));
        check(ids(f.entities.mobs).equals(original) && f.entities.mobs.size() == original.size(), "return adopted twice");
    }
    private static void saveReturn() throws Exception {
        try (Disk disk = new Disk()) {
            Scenario before = new Scenario(); Set<Long> original = before.herd(12); before.leave();
            Map<Long, SavedMob> expected = parked(before.world);
            ChunkSnapshot chunk = before.session.snapshotChunk(before.world.getChunkIfExists(0, 0));
            check(chunk != null && chunk.mobs.size() == 12, "parked herd missing in snapshot");
            disk.save.saveChunkAsync("world", chunk);
            disk.save.saveLevel("world", level(before)); disk.save.flushAndAwait();
            Scenario after = new Scenario(); after.traveller.at.x = 328.5f;
            SaveManager reopened = disk.reopen(); LevelData data = reopened.loadLevel("world");
            check(data != null, "travel level did not reopen");
            after.entities.restoreIds(data.extraSections.get(EntityStore.IDS_SECTION));
            after.session.ecology().restore(data.extraSections, bytes -> { throw new AssertionError("valid ecology quarantined"); });
            var loader = new ChunkLoader(after.world, null, reopened, "world"); loader.setMeshing(false);
            try {
                Chunk restored = loader.loadNow(0, 0); after.session.adoptChunkItems(restored);
                check(after.entities.mobs.isEmpty() && parked(after.world).keySet().equals(original), "far reopened records consumed active slots");
                var state = EcologyCodec.decode(after.sections().get(Ecology.SAVE_SECTION));
                check(state.reservations().keySet().equals(original) && after.session.ecology().available(MobType.COW, 8, 8) == 18,
                        "reopening released saved reservations");
                after.traveller.at.x = 8.5f; after.session.tickMobs(2.1f); after.session.adoptChunkItems(restored);
                check(ids(after.entities.mobs).equals(original) && after.entities.mobs.size() == 12, "reopened herd did not wake exactly once");
                for (Mob mob : after.entities.mobs) check(Arrays.equals(mob.savedTail, expected.get(mob.id).tail()), "saved tail changed on return");
                check(after.session.ecology().regionAt(8, 8).population(COW) == 30, "save and return changed population");
            } finally { loader.shutdown(); }
        }
    }
    private static void disabled() {
        Scenario f = new Scenario(); Set<Long> original = f.herd(3); f.leave();
        check(f.entities.mobs.isEmpty(), "herd did not park");
        f.session.ecology().setEnabled(false); f.session.tickMobs(0);
        check(ids(f.entities.mobs).equals(original), "legacy mode left old bodies asleep");
        f.session.tickMobs(0); check(f.entities.mobs.size() == 3, "legacy wake repeated");
        check(parked(f.world).isEmpty(), "awake herd also stayed in pending records");
    }
    private static void fallbacks() {
        Scenario readOnly = new Scenario(); Set<Long> ids = readOnly.herd(1);
        readOnly.world.getChunkIfExists(0, 0).markReadOnly(); readOnly.leave();
        check(ids(readOnly.entities.mobs).equals(ids), "read-only chunk swallowed animal");
        Scenario full = new Scenario(); Set<Long> fullIds = full.herd(1);
        List<SavedMob> records = new ArrayList<>();
        for (int i = 0; i < 256; i++) records.add(new SavedMob("FUTURE_SPECIES", 1000L + i, 8.5f, 65, 8.5f, 0, 1, new byte[] { 7 }));
        full.world.getChunkIfExists(0, 0).setPendingMobs(records); full.leave();
        check(ids(full.entities.mobs).equals(fullIds) && full.world.getChunkIfExists(0, 0).copyPendingMobs().size() == 256,
                "full pending section lost its animal or unknown records");
        Scenario duplicate = new Scenario(); Set<Long> duplicateIds = duplicate.herd(1); Mob cow = duplicate.entities.mobs.get(0);
        duplicate.world.getChunkIfExists(0, 0).setPendingMobs(List.of(new SavedMob(cow.type.name(), cow.id,
                cow.position.x, cow.position.y, cow.position.z, cow.yaw, cow.health, cow.savedTail)));
        duplicate.leave();
        check(ids(duplicate.entities.mobs).equals(duplicateIds)
                && duplicate.world.getChunkIfExists(0, 0).copyPendingMobs().size() == 1, "parking duplicated an already owned id");
        check(duplicate.session.snapshotChunk(duplicate.world.getChunkIfExists(0, 0)).mobs.size() == 1,
                "snapshot duplicated a body already owned by its pending record");
    }
    private static LevelData level(Scenario f) {
        return new LevelData("Ecology travel test", SEED, 8.5, 65, 8.5, f.clock.gameTimeFloat(), GameMode.SURVIVAL, 1,
                PlayerRecord.builder().pose(8.5, 65, 8.5, 0, 0, 0).build(), f.sections());
    }
    private static final class Disk implements AutoCloseable {
        final Path root = Files.createTempDirectory("mineclone-ecology-travel-");
        final SaveManager save = new SaveManager(root.toFile());
        final List<SaveManager> managers = new ArrayList<>();
        Disk() throws Exception { managers.add(save); }
        SaveManager reopen() { SaveManager next = new SaveManager(root.toFile()); managers.add(next); return next; }
        @Override public void close() throws Exception {
            var field = SaveManager.class.getDeclaredField("chunkWriter"); field.setAccessible(true);
            for (SaveManager manager : managers) { manager.flushAndAwait(); ((ExecutorService) field.get(manager)).shutdown(); }
            try (var files = Files.walk(root)) { for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file); }
        }
    }
}

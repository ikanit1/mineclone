package com.mineclone;

import com.mineclone.data.ResourceId;
import com.mineclone.game.LocalParticipant;
import com.mineclone.game.Player;
import com.mineclone.save.ChunkSnapshot;
import com.mineclone.save.LevelData;
import com.mineclone.save.PlayerRecord;
import com.mineclone.save.SaveManager;
import com.mineclone.save.SavedMob;
import com.mineclone.sim.EntityStore;
import com.mineclone.sim.Participants;
import com.mineclone.sim.WorldClock;
import com.mineclone.sim.WorldEvents;
import com.mineclone.sim.WorldSession;
import com.mineclone.sim.ecology.Ecology;
import com.mineclone.sim.ecology.EcologyCodec;
import com.mineclone.sim.ecology.EcologyRegion;
import com.mineclone.sim.ecology.SpeciesTable;
import com.mineclone.world.Chunk;
import com.mineclone.world.ChunkLoader;
import com.mineclone.world.GameMode;
import com.mineclone.world.GenProfile;
import com.mineclone.world.ItemStack;
import com.mineclone.world.World;
import com.mineclone.world.entity.Breeding;
import com.mineclone.world.entity.Mob;
import com.mineclone.world.entity.MobSpawner;
import com.mineclone.world.entity.MobType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import org.joml.Vector3f;

/** Real session breeding joins the same population, identity and chunk save path as wildlife. */
public final class EcologyBreedingTests {
    private static final long SEED = 20261002L;
    private static final ResourceId COW = ResourceId.of("mineclone:cow");

    public static void runAll(TestMain.Runner r) {
        r.run("a fed pair breeds one child and adds one ecological animal exactly once", EcologyBreedingTests::birth);
        r.run("breeding with ecology off leaves its population and reservations unchanged", EcologyBreedingTests::disabled);
        r.run("a newborn's full state and unknown tail survive a real chunk save and reopen", EcologyBreedingTests::reopen);
        r.run("materialized and bred animals share persistent identities beyond the integer range", EcologyBreedingTests::longIds);
    }

    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    private static final class Scenario {
        final World world = new World(SEED, GenProfile.FLAT);
        final WorldClock clock;
        final EntityStore entities = new EntityStore();
        final Participants participants = new Participants();
        final Vector3f focus = new Vector3f(8.5f, 65.001f, 12.5f);
        final WorldSession session;
        final Ecology ecology;

        Scenario() { this(new WorldClock(0.5)); }
        Scenario(WorldClock clock) {
            this.clock = clock;
            Player player = new Player();
            player.position.set(focus);
            player.setGameMode(GameMode.SURVIVAL);
            participants.put(new LocalParticipant(player, () -> 0));
            session = new WorldSession(world, clock, new MobSpawner(SEED), entities, participants,
                    new WorldSession.Host() {
                        @Override public Vector3f mobFocus() { return focus; }
                        @Override public boolean spawnMobs() { return false; }
                    }, WorldEvents.NONE);
            ecology = session.ecology();
            world.getChunk(0, 0);
        }

        void parents(boolean enabled) {
            EcologyRegion region = ecology.regionAt(8, 8);
            for (var definition : SpeciesTable.get().definitions()) {
                region.population(definition.id(), 0);
                region.capacity(definition.id(), 0);
                region.protectedCount(definition.id(), 0);
            }
            region.population(COW, 10);
            region.capacity(COW, 10);
            for (float x : new float[] {7.9f, 9.1f}) {
                Mob parent = cow(x);
                parent.onGround = true;
                ItemStack food = ItemStack.of("wheat", 1);
                check(Breeding.feed(parent, food, GameMode.SURVIVAL) && food.count == 0,
                        "a real parent could not be fed its breeding food");
                entities.mobs.add(parent);
            }
            ecology.setEnabled(enabled);
        }

        Mob cow(float x) { return new Mob(MobType.COW, x, 65.001f, 8.5f, new Random(SEED)); }

        void step() {
            clock.advance(0.5);
            session.tickMobs(0.5f);
        }

        Mob breed() {
            for (int i = 0; i < 6; i++) step();
            List<Mob> babies = entities.mobs.stream().filter(Mob::baby).toList();
            check(babies.size() == 1, "three seconds of actual session pairing did not produce exactly one baby: " + babies.size());
            Mob child = babies.get(0);
            check(child.id > 0 && child.age == -1200f && child.persistent(), "birth did not initialize the baby's identity, age and persistence");
            return child;
        }

        Map<String, byte[]> sections() {
            Map<String, byte[]> into = new LinkedHashMap<>();
            into.put(WorldClock.SAVE_SECTION, clock.encode());
            into.put(EntityStore.IDS_SECTION, entities.encodeIds());
            ecology.writeSections(into);
            return into;
        }

        EcologyCodec.Snapshot population() throws Exception {
            return EcologyCodec.decode(sections().get(Ecology.SAVE_SECTION));
        }
    }

    private static void birth() throws Exception {
        Scenario f = new Scenario();
        f.parents(true);
        check(f.population().reservations().size() == 2, "parents were not adopted into the animal budget");
        Mob child = f.breed();
        check(f.entities.mobs.size() == 3 && f.ecology.regionAt(8, 8).population(COW) == 11,
                "a real birth did not add precisely one body and one ecological animal");
        check(f.population().reservations().size() == 3 && f.population().reservations().containsKey(child.id),
                "the child was not reserved in the same population as its parents");
        f.ecology.born(child);
        f.ecology.adopted(child);
        for (int i = 0; i < 6; i++) f.step();
        check(f.entities.mobs.size() == 3 && f.ecology.regionAt(8, 8).population(COW) == 11
                && f.population().reservations().size() == 3, "a repeated callback or session tick counted the birth again");
        check(f.entities.mobs.stream().filter(m -> !m.baby()).allMatch(m -> m.love == 0 && m.loveCooldown > 0),
                "parents did not leave love mode and enter breeding cooldown");
    }

    private static void disabled() throws Exception {
        Scenario f = new Scenario();
        f.parents(false);
        f.breed();
        check(f.entities.mobs.size() == 3 && f.ecology.regionAt(8, 8).population(COW) == 10
                && f.population().reservations().isEmpty(), "disabled ecology changed legacy breeding or consumed its finite budget");
    }

    private static void reopen() throws Exception {
        try (Disk disk = new Disk()) {
            Scenario before = new Scenario();
            before.parents(true);
            Mob child = before.breed();
            child.age = -987.5f;
            child.love = 12.75f;
            child.loveCooldown = 33.5f;
            child.health = 4.5f;
            child.persistent = true;
            child.savedTail = new byte[] {0, 127, -1, 19, 3};
            ChunkSnapshot snapshot = before.session.snapshotChunk(before.world.getChunk(0, 0));
            check(snapshot != null && snapshot.mobs.size() == 3, "the parents and baby did not enter their real chunk snapshot");
            SavedMob saved = snapshot.mobs.stream().filter(m -> m.id() == child.id).findFirst().orElseThrow();
            sameBaby(saved, child);
            disk.save.saveChunkAsync("world", snapshot);
            disk.save.saveLevel("world", level(before.clock, before.sections()));
            disk.save.flushAndAwait();
            before.session.releaseEntitiesIn(Set.of(World.key(0, 0)));
            check(before.entities.mobs.isEmpty() && before.population().reservations().size() == 3,
                    "unloading a saved family released its animal reservations");

            SaveManager reopened = disk.reopen();
            LevelData loaded = reopened.loadLevel("world");
            check(loaded != null, "the family's actual saved level did not reopen");
            Scenario after = new Scenario(WorldClock.fromSaved(loaded.timeOfDay, loaded.extraSections.get(WorldClock.SAVE_SECTION)));
            after.entities.restoreIds(loaded.extraSections.get(EntityStore.IDS_SECTION));
            after.ecology.restore(loaded.extraSections, raw -> { throw new AssertionError("valid family ecology was quarantined"); });
            after.world.removeChunk(0, 0);
            ChunkLoader loader = new ChunkLoader(after.world, null, reopened, "world");
            loader.setMeshing(false);
            try {
                Chunk restored = loader.loadNow(0, 0);
                SavedMob pending = restored.copyPendingMobs().stream().filter(m -> m.id() == child.id).findFirst().orElseThrow();
                sameBaby(pending, child);
                after.session.adoptChunkItems(restored);
                Mob live = after.entities.mobs.stream().filter(m -> m.id == child.id).findFirst().orElseThrow();
                sameBaby(SavedMob.from(live), child);
                check(after.entities.mobs.size() == 3 && after.ecology.regionAt(8, 8).population(COW) == 11
                        && after.population().reservations().size() == 3, "reopening the family added duplicate bodies or animals");
                restored.setPendingMobs(snapshot.mobs);
                after.session.adoptChunkItems(restored);
                check(after.entities.mobs.size() == 3 && after.population().reservations().size() == 3,
                        "a repeated chunk adoption duplicated the saved baby");
                check(after.clock.nextEntityId() > child.id, "reopening reused the baby's persistent identity");
            } finally { loader.shutdown(); }
        }
    }

    private static void sameBaby(SavedMob saved, Mob child) {
        check(saved.id() == child.id && saved.age() == child.age && saved.persistent() == child.persistent()
                && saved.love() == child.love && saved.loveCooldown() == child.loveCooldown
                && saved.health() == child.health && Arrays.equals(saved.tail(), child.savedTail),
                "the chunk save path lost the baby's age, love, cooldown, health, persistence or opaque tail");
    }

    private static void longIds() throws Exception {
        Scenario f = new Scenario();
        long first = (long) Integer.MAX_VALUE + 90;
        f.clock.observeEntityId(first);
        f.parents(true);
        Mob materialized = f.cow(12.5f);
        check(f.ecology.claim(materialized), "an existing animal could not be materialized with a long identity");
        f.entities.mobs.add(materialized);
        long claimed = materialized.id;
        check(f.ecology.regionAt(8, 8).population(COW) == 10, "materialization added an ecological birth");
        Mob child = f.breed();
        HashSet<Long> ids = new HashSet<>();
        for (Mob animal : f.entities.mobs)
            check(animal.id > first && ids.add(animal.id), "materialization and breeding collided or truncated a long identity");
        check(f.entities.mobs.size() == 4 && child.id > claimed && f.clock.entityCounter() == child.id,
                "breeding and materialization used independent identity counters");
        WorldClock restored = WorldClock.fromSaved(f.clock.gameTime(), f.clock.encode());
        check(restored.entityCounter() == child.id && restored.nextEntityId() > child.id,
                "saved long identity allocation was not monotonic after reopening");
        check(f.ecology.regionAt(8, 8).population(COW) == 11 && f.population().reservations().size() == 4,
                "a long-ID materialization or birth was counted incorrectly");
    }

    private static LevelData level(WorldClock clock, Map<String, byte[]> sections) {
        return new LevelData("Living family test", SEED, 8.5, 65, 12.5, clock.gameTimeFloat(),
                GameMode.SURVIVAL, 1L, PlayerRecord.builder().pose(8.5, 65, 12.5, 0, 0, 0).build(), sections);
    }

    private static final class Disk implements AutoCloseable {
        final Path root = Files.createTempDirectory("mineclone-ecology-breeding-");
        final SaveManager save = new SaveManager(root.toFile());
        final List<SaveManager> managers = new ArrayList<>();
        Disk() throws Exception { managers.add(save); }
        SaveManager reopen() {
            SaveManager next = new SaveManager(root.toFile());
            managers.add(next);
            return next;
        }
        @Override public void close() throws Exception {
            var field = SaveManager.class.getDeclaredField("chunkWriter");
            field.setAccessible(true);
            for (SaveManager manager : managers) {
                manager.flushAndAwait();
                ((ExecutorService) field.get(manager)).shutdown();
            }
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}

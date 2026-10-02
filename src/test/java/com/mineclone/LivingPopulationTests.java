package com.mineclone;

import com.mineclone.data.ResourceId;
import com.mineclone.game.LocalParticipant;
import com.mineclone.game.Player;
import com.mineclone.save.ChunkSnapshot;
import com.mineclone.save.LevelData;
import com.mineclone.save.PlayerRecord;
import com.mineclone.save.SaveManager;
import com.mineclone.sim.EntityStore;
import com.mineclone.sim.Participants;
import com.mineclone.sim.WorldClock;
import com.mineclone.sim.WorldEvents;
import com.mineclone.sim.WorldSession;
import com.mineclone.sim.ecology.Ecology;
import com.mineclone.sim.ecology.EcologyCodec;
import com.mineclone.sim.ecology.EcologyModel;
import com.mineclone.sim.ecology.EcologyRegion;
import com.mineclone.sim.ecology.SpeciesTable;
import com.mineclone.world.Chunk;
import com.mineclone.world.ChunkLoader;
import com.mineclone.world.GameMode;
import com.mineclone.world.GenProfile;
import com.mineclone.world.World;
import com.mineclone.world.damage.DamageSource;
import com.mineclone.world.damage.DamageType;
import com.mineclone.world.entity.Mob;
import com.mineclone.world.entity.MobSpawner;
import com.mineclone.world.entity.MobType;
import java.nio.ByteBuffer;
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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.joml.Vector3f;

/** Living World host integration: one animal budget survives bodies, deaths and reopening. */
public final class LivingPopulationTests {
    private static final long SEED = 20261002L;
    private static final ResourceId COW = ResourceId.of("mineclone:cow");

    public static void runAll(TestMain.Runner r) {
        r.run("daily population births and a session death are accounted once", LivingPopulationTests::birthAndDeath);
        r.run("five hunted cows reduce the next day's finite population by exactly five without births", LivingPopulationTests::fiveCowDeaths);
        r.run("materialized bodies reserve the regional total without creating animals", LivingPopulationTests::claims);
        r.run("unloaded saved bodies retain reservations before and after real save reopening", LivingPopulationTests::reopen);
        r.run("damaged ecology bytes are preserved before the population rebuild", LivingPopulationTests::corruption);
        r.run("newer ecology remains disabled and byte-identical through a level save", LivingPopulationTests::newer);
        r.run("ecology rule toggles preserve unknown rule suffix bytes", LivingPopulationTests::rules);
        r.run("future living-world rules keep their raw ecology and rules through saving", LivingPopulationTests::futureRules);
        r.run("a saved table beyond the combined species limit stays opaque and disabled", LivingPopulationTests::speciesLimit);
        r.run("a hundred absent days replay exactly the chosen final sixty days", LivingPopulationTests::catchUpBound);
        r.run("a saturated absolute day finishes exactly sixty steps without counter overflow", LivingPopulationTests::saturatedDay);
        r.run("the session releases a dead body's reservation while ecology is off", LivingPopulationTests::disabledDeath);
    }

    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    private static final class Scenario {
        final World world = new World(SEED, GenProfile.FLAT);
        final WorldClock clock = new WorldClock(0.5);
        final EntityStore entities = new EntityStore();
        final Participants participants = new Participants();
        final Vector3f focus = new Vector3f(8.5f, 65.001f, 8.5f);
        final WorldSession session = new WorldSession(world, clock, new MobSpawner(SEED), entities, participants,
                new WorldSession.Host() {
                    @Override public Vector3f mobFocus() { return focus; }
                    @Override public boolean spawnMobs() { return false; }
                }, WorldEvents.NONE);
        final Ecology ecology = session.ecology();

        Scenario() { this(true); }
        Scenario(boolean nearBody) {
            if (nearBody) {
                Player player = new Player();
                player.position.set(focus);
                player.setGameMode(GameMode.SURVIVAL);
                participants.put(new LocalParticipant(player, () -> 0));
            }
        }

        void population(int count, int capacity) {
            EcologyRegion region = ecology.regionAt(8, 8);
            for (var definition : SpeciesTable.get().definitions()) {
                region.population(definition.id(), 0);
                region.capacity(definition.id(), 0);
                region.protectedCount(definition.id(), 0);
            }
            region.population(COW, count);
            region.capacity(COW, capacity);
        }

        Mob cow(float x) { return new Mob(MobType.COW, x, 65.001f, 8.5f, new Random(SEED)); }

        Map<String, byte[]> sections() {
            Map<String, byte[]> into = new LinkedHashMap<>();
            into.put(WorldClock.SAVE_SECTION, clock.encode());
            into.put(EntityStore.IDS_SECTION, entities.encodeIds());
            ecology.writeSections(into);
            return into;
        }
    }

    private static void birthAndDeath() throws Exception {
        Scenario f = new Scenario();
        f.world.getChunk(0, 0);
        EcologyRegion habitat = f.ecology.regionAt(8, 8).copy();
        for (var definition : SpeciesTable.get().definitions()) habitat.population(definition.id(), 0);
        int before = -1, expected = -1;
        // Choose a real climate-supported population whose deterministic first
        // day has a birth. The loaded grass must retain its actual capacity.
        for (int count = SpeciesTable.get().byId(COW).minimum(); count < habitat.capacity(COW); count++) {
            habitat.population(COW, count);
            int next = EcologyModel.step(List.of(habitat), SpeciesTable.get(), 1, SEED).get(0).population(COW);
            if (next > count) { before = count; expected = next; break; }
        }
        check(before > 0, "the test climate has no deterministic cow birth below its actual capacity");
        f.population(before, habitat.capacity(COW));
        for (var definition : SpeciesTable.get().definitions())
            f.ecology.regionAt(8, 8).capacity(definition.id(), habitat.capacity(definition.id()));
        f.ecology.setEnabled(true);
        f.ecology.advanceDays(1);
        int born = f.ecology.regionAt(8, 8).population(COW);
        check(born > before && born == expected, "one day's real-climate reproduction was missing or repeated: " + born);
        for (int i = 0; i < 5; i++) f.session.tickMobs(0.05f);
        check(f.ecology.regionAt(8, 8).population(COW) == born, "a fixed-time session tick repeated births");
        Mob cow = f.cow(8.5f);
        check(f.ecology.claim(cow), "could not materialize an existing cow");
        f.entities.mobs.add(cow);
        f.ecology.adopted(cow);
        f.ecology.adopted(cow);
        check(f.ecology.regionAt(8, 8).population(COW) == born, "adoption created another animal");
        cow.hurtBy(100f, 8f, 8f, 0f, -1);
        f.session.tickMobs(0.05f);
        check(cow.dead && cow.deathEffectsDone && f.ecology.regionAt(8, 8).population(COW) == born - 1,
                "the actual session death did not consume one animal");
        for (int i = 0; i < 5; i++) f.session.tickMobs(0.05f);
        f.ecology.died(cow);
        check(f.ecology.regionAt(8, 8).population(COW) == born - 1, "the corpse consumed the population again");
        check(EcologyCodec.decode(f.sections().get(Ecology.SAVE_SECTION)).reservations().isEmpty(), "dead body stayed reserved");
    }

    private static void fiveCowDeaths() throws Exception {
        Scenario f = new Scenario();
        f.world.getChunk(0, 0);
        int initial = 42;
        f.population(initial, initial);
        f.ecology.setEnabled(true);
        List<Mob> herd = new ArrayList<>();
        for (int i = 0; i < initial; i++) {
            Mob cow = new Mob(MobType.COW, 2.5f + (i % 7) * 1.5f, 65.001f,
                    2.5f + (i / 7) * 1.5f, new Random(SEED + i));
            check(f.ecology.claim(cow), "initial cohort could not reserve its cow " + i);
            herd.add(cow); f.entities.mobs.add(cow);
        }
        // The surviving represented cohort exceeds every configured cow habitat
        // capacity, so this scenario has neither abstract births nor emigration.
        int maxCapacity = SpeciesTable.get().byId(COW).biomeCapacity().values().stream()
                .mapToInt(Integer::intValue).max().orElseThrow();
        check(initial - 5 > maxCapacity, "test cohort no longer guarantees a day without births");
        for (int i = 0; i < 5; i++) {
            Mob cow = herd.get(i);
            check(cow.damage(DamageSource.byPlayer(DamageType.MELEE, 0, f.focus.x, f.focus.y, f.focus.z, 0), 100),
                    "player attack was refused for cow " + cow.id);
        }
        f.session.tickMobs(.05f);
        check(f.ecology.regionAt(8, 8).population(COW) == initial - 5, "actual session deaths did not subtract exactly five");
        for (int i = 0; i < 5; i++) check(herd.get(i).deathEffectsDone, "cow death was not processed by the session");
        f.ecology.advanceDays(1);
        f.session.tickMobs(.05f);
        check(f.ecology.regionAt(8, 8).population(COW) == initial - 5,
                "one day without births changed the five-death population delta");
        EcologyCodec.Snapshot saved = EcologyCodec.decode(f.sections().get(Ecology.SAVE_SECTION));
        check(saved.reservations().size() == initial - 5, "dead cows kept reservations or survivors were released");
        for (int i = 0; i < 5; i++) check(!saved.reservations().containsKey(herd.get(i).id), "dead cow is still available as a saved body");
        check(f.ecology.regionAt(8, 8).lossCount(EcologyRegion.LossCause.PLAYER) == 5, "five player kills lost their reason");
        for (int i = 0; i < 10; i++) f.session.tickMobs(.05f);
        check(f.ecology.regionAt(8, 8).population(COW) == initial - 5
                && f.ecology.regionAt(8, 8).lossCount(EcologyRegion.LossCause.PLAYER) == 5,
                "corpse cleanup counted one of the five deaths again");
    }

    private static void claims() throws Exception {
        Scenario f = new Scenario();
        f.population(2, 2);
        f.ecology.setEnabled(true);
        Mob first = f.cow(4.5f), second = f.cow(11.5f), third = f.cow(14.5f);
        check(f.ecology.claim(first), "first claim failed"); f.entities.mobs.add(first);
        check(f.ecology.available(MobType.COW, 8, 8) == 1, "a claim did not consume availability");
        check(f.ecology.claim(second), "second claim failed"); f.entities.mobs.add(second);
        check(!f.ecology.claim(third) && f.ecology.available(MobType.COW, 8, 8) == 0,
                "materialization created a third cow from a population of two");
        check(first.id > 0 && second.id > 0 && first.id != second.id, "reserved bodies have no unique persistent identities");
        f.ecology.adopted(first);
        check(f.ecology.regionAt(8, 8).population(COW) == 2
                && EcologyCodec.decode(f.sections().get(Ecology.SAVE_SECTION)).reservations().size() == 2,
                "the regional total was changed by representing it as bodies");
    }

    private static void reopen() throws Exception {
        try (Disk disk = new Disk()) {
            Scenario before = new Scenario();
            Chunk chunk = before.world.getChunk(0, 0);
            before.population(3, 3);
            before.ecology.setEnabled(true);
            Mob animal = before.cow(8.5f);
            check(before.ecology.claim(animal), "initial body claim"); before.entities.mobs.add(animal);
            ChunkSnapshot snapshot = before.session.snapshotChunk(chunk);
            check(snapshot != null && snapshot.mobs.size() == 1, "animal did not enter its actual chunk snapshot");
            disk.save.saveChunkAsync("world", snapshot);
            disk.save.saveLevel("world", level(before.clock, before.sections()));
            disk.save.flushAndAwait();
            before.session.releaseEntitiesIn(Set.of(World.key(0, 0)));
            before.world.removeChunk(0, 0);
            check(before.entities.mobs.isEmpty() && before.ecology.available(MobType.COW, 8, 8) == 2,
                    "unloading a saved body released its reserved population");

            Scenario after = new Scenario();
            SaveManager reopened = disk.reopen();
            LevelData loaded = reopened.loadLevel("world");
            check(loaded != null, "saved level did not reopen");
            after.clock.setGameTime(loaded.timeOfDay);
            after.entities.restoreIds(loaded.extraSections.get(EntityStore.IDS_SECTION));
            after.ecology.restore(loaded.extraSections, bytes -> { throw new AssertionError("valid ecology was quarantined"); });
            check(after.ecology.enabled() && after.ecology.available(MobType.COW, 8, 8) == 2,
                    "reopening released a chunk body before the chunk was adopted");
            ChunkLoader loader = new ChunkLoader(after.world, null, reopened, "world");
            loader.setMeshing(false);
            try {
                Chunk restored = loader.loadNow(0, 0);
                after.session.adoptChunkItems(restored);
                check(after.entities.mobs.size() == 1 && after.entities.mobs.get(0).id == animal.id
                        && after.ecology.regionAt(8, 8).population(COW) == 3
                        && after.ecology.available(MobType.COW, 8, 8) == 2, "restored body was counted twice");
                restored.setPendingMobs(snapshot.mobs);
                after.session.adoptChunkItems(restored);
                check(after.entities.mobs.size() == 1, "repeat adoption added a duplicate body");
                Mob a = after.cow(4.5f), b = after.cow(11.5f);
                check(after.ecology.claim(a), "second total cow missing"); after.entities.mobs.add(a);
                check(after.ecology.claim(b), "third total cow missing"); after.entities.mobs.add(b);
                check(!after.ecology.claim(after.cow(14.5f)) && after.entities.mobs.size() == 3,
                        "saved body and regenerated wildlife exceeded the finite budget");
            } finally { loader.shutdown(); }
        }
    }

    private static void corruption() throws Exception {
        Scenario f = new Scenario();
        byte[] bad = { 0, 0, 0, 1, 0, 0, 0, 1, 9, 9 };
        List<byte[]> evidence = new ArrayList<>();
        Map<String, byte[]> original = new LinkedHashMap<>(Map.of(Ecology.SAVE_SECTION, bad, "rules", livingRules(1, false)));
        f.ecology.restore(original, evidence::add);
        check(evidence.size() == 1 && Arrays.equals(evidence.get(0), bad), "corrupt section was discarded before preservation");
        evidence.get(0)[0] = 77;
        check(bad[0] == 0, "preservation borrowed the live section bytes");
        check(f.ecology.supported() && f.ecology.recoveryMessage().contains("preserved"), "successful preservation blocked a rebuild");
        f.ecology.writeSections(original);
        check(!original.containsKey(Ecology.SAVE_SECTION) && !f.ecology.enabled(),
                "successfully quarantined ecology was copied from the old section map into the next OFF save");
        f.ecology.setEnabled(true);
        f.ecology.regionAt(8, 8);
        check(!EcologyCodec.decode(f.sections().get(Ecology.SAVE_SECTION)).regions().isEmpty(), "no rebuilt population could be saved");

        Scenario failed = new Scenario();
        failed.ecology.restore(Map.of(Ecology.SAVE_SECTION, bad), bytes -> { throw new IllegalStateException("disk unavailable"); });
        check(!failed.ecology.supported() && !failed.ecology.enabled()
                && Arrays.equals(failed.sections().get(Ecology.SAVE_SECTION), bad), "failed preservation overwrote corrupt evidence");
    }

    private static void newer() throws Exception {
        try (Disk disk = new Disk()) {
            Scenario f = new Scenario();
            byte[] future = ByteBuffer.allocate(13).putInt(2).putInt(2).put(new byte[] { 4, 3, 2, 1, 0 }).array();
            Map<String, byte[]> sections = new LinkedHashMap<>(Map.of(Ecology.SAVE_SECTION, future));
            f.ecology.restore(sections, bytes -> { throw new AssertionError("newer data were treated as corrupt"); });
            check(!f.ecology.supported() && !f.ecology.enabled(), "future ecology was simulated");
            f.ecology.tick();
            check(f.ecology.materialize(1f) == 0, "future ecology produced bodies");
            f.ecology.writeSections(sections);
            disk.save.saveLevel("world", level(f.clock, sections));
            disk.save.flushAndAwait();
            LevelData loaded = disk.reopen().loadLevel("world");
            check(loaded != null && Arrays.equals(loaded.extraSections.get(Ecology.SAVE_SECTION), future),
                    "an actual save changed the future ecology payload");
        }
    }

    private static void rules() throws Exception {
        try (Disk disk = new Disk()) {
            Scenario f = new Scenario();
            byte[] unknownRules = ByteBuffer.allocate(9).putInt(GameMode.SURVIVAL.ordinal())
                    .put(new byte[] { 5, 4, 3, 2, 1 }).array();
            f.ecology.restore(Map.of("rules", unknownRules), bytes -> { throw new AssertionError(); });
            f.ecology.setEnabled(true);
            Map<String, byte[]> sections = f.sections();
            check(Arrays.equals(Arrays.copyOf(sections.get("rules"), unknownRules.length), unknownRules),
                    "enabling ecology erased unknown rules");
            disk.save.saveLevel("world", level(f.clock, sections));
            disk.save.flushAndAwait();
            LevelData loaded = disk.reopen().loadLevel("world");
            Scenario again = new Scenario();
            again.ecology.restore(loaded.extraSections, bytes -> { throw new AssertionError(); });
            check(again.ecology.enabled(), "living-world switch was not restored");
            again.ecology.setEnabled(false);
            byte[] toggled = again.sections().get("rules");
            check(toggled.length == unknownRules.length + 6 && toggled[toggled.length - 1] == 0
                    && Arrays.equals(Arrays.copyOf(toggled, unknownRules.length), unknownRules),
                    "disabling ecology appended a duplicate footer or changed unknown rules");
        }
    }

    private static void catchUpBound() throws Exception {
        Scenario f = new Scenario(false);
        f.population(20, 100);
        EcologyRegion initial = f.ecology.regionAt(8, 8).copy();
        initial.lastDay(40);
        List<EcologyRegion> expected = List.of(initial);
        for (long day = 41; day <= 100; day++) expected = EcologyModel.step(expected, SpeciesTable.get(), day, SEED);
        f.clock.setGameTime(100 * Math.PI * 2 + 0.5);
        f.ecology.setEnabled(true);
        EcologyRegion actual = f.ecology.regionAt(8, 8), finalExpected = expected.get(0);
        check(actual.lastDay() == 100, "catch-up did not finish at the current absolute day");
        for (var definition : SpeciesTable.get().definitions()) {
            ResourceId id = definition.id();
            check(actual.population(id) == finalExpected.population(id)
                    && Arrays.equals(actual.trend(id), finalExpected.trend(id)), "catch-up used the wrong replay window for " + id);
        }
        byte[] checkpoint = f.sections().get(Ecology.SAVE_SECTION);
        f.ecology.tick(); f.ecology.tick();
        check(Arrays.equals(checkpoint, f.sections().get(Ecology.SAVE_SECTION)), "same-day ticks repeated the bounded catch-up");
    }

    private static byte[] livingRules(int version, boolean enabled) {
        return ByteBuffer.allocate(10).putInt(GameMode.SURVIVAL.ordinal()).putInt(0x4C575231)
                .put((byte) version).put((byte) (enabled ? 1 : 0)).array();
    }

    private static void futureRules() throws Exception {
        try (Disk disk = new Disk()) {
            Scenario f = new Scenario(false);
            byte[] rules = livingRules(2, true), rawEcology = { 1, 4, 8, 16, 32 };
            Map<String, byte[]> original = new LinkedHashMap<>(Map.of("rules", rules, Ecology.SAVE_SECTION, rawEcology));
            f.ecology.restore(original, bytes -> { throw new AssertionError("future rules caused current-reader ecology recovery"); });
            check(!f.ecology.supported() && !f.ecology.enabled(), "future rules enabled current simulation");
            f.ecology.tick();
            check(f.ecology.materialize(1f) == 0, "future rules materialized an animal");
            f.ecology.writeSections(original);
            disk.save.saveLevel("world", level(f.clock, original));
            disk.save.flushAndAwait();
            LevelData saved = disk.reopen().loadLevel("world");
            check(saved != null && Arrays.equals(saved.extraSections.get("rules"), rules)
                    && Arrays.equals(saved.extraSections.get(Ecology.SAVE_SECTION), rawEcology),
                    "a future rules footer or its raw ecology was changed by actual saving");
        }
    }

    private static void speciesLimit() throws Exception {
        try (Disk disk = new Disk()) {
            EcologyRegion region = new EcologyRegion(0, 0, 0);
            for (int i = 0; i < SpeciesTable.MAX_SPECIES; i++) {
                ResourceId id = ResourceId.of("future:animal_" + i);
                region.population(id, 3);
                region.capacity(id, 5);
            }
            byte[] raw = EcologyCodec.encode(new EcologyCodec.Snapshot(List.of(region), Map.of()));
            Scenario f = new Scenario(false);
            Map<String, byte[]> sections = new LinkedHashMap<>(Map.of(Ecology.SAVE_SECTION, raw, "rules", livingRules(1, true)));
            f.ecology.restore(sections, bytes -> { throw new AssertionError("valid unknown species were treated as corrupt"); });
            check(!f.ecology.supported() && !f.ecology.enabled(), "the combined known/unknown species table overflowed silently");
            f.ecology.tick(); f.ecology.writeSections(sections);
            disk.save.saveLevel("world", level(f.clock, sections));
            disk.save.flushAndAwait();
            LevelData loaded = disk.reopen().loadLevel("world");
            check(loaded != null && Arrays.equals(loaded.extraSections.get(Ecology.SAVE_SECTION), raw),
                    "species beyond this reader's combined limit were truncated or reencoded");
        }
    }

    private static void saturatedDay() throws Exception {
        Scenario f = new Scenario(false);
        f.population(20, 100);
        EcologyRegion first = f.ecology.regionAt(8, 8).copy();
        first.lastDay(Long.MAX_VALUE - 60);
        List<EcologyRegion> expected = List.of(first);
        for (int offset = 1; offset <= 60; offset++)
            expected = EcologyModel.step(expected, SpeciesTable.get(), Long.MAX_VALUE - 60 + offset, SEED);
        f.clock.setGameTime(1e30);
        check(Ecology.day(f.clock.gameTime()) == Long.MAX_VALUE, "the fixture did not saturate the absolute day");
        ExecutorService worker = Executors.newSingleThreadExecutor(job -> {
            Thread thread = new Thread(job, "ecology-saturated-day-test");
            thread.setDaemon(true);
            return thread;
        });
        try {
            var result = worker.submit(() -> {
                f.ecology.setEnabled(true);
                f.ecology.tick();
                return f.ecology.regionAt(8, 8).copy();
            });
            EcologyRegion actual;
            try { actual = result.get(5, TimeUnit.SECONDS); }
            catch (TimeoutException timeout) {
                result.cancel(true);
                throw new AssertionError("catch-up overflowed its long day counter and did not finish", timeout);
            }
            check(actual.lastDay() == Long.MAX_VALUE, "saturated catch-up stopped at the wrong day");
            for (var definition : SpeciesTable.get().definitions()) {
                ResourceId id = definition.id();
                check(actual.population(id) == expected.get(0).population(id)
                        && Arrays.equals(actual.trend(id), expected.get(0).trend(id)),
                        "saturated catch-up did not execute exactly sixty final steps for " + id);
            }
        } finally { worker.shutdownNow(); }
    }

    private static void disabledDeath() throws Exception {
        Scenario f = new Scenario();
        f.world.getChunk(0, 0);
        f.population(3, 3);
        f.ecology.setEnabled(true);
        Mob cow = f.cow(8.5f);
        check(f.ecology.claim(cow), "could not reserve body before disabling"); f.entities.mobs.add(cow);
        f.ecology.setEnabled(false);
        cow.hurtBy(100f, 8f, 8f, 0f, -1);
        f.session.tickMobs(0.05f);
        check(f.ecology.regionAt(8, 8).population(COW) == 2
                && EcologyCodec.decode(f.sections().get(Ecology.SAVE_SECTION)).reservations().isEmpty(),
                "a death while off leaked its reservation");
        f.ecology.setEnabled(true);
        check(f.ecology.available(MobType.COW, 8, 8) == 2, "reenabling recreated the dead body or kept it reserved");
    }

    private static LevelData level(WorldClock clock, Map<String, byte[]> sections) {
        return new LevelData("Living population test", SEED, 8.5, 65, 8.5, clock.gameTimeFloat(),
                GameMode.SURVIVAL, 1L, PlayerRecord.builder().pose(8.5, 65, 8.5, 0, 0, 0).build(), sections);
    }

    private static final class Disk implements AutoCloseable {
        final Path root = Files.createTempDirectory("mineclone-living-population-");
        final SaveManager save = new SaveManager(root.toFile());
        final List<SaveManager> managers = new ArrayList<>();
        Disk() throws Exception { managers.add(save); }
        SaveManager reopen() {
            SaveManager next = new SaveManager(root.toFile());
            managers.add(next);
            return next;
        }
        @Override public void close() throws Exception {
            var field = SaveManager.class.getDeclaredField("chunkWriter"); field.setAccessible(true);
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

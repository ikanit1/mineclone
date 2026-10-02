package com.mineclone;

import com.mineclone.data.ResourceId;
import com.mineclone.game.LocalParticipant;
import com.mineclone.game.Player;
import com.mineclone.save.SavedMob;
import com.mineclone.sim.*;
import com.mineclone.sim.ecology.*;
import com.mineclone.world.*;
import com.mineclone.world.entity.*;
import java.util.*;

/** Existing saved animals must consume finite population even before a distant body becomes active. */
public final class EcologyPendingMigrationTests {
    private static final ResourceId COW = ResourceId.of("cow");

    public static void runAll(TestMain.Runner runner) {
        runner.run("enabling ecology reserves distant pending animals before materialization and across toggles",
                EcologyPendingMigrationTests::enablePending);
        runner.run("a far chunk adopted after enabling ecology immediately reserves its saved animals",
                EcologyPendingMigrationTests::adoptFar);
        runner.run("saving before the death tick releases the omitted corpse reservation exactly once",
                EcologyPendingMigrationTests::saveBeforeDeathTick);
    }

    private static void check(boolean ok, String reason) {
        if (!ok) throw new AssertionError(reason);
    }

    private static final class Scenario {
        final World world = new World(20261002L, GenProfile.FLAT);
        final WorldClock clock = new WorldClock(.5);
        final EntityStore store = new EntityStore();
        final Participants participants = new Participants();
        final Player player = new Player();
        final WorldSession session;
        final List<SavedMob> records = new ArrayList<>();
        final Set<Long> animalIds = new HashSet<>();
        Scenario() {
            player.position.set(328.5f, 65.001f, 8.5f);
            participants.put(new LocalParticipant(player, () -> 0));
            session = new WorldSession(world, clock, new MobSpawner(1), store, participants,
                    new WorldSession.Host() {
                        @Override public org.joml.Vector3f mobFocus() { return player.position; }
                        @Override public boolean spawnMobs() { return false; }
                    }, WorldEvents.NONE);
            for (int i = 0; i < 12; i++) {
                long id = 9_000_000_000L + i;
                animalIds.add(id);
                records.add(new SavedMob("COW", id, 2.5f + i, 65.001f, 8.5f, 0, 3,
                        -850, true, 290, 22, new byte[] { (byte) i, 17, -1 }));
            }
            records.add(new SavedMob("FUTURE_ANIMAL", 9_000_000_100L, 7, 65, 7, 0, 30,
                    new byte[] { 2, 4, 8 }));
            var region = session.ecology().regionAt(8, 8);
            for (var species : SpeciesTable.get().definitions()) {
                region.population(species.id(), 0); region.capacity(species.id(), 0);
            }
            region.population(COW, 10); region.capacity(COW, 10);
        }
        void verify() throws Exception {
            var region = session.ecology().regionAt(8, 8);
            check(region.population(COW) == 12 && region.protectedCount(COW) == 12,
                    "pending cohort was absent from population/protection");
            check(session.ecology().available(MobType.COW, 8, 8) == 0, "saved animals became available again");
            var sections = new LinkedHashMap<String, byte[]>();
            session.ecology().writeSections(sections);
            var saved = EcologyCodec.decode(sections.get(Ecology.SAVE_SECTION));
            check(saved.reservations().keySet().equals(animalIds), "saved cohort does not own exactly its twelve IDs");
        }
    }

    private static void enablePending() throws Exception {
        Scenario f = new Scenario();
        Chunk chunk = f.world.getChunk(0, 0);
        chunk.restoredFromSave = true;
        chunk.setPendingMobs(f.records);
        f.session.ecology().setEnabled(true);
        f.verify();
        check(f.store.mobs.isEmpty(), "enabling activated a far herd");
        f.session.adoptChunkItems(chunk);
        f.verify();
        check(chunk.copyPendingMobs().size() == 13 && f.store.mobs.isEmpty(), "far saved bodies were lost or awakened");
        var extra = new Mob(MobType.COW, 8, 65.001f, 8, new Random(1));
        check(!f.session.ecology().claim(extra), "materialization spent an already saved animal");

        f.session.ecology().setEnabled(false);
        f.session.tickMobs(.05f);
        check(f.store.mobs.size() == 12 && chunk.copyPendingMobs().size() == 1, "disabling did not awaken the saved herd");
        f.session.ecology().setEnabled(true);
        f.verify();
        f.player.position.x = 8.5f;
        f.session.tickMobs(.05f);
        f.session.adoptChunkItems(chunk);
        check(f.store.mobs.stream().map(m -> m.id).collect(java.util.stream.Collectors.toSet()).equals(f.animalIds),
                "return duplicated or replaced the old animals");
        f.verify();
        check(Arrays.equals(chunk.copyPendingMobs().get(0).tail(), new byte[] { 2, 4, 8 }), "unknown tail changed");
        check(f.clock.nextEntityId() > 9_000_000_100L, "future saved identity was not reserved by the allocator");
    }

    private static void adoptFar() throws Exception {
        Scenario f = new Scenario();
        f.session.ecology().setEnabled(true);
        Chunk chunk = f.world.getChunk(0, 0);
        chunk.restoredFromSave = true;
        chunk.setPendingMobs(f.records);
        f.session.adoptChunkItems(chunk);
        check(f.store.mobs.isEmpty() && chunk.copyPendingMobs().size() == 13, "far saved cohort did not remain pending");
        f.verify();
        f.session.adoptChunkItems(chunk);
        f.verify();
    }

    private static void saveBeforeDeathTick() throws Exception {
        Scenario f = new Scenario();
        f.player.position.x = 8.5f;
        Chunk chunk = f.world.getChunk(0, 0);
        chunk.modified = true;
        f.session.ecology().setEnabled(true);
        var cow = new Mob(MobType.COW, 8.5f, 65.001f, 8.5f, new Random(3));
        check(f.session.ecology().claim(cow), "cow could not reserve its original population");
        f.store.mobs.add(cow);
        check(cow.damage(com.mineclone.world.damage.DamageSource.byPlayer(
                com.mineclone.world.damage.DamageType.MELEE, 0, 8, 65, 8, 0), 100), "kill was refused");
        check(cow.dead && !cow.deathEffectsDone, "scenario already processed the death tick");
        var sections = new LinkedHashMap<String, byte[]>();
        f.session.ecology().writeSections(sections);
        var saved = EcologyCodec.decode(sections.get(Ecology.SAVE_SECTION));
        var region = saved.regions().stream().filter(r -> r.x() == 0 && r.z() == 0).findFirst().orElseThrow();
        check(saved.reservations().isEmpty() && region.population(COW) == 9
                && region.lossCount(EcologyRegion.LossCause.PLAYER) == 1,
                "save left a ghost reservation or omitted the death accounting");
        check(f.session.snapshotChunk(chunk).mobs.isEmpty(), "dead corpse entered the chunk save");
        check(!cow.deathEffectsDone, "save prematurely consumed death presentation");
        f.session.tickMobs(.05f);
        f.session.ecology().writeSections(sections);
        region = f.session.ecology().regionAt(8, 8);
        check(cow.deathEffectsDone && region.population(COW) == 9
                && region.lossCount(EcologyRegion.LossCause.PLAYER) == 1,
                "normal death tick counted the already saved death again");
    }
}

package com.mineclone;

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
import com.mineclone.sim.ecology.EcologyRegion;
import com.mineclone.sim.ecology.Lair;
import com.mineclone.sim.ecology.LairSpots;
import com.mineclone.sim.ecology.LairTable;
import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.ChunkLoader;
import com.mineclone.world.GameMode;
import com.mineclone.world.World;
import com.mineclone.world.behavior.Behaviors;
import com.mineclone.world.behavior.SpiderNestBehavior;
import com.mineclone.world.damage.DamageSource;
import com.mineclone.world.damage.DamageType;
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
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/** Actual host-session lair lifecycle, with two fully closed loaded caves in one region. */
public final class LairRuntimeTests {
    private static final long SEED = 20261002L, REGION = EcologyRegion.key(0, 0);
    private static final int HEART_X = 32, HEART_Y = 41, HEART_Z = 32, OTHER_X = 96;

    public static void runAll(TestMain.Runner r) {
        r.run("placed nest is adopted and finite session spiders physically return at dawn", LairRuntimeTests::nightAndDawn);
        r.run("lair emission shares the participant and global hostile caps with undead", LairRuntimeTests::hostileCaps);
        r.run("session deaths and deaths immediately before save consume a lair member once", LairRuntimeTests::deaths);
        r.run("broken nest survivors remain finite and a different closed cave reseeds after the delay", LairRuntimeTests::destructionAndReseed);
        r.run("ordinary daily ticks and ecology advance replay the same lair founding and growth", LairRuntimeTests::dailyParity);
        r.run("real chunk and level reopening preserves lair body IDs and parked residency", LairRuntimeTests::reopen);
        r.run("actual ecology preserves unknown lairs and known suffixes through level reopening", LairRuntimeTests::unknownRecords);
        r.run("actual ecology preserves newer lairs and quarantines corrupt known records before rebuild", LairRuntimeTests::futureAndCorrupt);
    }

    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    private static final class Scenario {
        final World world = new World(SEED);
        final WorldClock clock = new WorldClock(3 * Math.PI / 2);
        final EntityStore entities = new EntityStore();
        final Participants participants = new Participants();
        final Player player = new Player();
        final Vector3f focus = new Vector3f(64.5f, 61, 64.5f);
        final WorldSession session;
        final Ecology ecology;
        final Map<String, byte[]> restoredSections = new LinkedHashMap<>();
        boolean spawn = true;

        Scenario(boolean caves, boolean heart) throws Exception {
            player.position.set(focus); player.setGameMode(GameMode.CREATIVE);
            participants.put(new LocalParticipant(player, () -> 0));
            // Disable only unrelated random natural rolls. The actual session's
            // lair emission, caps, senses, movement, collision and death path run.
            MobSpawner spawner = new MobSpawner(SEED) {
                @Override public void trySpawnHostiles(World w, List<Mob> mobs,
                        List<? extends Vector3fc> centres, float daylight) {}
            };
            session = new WorldSession(world, clock, spawner, entities, participants,
                    new WorldSession.Host() {
                        public Vector3f mobFocus() { return focus; }
                        public boolean spawnMobs() { return spawn; }
                    }, WorldEvents.NONE);
            ecology = session.ecology();
            if (caves) {
                for (int cx : new int[] { 1, 2, 5, 6 }) for (int cz = 1; cz <= 2; cz++)
                    world.publish(new Chunk(cx, cz));
                cave(HEART_X); cave(OTHER_X);
                if (heart) placeHeart();
                for (Chunk c : world.getLoadedChunks()) c.computeSkyLight();
                check(world.getSkyLight(HEART_X - 3, HEART_Y, HEART_Z) == 0
                        && world.getSkyLight(OTHER_X - 3, HEART_Y, HEART_Z) == 0,
                        "closed cave fixture has sky leakage");
            }
        }
        void cave(int centre) {
            for (int x = centre - 12; x <= centre + 11; x++) for (int z = 20; z <= 43; z++)
                for (int y = 40; y <= 44; y++)
                    if (y == 40 || y == 44 || x == centre - 12 || x == centre + 11 || z == 20 || z == 43)
                        world.setBlock(x, y, z, BlockType.STONE);
        }
        void placeHeart() {
            world.setBlock(HEART_X, HEART_Y, HEART_Z, BlockType.SPIDER_NEST);
            Behaviors.of(BlockType.SPIDER_NEST).onPlaced(world, HEART_X, HEART_Y, HEART_Z, (byte) 0);
        }
        void enable() { ecology.setEnabled(true); }
        void step(int frames) {
            for (int i = 0; i < frames; i++) { clock.advance(.05); session.tickMobs(.05f); }
        }
        List<Mob> spiders() { return entities.mobs.stream().filter(m -> m.type == MobType.SPIDER && !m.dead).toList(); }
        Lair old() { return ecology.lairs().lairs(REGION).stream().filter(l -> l.x() == HEART_X && l.z() == HEART_Z).findFirst().orElseThrow(); }
        int hostiles() { return (int) entities.mobs.stream().filter(m -> m.type.hostile && !m.dead).count(); }
        Set<Long> liveIds() { Set<Long> ids = new TreeSet<>(); for (Mob m : spiders()) ids.add(m.id); return ids; }
        void kill(Mob m) {
            check(m.damage(DamageSource.byPlayer(DamageType.MELEE, 0, focus.x, focus.y, focus.z, 0), 100), "actual player damage refused");
        }
        Map<String, byte[]> sections() {
            Map<String, byte[]> into = new LinkedHashMap<>(restoredSections);
            into.put(WorldClock.SAVE_SECTION, clock.encode()); into.put(EntityStore.IDS_SECTION, entities.encodeIds());
            ecology.writeSections(into); return into;
        }
        void restore(Map<String, byte[]> sections) {
            restoredSections.clear(); sections.forEach((key, bytes) -> restoredSections.put(key, bytes.clone()));
            entities.restoreIds(sections.get(EntityStore.IDS_SECTION));
            ecology.restore(sections, bytes -> { throw new AssertionError("valid lair data quarantined"); });
        }
        void breakHeart() { world.setBlock(HEART_X, HEART_Y, HEART_Z, BlockType.AIR); }
    }

    private static void nightAndDawn() throws Exception {
        Scenario f = new Scenario(true, true); f.enable(); f.step(6);
        Lair lair = f.old(); Set<Long> first = f.liveIds();
        check(lair.population() == 6 && lair.bodyIds().size() == 4 && first.equals(lair.bodyIds()),
                "placed heart was not adopted into six finite members / four bodies");
        f.step(120);
        check(f.spiders().size() == 4 && lair.population() == 6 && first.equals(lair.bodyIds()), "night emission exceeded or recycled its four body slots");
        f.spawn = false;
        f.spiders().get(0).position.set(HEART_X - 5.5f, HEART_Y + .001f, HEART_Z + .5f);
        f.clock.setHours(12);
        boolean walkedHome = false;
        for (int i = 0; i < 600 && !f.spiders().isEmpty(); i++) {
            f.step(1);
            walkedHome |= f.spiders().stream().anyMatch(m -> m.lairDestination != null && m.walkAmount > .01f);
        }
        check(walkedHome && f.spiders().isEmpty() && lair.bodyIds().isEmpty() && lair.population() == 6,
                "dawn did not physically walk distant bodies home without population loss");
        f.clock.setHours(0); f.spawn = true; f.step(6);
        check(f.spiders().size() == 4 && lair.population() == 6 && f.liveIds().stream().noneMatch(first::contains),
                "next night did not rematerialize exactly four new identities from the same population");
    }

    private static void hostileCaps() throws Exception {
        Scenario f = new Scenario(true, true);
        for (int i = 0; i < 5; i++) {
            Mob m = new Mob(MobType.ZOMBIE, OTHER_X - 5.5f + i * 2, HEART_Y + .001f, 38.5f, new Random(SEED + i));
            m.onGround = true; f.entities.mobs.add(m);
        }
        f.enable(); f.step(10);
        check(f.hostiles() == MobSpawner.HOSTILE_CAP && f.spiders().size() == 3 && f.old().population() == 6,
                "five undead plus a lair exceeded the shared cap of eight");
        Player second = new Player(); second.setGameMode(GameMode.CREATIVE); second.position.set(70, 61, 64);
        f.participants.put(new LocalParticipant(second, () -> 1));
        f.step(10);
        check(f.hostiles() == 8 && f.spiders().size() == 3,
                "a second nearby participant doubled a participant's local cap");
        f.kill(f.entities.mobs.stream().filter(m -> m.type == MobType.ZOMBIE).findFirst().orElseThrow());
        f.step(4);
        check(f.hostiles() == 8 && f.spiders().size() == 4 && f.old().bodyIds().size() == 4,
                "freeing one undead slot did not allow the remaining finite spider body");
    }

    private static void deaths() throws Exception {
        Scenario f = new Scenario(true, true); f.enable(); f.step(6); f.spawn = false;
        Mob first = f.spiders().get(0); f.kill(first); f.step(1);
        check(first.deathEffectsDone && f.old().population() == 5 && !f.old().bodyIds().contains(first.id), "session death failed finite accounting");
        f.ecology.died(first); f.step(4);
        check(f.old().population() == 5, "repeat corpse handling consumed another member");
        Mob beforeSave = f.spiders().get(0); f.kill(beforeSave);
        Lair saved = known(f.sections(), HEART_X);
        check(saved.population() == 4 && saved.bodyIds().size() == 2 && !saved.bodyIds().contains(beforeSave.id),
                "death immediately before save retained a dead reservation");
        f.step(2); check(f.old().population() == 4, "post-save death processing consumed the member again");
        Mob dawn = f.spiders().get(0);
        // Pose a represented body physically at home, then kill it before the dawn
        // tick: the host must account death before treating proximity as a return.
        dawn.position.set(HEART_X - 1.5f, HEART_Y + .001f, HEART_Z + .5f);
        f.clock.setHours(12); f.kill(dawn); f.step(1);
        check(dawn.deathEffectsDone && f.old().population() == 3 && !f.old().bodyIds().contains(dawn.id),
                "a dead spider at dawn was folded as a live return");
        Scenario peaceful = new Scenario(true, true); peaceful.enable(); peaceful.step(6);
        Mob killedBeforePeaceful = peaceful.spiders().get(0); peaceful.kill(killedBeforePeaceful);
        peaceful.world.rules = peaceful.world.rules.difficulty(com.mineclone.world.Difficulty.PEACEFUL);
        peaceful.step(1);
        check(peaceful.old().population() == 5 && peaceful.old().bodyIds().isEmpty() && peaceful.entities.mobs.isEmpty(),
                "Peaceful retirement folded a killed spider without its finite death: population="
                        + peaceful.old().population() + " bodies=" + peaceful.old().bodyIds());
        peaceful.ecology.died(killedBeforePeaceful); peaceful.step(1);
        check(known(peaceful.sections(), HEART_X).population() == 5,
                "post-Peaceful save or repeated death consumed the killed member twice");
    }

    private static Scenario cleared() throws Exception {
        Scenario f = new Scenario(true, true); f.enable(); f.step(6); f.spawn = false;
        f.breakHeart(); f.step(12);
        check(!f.old().alive() && f.old().population() == 6 && f.old().bodyIds().size() == 4 && f.spiders().size() == 4,
                "destroying a heart removed or duplicated surviving scouts");
        for (Mob m : f.spiders()) f.kill(m);
        f.step(4);
        check(f.old().population() == 2 && f.old().bodyIds().isEmpty(), "destroyed lair scouts did not die once");
        return f;
    }

    private static void destructionAndReseed() throws Exception {
        Scenario immediate = new Scenario(true, true); immediate.enable(); immediate.step(6);
        immediate.ecology.advanceDays(2);
        long destructionDay = Ecology.day(immediate.clock.gameTime());
        immediate.breakHeart();
        // Saving can happen before the next observation or mob tick. The
        // destroyed chunk and finite history must describe the same instant.
        Lair savedHeart = known(immediate.sections(), HEART_X);
        check(!savedHeart.alive() && savedHeart.destroyedDay() == destructionDay,
                "heart destroyed immediately before save remained alive or lost its actual destruction day: alive="
                        + savedHeart.alive() + " destroyedDay=" + savedHeart.destroyedDay() + " currentDay=" + destructionDay);
        Scenario advanced = new Scenario(true, true); advanced.enable(); advanced.step(6);
        advanced.ecology.advanceDays(2);
        long beforeAdvance = Ecology.day(advanced.clock.gameTime());
        advanced.breakHeart(); advanced.ecology.advanceDays(1);
        check(!advanced.old().alive() && advanced.old().destroyedDay() == beforeAdvance
                        && Ecology.day(advanced.clock.gameTime()) == beforeAdvance + 1,
                "advancing immediately after heart destruction shifted its day onto replay: destroyedDay="
                        + advanced.old().destroyedDay() + " brokenDay=" + beforeAdvance);
        Scenario webTiming = cleared();
        check(webTiming.world.scheduledTickTime() == 0, "web replay fixture serviced its scheduler prematurely");
        webTiming.ecology.advanceDays(8);
        Lair born = webTiming.ecology.lairs().lairs(REGION).stream().filter(Lair::alive).findFirst().orElseThrow();
        check(born.lastDay() == 8, "deterministic replay fixture did not found its replacement on day eight");
        Chunk bornChunk = webTiming.world.getChunkIfExists(Math.floorDiv(born.x(), 16), Math.floorDiv(born.z(), 16));
        long due = bornChunk.scheduledTicks().due(Chunk.idx(Math.floorMod(born.x(), 16), born.y(), Math.floorMod(born.z(), 16)),
                BlockType.SPIDER_NEST.ordinal());
        long earliestWebTick = webTiming.clock.worldTicks() + SpiderNestBehavior.INTERVAL;
        check(due >= earliestWebTick,
                "a replay-founded heart scheduled silk before its first full day: due=" + due
                        + " earliest=" + earliestWebTick + " bornDay=" + born.lastDay());
        Scenario f = cleared(); Map<String, byte[]> sections = f.sections();
        Scenario reopened = new Scenario(true, false); reopened.restore(sections); reopened.step(30);
        check(reopened.ecology.lairs().lairs(REGION).size() == 1 && !reopened.old().alive() && reopened.spiders().isEmpty(),
                "reopening a cleared region bypassed the reseeding delay");
        long deadDay = reopened.old().destroyedDay(), foundDay = -1;
        for (int day = 1; day <= 10; day++) {
            reopened.ecology.advanceDays(1); reopened.clock.setHours(0); reopened.step(12);
            check(reopened.world.getBlock(HEART_X, HEART_Y, HEART_Z) == BlockType.AIR && !reopened.old().alive()
                    && reopened.old().population() == 2 && reopened.old().bodyIds().isEmpty(),
                    "cleared old territory grew or emitted new spiders");
            for (Mob m : reopened.spiders()) {
                float dx = m.position.x - HEART_X, dz = m.position.z - HEART_Z;
                check(dx * dx + dz * dz >= 48 * 48, "a new spider appeared in the old territory");
            }
            List<Lair> alive = reopened.ecology.lairs().lairs(REGION).stream().filter(Lair::alive).toList();
            if (day < 3) check(alive.isEmpty(), "colony founded before its minimum three-day delay");
            if (!alive.isEmpty() && foundDay < 0) {
                foundDay = alive.get(0).lastDay();
                check(alive.size() == 1 && alive.get(0).x() > 84 && alive.get(0).x() < 107
                        && alive.get(0).z() > 20 && alive.get(0).z() < 43,
                        "reseeding did not use the other fully closed loaded cave");
            }
        }
        check(foundDay - deadDay >= 3 && foundDay - deadDay <= 10 && !reopened.spiders().isEmpty(),
                "eligible hidden second cave did not reseed and emit within three to ten days");
    }

    private static void dailyParity() throws Exception {
        Scenario firstDaily = new Scenario(true, true), firstCommand = new Scenario(true, true);
        firstDaily.spawn = firstCommand.spawn = false; firstDaily.enable(); firstCommand.enable();
        for (int day = 0; day < 10; day++) { firstDaily.clock.addHours(24); firstDaily.session.tickMobs(.05f); }
        firstCommand.ecology.advanceDays(10);
        check(Arrays.equals(firstDaily.old().encode(), firstCommand.old().encode()),
                "a placed heart awaiting its first session tick differs between ordinary days and advance");
        Scenario original = cleared(); Map<String, byte[]> sections = original.sections();
        Scenario daily = new Scenario(true, false), command = new Scenario(true, false);
        daily.restore(sections); command.restore(sections); daily.spawn = command.spawn = false;
        for (int day = 0; day < 10; day++) { daily.clock.addHours(24); daily.session.tickMobs(.05f); }
        command.ecology.advanceDays(10);
        List<Lair> a = daily.ecology.lairs().lairs(REGION), b = command.ecology.lairs().lairs(REGION);
        check(a.size() == 2 && b.size() == 2, "daily replay or advance did not found the eligible cave");
        for (int i = 0; i < a.size(); i++) check(Arrays.equals(a.get(i).encode(), b.get(i).encode()), "ordinary day and advance differ in lair history/growth");
    }

    private static void reopen() throws Exception {
        try (Disk disk = new Disk()) {
            Scenario before = new Scenario(true, true); before.enable(); before.step(6); before.spawn = false;
            Set<Long> ids = before.liveIds(); Map<Long, ChunkSnapshot> snapshots = new LinkedHashMap<>();
            for (Chunk c : before.world.getLoadedChunks()) {
                ChunkSnapshot snapshot = before.session.snapshotChunk(c); snapshots.put(World.key(c.cx, c.cz), snapshot);
                disk.save.saveChunkAsync("world", snapshot);
            }
            disk.save.saveLevel("world", level(before, before.sections())); disk.save.flushAndAwait();
            before.session.releaseEntitiesIn(snapshots.keySet());
            check(before.entities.mobs.isEmpty() && before.old().bodyIds().equals(ids), "eviction released reserved saved bodies");
            SaveManager manager = disk.reopen(); LevelData loaded = manager.loadLevel("world");
            check(loaded != null, "level did not reopen");
            Scenario after = new Scenario(false, false); after.clock.setGameTime(loaded.timeOfDay); after.restore(loaded.extraSections);
            check(after.old().bodyIds().equals(ids) && after.entities.mobs.isEmpty() && after.old().available() == 0,
                    "restore emitted or released bodies before chunk adoption");
            ChunkLoader loader = new ChunkLoader(after.world, null, manager, "world"); loader.setMeshing(false);
            try {
                for (long key : snapshots.keySet()) { Chunk c = loader.loadNow((int) (key >> 32), (int) key); after.session.adoptChunkItems(c); }
                check(after.liveIds().equals(ids) && after.old().population() == 6, "chunk adoption duplicated/lost lair identities");
                for (var entry : snapshots.entrySet()) {
                    Chunk c = after.world.getChunkIfExists((int) (entry.getKey() >> 32), (int) (long) entry.getKey());
                    c.setPendingMobs(entry.getValue().mobs); after.session.adoptChunkItems(c);
                }
                after.step(12);
                check(after.liveIds().equals(ids) && after.old().bodyIds().equals(ids), "repeat chunk adoption or night tick duplicated finite bodies");
                after.player.position.set(400, 61, 400); after.focus.set(after.player.position); after.step(50);
                check(after.spiders().isEmpty() && after.old().bodyIds().equals(ids), "distant residency lost the finite body reservations");
                int parked = 0;
                for (Chunk c : after.world.getLoadedChunks()) parked += c.copyPendingMobs().size();
                check(parked == 4, "distant spiders were not parked as four saved identities");
                after.player.position.set(64.5f, 61, 64.5f); after.focus.set(after.player.position); after.step(50);
                check(after.liveIds().equals(ids) && after.old().bodyIds().equals(ids), "returning to parked chunks regenerated or duplicated spiders");
            } finally { loader.shutdown(); }
        }
    }

    private static Lair known(Map<String, byte[]> sections, int x) throws Exception {
        for (EcologyRegion region : EcologyCodec.decode(sections.get(Ecology.SAVE_SECTION)).regions())
            for (byte[] raw : region.lairs()) { Lair l = Lair.decode(raw); if (l != null && l.x() == x) return l; }
        throw new AssertionError("known lair missing from saved ecology");
    }

    private static Map<String, byte[]> records(byte[]... blobs) throws Exception {
        Scenario f = new Scenario(false, false); f.enable(); Map<String, byte[]> sections = f.sections();
        EcologyCodec.Snapshot original = EcologyCodec.decode(sections.get(Ecology.SAVE_SECTION));
        List<EcologyRegion> regions = original.regions();
        regions.stream().filter(r -> r.key() == REGION).findFirst().orElseThrow().lairs(List.of(blobs));
        sections.put(Ecology.SAVE_SECTION, EcologyCodec.encode(new EcologyCodec.Snapshot(regions, original.reservations(), original.tail())));
        return sections;
    }

    private static void unknownRecords() throws Exception {
        byte[] opaque = { 42, 19, 7, 1, 3, 5 }, suffix = { 8, 6, 7, 5, 3, 0, 9 };
        Lair known = new Lair(HEART_X, HEART_Y, HEART_Z, 0, LairTable.get().byType(MobType.SPIDER)); known.tail(suffix);
        Map<String, byte[]> sections = records(opaque, known.encode());
        Scenario f = new Scenario(false, false); f.restore(sections); f.ecology.advanceDays(1);
        try (Disk disk = new Disk()) {
            disk.save.saveLevel("world", level(f, f.sections())); disk.save.flushAndAwait();
            Map<String, byte[]> saved = disk.reopen().loadLevel("world").extraSections;
            EcologyRegion region = EcologyCodec.decode(saved.get(Ecology.SAVE_SECTION)).regions().stream().filter(r -> r.key() == REGION).findFirst().orElseThrow();
            check(region.lairs().stream().anyMatch(raw -> Arrays.equals(raw, opaque)) && Arrays.equals(known(saved, HEART_X).tail(), suffix)
                    && known(saved, HEART_X).population() == 7, "actual ecology/save lost an unknown blob or known suffix while growing the typed lair");
        }
    }

    private static void futureAndCorrupt() throws Exception {
        Lair lair = new Lair(HEART_X, HEART_Y, HEART_Z, 0, LairTable.get().byType(MobType.SPIDER));
        byte[] future = lair.encode(); future[4] = (byte) (Lair.VERSION + 1);
        Map<String, byte[]> newer = records(future); Scenario f = new Scenario(false, false); f.restore(newer);
        check(!f.ecology.supported() && !f.ecology.enabled(), "newer known lair layout was simulated");
        f.session.tickMobs(.05f);
        try (Disk disk = new Disk()) {
            disk.save.saveLevel("world", level(f, f.sections())); disk.save.flushAndAwait();
            var saved = disk.reopen().loadLevel("world").extraSections;
            check(Arrays.equals(saved.get(Ecology.SAVE_SECTION), newer.get(Ecology.SAVE_SECTION))
                    && Arrays.equals(saved.get("rules"), newer.get("rules")), "actual level save changed newer lair ecology/rules bytes");
        }
        byte[] unknownType = lair.encode();
        int typeOffset = 7 + lair.id().toString().length() + 2;
        System.arraycopy("BEETLE".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, unknownType, typeOffset, 6);
        Lair zombie = new Lair(HEART_X, HEART_Y, HEART_Z, 0,
                new LairTable.Definition(com.mineclone.data.ResourceId.of("zombie_crypt"), MobType.ZOMBIE, 6, 8, 1, 4, 24));
        for (byte[] unsupported : new byte[][] { unknownType, zombie.encode() }) {
            Map<String, byte[]> raw = records(unsupported); Scenario protectedData = new Scenario(true, false);
            protectedData.restore(raw); protectedData.step(12);
            check(!protectedData.ecology.supported() && !protectedData.ecology.enabled() && protectedData.spiders().isEmpty()
                    && Arrays.equals(protectedData.sections().get(Ecology.SAVE_SECTION), raw.get(Ecology.SAVE_SECTION))
                    && Arrays.equals(protectedData.sections().get("rules"), raw.get("rules")),
                    "unsupported valid lair definition was rebuilt or emitted as a spider");
        }
        byte[] corrupt = Arrays.copyOf(lair.encode(), 12); Map<String, byte[]> damaged = records(corrupt);
        Scenario rebuilt = new Scenario(false, false); List<byte[]> evidence = new ArrayList<>();
        rebuilt.ecology.restore(damaged, evidence::add);
        check(evidence.size() == 1 && Arrays.equals(evidence.get(0), damaged.get(Ecology.SAVE_SECTION))
                && rebuilt.ecology.supported() && rebuilt.ecology.lairs().lairs(REGION).isEmpty(), "known malformed record rebuilt before preserving the whole ecology evidence");
        rebuilt.step(1); check(knownCount(rebuilt.sections()) == 0, "corrupt known lair survived a successful rebuild");
        Scenario blocked = new Scenario(false, false);
        blocked.ecology.restore(damaged, bytes -> { throw new IllegalStateException("evidence unavailable"); });
        check(!blocked.ecology.supported() && !blocked.ecology.enabled()
                && Arrays.equals(blocked.sections().get(Ecology.SAVE_SECTION), damaged.get(Ecology.SAVE_SECTION)), "failed evidence preservation overwrote damaged lair bytes");
    }
    private static int knownCount(Map<String, byte[]> sections) throws Exception {
        int count = 0;
        for (EcologyRegion region : EcologyCodec.decode(sections.get(Ecology.SAVE_SECTION)).regions())
            for (byte[] raw : region.lairs()) if (Lair.decode(raw) != null) count++;
        return count;
    }
    private static LevelData level(Scenario f, Map<String, byte[]> sections) {
        GameMode mode = GameMode.values()[java.nio.ByteBuffer.wrap(sections.getOrDefault("rules", new byte[4])).getInt()];
        return new LevelData("Lair runtime test", SEED, 64.5, 61, 64.5, f.clock.gameTimeFloat(), mode,
                1L, PlayerRecord.builder().pose(64.5, 61, 64.5, 0, 0, 0).build(), sections);
    }
    private static final class Disk implements AutoCloseable {
        final Path root;
        final SaveManager save;
        final List<SaveManager> managers = new ArrayList<>();
        Disk() throws Exception {
            Files.createDirectories(Path.of("out-test")); root = Files.createTempDirectory(Path.of("out-test"), "lair-runtime-");
            save = new SaveManager(root.toFile()); managers.add(save);
        }
        SaveManager reopen() { SaveManager next = new SaveManager(root.toFile()); managers.add(next); return next; }
        @Override public void close() throws Exception {
            var field = SaveManager.class.getDeclaredField("chunkWriter"); field.setAccessible(true);
            for (SaveManager manager : managers) { manager.flushAndAwait(); ((ExecutorService) field.get(manager)).shutdown(); }
            try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
    }
}

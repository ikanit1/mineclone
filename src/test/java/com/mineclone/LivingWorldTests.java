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
import com.mineclone.sim.ecology.BurnScars;
import com.mineclone.sim.ecology.Ecology;
import com.mineclone.sim.ecology.EcologyCodec;
import com.mineclone.sim.ecology.EcologyRegion;
import com.mineclone.sim.ecology.Lair;
import com.mineclone.sim.ecology.Trample;
import com.mineclone.world.BlockTicker;
import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.ChunkLoader;
import com.mineclone.world.GameMode;
import com.mineclone.world.World;
import com.mineclone.world.behavior.Behaviors;
import com.mineclone.world.damage.DamageSource;
import com.mineclone.world.damage.DamageType;
import com.mineclone.world.entity.Mob;
import com.mineclone.world.entity.MobSpawner;
import com.mineclone.world.entity.MobType;
import com.mineclone.world.shape.BodyCollision;
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

/** LW-11: A/B/C share one valley, one authoritative session and one durable history. */
public final class LivingWorldTests {
    private static final long SEED = 2, REGION = EcologyRegion.key(0, 0);
    private static final ResourceId RABBIT = ResourceId.of("rabbit"), WOLF = ResourceId.of("wolf"), COW = ResourceId.of("cow");
    private static final int GROUND = 32, FEET = GROUND + 1, HEART_X = 96, HEART_Y = 25, HEART_Z = 32;
    private static final int OPEN_X = 8, CLOSED_X = 32, FIELD_Z = 14;
    private static final int[][] SCAR_SITES = { { 4, 8 }, { 12, 8 } };
    private static final byte[] FUTURE = { 11, 29, 73, (byte) 201 };

    public static void runAll(TestMain.Runner r) {
        r.run("living valley A/C: hunted wolves, actual gated crop raids, fleeing herd and seven-day forest coexist", LivingWorldTests::valley);
        r.run("living valley B/C: cleared finite scouts stop silk while another cave colonizes beside a recovered grove", LivingWorldTests::clearAndRecover);
        r.run("living valley A/B/C: real close and reopening preserve raid, silk, trails, scar flags and parked identities", LivingWorldTests::reopen);
        r.run("living valley absence: thirty days with unloaded terrain recover on arrival and return the original finite bodies", LivingWorldTests::absence);
        r.run("living valley daily and owner advance agree on thirty-day population, colony and scar history", LivingWorldTests::parity);
    }

    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    private static final class Valley {
        final World world = new World(SEED) {
            @Override public Chunk generateDetached(int cx, int cz) {
                Chunk chunk = new Chunk(cx, cz);
                for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) chunk.set(x, GROUND, z, BlockType.GRASS);
                chunk.computeSkyLight(); return chunk;
            }
        };
        final WorldClock clock;
        final EntityStore entities = new EntityStore();
        final Participants participants = new Participants();
        final Player player = new Player();
        final WorldSession session;
        final Ecology ecology;
        final BlockTicker fire = new BlockTicker(90210);
        final Map<String, byte[]> extras = new LinkedHashMap<>();
        final List<Mob> wolves = new ArrayList<>();
        boolean spawn;

        Valley(boolean terrain) throws Exception { this(terrain, new WorldClock(Math.PI / 2)); }
        Valley(boolean terrain, WorldClock clock) throws Exception {
            this.clock = clock; home(); player.setGameMode(GameMode.CREATIVE);
            participants.put(new LocalParticipant(player, () -> 0));
            // Random undead are unrelated to these stories; finite materialization and lair emission remain real.
            MobSpawner spawner = new MobSpawner(SEED) {
                @Override public void trySpawnHostiles(World w, List<Mob> mobs, List<? extends Vector3fc> centres, float daylight) {}
            };
            session = new WorldSession(world, clock, spawner, entities, participants, new WorldSession.Host() {
                @Override public Vector3f mobFocus() { return player.position; }
                @Override public boolean spawnMobs() { return spawn; }
            }, WorldEvents.NONE);
            ecology = session.ecology();
            if (!terrain) return;
            for (int cx = -1; cx <= 3; cx++) for (int cz = -1; cz <= 3; cz++) world.getChunk(cx, cz);
            // Both a placed heart and any later candidate need their four dispatcher neighbors.
            for (int cx = 4; cx <= 11; cx++) for (int cz = 0; cz <= 3; cz++) world.getChunk(cx, cz);
            cave(HEART_X); cave(160); plot(OPEN_X); plot(CLOSED_X);
            for (int z = 6; z <= 19; z++) { fence(30, z); fence(37, z); }
            for (int x = 30; x <= 37; x++) { fence(x, 6); fence(x, 12); fence(x, 19); }
            world.setBlock(33, FEET, 12, BlockType.FENCE_GATE, (byte) 0);
            for (int x = 53; x <= 62; x++) for (int z = 24; z <= 31; z++)
                if (x == 53 || x == 62 || z == 24 || z == 31)
                    for (int y = FEET; y <= FEET + 2; y++) world.setBlock(x, y, z, BlockType.STONE);
            world.setBlock(HEART_X, HEART_Y, HEART_Z, BlockType.SPIDER_NEST);
            Behaviors.of(BlockType.SPIDER_NEST).onPlaced(world, HEART_X, HEART_Y, HEART_Z, (byte) 0);
            for (Chunk chunk : world.getLoadedChunks()) chunk.computeSkyLight();
            check(world.getSkyLight(HEART_X - 3, HEART_Y, HEART_Z) == 0, "valley cave leaks daylight");
            ecology.setEnabled(true);
            region().population(RABBIT, 20); region().population(WOLF, 5); region().population(COW, 10);
            for (int i = 0; i < 5; i++) wolves.add(animal(MobType.WOLF, 55.5f + i, 27.5f, 10 + i));
            session.tickMobs(0);
        }
        void home() { player.position.set(40.5f, GROUND + 14, 32.5f); }
        EcologyRegion region() { return ecology.regionAt(8, 8); }
        Lair old() { return ecology.lairs().lairs(REGION).stream().filter(l -> l.x() == HEART_X && l.z() == HEART_Z).findFirst().orElseThrow(); }
        List<Lair> aliveLairs() { return ecology.lairs().lairs(REGION).stream().filter(Lair::alive).toList(); }
        List<Mob> spiders() { return entities.mobs.stream().filter(m -> m.type == MobType.SPIDER && !m.dead).toList(); }
        void cave(int centre) {
            for (int x = centre - 12; x <= centre + 11; x++) for (int z = 20; z <= 43; z++) for (int y = 24; y <= 28; y++)
                if (y == 24 || y == 28 || x == centre - 12 || x == centre + 11 || z == 20 || z == 43)
                    world.setBlock(x, y, z, BlockType.STONE);
            // The flat surface must not create a second unintended dark cavity above this roof.
            for (int x = centre - 12; x <= centre + 11; x++) for (int z = 20; z <= 43; z++) for (int y = 29; y < GROUND; y++)
                world.setBlock(x, y, z, BlockType.STONE);
        }
        void plot(int left) {
            for (int x = left; x < left + 4; x++) for (int z = FIELD_Z; z < FIELD_Z + 4; z++) {
                world.setBlock(x, GROUND, z, BlockType.FARMLAND, (byte) 7);
                world.setBlock(x, FEET, z, BlockType.CARROTS, (byte) 7);
                Behaviors.of(BlockType.CARROTS).onPlaced(world, x, FEET, z, (byte) 7);
            }
        }
        void fence(int x, int z) { world.setBlock(x, FEET, z, BlockType.FENCE); }
        Mob animal(MobType type, float x, float z, int salt) {
            Mob mob = new Mob(type, x, FEET + .001f, z, new Random(SEED + salt)); mob.onGround = true;
            mob.savedTail = new byte[] { (byte) salt, 91, 7 };
            check(ecology.claim(mob), "finite " + type + " reservation refused"); entities.mobs.add(mob); return mob;
        }
        Mob rabbit(float x, float z, int salt) throws Exception {
            Mob mob = animal(MobType.RABBIT, x, z, salt);
            var hunger = Mob.class.getDeclaredField("hungerTimer"); hunger.setAccessible(true); hunger.setFloat(mob, 0); return mob;
        }
        void kill(Mob mob) {
            check(mob.damage(DamageSource.byPlayer(DamageType.MELEE, 0, player.position.x, player.position.y, player.position.z, 0), 100),
                    "actual player damage refused");
        }
        void hunt() { for (int i = 0; i < 4; i++) kill(wolves.get(i)); step(20); }
        void abstractWolves() {
            // Coarse predation excludes represented bodies: their prey would be killed by real combat.
            // Exercise the session's far/unloaded fold so both controls compare unrepresented wolves.
            Chunk pen = world.removeChunk(3, 1); player.position.set(400.5f, FEET + .001f, 400.5f); session.tickMobs(0);
            world.publish(pen); home(); session.tickMobs(0);
            check(region().protectedCount(WOLF) == 0, "the actual far/unloaded path did not fold the surviving wolf bodies");
        }
        void step(int frames) {
            for (int i = 0; i < frames; i++) { clock.advance(.05); session.tickMobs(.05f); session.tickScheduled(); }
        }
        void night() {
            clock.setHours(0); spawn = true; step(6); spawn = false;
            check(spiders().size() == 4 && old().bodyIds().size() == 4, "valley nest did not emit four finite scouts");
        }
        void burn() {
            for (int[] site : SCAR_SITES) {
                int x = site[0], z = site[1]; world.setBlock(x, GROUND + 2, z, BlockType.LEAVES);
                for (int n = 0; n < 2000 && world.getBlock(x, GROUND + 2, z) == BlockType.LEAVES; n++) {
                    world.setBlock(x, FEET, z, BlockType.FIRE, (byte) 0); fire.apply(world, x, FEET, z);
                }
                check(world.getBlock(x, GROUND + 2, z) != BlockType.LEAVES && world.getBlock(x, GROUND, z) == BlockType.DIRT,
                        "production fire did not consume canopy and scorch its actual ground at " + x + "," + z
                                + ": fuel=" + world.getBlock(x, GROUND + 2, z) + " ground=" + world.getBlock(x, GROUND, z)
                                + " enabled=" + ecology.enabled() + " scars=" + scars().size());
                for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int y = FEET; y <= GROUND + 3; y++)
                    if (world.getBlock(x + dx, y, z + dz) == BlockType.FIRE) fire.apply(world, x + dx, y, z + dz, true);
            }
        }
        void walk(int trips) {
            player.position.set(20.5f, FEET + .001f, 8.5f); session.tickMobs(0);
            for (int trip = 0; trip < trips; trip++) {
                for (int x = 21; x <= 26; x++) { player.position.x = x + .5f; session.tickMobs(0); }
                for (int x = 25; x >= 20; x--) { player.position.x = x + .5f; session.tickMobs(0); }
            }
            home(); session.tickMobs(0);
            check(Trample.pressureAt(world, 23, GROUND, 8) >= trips * 2, "ordinary participant route left no pressure");
        }
        void days(int days) { ecology.advanceDays(days); session.tickScheduled(); }
        int damaged(int left) {
            int damaged = 0;
            for (int x = left; x < left + 4; x++) for (int z = FIELD_Z; z < FIELD_Z + 4; z++) {
                check(world.getBlock(x, GROUND, z) == BlockType.FARMLAND && world.getBlock(x, FEET, z) == BlockType.CARROTS,
                        "other valley systems overwrote the player's field");
                if ((world.getBlockMeta(x, FEET, z) & 7) < 7) damaged++;
            }
            return damaged;
        }
        int webs(int centre) {
            int count = 0;
            for (int x = centre - 12; x < centre + 12; x++) for (int z = 20; z < 44; z++) for (int y = 25; y < 28; y++)
                if (world.getBlock(x, y, z) == BlockType.WEB) count++;
            return count;
        }
        void recovered() {
            boolean tree = false;
            for (int[] site : SCAR_SITES) {
                check(world.getBlock(site[0], GROUND, site[1]) == BlockType.GRASS && !world.burnDamaged(site[0], GROUND, site[1]),
                        "seven-day scar did not recover grass");
                tree |= world.getBlock(site[0], FEET, site[1]) == BlockType.WOOD;
            }
            check(tree && scars().stream().flatMap(s -> s.sites().stream()).anyMatch(BurnScars.Site::plantedOnce),
                    "valley recovery checked grass without an actually grown natural tree");
        }
        List<BurnScars.Scar> scars() { return ecology.scars().scars(REGION); }
        Map<String, byte[]> sections() {
            Map<String, byte[]> result = new LinkedHashMap<>(extras); ecology.writeSections(result);
            result.put(WorldClock.SAVE_SECTION, clock.encode()); result.put(EntityStore.IDS_SECTION, entities.encodeIds()); return result;
        }
        void restore(Map<String, byte[]> sections) {
            sections.forEach((key, bytes) -> extras.put(key, bytes.clone())); entities.restoreIds(sections.get(EntityStore.IDS_SECTION));
            ecology.restore(sections, bytes -> { throw new AssertionError("valid combined story was quarantined"); });
        }
        void leave() {
            // Keep the save fixture's full represented herd loaded, including animals that wandered past its initial edge.
            for (Mob mob : entities.mobs) if (!mob.dead && mob.persistent())
                world.getChunk(Math.floorDiv((int) Math.floor(mob.position.x), 16), Math.floorDiv((int) Math.floor(mob.position.z), 16));
            player.position.set(400.5f, FEET + .001f, 400.5f); session.tickMobs(2.1f);
        }
    }

    private static void valley() throws Exception {
        Valley f = new Valley(true); f.night(); f.hunt();
        check(f.region().population(WOLF) == 1 && f.region().lossCount(EcologyRegion.LossCause.PLAYER) == 4,
                "actual wolf deaths were not counted exactly once");
        Mob cow = f.animal(MobType.COW, 9.5f, 8.5f, 33); long id = cow.id; Vector3f start = new Vector3f(cow.position);
        f.burn(); f.walk(10); f.session.tickMobs(0);
        check(cow.burnDestination != null, "the common valley's herd ignored fresh scorched ground");
        Vector3f previous = new Vector3f(cow.position);
        for (int i = 0; i < 240; i++) {
            f.step(1); check(cow.position.distance(previous) < .7f, "scar escape teleported its finite cow");
            check(!BodyCollision.overlaps(f.world, cow.position.x, cow.position.y, cow.position.z, cow.type.width, cow.type.height),
                    "cow escaped into terrain"); previous.set(cow.position);
        }
        check(cow.id == id && !cow.dead && cow.position.distance(start) > 6, "physical escape lost identity or never left the grove");
        Valley unhunted = new Valley(true); unhunted.night(); unhunted.step(20);
        unhunted.animal(MobType.COW, 9.5f, 8.5f, 33); unhunted.burn(); unhunted.walk(10); unhunted.step(240);
        f.abstractWolves(); unhunted.abstractWolves();
        f.clock.setHours(12); long before = Ecology.day(f.clock.gameTime()); f.days(7);
        unhunted.clock.setHours(12); unhunted.days(7);
        check(Ecology.day(f.clock.gameTime()) == before + 7 && f.region().population(RABBIT) > 20,
                "shared owner advancement did not release rabbit pressure after hunting");
        check(f.region().population(RABBIT) > unhunted.region().population(RABBIT)
                        && unhunted.region().lossCount(EcologyRegion.LossCause.PLAYER) == 0,
                "the same fire and field history with five unhunted wolves had no lower rabbit pressure: hunted="
                        + f.region().population(RABBIT) + " control=" + unhunted.region().population(RABBIT)
                        + " wolves=" + f.region().population(WOLF) + "/" + unhunted.region().population(WOLF));
        f.recovered(); check(f.webs(HEART_X) > 0 && f.old().alive(), "forest recovery erased the neighboring living nest's silk");
        Mob open = f.rabbit(9.5f, 11.5f, 20), closed = f.rabbit(33.5f, 10.5f, 21); f.step(400);
        check(f.damaged(OPEN_X) > 0 && f.damaged(CLOSED_X) == 0 && closed.position.z < 12.5f,
                "real raids did not distinguish open crops from the closed gate");
        f.world.setBlock(33, FEET, 12, BlockType.FENCE_GATE, (byte) 4); f.step(600);
        check(f.damaged(CLOSED_X) > 0 && !open.dead && !closed.dead && open.id != closed.id,
                "opening the valley gate failed to expose its field to the same finite rabbit");
        check(Trample.pressureAt(f.world, 23, GROUND, 8) > 0, "week-old frequently walked path lost its independent history");
    }

    private static void clearAndRecover() throws Exception {
        Valley f = new Valley(true); f.night(); f.hunt(); f.burn(); f.walk(10); f.days(2);
        int silk = f.webs(HEART_X); check(silk > 0, "clearance story started without real scheduled silk");
        long clearedDay = Ecology.day(f.clock.gameTime()); Set<Long> scouts = new TreeSet<>(f.old().bodyIds());
        f.world.setBlock(HEART_X, HEART_Y, HEART_Z, BlockType.AIR);
        for (Mob mob : f.spiders()) f.kill(mob); f.step(4);
        int survivors = f.old().population(); f.clock.setHours(12);
        check(!f.old().alive() && f.old().destroyedDay() == clearedDay && f.old().bodyIds().isEmpty()
                        && survivors == 4 && scouts.size() == 4,
                "destroyed heart or four actual deaths lost the finite colony history");
        f.days(2); check(f.aliveLairs().isEmpty(), "another cave colonized before the minimum three-day clearance delay");
        f.days(8); f.recovered();
        List<Lair> colonies = f.aliveLairs();
        check(colonies.size() == 1 && colonies.get(0).x() >= 148 && colonies.get(0).x() <= 171
                        && colonies.get(0).lastDay() - clearedDay >= 3 && f.old().population() == survivors,
                "clearance regrew its old territory or failed to colonize the other closed cave");
        check(f.webs(HEART_X) == silk && f.world.getBlock(HEART_X, HEART_Y, HEART_Z) == BlockType.AIR,
                "a dead heart continued scheduled silk while the grove recovered");
        f.player.position.set(128.5f, GROUND + 14, 32.5f); f.clock.setHours(0); f.spawn = true; f.step(8); f.spawn = false;
        check(!f.spiders().isEmpty() && f.spiders().stream().noneMatch(m -> scouts.contains(m.id))
                        && f.spiders().stream().allMatch(m -> m.position.x >= 144),
                "replacement colony reused dead IDs or emitted into the cleared territory");
        check(f.damaged(OPEN_X) == 0 && f.damaged(CLOSED_X) == 0, "colonization or tree recovery overwrote untouched crops");
    }

    private static Valley savedJourney() throws Exception {
        Valley f = new Valley(true); f.night(); f.hunt(); f.burn(); f.clock.setHours(12); f.days(7); f.recovered();
        f.rabbit(9.5f, 11.5f, 20); f.step(400); check(f.damaged(OPEN_X) > 0, "saved journey omitted actual crop damage");
        f.walk(10); f.clock.setHours(0); f.spawn = true; f.step(6); f.spawn = false;
        check(f.spiders().size() == 4, "saved journey omitted represented finite spiders");
        f.extras.put("future_story", FUTURE); f.world.getChunkIfExists(0, 0).setExtraSection("future_valley", FUTURE);
        return f;
    }

    private static void reopen() throws Exception {
        try (Disk disk = new Disk()) {
            Valley before = savedJourney(); Set<Long> ids = representedIds(before); Set<Long> spiders = new TreeSet<>(before.old().bodyIds());
            int raid = before.damaged(OPEN_X), silk = before.webs(HEART_X), pressure = Trample.pressureAt(before.world, 23, GROUND, 8);
            long counter = before.clock.entityCounter(); before.leave();
            Map<Long, SavedMob> parked = parked(before); check(parked.keySet().equals(ids) && spiders.size() == 4,
                    "journey did not park its represented identities: before=" + ids + " parked=" + parked.keySet()
                            + " live=" + livingIds(before) + " scouts=" + spiders);
            Map<Long, ChunkSnapshot> snapshots = disk.write(before); disk.closeCurrent();
            SaveManager reopened = disk.reopen(); LevelData level = reopened.loadLevel("world"); check(level != null, "combined story level vanished");
            Valley after = from(level); ChunkLoader loader = loader(after, reopened);
            try {
                load(loader, after, snapshots.keySet()); after.session.tickMobs(0);
                check(after.entities.mobs.isEmpty() && parked(after).keySet().equals(ids) && after.old().bodyIds().equals(spiders),
                        "far reopening released parked animals or finite spider reservations");
                check(after.damaged(OPEN_X) == raid && after.damaged(CLOSED_X) == 0 && after.webs(HEART_X) == silk
                                && Trample.pressureAt(after.world, 23, GROUND, 8) == pressure,
                        "real reopening lost crop, silk or participant-route traces from the common journey");
                after.recovered();
                check(Arrays.equals(after.sections().get("future_story"), FUTURE)
                                && Arrays.equals(after.world.getChunkIfExists(0, 0).extraSection("future_valley"), FUTURE)
                                && after.clock.entityCounter() == counter,
                        "reopening changed opaque sections or allocated replacement identities");
                after.home(); after.session.tickMobs(2.1f); load(loader, after, snapshots.keySet()); after.session.tickMobs(0);
                check(livingIds(after).equals(ids) && after.entities.mobs.size() == ids.size() && after.old().bodyIds().equals(spiders),
                        "return or repeated adoption duplicated a saved finite animal or spider");
                for (Mob mob : after.entities.mobs) check(Arrays.equals(mob.savedTail == null ? new byte[0] : mob.savedTail, parked.get(mob.id).tail()),
                        "opaque represented-body tail changed on return");
                check(after.clock.nextEntityId() > counter, "the shared allocator recycled a story identity");
            } finally { loader.shutdown(); }
        }
    }

    private static void absence() throws Exception {
        try (Disk disk = new Disk()) {
            Valley before = new Valley(true); before.night(); before.hunt(); before.burn(); before.walk(10);
            Set<Long> ids = representedIds(before), spiders = new TreeSet<>(before.old().bodyIds()); before.leave();
            Map<Long, ChunkSnapshot> snapshots = disk.write(before); disk.closeCurrent();
            SaveManager reopened = disk.reopen(); Valley after = from(reopened.loadLevel("world"));
            int rabbits = after.region().population(RABBIT); long day = Ecology.day(after.clock.gameTime()); after.clock.setHours(12);
            after.ecology.advanceDays(30);
            check(Ecology.day(after.clock.gameTime()) == day + 30 && after.region().lastDay() == day + 30
                            && after.region().population(RABBIT) != rabbits && after.old().bodyIds().equals(spiders),
                    "thirty absent days failed to advance the saved population without releasing absent spider identities");
            check(!after.world.getLoadedChunks().iterator().hasNext() && after.scars().get(0).lastProcessedDay() == day,
                    "absence generated terrain or consumed unloaded scar recovery opportunities");
            ChunkLoader loader = loader(after, reopened);
            try {
                load(loader, after, snapshots.keySet()); long arrivalDay = Ecology.day(after.clock.gameTime());
                check(after.scars().get(0).lastProcessedDay() == day, "fixture already processed arriving terrain before save");
                Map<Long, ChunkSnapshot> arrivingSave = disk.write(after); disk.closeCurrent();
                SaveManager boundarySave = disk.reopen(); Valley boundary = from(boundarySave.loadLevel("world"));
                ChunkLoader boundaryLoader = loader(boundary, boundarySave);
                try {
                    Chunk restored = boundaryLoader.loadNow(0, 0);
                    check(Arrays.equals(restored.copyBlocks(), arrivingSave.get(World.key(0, 0)).blocks)
                                    && boundary.scars().get(0).lastProcessedDay() == arrivalDay
                                    && boundary.scars().get(0).sites().stream().anyMatch(BurnScars.Site::plantedOnce)
                                    && restored.get(4, GROUND, 8) == BlockType.GRASS,
                            "save immediately after arrival captured old terrain with already advanced scar flags");
                } finally { boundaryLoader.shutdown(); }
                after.session.tickMobs(0); after.session.tickScheduled();
                check(Ecology.day(after.clock.gameTime()) == arrivalDay && after.scars().get(0).lastProcessedDay() == arrivalDay,
                        "saved chunk arrival required an additional day to recover its history");
                after.recovered(); check(after.entities.mobs.isEmpty() && parked(after).keySet().equals(ids), "arrival respawned replacement animals before their owner returned");
                after.clock.setHours(0); after.home(); after.session.tickMobs(2.1f);
                new BlockTicker(91).tick(after.world, after.player.position, 0);
                check(livingIds(after).equals(ids) && after.old().bodyIds().equals(spiders)
                                && Trample.pressureAt(after.world, 23, GROUND, 8) == 0
                                && after.world.getBlockMeta(23, GROUND, 8) == 0,
                        "return duplicated finite residents or left a thirty-day abandoned path fresh");
                check(after.world.getBlock(HEART_X, HEART_Y, HEART_Z) == BlockType.SPIDER_NEST && after.webs(HEART_X) > 0
                                && after.damaged(OPEN_X) == 0 && after.damaged(CLOSED_X) == 0,
                        "absence lost its living nest, bounded silk or untouched player fields");
            } finally { loader.shutdown(); }
        }
    }

    private static void parity() throws Exception {
        Valley daily = new Valley(true), command = new Valley(true);
        for (Valley f : List.of(daily, command)) {
            f.hunt(); f.burn(); f.walk(10); f.world.setBlock(HEART_X, HEART_Y, HEART_Z, BlockType.AIR); f.session.tickMobs(0);
        }
        // No live colony scouts are emitted here: compare the daily coarse path itself,
        // with real tree dispatch on every ordinary day and once after owner replay.
        for (int day = 1; day <= 30; day++) {
            daily.clock.addHours(24); daily.session.tickMobs(0); daily.session.tickScheduled();
            for (Chunk chunk : daily.world.getLoadedChunks()) Trample.advanceChunk(daily.world, chunk, Ecology.day(daily.clock.gameTime()));
        }
        command.days(30);
        for (Chunk chunk : command.world.getLoadedChunks()) Trample.advanceChunk(command.world, chunk, Ecology.day(command.clock.gameTime()));
        check(Arrays.equals(daily.sections().get(Ecology.SAVE_SECTION), command.sections().get(Ecology.SAVE_SECTION)),
                "daily play and owner replay differ in their common thirty-day population, colony or burn history");
        daily.recovered(); command.recovered();
        check(daily.aliveLairs().size() == 1 && command.aliveLairs().size() == 1 && !daily.old().alive() && !command.old().alive(),
                "parity was vacuous without a destroyed colony and actual replacement");
        for (int cx = -1; cx <= 3; cx++) for (int cz = -1; cz <= 3; cz++) {
            Chunk a = daily.world.getChunkIfExists(cx, cz), b = command.world.getChunkIfExists(cx, cz);
            check(Arrays.equals(a.copyBlocks(), b.copyBlocks()) && Arrays.equals(a.copyMeta(), b.copyMeta()),
                    "ordinary and replay tree, field or trail terrain differs in the same surface valley");
        }
        check(daily.webs(160) > 0 && command.webs(160) > 0 && command.webs(160) <= 6,
                "replacement silk was absent or fabricated more than the scheduler's two-day lateness budget");
        check(daily.clock.worldTicks() == command.clock.worldTicks() && livingIds(daily).equals(livingIds(command)),
                "coarse owner replay changed elapsed ticks or the existing finite identities");
    }

    private static Set<Long> livingIds(Valley f) {
        Set<Long> result = new TreeSet<>();
        for (Mob mob : f.entities.mobs) if (!mob.dead && mob.persistent()) check(result.add(mob.id) && mob.id > 0, "duplicate or missing live identity");
        return result;
    }
    private static Map<Long, SavedMob> parked(Valley f) {
        Map<Long, SavedMob> result = new LinkedHashMap<>();
        for (Chunk chunk : f.world.getLoadedChunks()) for (SavedMob mob : chunk.copyPendingMobs())
            check(result.put(mob.id(), mob) == null, "the same parked story identity appears in two chunks");
        return result;
    }
    private static Set<Long> representedIds(Valley f) {
        Set<Long> result = livingIds(f);
        for (long id : parked(f).keySet()) check(result.add(id), "a live story body also has a parked record");
        return result;
    }
    private static Valley from(LevelData level) throws Exception {
        check(level != null, "saved valley level missing");
        Valley f = new Valley(false, WorldClock.fromSaved(level.timeOfDay, level.extraSections.get(WorldClock.SAVE_SECTION)));
        f.player.position.set(400.5f, FEET + .001f, 400.5f); f.restore(level.extraSections); return f;
    }
    private static ChunkLoader loader(Valley f, SaveManager save) {
        ChunkLoader loader = new ChunkLoader(f.world, null, save, "world"); loader.setMeshing(false); return loader;
    }
    private static void load(ChunkLoader loader, Valley f, Set<Long> keys) throws Exception {
        for (long key : keys) { Chunk c = loader.loadNow((int) (key >> 32), (int) key); f.session.adoptChunkItems(c); }
    }
    private static final class Disk implements AutoCloseable {
        final Path root;
        SaveManager current;
        final List<SaveManager> managers = new ArrayList<>();
        Disk() throws Exception { Files.createDirectories(Path.of("out-test")); root = Files.createTempDirectory(Path.of("out-test"), "living-valley-"); current = reopen(); }
        SaveManager reopen() { current = new SaveManager(root.toFile()); managers.add(current); return current; }
        Map<Long, ChunkSnapshot> write(Valley f) {
            // Match Game and server: writing ecology can recover arriving terrain before it is captured.
            Map<String, byte[]> sections = f.sections();
            Map<Long, ChunkSnapshot> snapshots = new LinkedHashMap<>();
            for (Chunk chunk : f.world.getLoadedChunks()) {
                chunk.modified = true; ChunkSnapshot snapshot = f.session.snapshotChunk(chunk);
                check(snapshot != null, "writable valley chunk could not be saved"); snapshots.put(World.key(chunk.cx, chunk.cz), snapshot);
                current.saveChunkAsync("world", snapshot);
            }
            current.saveLevel("world", new LevelData("Living valley", SEED, 40.5, FEET, 32.5, f.clock.gameTimeFloat(), GameMode.CREATIVE, 1,
                    PlayerRecord.builder().pose(f.player.position.x, f.player.position.y, f.player.position.z, 0, 0, 0).build(), sections));
            current.flushAndAwait(); return snapshots;
        }
        void closeCurrent() throws Exception { shutdown(current); }
        private static void shutdown(SaveManager save) throws Exception {
            var writer = SaveManager.class.getDeclaredField("chunkWriter"); writer.setAccessible(true);
            ExecutorService executor = (ExecutorService) writer.get(save);
            if (!executor.isShutdown()) { save.flushAndAwait(); executor.shutdown(); }
        }
        @Override public void close() throws Exception {
            for (SaveManager manager : managers) shutdown(manager);
            try (var files = Files.walk(root)) { for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file); }
        }
    }
}

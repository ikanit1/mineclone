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
import com.mineclone.sim.ecology.BurnScars;
import com.mineclone.sim.ecology.Ecology;
import com.mineclone.sim.ecology.EcologyCodec;
import com.mineclone.sim.ecology.EcologyRegion;
import com.mineclone.sim.ecology.Materializer;
import com.mineclone.world.Biome;
import com.mineclone.world.BlockTicker;
import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.ChunkLoader;
import com.mineclone.world.GameMode;
import com.mineclone.world.World;
import com.mineclone.world.behavior.Behaviors;
import com.mineclone.world.behavior.SaplingBehavior;
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
import java.util.concurrent.ExecutorService;
import org.joml.Vector3f;

/** Actual fire, shared-session scar recovery, physical residency and protected level reopening. */
final class BurnRuntimeTests {
    private static final int Y = 32;
    private static final long REGION = EcologyRegion.key(0, 0);
    private static final ResourceId COW = ResourceId.of("cow");

    static void runAll(TestMain.Runner runner) {
        runner.run("actual fire consumption scorches owned ground and lowers regional greenery and capacity", BurnRuntimeTests::fire);
        runner.run("disabled Living World preserves ordinary fire terrain and creates no scar history", BurnRuntimeTests::disabled);
        runner.run("actual fire scar detection stops at fields and player obstacles and refuses protected ground", BurnRuntimeTests::obstacles);
        runner.run("actual ecology preserves newer scars and unknown records through real level save", BurnRuntimeTests::newer);
        runner.run("corrupt known scars require durable evidence before population rebuild", BurnRuntimeTests::corrupt);
        runner.run("a saved scar chunk arriving during the same day recovers its missed days", BurnRuntimeTests::arrival);
        runner.run("living animals physically flee fresh scars without teleporting or losing identity", BurnRuntimeTests::flee);
        runner.run("finite materialization refuses damaged scar ground and resumes on recovered terrain", BurnRuntimeTests::materialize);
        runner.run("ordinary days and owner advancement share fire recovery, tree growth and population history", BurnRuntimeTests::parity);
        runner.run("actual chunk and level reopening retains scar planted-once state and animal IDs", BurnRuntimeTests::reopen);
        runner.run("natural tree growth waits for actual participants and animals to leave its crown", BurnRuntimeTests::bodies);
    }

    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    private static long seed() {
        for (long seed = 1; seed < 1000; seed++)
            if (new World(seed).biomes.biomeAt(8, 8).treeType == Biome.TreeType.OAK) return seed;
        throw new AssertionError("forest seed not found");
    }

    private static final class Fixture {
        final World world;
        final WorldClock clock;
        final EntityStore entities = new EntityStore();
        final Participants participants = new Participants();
        final Player player = new Player();
        final WorldSession session;
        final Ecology ecology;
        final BlockTicker fire = new BlockTicker(90210);
        final Map<String, byte[]> restored = new LinkedHashMap<>();

        Fixture(boolean land, boolean enabled) { this(land, enabled, new WorldClock(Math.PI / 2)); }
        Fixture(boolean land, boolean enabled, WorldClock clock) {
            this.clock = clock;
            world = new World(seed()) {
                @Override public Chunk generateDetached(int cx, int cz) { return flat(cx, cz); }
            };
            player.position.set(24.5f, Y + 1.001f, 24.5f);
            player.setGameMode(GameMode.CREATIVE);
            participants.put(new LocalParticipant(player, () -> 0));
            session = new WorldSession(world, clock, new MobSpawner(world.seed), entities, participants,
                    new WorldSession.Host() {
                        @Override public Vector3f mobFocus() { return player.position; }
                        @Override public boolean spawnMobs() { return false; }
                    }, WorldEvents.NONE);
            ecology = session.ecology();
            if (land) for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) world.getChunk(cx, cz);
            if (enabled) ecology.setEnabled(true);
        }
        EcologyRegion region() { return ecology.regionAt(8, 8); }
        List<BurnScars.Scar> scars() { return ecology.scars().scars(REGION); }
        void burn(int x, int z) { consume(x, z, Y + 2); }
        void consume(int x, int z, int fuelY) {
            world.setBlock(x, fuelY, z, BlockType.LEAVES);
            for (int n = 0; n < 2000 && world.getBlock(x, fuelY, z) == BlockType.LEAVES; n++) {
                world.setBlock(x, fuelY - 1, z, BlockType.FIRE, (byte) 0);
                fire.apply(world, x, fuelY - 1, z);
            }
            check(world.getBlock(x, fuelY, z) != BlockType.LEAVES, "actual fire never consumed fixture leaves");
            // Extinguish actual fire with its ordinary exposed-rain rule, not by replacing it.
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++)
                for (int y = Y + 1; y <= fuelY + 1; y++)
                    if (world.getBlock(x + dx, y, z + dz) == BlockType.FIRE)
                        fire.apply(world, x + dx, y, z + dz, true);
        }
        void days(int count) { ecology.advanceDays(count); }
        void step(int frames) {
            for (int n = 0; n < frames; n++) { clock.advance(.05); session.tickMobs(.05f); }
        }
        Map<String, byte[]> sections() {
            Map<String, byte[]> sections = new LinkedHashMap<>(restored);
            sections.put(WorldClock.SAVE_SECTION, clock.encode());
            sections.put(EntityStore.IDS_SECTION, entities.encodeIds());
            ecology.writeSections(sections);
            return sections;
        }
        void restore(Map<String, byte[]> sections) {
            restored.clear(); sections.forEach((name, bytes) -> restored.put(name, bytes.clone()));
            entities.restoreIds(sections.get(EntityStore.IDS_SECTION));
            ecology.restore(sections, bytes -> { throw new AssertionError("valid scar data quarantined"); });
        }
        Mob cow(float x, float z) {
            Mob body = new Mob(MobType.COW, x, Y + 1.001f, z, new Random(1));
            body.persistent = true;
            check(ecology.claim(body), "fixture cow not claimed from actual finite population");
            entities.mobs.add(body);
            return body;
        }
    }

    private static Chunk flat(int cx, int cz) {
        Chunk chunk = new Chunk(cx, cz);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) chunk.set(x, Y, z, BlockType.GRASS);
        chunk.computeSkyLight();
        return chunk;
    }

    private static void fire() {
        Fixture f = new Fixture(true, true);
        for (int cx = 0; cx < 4; cx++) for (int cz = 0; cz < 4; cz++) f.world.getChunk(cx, cz);
        Map<ResourceId, Integer> capacity = new LinkedHashMap<>(f.region().capacities());
        for (int cx = 0; cx < 4; cx++) for (int cz = 0; cz < 4; cz++)
            for (int x = 2; x < 16; x += 4) for (int z = 2; z < 16; z += 4) f.burn(cx * 16 + x, cz * 16 + z);
        check(f.scars().size() == 16 && f.scars().stream().mapToInt(s -> s.sites().size()).sum() == 256,
                "actual consumption did not record exact bounded burned columns");
        check(f.world.getBlock(2, Y, 2) == BlockType.DIRT && f.world.burnDamaged(2, Y, 2), "fire did not damage its ground");
        check(f.region().averageGreenery() < 1 && capacity.entrySet().stream()
                .anyMatch(e -> f.region().capacity(e.getKey()) < e.getValue()), "canopy damage did not lower real regional capacity");
        check(f.region().lossCount(EcologyRegion.LossCause.FIRE) == 0, "burning terrain invented animal deaths");
    }

    private static void disabled() {
        Fixture off = new Fixture(true, false), on = new Fixture(true, true);
        off.burn(8, 8); on.burn(8, 8);
        check(off.scars().isEmpty() && off.world.getBlock(8, Y, 8) == BlockType.GRASS, "OFF produced a scar or scorched grass");
        check(on.world.getBlock(8, Y, 8) == BlockType.DIRT && on.scars().size() == 1, "ON did not observe the same fuel consumption");
        for (Chunk a : off.world.getLoadedChunks()) {
            Chunk b = on.world.getChunkIfExists(a.cx, a.cz);
            byte[] offBlocks = a.copyBlocks(), onBlocks = b.copyBlocks(), offMeta = a.copyMeta(), onMeta = b.copyMeta();
            for (int i = 0; i < offBlocks.length; i++) {
                if (a.cx == 0 && a.cz == 0 && i == Chunk.idx(8, Y, 8)) continue;
                check(offBlocks[i] == onBlocks[i] && offMeta[i] == onMeta[i], "ecology changed ordinary fire RNG or residue");
            }
        }
        on.ecology.setEnabled(false);
        byte[] before = on.world.getChunkIfExists(0, 0).copyBlocks();
        on.days(7); on.session.tickScheduled();
        check(Arrays.equals(before, on.world.getChunkIfExists(0, 0).copyBlocks()), "OFF advanced scar terrain");
    }

    private static void obstacles() {
        Fixture field = new Fixture(true, true);
        field.world.setBlock(8, Y, 8, BlockType.FARMLAND, (byte) 7);
        field.world.setBlock(8, Y + 1, 8, BlockType.WHEAT, (byte) 5);
        field.consume(8, 8, Y + 4);
        check(field.scars().isEmpty() && field.world.getBlock(8, Y, 8) == BlockType.FARMLAND
                && field.world.getBlock(8, Y + 1, 8) == BlockType.WHEAT && field.world.getBlockMeta(8, Y + 1, 8) == 5,
                "scar hook adopted or changed a planted field");
        Fixture roof = new Fixture(true, true);
        roof.world.setBlock(8, Y + 2, 8, BlockType.STONE);
        roof.consume(8, 8, Y + 4);
        check(roof.scars().isEmpty() && roof.world.getBlock(8, Y, 8) == BlockType.GRASS
                && roof.world.getBlock(8, Y + 2, 8) == BlockType.STONE, "scar crossed a player obstacle");
        Fixture protectedGround = new Fixture(true, true);
        protectedGround.world.setBlock(8, Y + 2, 8, BlockType.LEAVES);
        protectedGround.world.setBlock(8, Y + 1, 8, BlockType.FIRE);
        protectedGround.world.getChunkIfExists(0, 0).markReadOnly();
        for (int n = 0; n < 100; n++) protectedGround.fire.apply(protectedGround.world, 8, Y + 1, 8);
        check(protectedGround.scars().isEmpty() && protectedGround.world.getBlock(8, Y, 8) == BlockType.GRASS
                && protectedGround.world.getBlock(8, Y + 2, 8) == BlockType.LEAVES, "protected fuel or ground changed");
        Fixture occupied = new Fixture(true, true);
        occupied.burn(8, 8);
        occupied.world.setBlock(8, Y, 8, BlockType.STONE);
        occupied.world.setBlock(9, Y, 9, BlockType.DIRT);
        occupied.days(7);
        check(occupied.world.getBlock(8, Y, 8) == BlockType.STONE && occupied.world.getBlock(9, Y, 9) == BlockType.DIRT,
                "daily recovery overwrote replacement or foreign player ground");
    }

    private static Map<String, byte[]> records(byte[]... scars) throws Exception {
        Fixture original = new Fixture(true, true);
        Map<String, byte[]> sections = original.sections();
        EcologyCodec.Snapshot snapshot = EcologyCodec.decode(sections.get(Ecology.SAVE_SECTION));
        List<EcologyRegion> regions = snapshot.regions();
        regions.stream().filter(r -> r.key() == REGION).findFirst().orElseThrow().scars(List.of(scars));
        sections.put(Ecology.SAVE_SECTION, EcologyCodec.encode(new EcologyCodec.Snapshot(regions, snapshot.reservations(), snapshot.tail())));
        return sections;
    }

    private static byte[] scarRecord() throws Exception {
        Fixture fixture = new Fixture(true, true); fixture.burn(8, 8); return fixture.scars().get(0).encode();
    }

    private static void newer() throws Exception {
        byte[] future = scarRecord(); future[4] = (byte) (BurnScars.VERSION + 1);
        Map<String, byte[]> sections = records(future);
        Fixture frozen = new Fixture(true, false); frozen.restore(sections);
        check(!frozen.ecology.supported() && !frozen.ecology.enabled(), "newer scar history was simulated");
        frozen.session.tickMobs(.05f);
        try (Disk disk = new Disk()) {
            disk.save.saveLevel("world", level(frozen, frozen.sections())); disk.save.flushAndAwait();
            Map<String, byte[]> saved = disk.reopen().loadLevel("world").extraSections;
            check(Arrays.equals(saved.get(Ecology.SAVE_SECTION), sections.get(Ecology.SAVE_SECTION))
                    && Arrays.equals(saved.get("rules"), sections.get("rules")), "newer scar ecology/rules changed through actual save");
        }
        byte[] unknown = { 3, 2, 1, 7, 8 }, known = scarRecord();
        byte[] suffix = { 8, 0, 9 };
        byte[] withTail = Arrays.copyOf(known, known.length + suffix.length);
        System.arraycopy(suffix, 0, withTail, known.length, suffix.length);
        Fixture preserved = new Fixture(true, false); preserved.restore(records(unknown, withTail));
        preserved.days(1);
        EcologyRegion saved = EcologyCodec.decode(preserved.sections().get(Ecology.SAVE_SECTION)).regions().stream()
                .filter(r -> r.key() == REGION).findFirst().orElseThrow();
        check(saved.scars().stream().anyMatch(raw -> Arrays.equals(raw, unknown))
                && saved.scars().stream().map(raw -> {
                    try { return BurnScars.Scar.decode(raw); } catch (Exception fail) { throw new AssertionError(fail); }
                }).anyMatch(s -> s != null && Arrays.equals(s.tail(), suffix)), "unknown record or known suffix was lost during actual daily replay");
    }

    private static void corrupt() throws Exception {
        byte[] corrupt = scarRecord(); corrupt[21] = 0;
        Map<String, byte[]> sections = records(corrupt);
        Fixture rejected = new Fixture(true, false);
        rejected.ecology.restore(sections, bytes -> { throw new IllegalStateException("evidence unavailable"); });
        check(!rejected.ecology.supported() && !rejected.ecology.enabled(), "failed durable evidence allowed scar rebuild");
        Map<String, byte[]> result = new LinkedHashMap<>(sections); rejected.ecology.writeSections(result);
        check(Arrays.equals(result.get(Ecology.SAVE_SECTION), sections.get(Ecology.SAVE_SECTION)), "unpreserved damage was overwritten");
        try (Disk disk = new Disk()) {
            Path evidence = disk.root.resolve("ecology-corrupt.bin");
            Fixture rebuilt = new Fixture(true, false);
            rebuilt.ecology.restore(sections, bytes -> {
                try { Files.write(evidence, bytes); }
                catch (java.io.IOException failed) { throw new java.io.UncheckedIOException(failed); }
            });
            check(rebuilt.ecology.supported() && Arrays.equals(Files.readAllBytes(evidence), sections.get(Ecology.SAVE_SECTION))
                    && rebuilt.scars().isEmpty(), "known corrupt data were not durably quarantined before rebuild");
        }
    }

    private static void arrival() throws Exception {
        Fixture fixture = new Fixture(true, true); fixture.burn(8, 8);
        try (Disk disk = new Disk()) {
            disk.save.saveChunkAsync("world", fixture.session.snapshotChunk(fixture.world.getChunkIfExists(0, 0)));
            disk.save.flushAndAwait();
            fixture.world.removeChunk(0, 0); fixture.days(7);
            check(fixture.scars().get(0).lastProcessedDay() == 0, "unloaded scar consumed its recovery days");
            ChunkLoader loader = new ChunkLoader(fixture.world, null, disk.save, "world"); loader.setMeshing(false);
            try {
                loader.loadNow(0, 0);
                long day = Ecology.day(fixture.clock.gameTime());
                fixture.session.tickMobs(.05f);
                check(Ecology.day(fixture.clock.gameTime()) == day && fixture.scars().get(0).lastProcessedDay() == 7
                        && fixture.world.getBlock(8, Y, 8) == BlockType.GRASS, "same-day arrival failed to recover saved scar ground");
            } finally { loader.shutdown(); }
        }
    }

    private static void flee() {
        Fixture fixture = new Fixture(true, true);
        Mob cow = fixture.cow(9.5f, 8.5f); long id = cow.id;
        int population = fixture.region().population(COW), protectedCount = fixture.region().protectedCount(COW);
        fixture.burn(8, 8);
        fixture.world.getChunk(2, 0);
        // All eight first-ring choices are damaged; a larger loaded clear escape
        // must still route this same body through ordinary navigation.
        for (int[] site : new int[][] { { 21, 8 }, { 17, 16 }, { 17, 0 }, { 9, 20 },
                { 9, -4 }, { 1, 16 }, { 1, 0 }, { -3, 8 } }) fixture.burn(site[0], site[1]);
        Vector3f before = new Vector3f(cow.position);
        fixture.ecology.tickBurnScars(.6f);
        check(cow.burnDestination != null && cow.position.equals(before), "assigning the escape route teleported the body");
        check(cow.burnDestination.distance(before) >= 23.5f, "blocked twelve-block escape choices prevented a larger clear route");
        boolean moved = false;
        for (int frame = 0; frame < 240; frame++) {
            Vector3f previous = new Vector3f(cow.position);
            fixture.step(1);
            check(cow.position.distance(previous) < .7f, "fleeing animal teleported instead of navigating");
            check(!BodyCollision.overlaps(fixture.world, cow.position.x, cow.position.y, cow.position.z, cow.type.width, cow.type.height),
                    "fleeing body was embedded in terrain");
            moved |= cow.position.distance(before) > 2;
        }
        check(moved && cow.position.distanceSquared(new Vector3f(8.5f, Y + 1.001f, 8.5f)) > 36,
                "actual mob brain did not leave the damaged grove: before=" + before + " position=" + cow.position
                        + " destination=" + cow.burnDestination + " moved=" + moved);
        check(cow.id == id && !cow.dead && fixture.entities.mobs.size() == 1
                && fixture.region().population(COW) == population && fixture.region().protectedCount(COW) == protectedCount,
                "fleeing lost, duplicated or killed a finite body");
        fixture.ecology.setEnabled(false);
        check(cow.burnDestination == null, "OFF retained an escape route");
    }

    private static void materialize() {
        Fixture fixture = new Fixture(true, true);
        fixture.player.position.set(-32.5f, Y + 1.001f, 8.5f);
        for (int cx = -4; cx <= 4; cx++) for (int cz = -4; cz <= 4; cz++) fixture.world.getChunk(cx, cz);
        for (int x = 4; x < 12; x++) for (int z = 4; z < 12; z++) fixture.burn(x, z);
        fixture.days(2);
        check(fixture.world.getBlock(8, Y, 8) == BlockType.GRASS && fixture.world.burnDamaged(8, Y, 8),
                "fixture must reach green but still damaged habitat before testing the materializer scar gate");
        class Stock implements Materializer.Populations {
            int claimed, opportunities;
            @Override public int available(MobType type, float x, float z) {
                if (type != MobType.COW || x < 4 || x >= 12 || z < 4 || z >= 12) return 0;
                opportunities++;
                return 3 - claimed;
            }
            @Override public boolean claim(Mob body) { body.id = ++claimed; return true; }
            @Override public int groupMin(MobType type) { return 1; }
            @Override public int groupMax(MobType type) { return 1; }
        }
        Stock stock = new Stock(); Materializer materializer = new Materializer(99); List<Mob> bodies = new ArrayList<>();
        for (int attempt = 0; attempt < 800; attempt++) materializer.tryMaterialize(fixture.world, bodies, fixture.participants, 1, stock);
        check(stock.opportunities > 0 && bodies.isEmpty() && stock.claimed == 0,
                "materialization added an animal on damaged ground or never exercised an eligible site");
        fixture.days(1);
        check(!fixture.world.burnDamaged(8, Y, 8), "recovered scar still rejects ground");
        for (int attempt = 0; attempt < 1600 && stock.claimed < 3; attempt++)
            materializer.tryMaterialize(fixture.world, bodies, fixture.participants, 1, stock);
        check(bodies.size() == 3 && stock.claimed == 3 && bodies.stream().map(m -> m.id).distinct().count() == 3,
                "ordinary finite materializer did not return unique animals to the recovered grove");
    }

    private static void parity() throws Exception {
        Fixture daily = new Fixture(true, true), advance = new Fixture(true, true);
        // Seed two's two non-overlapping sites have ordinary births on days three/five.
        // Four corner sites never roll a birth before day seven and would test only grass.
        int[][] sites = { { 4, 8 }, { 12, 8 } };
        for (int[] site : sites) {
            daily.burn(site[0], site[1]); advance.burn(site[0], site[1]);
        }
        for (int day = 1; day <= 7; day++) {
            daily.clock.addHours(24); daily.session.tickMobs(.05f); daily.session.tickScheduled();
        }
        advance.days(7); advance.session.tickScheduled();
        check(Arrays.equals(daily.sections().get(Ecology.SAVE_SECTION), advance.sections().get(Ecology.SAVE_SECTION)),
                "ordinary and advanced fire recovery/population history differ");
        for (Chunk a : daily.world.getLoadedChunks()) {
            Chunk b = advance.world.getChunkIfExists(a.cx, a.cz);
            check(Arrays.equals(a.copyBlocks(), b.copyBlocks()) && Arrays.equals(a.copyMeta(), b.copyMeta()), "ordinary and advanced scar terrain differs");
        }
        boolean tree = false;
        for (int[] site : sites) {
            check(daily.world.getBlock(site[0], Y, site[1]) == BlockType.GRASS
                            && advance.world.getBlock(site[0], Y, site[1]) == BlockType.GRASS,
                    "seven days did not restore actual scar grass");
            tree |= daily.world.getBlock(site[0], Y + 1, site[1]) == BlockType.WOOD
                    && advance.world.getBlock(site[0], Y + 1, site[1]) == BlockType.WOOD;
        }
        check(tree, "ordinary and advanced fixture did not exercise an actually grown tree by day seven");
    }

    private static LevelData level(Fixture fixture, Map<String, byte[]> sections) {
        GameMode mode = GameMode.values()[java.nio.ByteBuffer.wrap(sections.getOrDefault("rules", new byte[4])).getInt()];
        return new LevelData("burn-runtime", fixture.world.seed, 24.5, Y + 1, 24.5, fixture.clock.gameTimeFloat(),
                mode, 0, PlayerRecord.builder().pose(24.5, Y + 1, 24.5, 0, 0, 0).build(), sections);
    }

    private static void reopen() throws Exception {
        Fixture before = new Fixture(true, true); Mob cow = before.cow(24.5f, 8.5f);
        for (int x : new int[] { 4, 8, 12 }) for (int z : new int[] { 4, 8, 12 }) before.burn(x, z);
        before.days(7);
        BurnScars.Site planted = before.scars().get(0).sites().stream().filter(BurnScars.Site::plantedOnce).findFirst().orElseThrow();
        int plantX = planted.localX(), plantZ = planted.localZ();
        long id = cow.id;
        try (Disk disk = new Disk()) {
            for (Chunk chunk : before.world.getLoadedChunks()) disk.save.saveChunkAsync("world", before.session.snapshotChunk(chunk));
            Map<String, byte[]> sections = before.sections();
            disk.save.saveLevel("world", level(before, sections)); disk.save.flushAndAwait();
            SaveManager manager = disk.reopen(); LevelData loaded = manager.loadLevel("world");
            check(loaded != null, "level did not reopen");
            WorldClock clock = WorldClock.fromSaved(loaded.timeOfDay, loaded.extraSections.get(WorldClock.SAVE_SECTION));
            Fixture after = new Fixture(false, false, clock); after.restore(loaded.extraSections);
            ChunkLoader loader = new ChunkLoader(after.world, null, manager, "world"); loader.setMeshing(false);
            try {
                for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) after.session.adoptChunkItems(loader.loadNow(cx, cz));
                int population = after.region().population(COW);
                check(after.entities.mobs.size() == 1 && after.entities.mobs.get(0).id == id
                        && after.scars().get(0).sites().stream().anyMatch(s -> s.index() == planted.index() && s.plantedOnce()),
                        "actual reopening lost the finite animal ID or scar planted flag");
                for (Chunk chunk : after.world.getLoadedChunks()) after.session.adoptChunkItems(chunk);
                check(after.entities.mobs.size() == 1 && after.region().population(COW) == population,
                        "repeated chunk adoption duplicated a finite animal");
                after.world.setBlock(plantX, Y + 1, plantZ, BlockType.AIR);
                after.days(60); after.session.tickScheduled();
                check(after.world.getBlock(plantX, Y + 1, plantZ) != BlockType.SAPLING
                        && after.scars().get(0).sites().stream().anyMatch(s -> s.index() == planted.index() && s.plantedOnce()),
                        "reopening and removing the first seedling recreated it from the same scar");
            } finally { loader.shutdown(); }
        }
    }

    private static void bodies() {
        Fixture fixture = new Fixture(true, true);
        fixture.world.setBlock(8, Y + 1, 8, BlockType.SAPLING, (byte) SaplingBehavior.NATURAL_SCAR);
        Behaviors.of(BlockType.SAPLING).onPlaced(fixture.world, 8, Y + 1, 8, (byte) SaplingBehavior.NATURAL_SCAR);
        fixture.player.position.set(8.5f, Y + 1.001f, 8.5f);
        fixture.world.setSimulationDaylight(1);
        long due = fixture.world.getChunkIfExists(0, 0).scheduledTicks().due(Chunk.idx(8, Y + 1, 8), BlockType.SAPLING.ordinal());
        fixture.world.runScheduledTicks(due, 512, WorldSession.MAX_TICK_LATENESS);
        check(fixture.world.getBlock(8, Y + 1, 8) == BlockType.SAPLING, "natural trunk grew through an actual participant");
        fixture.player.position.set(24.5f, Y + 1.001f, 24.5f); Mob cow = fixture.cow(8.5f, 8.5f);
        due = fixture.world.getChunkIfExists(0, 0).scheduledTicks().due(Chunk.idx(8, Y + 1, 8), BlockType.SAPLING.ordinal());
        fixture.world.runScheduledTicks(due, 512, WorldSession.MAX_TICK_LATENESS);
        check(fixture.world.getBlock(8, Y + 1, 8) == BlockType.SAPLING, "natural trunk grew through an actual finite animal");
        cow.position.set(24.5f, Y + 1.001f, 8.5f);
        due = fixture.world.getChunkIfExists(0, 0).scheduledTicks().due(Chunk.idx(8, Y + 1, 8), BlockType.SAPLING.ordinal());
        fixture.world.runScheduledTicks(due, 512, WorldSession.MAX_TICK_LATENESS);
        check(fixture.world.getBlock(8, Y + 1, 8) == BlockType.WOOD && !BodyCollision.overlaps(fixture.world,
                cow.position.x, cow.position.y, cow.position.z, cow.type.width, cow.type.height), "tree failed to resume safely after bodies left");
    }

    private static final class Disk implements AutoCloseable {
        final Path root;
        final SaveManager save;
        final List<SaveManager> managers = new ArrayList<>();
        Disk() throws Exception {
            Files.createDirectories(Path.of("out-test")); root = Files.createTempDirectory(Path.of("out-test"), "burn-runtime-");
            save = new SaveManager(root.toFile()); managers.add(save);
        }
        SaveManager reopen() { SaveManager manager = new SaveManager(root.toFile()); managers.add(manager); return manager; }
        @Override public void close() throws Exception {
            var writer = SaveManager.class.getDeclaredField("chunkWriter"); writer.setAccessible(true);
            for (SaveManager manager : managers) { manager.flushAndAwait(); ((ExecutorService) writer.get(manager)).shutdown(); }
            try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
    }
}

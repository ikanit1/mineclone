package com.mineclone;

import com.mineclone.save.ChunkSnapshot;
import com.mineclone.save.ItemStackCodec;
import com.mineclone.save.SaveManager;
import com.mineclone.game.Player;
import com.mineclone.game.Raycaster;
import com.mineclone.item.Components;
import com.mineclone.item.Items;
import com.mineclone.item.loot.LootContext;
import com.mineclone.net.Multiplayer;
import com.mineclone.sim.EntityStore;
import com.mineclone.sim.Participants;
import com.mineclone.sim.WorldClock;
import com.mineclone.sim.WorldEvents;
import com.mineclone.sim.WorldSession;
import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.ChunkLoader;
import com.mineclone.world.ChunkMesher;
import com.mineclone.world.GameMode;
import com.mineclone.world.Inventory;
import com.mineclone.world.ItemStack;
import com.mineclone.world.World;
import com.mineclone.world.behavior.Behaviors;
import com.mineclone.world.behavior.DropSink;
import com.mineclone.world.behavior.SaplingBehavior;
import com.mineclone.world.entity.MobSpawner;
import com.mineclone.world.gen.TreeShapes;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.joml.Vector3f;

/** LW-08 seedlings use the real saved dispatcher without replacing occupied or protected cells. */
final class BurnGrowthTests {
    private static final int X = 8, Y = 41, Z = 8;
    private static final long DAY = WorldClock.TICKS_PER_DAY;
    private static final byte NATURAL = (byte) SaplingBehavior.NATURAL_SCAR;

    static void runAll(TestMain.Runner runner) {
        runner.run("scar seedlings retain the legacy deterministic delay and grow in one observer batch", BurnGrowthTests::ordinary);
        runner.run("a seedling born on the target replay day waits its own complete growth delay", BurnGrowthTests::birth);
        runner.run("a seedling born earlier in replay catches up once through the normal bounded dispatcher", BurnGrowthTests::late);
        runner.run("seedling crowns refuse protected chunks and missing chunks without partial growth", BurnGrowthTests::protectedCrown);
        runner.run("natural seedlings never replace another sapling or a player's leaves and blocks", BurnGrowthTests::occupied);
        runner.run("natural seedlings pause with Living World while player-planted trees keep growing", BurnGrowthTests::disabled);
        runner.run("late seedlings defer in current darkness instead of fabricating historical sunlight", BurnGrowthTests::night);
        runner.run("natural seedling identity and remaining due time survive actual save and reopen", BurnGrowthTests::saved);
        runner.run("harvested and collapsed natural seedlings drop their species without carrying world flags", BurnGrowthTests::harvested);
        runner.run("Ctrl-picked and saved natural seedlings become player trees that grow with Living World off", BurnGrowthTests::picked);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static World terrain() {
        return new World(71) {
            @Override public Chunk generateDetached(int cx, int cz) {
                Chunk chunk = new Chunk(cx, cz);
                for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
                    chunk.set(x, Y - 1, z, BlockType.GRASS);
                chunk.computeSkyLight();
                return chunk;
            }
        };
    }

    private static World world() {
        World world = terrain();
        for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) world.getChunk(cx, cz);
        world.simulate(DropSink.NONE, 0);
        world.setLivingWorldEnabled(true);
        world.setSimulationDaylight(1);
        return world;
    }

    private static void plant(World world, int x, byte meta) {
        world.setBlock(x, Y, Z, BlockType.SAPLING, meta);
        Behaviors.of(BlockType.SAPLING).onPlaced(world, x, Y, Z, meta);
    }

    private static void tick(World world, long at) {
        world.runScheduledTicks(at, WorldSession.TICK_BUDGET, WorldSession.MAX_TICK_LATENESS);
    }

    private static long due(World world, int x) {
        return world.getChunkIfExists(Math.floorDiv(x, 16), 0).scheduledTicks()
                .due(Chunk.idx(Math.floorMod(x, 16), Y, Z), BlockType.SAPLING.ordinal());
    }

    private static Map<Long, byte[]> blocks(World world) {
        Map<Long, byte[]> result = new LinkedHashMap<>();
        for (Chunk chunk : world.getLoadedChunks()) result.put(World.key(chunk.cx, chunk.cz), chunk.copyBlocks());
        return result;
    }

    private static void unchanged(World world, Map<Long, byte[]> before, String message) {
        check(blocks(world).size() == before.size(), message + ": generated terrain");
        for (Chunk chunk : world.getLoadedChunks())
            check(Arrays.equals(before.get(World.key(chunk.cx, chunk.cz)), chunk.copyBlocks()), message);
    }

    private static void ordinary() {
        World world = world();
        long hash = world.seed ^ X * 341873128712L ^ Z * 132897987541L ^ Y * 524287L;
        long delay = SaplingBehavior.growthDelay(world, X, Y, Z);
        check(delay == 6000 + Math.floorMod(hash, 6001), "changed the legacy growth formula");
        plant(world, X, NATURAL);
        final int[] batches = { 0 };
        world.setBlockObserver(new World.BlockObserver() {
            @Override public void changed(int x, int y, int z, BlockType old, BlockType now, byte meta) { }
            @Override public void batch(java.util.List<World.BlockChange> changes) { batches[0]++; }
        });
        tick(world, delay - 1);
        check(world.getBlock(X, Y, Z) == BlockType.SAPLING, "seedling grew early");
        tick(world, delay);
        check(world.getBlock(X, Y, Z) == BlockType.WOOD && batches[0] == 1, "tree not grown as one complete batch");
        check(world.pendingScheduledTicks() == 0, "grown tree retained a seedling tick");
    }

    private static void birth() {
        World world = world();
        WorldClock clock = new WorldClock(Math.PI / 2);
        clock.addHours(24 * 7);
        world.setBlock(X, Y, Z, BlockType.SAPLING, NATURAL);
        SaplingBehavior.scheduleNatural(world, X, Y, Z, 7, clock);
        long expected = clock.worldTicks() + SaplingBehavior.growthDelay(world, X, Y, Z);
        check(due(world, X) == expected, "birth counted from the old dispatcher instead of logical day seven");
        tick(world, clock.worldTicks());
        check(world.getBlock(X, Y, Z) == BlockType.SAPLING, "target-day seedling caught up before birth");
        tick(world, expected - 1);
        check(world.getBlock(X, Y, Z) == BlockType.SAPLING, "seedling did not wait its entire own delay");
        tick(world, expected);
        check(world.getBlock(X, Y, Z) == BlockType.WOOD, "target-day birth never grew");
        check(clock.logicalDayTicks(7) == clock.worldTicks() && clock.logicalDayTicks(8) == clock.worldTicks(),
                "present or future logical day changed the tick origin");
        try {
            clock.logicalDayTicks(-1);
            throw new AssertionError("negative logical day accepted");
        } catch (IllegalArgumentException expectedInvalid) { }
        WorldClock origin = new WorldClock(7 * Math.PI * 2);
        check(origin.logicalDayTicks(1) == 0, "replay before clock origin did not clamp to zero");
        long largeTicks = (1L << 54) + 19;
        WorldClock precise = WorldClock.synced(7 * Math.PI * 2 + .3, largeTicks);
        check(precise.logicalDayTicks(7) == largeTicks
                        && precise.logicalDayTicks(6) == largeTicks - (long) Math.ceil(Math.PI * 2 / WorldClock.TIME_SCALE * WorldClock.TICKS_PER_SECOND),
                "logical-day anchoring rounded away the integer precision of a long-lived clock");
    }

    private static void late() {
        World ordinary = world();
        WorldClock normalClock = new WorldClock(Math.PI / 2);
        normalClock.addHours(24 * 3);
        tick(ordinary, normalClock.worldTicks());
        ordinary.setBlock(X, Y, Z, BlockType.SAPLING, NATURAL);
        SaplingBehavior.scheduleNatural(ordinary, X, Y, Z, 3, normalClock);
        long expectedDue = due(ordinary, X);
        tick(ordinary, due(ordinary, X));
        World replay = world();
        WorldClock replayClock = new WorldClock(Math.PI / 2);
        replayClock.addHours(24 * 7);
        replay.setBlock(X, Y, Z, BlockType.SAPLING, NATURAL);
        SaplingBehavior.scheduleNatural(replay, X, Y, Z, 3, replayClock);
        check(due(replay, X) == expectedDue, "ordinary and replay logical births differ by rounded game-day ticks");
        check(due(replay, X) < replayClock.worldTicks() - WorldSession.MAX_TICK_LATENESS,
                "fixture did not reach bounded lateness");
        tick(replay, replayClock.worldTicks());
        check(replay.getBlock(X, Y, Z) == BlockType.WOOD && replay.pendingScheduledTicks() == 0,
                "one late growth either failed or repeated");
        var expected = blocks(ordinary);
        unchanged(replay, expected, "late growth differs from ordinary tree cells");
    }

    private static void protectedCrown() {
        for (byte meta : new byte[] { NATURAL, 0 }) {
            World world = world();
            plant(world, 15, meta);
            world.getChunkIfExists(1, 0).markReadOnly();
            var before = blocks(world);
            tick(world, due(world, 15));
            unchanged(world, before, "tree crossed a protected crown chunk or left a partial trunk");
            check(world.pendingScheduledTicks() == 1, "blocked tree lost its later growth chance");
        }
        World missing = world();
        plant(missing, 15, NATURAL);
        missing.removeChunk(1, 0);
        var before = blocks(missing);
        tick(missing, due(missing, 15));
        unchanged(missing, before, "tree grew through an unloaded crown chunk");
        World frozen = world();
        plant(frozen, X, NATURAL);
        frozen.getChunkIfExists(0, 0).markReadOnly();
        var frozenBlocks = blocks(frozen);
        Behaviors.of(BlockType.SAPLING).scheduledTick(frozen, X, Y, Z, NATURAL, Long.MAX_VALUE);
        unchanged(frozen, frozenBlocks, "protected seedling anchor grew");
        long frozenDue = due(frozen, X);
        SaplingBehavior.scheduleNatural(frozen, X, Y, Z, 10, new WorldClock());
        check(due(frozen, X) == frozenDue, "absolute schedule changed a protected anchor");
    }

    private static TreeShapes.Cell crown(World world) {
        long hash = world.seed ^ X * 341873128712L ^ Z * 132897987541L ^ Y * 524287L;
        return TreeShapes.cells(0, X, Y - 1, Z, hash).stream()
                .filter(cell -> cell.block() == BlockType.LEAVES && cell.x() != X).findFirst().orElseThrow();
    }

    private static void occupied() {
        for (BlockType obstacle : new BlockType[] { BlockType.LEAVES, BlockType.SAPLING, BlockType.GLASS, BlockType.CHEST }) {
            World world = world();
            plant(world, X, NATURAL);
            TreeShapes.Cell cell = crown(world);
            world.setBlock(cell.x(), cell.y(), cell.z(), obstacle, (byte) 2);
            var before = blocks(world);
            tick(world, due(world, X));
            unchanged(world, before, "natural crown overwrote " + obstacle);
            check(world.getBlockMeta(cell.x(), cell.y(), cell.z()) == 2, "obstacle metadata changed");
        }
        World player = world();
        plant(player, X, (byte) 0);
        TreeShapes.Cell cell = crown(player);
        player.setBlock(cell.x(), cell.y(), cell.z(), BlockType.LEAVES);
        tick(player, due(player, X));
        check(player.getBlock(X, Y, Z) == BlockType.WOOD, "legacy player tree no longer grows through existing leaves");
        World occupied = world();
        plant(occupied, X, NATURAL);
        TreeShapes.Cell bodyCell = crown(occupied);
        occupied.setNaturalGrowthSpace((x, y, z) -> x != bodyCell.x() || y != bodyCell.y() || z != bodyCell.z());
        var before = blocks(occupied);
        tick(occupied, due(occupied, X));
        unchanged(occupied, before, "tree made a partial trunk before encountering an occupied crown cell");
        occupied.setNaturalGrowthSpace((x, y, z) -> true);
        tick(occupied, due(occupied, X));
        check(occupied.getBlock(X, Y, Z) == BlockType.WOOD, "tree did not resume after a body cleared its crown");
    }

    private static void disabled() {
        World world = world();
        plant(world, X, NATURAL);
        world.setLivingWorldEnabled(false);
        long first = due(world, X);
        tick(world, first);
        check(world.getBlock(X, Y, Z) == BlockType.SAPLING && due(world, X) > first,
                "OFF grew a natural seedling or lost its tick");
        world.setLivingWorldEnabled(true);
        tick(world, due(world, X));
        check(world.getBlock(X, Y, Z) == BlockType.WOOD, "ON did not resume growth");
        World player = world();
        plant(player, X, (byte) 0);
        player.setLivingWorldEnabled(false);
        tick(player, due(player, X));
        check(player.getBlock(X, Y, Z) == BlockType.WOOD, "OFF stopped player farming");
        World unsupported = world();
        plant(unsupported, X, NATURAL);
        unsupported.setBlock(X, Y - 1, Z, BlockType.AIR);
        unsupported.setLivingWorldEnabled(false);
        Behaviors.of(BlockType.SAPLING).scheduledTick(unsupported, X, Y, Z, NATURAL, 0);
        check(unsupported.getBlock(X, Y, Z) == BlockType.SAPLING, "OFF callback mutated an unsupported seedling");
    }

    private static void night() {
        World world = world();
        plant(world, X, NATURAL);
        world.setSimulationDaylight(0);
        tick(world, 7 * DAY);
        check(world.getBlock(X, Y, Z) == BlockType.SAPLING && world.pendingScheduledTicks() == 1,
                "late growth invented sunlight");
        world.setSimulationDaylight(1);
        tick(world, due(world, X));
        check(world.getBlock(X, Y, Z) == BlockType.WOOD, "tree did not grow when light returned");
    }

    /** Exercise the real controller's click paths without constructing a native Game window. */
    private static final class Clicks {
        final World world = world();
        final Player player = new Player();
        final Inventory inventory = new Inventory();
        final List<ItemStack> drops = new ArrayList<>();
        final Class<?> controllerType = Class.forName("com.mineclone.game.InteractionController");
        final Object controller;
        int selected;

        Clicks(GameMode mode) throws Exception {
            player.setGameMode(mode);
            Class<?> hostType = Class.forName("com.mineclone.game.InteractionController$Host");
            Object host = Proxy.newProxyInstance(hostType.getClassLoader(), new Class<?>[] { hostType },
                    (proxy, method, arguments) -> switch (method.getName()) {
                        case "world" -> world;
                        case "player" -> player;
                        case "gameMode" -> mode;
                        case "inventory" -> inventory;
                        case "selectedSlot" -> selected;
                        case "pickedSlot" -> { selected = (int) arguments[0]; yield null; }
                        case "itemRandom" -> new SplittableRandom(17);
                        case "drop" -> { drops.add(((ItemStack) arguments[0]).copy()); yield null; }
                        default -> method.getReturnType() == boolean.class ? false : null;
                    });
            var constructor = controllerType.getDeclaredConstructor(hostType,
                    Class.forName("com.mineclone.core.Input"), Multiplayer.class);
            constructor.setAccessible(true);
            controller = constructor.newInstance(host, null, new Multiplayer(null));
        }

        void breakSeedling() throws Exception {
            var method = controllerType.getDeclaredMethod("breakBlock", World.class,
                    int.class, int.class, int.class, BlockType.class);
            method.setAccessible(true);
            method.invoke(controller, world, X, Y, Z, BlockType.SAPLING);
        }

        ItemStack pickSeedling() throws Exception {
            var method = controllerType.getDeclaredMethod("pickBlock", World.class,
                    int.class, int.class, int.class, boolean.class);
            method.setAccessible(true);
            method.invoke(controller, world, X, Y, Z, true);
            return inventory.get(selected);
        }

        void placeBesideStone() throws Exception {
            world.setBlock(X - 1, Y, Z, BlockType.STONE);
            var hit = controllerType.getDeclaredField("hit");
            hit.setAccessible(true);
            hit.set(controller, new Raycaster.Hit(X - 1, Y, Z, 1, 0, 0, 1));
            var method = controllerType.getDeclaredMethod("place", World.class, Player.class, BlockType.class);
            method.setAccessible(true);
            method.invoke(controller, world, player, BlockType.STONE);
            world.setBlock(X - 1, Y, Z, BlockType.AIR);
        }
    }

    private static void speciesDrop(List<ItemStack> drops, String id, String path) {
        check(drops.size() == 1 && drops.get(0).count == 1 && drops.get(0).item.id.path().equals(id),
                path + " lost or changed the " + id + " drop: " + drops);
        check(drops.get(0).get(Components.BLOCK_STATE) == null, path + " put a world-only flag in the item");
    }

    private static void harvested() throws Exception {
        String[] species = { "oak_sapling", "spruce_sapling", "acacia_sapling" };
        for (int kind = 0; kind < species.length; kind++) {
            byte meta = (byte) (NATURAL | kind);
            Clicks hand = new Clicks(GameMode.SURVIVAL);
            hand.world.setBlock(X, Y, Z, BlockType.SAPLING, meta);
            hand.breakSeedling();
            check(hand.world.getBlock(X, Y, Z) == BlockType.AIR, "hand did not remove seedling");
            speciesDrop(hand.drops, species[kind], "hand break");
            World collapsed = world();
            collapsed.setBlock(X, Y, Z, BlockType.SAPLING, meta);
            List<ItemStack> fallen = new ArrayList<>();
            Behaviors.of(BlockType.SAPLING).collapse(collapsed, X, Y, Z, meta,
                    (stack, x, y, z) -> fallen.add(stack));
            speciesDrop(fallen, species[kind], "support collapse");
            LootContext context = new LootContext(71, X, Y, Z, null, false, GameMode.SURVIVAL, null, meta);
            speciesDrop(Items.get().loot().explosionDrops(BlockType.SAPLING, context), species[kind], "explosion");
            check(Items.get().loot().blockSources(BlockType.SAPLING, context).equals(
                    java.util.Set.of(Items.get().require(species[kind]))), "source query lost natural species");
        }
    }

    private static void picked() throws Exception {
        for (int kind = 0; kind <= 2; kind++) {
            Clicks creative = new Clicks(GameMode.CREATIVE);
            byte natural = (byte) (NATURAL | kind);
            creative.world.setBlock(X, Y, Z, BlockType.SAPLING, natural);
            ItemStack carried = creative.pickSeedling();
            check(carried != null && carried.get(Components.BLOCK_STATE).meta() == natural,
                    "Ctrl-pick fixture did not capture raw natural block state");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) { ItemStackCodec.write(out, carried); }
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                carried = ItemStackCodec.read(in);
            }
            check(carried.get(Components.BLOCK_STATE).meta() == natural, "item save silently rewrote world metadata");
            creative.inventory.set(creative.selected, carried);
            creative.world.setBlock(X, Y, Z, BlockType.AIR);
            creative.placeBesideStone();
            check(creative.world.getBlock(X, Y, Z) == BlockType.SAPLING
                    && creative.world.getBlockMeta(X, Y, Z) == kind,
                    "player placement kept the natural flag or changed species " + kind);
            creative.world.setLivingWorldEnabled(false);
            tick(creative.world, due(creative.world, X));
            check(creative.world.getBlock(X, Y, Z) == BlockType.WOOD,
                    "OFF stopped a player tree copied from natural species " + kind);
        }
    }

    private static WorldSession session(World world, WorldClock clock) {
        WorldSession session = new WorldSession(world, clock, new MobSpawner(1), new EntityStore(),
                new Participants(), () -> new Vector3f(X, Y, Z), WorldEvents.NONE);
        world.setLivingWorldEnabled(true);
        return session;
    }

    private static void saved() throws Exception {
        Path root = Files.createTempDirectory("mineclone-burn-growth-");
        Files.createDirectories(root.resolve("world/chunks"));
        SaveManager save = new SaveManager(root.toFile());
        try {
            World original = terrain();
            for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) original.getChunk(cx, cz);
            // This fixture's deterministic due falls in daylight from the normal dawn start.
            WorldClock clock = new WorldClock();
            WorldSession first = session(original, clock);
            original.setBlock(X, Y, Z, BlockType.SAPLING, (byte) (NATURAL | 1));
            SaplingBehavior.scheduleNatural(original, X, Y, Z, 0, clock);
            long expected = due(original, X);
            clock.advance((expected / 2) / (double) WorldClock.TICKS_PER_SECOND);
            first.tickScheduled();
            ChunkSnapshot snapshot = first.snapshotChunk(original.getChunkIfExists(0, 0));
            check(snapshot != null && snapshot.ticks.length == 3 && snapshot.ticks[2] == expected,
                    "actual snapshot lost remaining growth tick");
            save.saveChunkAsync("world", snapshot);
            save.flushAndAwait();
            ChunkSnapshot read = save.loadChunk("world", 0, 0);
            check(read != null && read.meta[Chunk.idx(X, Y, Z)] == (NATURAL | 1)
                    && Arrays.equals(snapshot.ticks, read.ticks), "saved seedling flag/species/due changed");
            World reopened = terrain();
            ChunkLoader loader = new ChunkLoader(reopened, new ChunkMesher(reopened), save, "world");
            loader.setMeshing(false);
            try {
                for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) loader.loadNow(cx, cz);
                WorldClock resumed = WorldClock.synced(clock.gameTime(), clock.worldTicks());
                WorldSession second = session(reopened, resumed);
                resumed.advance((expected - 1 - resumed.worldTicks()) / (double) WorldClock.TICKS_PER_SECOND);
                second.tickScheduled();
                check(reopened.getBlock(X, Y, Z) == BlockType.SAPLING, "reopened seedling grew before saved due");
                resumed.advance(1.0 / WorldClock.TICKS_PER_SECOND);
                second.tickScheduled();
                check(reopened.getBlock(X, Y, Z) == BlockType.WOOD && reopened.pendingScheduledTicks() == 0,
                        "reopened seedling did not grow exactly once");
            } finally { loader.shutdown(); }
        } finally {
            save.flushAndAwait();
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}

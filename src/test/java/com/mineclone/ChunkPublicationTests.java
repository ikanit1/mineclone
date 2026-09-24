package com.mineclone;

import com.mineclone.net.Multiplayer;
import com.mineclone.net.PacketBuf;
import com.mineclone.save.ChunkLoad;
import com.mineclone.save.ChunkSnapshot;
import com.mineclone.save.SaveManager;
import com.mineclone.world.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Regression coverage for the generation -> restoration -> publication boundary. */
public final class ChunkPublicationTests {
    private static final String WORLD_ID = "publication";
    private static final int CHEST = Chunk.idx(1, 4, 1);
    private static final int FURNACE = Chunk.idx(2, 4, 1);

    public static void runAll(TestMain.Runner runner) {
        runner.run("saved chunks stay invisible through generation and restoration", ChunkPublicationTests::publicationLatch);
        runner.run("publication preserves its first winner and repeated loads preserve edits", ChunkPublicationTests::publicationWinner);
        runner.run("published containers reject worker access in checked mode", ChunkPublicationTests::threadOwnership);
        runner.run("the first network delta includes restored edits", ChunkPublicationTests::restoredNetworkDelta);
        runner.run("300 saved chunks with four workers preserve edits for 20 rounds", ChunkPublicationTests::stressPublication);
    }

    public static void main(String[] args) throws Exception {
        String previous = System.getProperty("mineclone.checkThreadOwnership");
        System.setProperty("mineclone.checkThreadOwnership", "true");
        try {
            publicationLatch();
            publicationWinner();
            threadOwnership();
            restoredNetworkDelta();
            stressPublication();
            System.out.println("CHUNK_PUBLICATION PASS: latches, ownership, delta, 300 chunks x 4 workers x 20 rounds");
        } finally { restoreProperty(previous); }
    }

    private static void publicationLatch() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ChunkSnapshot saved = initialSnapshot(0);
            fixture.save.saveChunkAsync(WORLD_ID, saved);
            fixture.save.flushAndAwait();
            BlockingWorld world = new BlockingWorld();
            ChunkLoader loader = fixture.loader(world);
            loader.ensureChunk(0, 0, 0, false);
            try {
                await(world.generated, "generation did not reach latch");
                check(world.getChunkIfExists(0, 0) == null, "terrain was visible before restoration");
                check(!world.getLoadedChunks().iterator().hasNext(), "unrestored terrain leaked into save iteration");
                world.allowRestore.countDown();
                await(world.restored, "restoration did not reach publication latch");
                check(world.getChunkIfExists(0, 0) == null, "snapshot was published before its finalization");
                assertSnapshot(world.candidate, saved, "detached snapshot");
                check(!world.candidate.modified, "restoration left detached chunk modified");
                check(world.candidate.getSkyLight(8, 127, 8) == Chunk.MAX_LIGHT, "local sky light missing before publish");
                // A frame may run exactly between registering the light barrier and publication.
                loader.drainLightFlood(8);
                check(loader.hasPendingLightFlood(World.key(0, 0)), "pre-publication light barrier was lost");
                world.allowPublish.countDown();
                waitUntil(() -> world.getChunkIfExists(0, 0) != null && loader.pendingGenCount() == 0,
                        "generation worker never published");
                Chunk visible = world.getChunkIfExists(0, 0);
                assertSnapshot(visible, saved, "published snapshot");
                loader.drainLightFlood(8);
                check(!loader.hasPendingLightFlood(World.key(0, 0)), "published light barrier did not drain");
            } finally {
                world.allowRestore.countDown();
                world.allowPublish.countDown();
            }
        }
    }

    private static final class BlockingWorld extends World {
        final CountDownLatch generated = new CountDownLatch(1), allowRestore = new CountDownLatch(1);
        final CountDownLatch restored = new CountDownLatch(1), allowPublish = new CountDownLatch(1);
        volatile Chunk candidate;
        BlockingWorld() { super(91234L); }
        @Override public Chunk generateDetached(int cx, int cz) {
            Chunk chunk = super.generateDetached(cx, cz);
            generated.countDown();
            awaitUnchecked(allowRestore);
            return chunk;
        }
        @Override public Chunk publish(Chunk chunk) {
            candidate = chunk;
            restored.countDown();
            awaitUnchecked(allowPublish);
            return super.publish(chunk);
        }
    }

    private static void publicationWinner() throws Exception {
        try (Fixture fixture = new Fixture()) {
            World world = new World(123);
            Chunk first = world.generateDetached(0, 0), loser = world.generateDetached(0, 0);
            first.set(8, 127, 8, BlockType.GLASS);
            check(world.getChunkIfExists(0, 0) == null, "detached generation inserted terrain");
            check(world.publish(first) == first, "first candidate was rejected");
            check(world.publish(loser) == first, "later candidate replaced an already visible chunk");
            check(world.getBlock(8, 127, 8) == BlockType.GLASS, "losing candidate erased existing edits");
            ChunkSnapshot old = initialSnapshot(0);
            fixture.save.saveChunkAsync(WORLD_ID, old);
            fixture.save.flushAndAwait();
            World loadedWorld = new World(123);
            ChunkLoader loader = fixture.loader(loadedWorld);
            Chunk loaded = loader.loadNow(0, 0);
            loadedWorld.setBlock(8, 127, 8, BlockType.GLASS);
            loaded.getChest(1, 4, 1)[0].count = 31;
            check(loader.loadNow(0, 0) == loaded, "repeat load changed chunk identity");
            check(loaded.get(8, 127, 8) == BlockType.GLASS && loaded.modified,
                    "repeat load erased block edit or modified flag");
            check(loaded.getChest(1, 4, 1)[0].count == 31, "repeat load erased container edits");
        }
    }

    private static void threadOwnership() throws Exception {
        String previous = System.getProperty("mineclone.checkThreadOwnership");
        System.setProperty("mineclone.checkThreadOwnership", "true");
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (Fixture fixture = new Fixture()) {
            World world = new World(42);
            ChunkLoader loader = fixture.loader(world);
            Chunk detached = worker.submit(() -> {
                Chunk chunk = world.generateDetached(0, 0);
                chunk.createChest(1, 4, 1)[0] = new ItemStack(BlockType.STONE, 2);
                chunk.createFurnace(2, 4, 1).cook = 0.5f;
                return chunk;
            }).get(15, TimeUnit.SECONDS);
            world.publish(detached);
            detached.createChest(3, 4, 1);
            check(detached.getChest(1, 4, 1)[0].count == 2, "worker-private container not published");
            expectIllegal(() -> world.getChunk(0, 0), "saved-world generation bypass was allowed");
            for (Runnable access : List.<Runnable>of(
                    () -> detached.createChest(4, 4, 1), () -> detached.removeChest(1, 4, 1),
                    () -> detached.restoreChests(null), detached::copyChests, detached::chests,
                    () -> detached.createFurnace(4, 4, 1), () -> detached.restoreFurnaces(null),
                    detached::copyFurnaces, detached::furnaces,
                    () -> detached.set(5, 4, 1, BlockType.STONE))) {
                check(worker.submit(() -> {
                    try { access.run(); return false; }
                    catch (IllegalStateException expected) { return true; }
                }).get(5, TimeUnit.SECONDS), "worker access to published mutable state was allowed");
            }
            check(loader.loadNow(0, 0) == detached, "checked load should return existing chunk");
        } finally {
            worker.shutdownNow();
            restoreProperty(previous);
        }
    }

    private static void restoredNetworkDelta() throws Exception {
        try (Fixture fixture = new Fixture()) {
            long seed = 29103L;
            Chunk saved = new World(seed).generateDetached(2, -3);
            saved.set(8, 127, 8, BlockType.STONE);
            saved.setMeta(8, 127, 8, (byte) 5);
            fixture.save.saveChunkAsync(WORLD_ID, snapshot(saved));
            fixture.save.flushAndAwait();
            World live = new World(seed);
            Chunk restored = fixture.loader(live).loadNow(2, -3);
            // Exercise the actual serializer used by queueChunkDelta for a guest's request.
            var buildDelta = Multiplayer.class.getDeclaredMethod("buildDelta", World.class,
                    int.class, int.class, byte[].class, byte[].class);
            buildDelta.setAccessible(true);
            PacketBuf encoded = (PacketBuf) buildDelta.invoke(null, new World(seed), 2, -3,
                    restored.copyBlocks(), restored.copyMeta());
            PacketBuf delta = PacketBuf.reading(encoded.toBytes());
            check(delta.readVarInt() == 1, "first guest delta omitted saved block edit");
            check(delta.readVarInt() == Chunk.idx(8, 127, 8), "delta edited wrong cell");
            check(delta.readU8() == BlockType.STONE.ordinal() && delta.readU8() == 5,
                    "delta did not preserve saved block and metadata");
            check(!delta.truncated() && !delta.hasMore(), "delta has invalid payload");
        }
    }

    private static void stressPublication() throws Exception {
        final int chunks = 300, workers = 4, rounds = 20;
        String previous = System.getProperty("mineclone.checkThreadOwnership");
        System.setProperty("mineclone.checkThreadOwnership", "true");
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try (Fixture fixture = new Fixture()) {
            ChunkSnapshot[] expected = new ChunkSnapshot[chunks];
            for (int i = 0; i < chunks; i++) {
                expected[i] = initialSnapshot(i);
                fixture.save.saveChunkAsync(WORLD_ID, expected[i]);
            }
            fixture.save.flushAndAwait();
            for (int round = 0; round < rounds; round++) {
                // Terrain cost is covered by the latch test and generation suite.
                // Here every load still reads a real gzip save and performs the full
                // restoration/light/publication path, concentrating on ownership races.
                World world = new World(8239) {
                    @Override public Chunk generateDetached(int cx, int cz) { return new Chunk(cx, cz); }
                };
                ChunkLoader loader = fixture.loader(world);
                AtomicInteger next = new AtomicInteger();
                ConcurrentLinkedQueue<Integer> published = new ConcurrentLinkedQueue<>();
                Semaphore outstanding = new Semaphore(16);
                CountDownLatch ready = new CountDownLatch(workers), start = new CountDownLatch(1);
                List<Future<?>> jobs = new ArrayList<>();
                for (int n = 0; n < workers; n++) jobs.add(pool.submit(() -> {
                    ready.countDown();
                    awaitUnchecked(start);
                    for (int i; (i = next.getAndIncrement()) < chunks;) {
                        try { outstanding.acquire(); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                        Chunk loaded = loader.loadNow(i % 20, i / 20);
                        check(loader.loadNow(loaded.cx, loaded.cz) == loaded, "duplicate concurrent load replaced chunk");
                        published.add(i);
                    }
                }));
                await(ready, "four generation workers did not start");
                start.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                int edited = 0;
                while (edited < chunks) {
                    Integer i = published.poll();
                    if (i == null) {
                        for (Future<?> job : jobs) if (job.isDone()) job.get();
                        check(System.nanoTime() < deadline, "publication stress timed out at round " + round);
                        Thread.sleep(1);
                        continue;
                    }
                    Chunk c = world.getChunkIfExists(i % 20, i / 20);
                    assertSnapshot(c, expected[i], "restored round " + round + " chunk " + i);
                    check(!c.modified, "published snapshot was spuriously modified");
                    world.setBlock(c.cx * 16 + 8, 127, c.cz * 16 + 8,
                            round % 2 == 0 ? BlockType.GLASS : BlockType.STONE, (byte) (round + 1));
                    c.getChest(1, 4, 1)[0].count++;
                    c.getFurnace(2, 4, 1).cook += 0.25f;
                    check(c.modified, "main-thread edit lost its save flag");
                    expected[i] = snapshot(c);
                    fixture.save.saveChunkAsync(WORLD_ID, expected[i]);
                    edited++;
                    outstanding.release();
                }
                for (Future<?> job : jobs) job.get(15, TimeUnit.SECONDS);
                fixture.save.flushAndAwait();
                for (int i = 0; i < chunks; i++) {
                    Chunk c = world.getChunkIfExists(i % 20, i / 20);
                    assertSnapshot(c, expected[i], "after all workers round " + round + " chunk " + i);
                    check(c.modified, "late restore cleared a player's modified flag");
                    ChunkLoad disk = fixture.save.readChunk(WORLD_ID, c.cx, c.cz);
                    check(disk instanceof ChunkLoad.Loaded, "stress save was unreadable");
                    assertSnapshots(((ChunkLoad.Loaded) disk).snapshot(), expected[i], "persisted edits");
                }
                loader.shutdown();
                fixture.loaders.remove(loader);
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
            restoreProperty(previous);
        }
    }

    private static ChunkSnapshot initialSnapshot(int i) {
        Chunk chunk = new Chunk(i % 20, i / 20);
        chunk.set(1, 4, 1, BlockType.CHEST);
        chunk.set(2, 4, 1, BlockType.FURNACE);
        chunk.set(3, 4, 1, BlockType.TORCH);
        chunk.setMeta(1, 4, 1, (byte) (i % 4));
        chunk.createChest(1, 4, 1)[0] = new ItemStack(BlockType.STONE, 1 + i % 20);
        Furnace furnace = chunk.createFurnace(2, 4, 1);
        furnace.input = new ItemStack(BlockType.SAND, 2);
        furnace.fuel = new ItemStack(BlockType.WOOD, 3);
        furnace.cook = 0.5f;
        furnace.burnLeft = 4;
        furnace.burnMax = 8;
        chunk.setPendingItems(List.of(new DroppedItem(new ItemStack(BlockType.COBBLE, 2),
                chunk.cx * 16 + 4.5f, 10, chunk.cz * 16 + 4.5f, 12)));
        return snapshot(chunk);
    }

    private static ChunkSnapshot snapshot(Chunk c) {
        return new ChunkSnapshot(c.cx, c.cz, c.copyBlocks(), c.copyMeta(),
                c.copyChests(), c.copyFurnaces(), c.copyPendingItems());
    }

    private static void assertSnapshot(Chunk c, ChunkSnapshot expected, String context) {
        check(c != null, context + ": missing chunk");
        assertSnapshots(snapshot(c), expected, context);
    }

    private static void assertSnapshots(ChunkSnapshot actual, ChunkSnapshot expected, String context) {
        check(Arrays.equals(actual.blocks, expected.blocks), context + ": blocks differ");
        check(Arrays.equals(actual.meta, expected.meta), context + ": metadata differs");
        check(actual.chests.keySet().equals(expected.chests.keySet()), context + ": chest set differs");
        if (expected.chests.containsKey(CHEST))
            check(actual.chests.get(CHEST)[0].count == expected.chests.get(CHEST)[0].count,
                    context + ": chest contents differ");
        check(actual.furnaces.keySet().equals(expected.furnaces.keySet()), context + ": furnace set differs");
        if (expected.furnaces.containsKey(FURNACE)) {
            Furnace a = actual.furnaces.get(FURNACE), e = expected.furnaces.get(FURNACE);
            check(a.cook == e.cook && a.burnLeft == e.burnLeft && a.burnMax == e.burnMax
                    && a.input.count == e.input.count && a.fuel.count == e.fuel.count,
                    context + ": furnace state differs");
        }
        check(actual.items.size() == expected.items.size(), context + ": item count differs");
        for (int i = 0; i < expected.items.size(); i++) {
            DroppedItem a = actual.items.get(i), e = expected.items.get(i);
            check(a.stack.count == e.stack.count && a.x == e.x && a.y == e.y && a.z == e.z && a.age == e.age,
                    context + ": pending item differs");
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Path root = Files.createTempDirectory("mineclone-publication-");
        final SaveManager save = new SaveManager(root.toFile());
        final List<ChunkLoader> loaders = new ArrayList<>();
        Fixture() throws Exception {}
        ChunkLoader loader(World world) {
            ChunkLoader loader = new ChunkLoader(world, null, save, WORLD_ID);
            loader.setMeshing(false);
            loaders.add(loader);
            return loader;
        }
        @Override public void close() throws Exception {
            for (ChunkLoader loader : loaders) loader.shutdown();
            save.flushAndAwait();
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void await(CountDownLatch latch, String message) throws InterruptedException {
        check(latch.await(15, TimeUnit.SECONDS), message);
    }
    private static void awaitUnchecked(CountDownLatch latch) {
        try { await(latch, "test latch timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    private static void waitUntil(java.util.function.BooleanSupplier predicate, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!predicate.getAsBoolean()) {
            check(System.nanoTime() < deadline, message);
            Thread.sleep(1);
        }
    }
    private static void expectIllegal(Runnable action, String message) {
        try { action.run(); }
        catch (IllegalStateException expected) { return; }
        throw new AssertionError(message);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void restoreProperty(String previous) {
        if (previous == null) System.clearProperty("mineclone.checkThreadOwnership");
        else System.setProperty("mineclone.checkThreadOwnership", previous);
    }
}

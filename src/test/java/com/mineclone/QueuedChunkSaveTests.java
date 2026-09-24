package com.mineclone;

import com.mineclone.save.*;
import com.mineclone.world.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Regression for evict/reload while a chunk's newest checkpoint is still queued. */
public final class QueuedChunkSaveTests {
    private static final int CHEST = Chunk.idx(1, 10, 1), FURNACE = Chunk.idx(2, 10, 1);

    public static void runAll(TestMain.Runner runner) {
        runner.run("evicted chunk reloads its newest queued checkpoint without waiting for disk", QueuedChunkSaveTests::evictReload);
        runner.run("queued chunk snapshots and read results never borrow mutable state", QueuedChunkSaveTests::detachedCopies);
        runner.run("completed earlier chunk write cannot erase a newer queued checkpoint", QueuedChunkSaveTests::supersedingWrite);
        runner.run("failed chunk write retains its latest checkpoint until a corrected save succeeds", QueuedChunkSaveTests::failedWrite);
        runner.run("pending chunk cannot hide an unreadable or newer disk file", QueuedChunkSaveTests::protectedRead);
    }

    public static void main(String[] args) {
        runAll((name, check) -> {
            try { check.run(); System.out.println("PASS " + name); }
            catch (Exception failure) { throw new AssertionError(name, failure); }
        });
    }

    private static void evictReload() throws Exception {
        try (Fixture f = new Fixture(); Gate gate = f.gate()) {
            gate.awaitEntered();
            World world = new World(17, GenFeatures.FLAT);
            ChunkLoader loader = new ChunkLoader(world, null, f.save, "world");
            loader.setMeshing(false);
            try {
                Chunk before = loader.loadNow(0, 0);
                before.set(4, 10, 4, BlockType.GLASS);
                before.getChest(1, 10, 1)[0].count = 31;
                f.save.saveChunkAsync("world", capture(before));
                loader.forget(World.key(0, 0)); world.removeChunk(0, 0);
                long started = System.nanoTime();
                Chunk restored = loader.loadNow(0, 0);
                check(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2), "read waited on blocked writer");
                check(restored.get(4, 10, 4) == BlockType.GLASS, "eviction restored stale disk blocks");
                check(restored.getChest(1, 10, 1)[0].count == 31, "eviction restored stale chest contents");
                restored.set(5, 10, 4, BlockType.TORCH);
                f.save.saveChunkAsync("world", capture(restored));
            } finally { loader.shutdown(); }
            gate.close(); f.save.flushAndAwait();
            var finalState = loaded(new SaveManager(f.root.toFile()).readChunk("world", 0, 0));
            check(finalState.blocks[Chunk.idx(4, 10, 4)] == (byte) BlockType.GLASS.ordinal(), "second save erased first edit");
            check(finalState.blocks[Chunk.idx(5, 10, 4)] == (byte) BlockType.TORCH.ordinal(), "second edit missing");
            check(finalState.chests.get(CHEST)[0].count == 31, "final chest lost queued edit");
        }
    }

    private static void detachedCopies() throws Exception {
        try (Fixture f = new Fixture(); Gate gate = f.gate()) {
            gate.awaitEntered();
            ChunkSnapshot input = snapshot(23);
            f.save.saveChunkAsync("world", input);
            mutate(input);
            ChunkSnapshot first = loaded(f.save.readChunk("world", 0, 0));
            pristine(first, 23);
            mutate(first);
            pristine(loaded(f.save.readChunk("world", 0, 0)), 23);
            gate.close(); f.save.flushAndAwait();
            pristine(loaded(new SaveManager(f.root.toFile()).readChunk("world", 0, 0)), 23);
        }
    }

    private static void supersedingWrite() throws Exception {
        try (Fixture f = new Fixture(); Gate first = f.gate()) {
            first.awaitEntered();
            f.save.saveChunkAsync("world", snapshot(21));
            try (Gate between = f.gate()) {
                f.save.saveChunkAsync("world", snapshot(22));
                first.close(); between.awaitEntered();
                check(loaded(new SaveManager(f.root.toFile()).readChunk("world", 0, 0)).chests.get(CHEST)[0].count == 21,
                        "test failed to stop between two writes");
                pristine(loaded(f.save.readChunk("world", 0, 0)), 22);
            }
            f.save.flushAndAwait();
            pristine(loaded(new SaveManager(f.root.toFile()).readChunk("world", 0, 0)), 22);
        }
    }

    private static void failedWrite() throws Exception {
        try (Fixture f = new Fixture()) {
            ChunkSnapshot original = snapshot(27);
            Map<String, byte[]> sections = new HashMap<>(original.extra);
            // Reach the worker's real codec/write IOException without OS ACLs or disk exhaustion.
            sections.put("future:oversized", new byte[com.mineclone.data.SectionCodec.MAX_SECTION_BYTES + 1]);
            f.save.saveChunkAsync("world", new ChunkSnapshot(0, 0, original.blocks, original.meta,
                    original.chests, original.furnaces, original.items, sections));
            f.save.flushAndAwait();
            check(f.save.writeMetrics().failedWrites() == 1, "failure injection did not reach the actual writer");
            pristine(loaded(new SaveManager(f.root.toFile()).readChunk("world", 0, 0)), 12);
            pristine(loaded(f.save.readChunk("world", 0, 0)), 27);
            f.save.saveChunkAsync("world", snapshot(28));
            f.save.flushAndAwait();
            pristine(loaded(new SaveManager(f.root.toFile()).readChunk("world", 0, 0)), 28);
            var field = SaveManager.class.getDeclaredField("pendingChunks"); field.setAccessible(true);
            check(((Map<?, ?>) field.get(f.save)).isEmpty(), "successful retry retained stale checkpoint memory");
        }
    }

    private static void protectedRead() throws Exception {
        for (boolean future : new boolean[] {false, true}) {
            try (Fixture f = new Fixture(); Gate gate = f.gate()) {
                gate.awaitEntered();
                f.save.saveChunkAsync("world", snapshot(42));
                Path path = f.root.resolve("world/chunks").resolve(SaveFormat.chunkFileName(0, 0));
                if (future) {
                    try (var out = new java.io.DataOutputStream(new java.util.zip.GZIPOutputStream(Files.newOutputStream(path)))) {
                        out.writeInt(SaveFormat.MAGIC); out.writeInt(100); out.writeInt(100);
                    }
                } else Files.write(path, new byte[] {1, 2, 3});
                byte[] original = Files.readAllBytes(path);
                ChunkLoad read = f.save.readChunk("world", 0, 0);
                check(future ? read instanceof ChunkLoad.TooNew : read instanceof ChunkLoad.Unreadable,
                        "queued snapshot hid unreadable/newer evidence");
                gate.close(); f.save.flushAndAwait();
                check(Arrays.equals(original, Files.readAllBytes(path)), "queued write replaced protected evidence");
            }
        }
    }

    private static ChunkSnapshot snapshot(int count) {
        byte[] blocks = new byte[SaveFormat.CHUNK_VOLUME], meta = blocks.clone();
        blocks[CHEST] = (byte) BlockType.CHEST.ordinal(); blocks[FURNACE] = (byte) BlockType.FURNACE.ordinal();
        blocks[Chunk.idx(4, 10, 4)] = (byte) BlockType.STONE.ordinal(); meta[CHEST] = 2;
        Map<Integer, ItemStack[]> chests = new HashMap<>();
        ItemStack chestStack = new ItemStack(BlockType.STONE, count);
        chestStack.setComponents(chestStack.components().withRaw("future:component", new byte[] {43}));
        chests.put(CHEST, new ItemStack[] {chestStack});
        Furnace furnace = new Furnace(); furnace.input = new ItemStack(BlockType.SAND, count); furnace.cook = 3.25f;
        return new ChunkSnapshot(0, 0, blocks, meta, chests, new HashMap<>(Map.of(FURNACE, furnace)),
                new ArrayList<>(List.of(new DroppedItem(new ItemStack(BlockType.STONE, count), 4, 11, 4, 2))),
                Map.of("future:payload", new byte[] {7, 8, 9}));
    }
    private static void mutate(ChunkSnapshot s) {
        s.blocks[CHEST] = 0; s.meta[CHEST] = 0; s.chests.get(CHEST)[0].count = 1;
        s.chests.get(CHEST)[0].setComponents(com.mineclone.item.ItemComponents.EMPTY);
        s.furnaces.get(FURNACE).input.count = 1; s.furnaces.get(FURNACE).cook = 0;
        s.items.get(0).stack.count = 1; s.extra.get("future:payload")[0] = 0;
    }
    private static void pristine(ChunkSnapshot s, int count) {
        check(s.blocks[CHEST] == (byte) BlockType.CHEST.ordinal() && s.meta[CHEST] == 2, "borrowed block/meta array");
        check(s.chests.get(CHEST)[0].count == count, "borrowed chest stack");
        check(Arrays.equals(s.chests.get(CHEST)[0].components().raw("future:component"), new byte[] {43}), "lost item component");
        check(s.furnaces.get(FURNACE).input.count == count && s.furnaces.get(FURNACE).cook == 3.25f, "borrowed furnace state");
        check(s.items.get(0).stack.count == count && s.extra.get("future:payload")[0] == 7, "borrowed item/extra section");
    }
    private static ChunkSnapshot capture(Chunk c) {
        return new ChunkSnapshot(c.cx, c.cz, c.copyBlocks(), c.copyMeta(), c.copyChests(), c.copyFurnaces(),
                c.copyPendingItems(), c.copyExtraSections());
    }
    private static ChunkSnapshot loaded(ChunkLoad read) {
        check(read instanceof ChunkLoad.Loaded, "expected loaded chunk, got " + read);
        return ((ChunkLoad.Loaded) read).snapshot();
    }
    private static final class Gate implements AutoCloseable {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Gate(ExecutorService writer) {
            writer.submit(() -> { entered.countDown(); try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
        }
        void awaitEntered() throws Exception { check(entered.await(3, TimeUnit.SECONDS), "writer gate timed out"); }
        public void close() { release.countDown(); }
    }
    private static final class Fixture implements AutoCloseable {
        final Path root = Files.createTempDirectory("mineclone-queued-chunk-");
        final SaveManager save = new SaveManager(root.toFile());
        final ExecutorService writer;
        Fixture() throws Exception {
            var field = SaveManager.class.getDeclaredField("chunkWriter"); field.setAccessible(true);
            writer = (ExecutorService) field.get(save);
            save.saveChunkAsync("world", snapshot(12)); save.flushAndAwait();
        }
        Gate gate() { return new Gate(writer); }
        public void close() throws Exception {
            save.flushAndAwait(); writer.shutdown();
            try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}

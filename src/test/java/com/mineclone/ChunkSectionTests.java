package com.mineclone;

import com.mineclone.data.SectionCodec;
import com.mineclone.data.VarInt;
import com.mineclone.save.*;
import com.mineclone.world.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Format boundaries and real loader cycles, including opaque future data. */
final class ChunkSectionTests {
    static void runAll(TestMain.Runner r) {
        r.run("chunk v8 preserves containers, item components and opaque sections through five loader cycles", ChunkSectionTests::roundTrips);
        r.run("canonical animals and empty spawn receipts require chunk reader8", ChunkSectionTests::canonicalReader);
        r.run("historical chunk v7 sections remain readable", ChunkSectionTests::historicalSections);
        r.run("additive future chunk loads but required future reader is preserved read-only", ChunkSectionTests::readerGate);
        r.run("oversized, duplicate and missing chunk sections are refused and quarantined", ChunkSectionTests::badSections);
    }
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private static final int CHEST = Chunk.idx(1, 4, 1), FURNACE = Chunk.idx(2, 4, 1);

    private static ChunkSnapshot example() {
        byte[] blocks = new byte[SaveFormat.CHUNK_VOLUME], meta = blocks.clone();
        blocks[CHEST] = (byte) BlockType.CHEST.ordinal();
        blocks[FURNACE] = (byte) BlockType.FURNACE.ordinal();
        meta[CHEST] = 3;
        ItemStack[] slots = new ItemStack[27];
        slots[0] = ItemStack.of("diamond", 16);
        slots[0].setComponents(slots[0].components().withRaw("future:component", new byte[] { 3, 7, -1 }));
        slots[26] = new ItemStack(BlockType.STONE, 31);
        Furnace furnace = new Furnace();
        furnace.input = new ItemStack(BlockType.SAND, 7);
        furnace.fuel = new ItemStack(BlockType.PLANKS, 3);
        furnace.output = new ItemStack(BlockType.GLASS, 2);
        furnace.burnLeft = 12.25f; furnace.burnMax = 15f; furnace.cook = 0.625f;
        return new ChunkSnapshot(0, 0, blocks, meta, Map.of(CHEST, slots), Map.of(FURNACE, furnace),
                List.of(new DroppedItem(ItemStack.of("diamond", 11), 7.25f, 6f, 5.5f, 2.75f,600)),
                Map.of("future:entity", new byte[] { 1, 0, -1, 17, 9 }));
    }
    private static void roundTrips() throws Exception {
        try (Fixture f = new Fixture()) {
            ChunkSnapshot initial = example();
            f.save.saveChunkAsync("world", initial);
            f.save.flushAndAwait();
            for (int i = 0; i < 5; i++) {
                World world = new World(732) {
                    @Override public Chunk generateDetached(int cx, int cz) { return new Chunk(cx, cz); }
                };
                ChunkLoader loader = new ChunkLoader(world, new ChunkMesher(world), f.save, "world");
                loader.setMeshing(false);
                try {
                    Chunk c = loader.loadNow(0, 0);
                    check(c.get(1, 4, 1) == BlockType.CHEST && c.getMeta(1, 4, 1) == 3, "terrain or metadata lost");
                    check(c.getChest(1, 4, 1)[0].count == 16 && c.getChest(1, 4, 1)[26].count == 31, "chest lost");
                    check(Arrays.equals(c.getChest(1, 4, 1)[0].components().raw("future:component"),
                            new byte[] { 3, 7, -1 }), "opaque item component lost");
                    Furnace furnace = c.getFurnace(2, 4, 1);
                    check(furnace.input.count == 7 && furnace.fuel.count == 3 && furnace.output.count == 2
                            && furnace.burnLeft == 12.25f && furnace.burnMax == 15f && furnace.cook == .625f, "furnace lost");
                    check(Arrays.equals(initial.extra.get("future:entity"), c.copyExtraSections().get("future:entity")), "opaque bytes lost");
                    ChunkSnapshot disk = ((ChunkLoad.Loaded) f.save.readChunk("world", 0, 0)).snapshot();
                    check(disk.items.size() == 1 && disk.items.get(0).stack.count == 11
                            && disk.items.get(0).x == 7.25f && disk.items.get(0).age == 2.75f && disk.items.get(0).lifetime==600, "dropped item lost");
                    c.set(3, 4, 1, BlockType.STONE);
                    f.save.saveChunkAsync("world", new ChunkSnapshot(0, 0, c.copyBlocks(), c.copyMeta(),
                            c.copyChests(), c.copyFurnaces(), disk.items, c.copyExtraSections()));
                    f.save.flushAndAwait();
                } finally { loader.shutdown(); }
            }
        }
    }

    private static void readerGate() throws Exception {
        try (Fixture f = new Fixture()) {
            int next = SaveFormat.CHUNK_VERSION + 1;
            f.write(next, SaveFormat.CHUNK_VERSION, out -> ChunkSectionCodec.write(out, example()));
            check(f.save.readChunk("world", 0, 0) instanceof ChunkLoad.Loaded, "additive future chunk refused");
            // Use a fresh reader after replacing an already inspected file.
            f.write(next, next, out -> ChunkSectionCodec.write(out, example()));
            byte[] original = Files.readAllBytes(f.chunk);
            SaveManager future = new SaveManager(f.root.toFile());
            check(future.readChunk("world", 0, 0) instanceof ChunkLoad.TooNew, "minimum reader ignored");
            World world = new World(732);
            ChunkLoader loader = new ChunkLoader(world, new ChunkMesher(world), future, "world");
            loader.setMeshing(false);
            try {
                check(loader.loadNow(0, 0).isReadOnly(), "future chunk not marked read-only");
                future.saveChunkAsync("world", example());
                future.flushAndAwait();
                check(Arrays.equals(original, Files.readAllBytes(f.chunk)), "future chunk overwritten");
                try (var files = Files.list(f.chunk.getParent())) {
                    check(files.noneMatch(path -> path.getFileName().toString().contains(".corrupt-")),
                            "future chunk was quarantined");
                }
            } finally { loader.shutdown(); }
        }
    }

    private static void canonicalReader() throws Exception {
        for (boolean empty : new boolean[] { false, true }) {
            try (Fixture f = new Fixture()) {
                ChunkSnapshot base = example();
                var extra = new LinkedHashMap<>(base.extra);
                extra.put(com.mineclone.sim.EntityPersistence.SPAWN_RECEIPT, new byte[] { 1 });
                List<SavedMob> mobs = empty ? List.of() : List.of(new SavedMob(
                        "COW", (long) Integer.MAX_VALUE + 9, 6.5f, 5f, 6.5f, .5f, 8f,
                        -600f, true, 120f, 0f, new byte[] { 9, 7 }));
                ChunkSnapshot snapshot = new ChunkSnapshot(0, 0, base.blocks, base.meta,
                        base.chests, base.furnaces, base.items, extra, 0, new long[0], mobs);
                f.save.saveChunkAsync("world", snapshot);
                f.save.flushAndAwait();
                try (var in = new DataInputStream(new GZIPInputStream(Files.newInputStream(f.chunk)))) {
                    check(in.readInt() == SaveFormat.MAGIC, "writer magic changed");
                    int version = in.readInt(), minimum = in.readInt();
                    check(version == SaveFormat.CHUNK_VERSION && minimum >= 8 && minimum <= version,
                            "canonical entities could reach a v7 reader");
                    byte[] terrain = new byte[SaveFormat.CHUNK_VOLUME];
                    RunLengthCodec.read(in, terrain);
                    RunLengthCodec.read(in, new byte[SaveFormat.CHUNK_VOLUME]);
                    Map<String, byte[]> sections = SectionCodec.read(in);
                    check(sections.containsKey("entities"), "canonical entities section omitted");
                    List<SavedMob> records = SavedMobCodec.decode(sections.get("entities"));
                    check(records.size() == mobs.size(), "canonical entities omitted");
                    check(sections.containsKey(com.mineclone.sim.EntityPersistence.SPAWN_RECEIPT),
                            "empty canonical receipt omitted");
                    if (!empty) {
                        SavedMob animal = records.get(0);
                        check(animal.id() == mobs.get(0).id() && animal.age() == -600f
                                && animal.loveCooldown() == 120f && Arrays.equals(animal.tail(), new byte[] { 9, 7 }),
                                "canonical animal state changed");
                    }
                }
                ChunkSnapshot loaded = ((ChunkLoad.Loaded) f.save.readChunk("world", 0, 0)).snapshot();
                check(Arrays.equals(base.blocks, loaded.blocks) && Arrays.equals(base.meta, loaded.meta),
                        "v8 header changed terrain");
            }
        }
    }

    private static void historicalSections() throws Exception {
        try (Fixture f = new Fixture()) {
            ChunkSnapshot original = example();
            f.write(7, 7, out -> ChunkSectionCodec.write(out, original));
            ChunkSnapshot loaded = ((ChunkLoad.Loaded) f.save.readChunk("world", 0, 0)).snapshot();
            check(Arrays.equals(original.blocks, loaded.blocks) && Arrays.equals(original.meta, loaded.meta),
                    "historical v7 terrain changed");
            check(loaded.chests.get(CHEST)[0].count == 16 && loaded.items.get(0).stack.count == 11,
                    "historical v7 possessions changed");
            check(Arrays.equals(original.extra.get("future:entity"), loaded.extra.get("future:entity")),
                    "historical opaque v7 section changed");
        }
    }

    private static void badSections() throws Exception {
        for (int kind = 0; kind < 3; kind++) {
            final int invalid = kind;
            try (Fixture f = new Fixture()) {
                f.write(7, 7, out -> {
                    RunLengthCodec.write(out, new byte[SaveFormat.CHUNK_VOLUME]);
                    RunLengthCodec.write(out, new byte[SaveFormat.CHUNK_VOLUME]);
                    if (invalid == 0) {
                        VarInt.write(out, 1); out.writeUTF("oversized");
                        VarInt.write(out, SectionCodec.MAX_SECTION_BYTES + 1);
                    } else if (invalid == 1) {
                        VarInt.write(out, 2);
                        for (int i = 0; i < 2; i++) { out.writeUTF("duplicate"); VarInt.write(out, 0); }
                    } else SectionCodec.write(out, Map.of("chests", new byte[4], "furnaces", new byte[4]));
                });
                byte[] original = Files.readAllBytes(f.chunk);
                check(f.save.readChunk("world", 0, 0) instanceof ChunkLoad.Unreadable, "invalid sections accepted: " + kind);
                World world = new World(12);
                ChunkLoader loader = new ChunkLoader(world, new ChunkMesher(world), f.save, "world");
                loader.setMeshing(false);
                try { loader.loadNow(0, 0); } finally { loader.shutdown(); }
                check(!Files.exists(f.chunk), "bad source not quarantined");
                try (var files = Files.list(f.chunk.getParent())) {
                    Path quarantine = files.filter(p -> p.getFileName().toString().contains(".corrupt-")).findFirst().orElseThrow();
                    check(Arrays.equals(original, Files.readAllBytes(quarantine)), "quarantine changed bytes");
                }
            }
        }
    }

    @FunctionalInterface private interface Body { void write(DataOutputStream out) throws IOException; }
    private static final class Fixture implements AutoCloseable {
        final Path root = Files.createTempDirectory("mineclone-chunk-sections-");
        final SaveManager save = new SaveManager(root.toFile());
        final Path chunk = root.resolve("world/chunks/" + SaveFormat.chunkFileName(0, 0));
        Fixture() throws IOException { Files.createDirectories(chunk.getParent()); }
        void write(int version, int minimum, Body body) throws IOException {
            try (var out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(chunk)))) {
                out.writeInt(SaveFormat.MAGIC); out.writeInt(version); out.writeInt(minimum); body.write(out);
            }
        }
        @Override public void close() throws IOException {
            save.flushAndAwait();
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}

package com.mineclone;

import com.mineclone.data.DataPack;
import com.mineclone.item.ItemRegistry;
import com.mineclone.save.ChunkSnapshot;
import com.mineclone.save.SaveManager;
import com.mineclone.world.*;
import com.mineclone.world.structure.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** GEN-04: detached generation owns chest loot; saved contents replace it before publication. */
final class StructureLootTests {
    private static final String TABLE = "mineclone:chests/ruin";
    private static final int X = -1, Y = 40, Z = 16;
    private static final StructurePiece CHEST = new StructurePiece() {
        public BoundingBox box() { return new BoundingBox(-2, Y, 15, 1, Y, 17); }
        public void place(StructureWriter out, BoundingBox clip) { chest(out, clip, X, Y, Z, TABLE); }
    };

    static void runAll(TestMain.Runner r) {
        r.run("structure chests are deterministic, clipped and unmodified on four workers", StructureLootTests::generation);
        r.run("the structure pass seeds loot with its world's seed and clips neighbouring chunks", StructureLootTests::pass);
        r.run("changed, emptied and broken generated chests survive real save and reload", StructureLootTests::saveReload);
        r.run("chest loot fills without item loss and refuses overflow before changing slots", StructureLootTests::capacity);
    }

    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    private static Chunk generate(long seed, int cx, int cz) {
        Chunk chunk = new Chunk(cx, cz);
        ChunkWriter out = new ChunkWriter(chunk, seed);
        BoundingBox clip = CHEST.box().intersection(out.box());
        if (clip != null) CHEST.place(out, clip);
        check(out.refused() == 0, "chest wrote outside its clip");
        return chunk;
    }

    private static ItemStack[] slots(Chunk chunk) { return chunk.getChest(15, Y, 0); }

    private static void equal(ItemStack[] a, ItemStack[] b) {
        check(a != null && b != null && a.length == b.length, "missing chest");
        for (int i = 0; i < a.length; i++)
            check(a[i] == null ? b[i] == null : a[i].contentEquals(b[i]), "chest slot " + i + " differs");
    }

    private static void generation() throws Exception {
        Chunk first = generate(37, -1, 1);
        check(first.get(15, Y, 0) == BlockType.CHEST && !first.modified, "generation dirtied chunk or missed block");
        check(Arrays.stream(slots(first)).anyMatch(Objects::nonNull), "ruin table has no loot");
        check(generate(37, 0, 1).chests().isEmpty() && generate(37, -1, 0).chests().isEmpty(), "boundary duplicated chest");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Chunk>> results = new ArrayList<>();
            for (int i = 0; i < 20; i++) results.add(pool.submit(() -> generate(37, -1, 1)));
            for (Future<Chunk> result : results) equal(slots(first), slots(result.get()));
        } finally { pool.shutdownNow(); }
        slots(first)[0] = ItemStack.of("diamond", 3);
        new ChunkWriter(first, 37).chest(X, Y, Z, TABLE);
        check(slots(first)[0].count == 3, "repeated placement refilled an existing chest");
    }

    private static void saveReload() throws Exception {
        Path root = Files.createTempDirectory("mineclone-structure-loot-");
        SaveManager save = new SaveManager(root.toFile());
        try {
            for (int mode = 0; mode < 3; mode++) {
                String id = "chest-" + mode;
                Chunk changed = generate(37, -1, 1);
                Arrays.fill(slots(changed), null);
                if (mode == 0) slots(changed)[4] = ItemStack.of("iron_pickaxe").set(com.mineclone.item.Components.DAMAGE, 17);
                if (mode == 2) { changed.set(15, Y, 0, BlockType.AIR); changed.removeChest(15, Y, 0); }
                changed.modified = true;
                save.saveChunkAsync(id, new ChunkSnapshot(-1, 1, changed.copyBlocks(), changed.copyMeta(), changed.copyChests()));
                save.flushAndAwait();
                World world = new World(37) {
                    @Override public Chunk generateDetached(int cx, int cz) { return generate(seed, cx, cz); }
                };
                ChunkLoader loader = new ChunkLoader(world, new ChunkMesher(world), save, id);
                loader.setMeshing(false);
                try {
                    Chunk loaded = loader.loadNow(-1, 1);
                    check(!loaded.modified, "restoration dirtied chunk");
                    if (mode == 2) check(loaded.get(15, Y, 0) == BlockType.AIR && slots(loaded) == null, "broken chest respawned");
                    else equal(slots(changed), slots(loaded));
                    check(loader.loadNow(-1, 1) == loaded, "repeated load replaced live contents");
                } finally { loader.shutdown(); }
            }
        } finally {
            save.flushAndAwait();
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void pass() {
        StructureStart.Terrain terrain = new StructureStart.Terrain() {
            public int height(int x, int z) { return 40; }
            public Biome biome(int x, int z) { return Biome.PLAINS; }
        };
        StructureType type = new StructureType("test:loot", 8, 3, 17, b -> true,
                StructureType.HeightMode.SURFACE, 1, ctx -> List.of(new StructurePiece() {
                    public BoundingBox box() { return BoundingBox.sized(ctx.x(), ctx.y(), ctx.z(), 20, 1, 20); }
                    public void place(StructureWriter out, BoundingBox clip) {
                        chest(out, clip, ctx.x() + 19, ctx.y(), ctx.z() + 19, TABLE);
                    }
                }));
        for (long seed : new long[] {0, 37, -77231}) {
            StructureIndex index = new StructureIndex(seed, List.of(type), terrain);
            StructureStart start = index.start(0, -1, -1);
            BoundingBox box = start.box();
            int x = box.maxX(), y = box.minY(), z = box.maxZ();
            Chunk c = new Chunk(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
            check(StructurePass.place(index, c) == 0, "pass wrote beyond owner chunk");
            ItemStack[] expected = new ItemStack[Chunk.CHEST_SLOTS];
            com.mineclone.item.Items.get().loot().fillChest(expected, TABLE, seed, x, y, z);
            equal(expected, c.getChest(Math.floorMod(x, 16), y, Math.floorMod(z, 16)));
            check(!c.modified, "pass made generated loot persistent without an edit");
        }
    }

    private static void capacity() throws Exception {
        Path root = Files.createTempDirectory("mineclone-chest-capacity-");
        try {
            Path table = root.resolve("probe/loot_tables/chests/test.json");
            Files.createDirectories(table.getParent());
            Files.writeString(root.resolve("probe/categories.json"), "{\"categories\":[{\"id\":\"test\",\"name\":\"Test\"}]}");
            Files.createDirectories(root.resolve("probe/items"));
            Files.writeString(root.resolve("probe/items/main.json"), "{\"a\":{\"name\":\"A\",\"category\":\"test\",\"icon\":\"stick\"}}");
            Files.writeString(table, "{\"pools\":[{\"rolls\":2,\"entries\":[{\"item\":\"a\",\"count\":40}]}]}");
            ItemRegistry registry = ItemRegistry.load(new DataPack(root));
            ItemStack[] full = new ItemStack[2];
            registry.loot().fillChest(full, "probe:chests/test", 42, 1, 2, 3);
            check(Arrays.stream(full).mapToInt(s -> s.count).sum() == 80, "loot was lost instead of merging/splitting");
            ItemStack[] small = new ItemStack[1];
            try {
                registry.loot().fillChest(small, "probe:chests/test", 42, 1, 2, 3);
                throw new AssertionError("overflow silently lost loot");
            } catch (IllegalArgumentException expected) { check(small[0] == null, "overflow partially changed chest"); }
            try {
                registry.loot().fillChest(full, "probe:chests/test", 42, 1, 2, 3);
                throw new AssertionError("filled chest was overwritten");
            } catch (IllegalArgumentException expected) { check(full[0].count + full[1].count == 80, "existing loot changed"); }
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}

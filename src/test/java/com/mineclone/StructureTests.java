package com.mineclone;

import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.World;
import com.mineclone.world.gen.GenFeatures;
import com.mineclone.world.gen.GenPolicy;
import com.mineclone.world.gen.WorldGenVersion;
import com.mineclone.world.structure.BoundingBox;
import com.mineclone.world.structure.ChunkWriter;
import com.mineclone.world.structure.StructureIndex;
import com.mineclone.world.structure.StructurePass;
import com.mineclone.world.structure.StructurePiece;
import com.mineclone.world.structure.StructureStart;
import com.mineclone.world.structure.StructureType;
import com.mineclone.world.structure.StructureTypes;
import com.mineclone.world.structure.StructureWriter;
import com.mineclone.world.Biome;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * GEN-03: structures larger than a chunk. A start is a pure function of the
 * seed and its region, each chunk writes its own cut of every piece that
 * reaches it, and the cuts add up to the whole in any order, on any thread.
 */
final class StructureTests {
    static void runAll(TestMain.Runner r) {
        r.run("bounding boxes intersect, join and cut to a chunk", StructureTests::boxes);
        r.run("starts keep to their region and their separation over 50x50 regions", StructureTests::spacing);
        r.run("a start is a pure function of seed and region, cached within its bound", StructureTests::pure);
        r.run("overlapping or far-reaching pieces are refused, unsuitable sites have no start", StructureTests::validation);
        r.run("a chunk writer refuses writes outside its chunk", StructureTests::writerBounds);
        r.run("a 40x40x20 hall over nine chunks is whole in any order and in parallel", StructureTests::acceptance);
        r.run("the index names the structure at a block, only where chunks have them", StructureTests::index);
        r.run("the pass runs only with structures2 and the shipped registry is empty", StructureTests::gated);
    }

    private static void check(boolean ok, String why) {
        if (!ok)
            throw new AssertionError(why);
    }

    // ------------------------------------------------------------ test types

    private static final StructureStart.Terrain FLAT = new StructureStart.Terrain() {
        @Override public int height(int x, int z) { return 64; }
        @Override public Biome biome(int x, int z) { return Biome.PLAINS; }
    };

    /** Fills its box by position alone: floor, wall or clearing. */
    private record Fill(BoundingBox box, int style, long salt) implements StructurePiece {
        static final int FLOOR = 0, WALL = 1, CLEAR = 2;

        @Override
        public void place(StructureWriter out, BoundingBox clip) {
            for (int x = clip.minX(); x <= clip.maxX(); x++)
                for (int y = clip.minY(); y <= clip.maxY(); y++)
                    for (int z = clip.minZ(); z <= clip.maxZ(); z++)
                        out.set(x, y, z, block(x, y, z), (byte) (style == WALL ? (x + z) & 3 : 0));
        }

        BlockType block(int x, int y, int z) {
            long n = StructurePiece.noise(x, y, z, salt);
            return switch (style) {
                case FLOOR -> n % 3 == 0 ? BlockType.STONE : BlockType.COBBLE;
                case WALL -> (y - box.minY()) % 5 == 3 && Math.floorMod(x + z, 6) == 0 ? BlockType.GLASS
                        : n % 5 == 0 ? BlockType.MOSSY_COBBLE : BlockType.COBBLE;
                default -> BlockType.AIR;
            };
        }
    }

    /**
     * A hall 40 x 20 x 40 whose corner is 12 blocks before its start chunk:
     * it spans that chunk and one on every side — nine chunks.
     */
    private static StructureType hall(int spacing, int separation) {
        return new StructureType("test:hall", spacing, separation, 0x7E57L, b -> true,
                StructureType.HeightMode.SURFACE, 1, ctx -> {
            int x0 = ctx.chunkX() * Chunk.SIZE_X - 12, z0 = ctx.chunkZ() * Chunk.SIZE_Z - 12;
            int y0 = Math.max(1, Math.min(ctx.y(), Chunk.SIZE_Y - 21));
            int x1 = x0 + 39, z1 = z0 + 39, y1 = y0 + 19;
            long salt = ctx.random().nextLong();
            return List.of(
                    new Fill(new BoundingBox(x0, y0, z0, x1, y0, z1), Fill.FLOOR, salt),
                    new Fill(new BoundingBox(x0, y0 + 1, z0, x1, y1, z0), Fill.WALL, salt),
                    new Fill(new BoundingBox(x0, y0 + 1, z1, x1, y1, z1), Fill.WALL, salt),
                    new Fill(new BoundingBox(x0, y0 + 1, z0 + 1, x0, y1, z1 - 1), Fill.WALL, salt),
                    new Fill(new BoundingBox(x1, y0 + 1, z0 + 1, x1, y1, z1 - 1), Fill.WALL, salt),
                    new Fill(new BoundingBox(x0 + 1, y0 + 1, z0 + 1, x1 - 1, y1, z1 - 1), Fill.CLEAR, salt));
        });
    }

    /** One block at the anchor: a start in every region, for spacing checks. */
    private static StructureType marker(int spacing, int separation) {
        return new StructureType("test:marker", spacing, separation, 0x3A7L, b -> true,
                StructureType.HeightMode.underground(5, 40), 0,
                ctx -> List.of(new Fill(new BoundingBox(ctx.x(), ctx.y(), ctx.z(), ctx.x(), ctx.y(), ctx.z()),
                        Fill.FLOOR, 1)));
    }

    // ------------------------------------------------------------ tests

    private static void boxes() {
        BoundingBox a = BoundingBox.sized(0, 0, 0, 4, 4, 4), b = new BoundingBox(3, 3, 3, 9, 9, 9);
        check(a.equals(new BoundingBox(0, 0, 0, 3, 3, 3)) && a.volume() == 64, "sized");
        check(a.intersects(b) && a.intersection(b).equals(new BoundingBox(3, 3, 3, 3, 3, 3)), "corner overlap");
        check(a.union(b).equals(new BoundingBox(0, 0, 0, 9, 9, 9)), "union");
        check(a.intersection(new BoundingBox(4, 0, 0, 5, 1, 1)) == null, "touching boxes do not overlap");
        check(BoundingBox.chunk(-1, 2).equals(new BoundingBox(-16, 0, 32, -1, Chunk.SIZE_Y - 1, 47)), "chunk column");
        check(a.contains(3, 0, 3) && !a.contains(4, 0, 0) && !a.contains(-1, 0, 0), "contains");
        try {
            new BoundingBox(1, 0, 0, 0, 0, 0);
            throw new AssertionError("an inside-out box was made");
        } catch (IllegalArgumentException expected) {
            // refused
        }
    }

    private static void spacing() {
        int spacing = 8, separation = 3;
        StructureIndex index = new StructureIndex(99L, List.of(marker(spacing, separation)), FLAT);
        StructureStart[][] starts = new StructureStart[50][50];
        Set<Integer> offsets = new HashSet<>();
        for (int rx = -25; rx < 25; rx++)
            for (int rz = -25; rz < 25; rz++) {
                StructureStart s = index.start(0, rx, rz);
                check(s != null, "a region without its marker: " + rx + "," + rz);
                int ox = s.chunkX() - rx * spacing, oz = s.chunkZ() - rz * spacing;
                check(ox >= 0 && ox < spacing - separation && oz >= 0 && oz < spacing - separation,
                        "start outside its region's allowed part: " + ox + "," + oz);
                check(s.y() >= 5 && s.y() <= 40, "underground height out of band: " + s.y());
                offsets.add(ox);
                starts[rx + 25][rz + 25] = s;
            }
        for (int i = 0; i < 50; i++)
            for (int j = 0; j < 50; j++) {
                if (i + 1 < 50)
                    check(starts[i + 1][j].chunkX() - starts[i][j].chunkX() >= separation, "x neighbours too close");
                if (j + 1 < 50)
                    check(starts[i][j + 1].chunkZ() - starts[i][j].chunkZ() >= separation, "z neighbours too close");
            }
        check(offsets.size() == spacing - separation, "offsets used: " + offsets);
    }

    private static List<String> describe(StructureStart s) {
        List<String> out = new ArrayList<>();
        out.add(s.chunkX() + "," + s.chunkZ() + "," + s.y() + " " + s.box());
        for (StructurePiece p : s.pieces())
            out.add(p.toString());
        return out;
    }

    private static void pure() throws Exception {
        StructureType hall = hall(6, 2);
        StructureIndex first = new StructureIndex(4242L, List.of(hall), FLAT);
        StructureIndex second = new StructureIndex(4242L, List.of(hall), FLAT);
        // The second asks in reverse order, from four threads.
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<List<String>>> answers = new ArrayList<>();
            for (int rx = 20; rx >= -20; rx--)
                for (int rz = 20; rz >= -20; rz--) {
                    int x = rx, z = rz;
                    answers.add(pool.submit(() -> describe(second.start(0, x, z))));
                }
            int k = 0;
            for (int rx = 20; rx >= -20; rx--)
                for (int rz = 20; rz >= -20; rz--)
                    check(describe(first.start(0, rx, rz)).equals(answers.get(k++).get()), "start " + rx + "," + rz + " differs");
        } finally {
            pool.shutdownNow();
        }
        check(first.cached() <= StructureIndex.CACHE_SIZE && second.cached() <= StructureIndex.CACHE_SIZE,
                "cache over its bound: " + first.cached() + ", " + second.cached());
        // Evicted starts come back the same.
        check(describe(first.start(0, 20, 20)).equals(describe(new StructureIndex(4242L, List.of(hall), FLAT).start(0, 20, 20))),
                "an evicted start came back different");
        int moved = 0;
        StructureIndex other = new StructureIndex(4243L, List.of(hall), FLAT);
        for (int r = 0; r < 20; r++)
            if (first.start(0, r, -r).chunkX() != other.start(0, r, -r).chunkX())
                moved++;
        check(moved >= 10, "another seed barely moves the starts: " + moved);
    }

    private static void expectState(StructureType type, String why) {
        try {
            StructureStart.create(type, 1L, 0, 0, FLAT);
            throw new AssertionError(why);
        } catch (IllegalStateException expected) {
            // refused
        }
    }

    private static void validation() {
        expectState(new StructureType("test:overlap", 4, 1, 1L, b -> true, StructureType.HeightMode.SURFACE, 1,
                ctx -> List.of(new Fill(BoundingBox.sized(ctx.x(), 70, ctx.z(), 3, 3, 3), Fill.FLOOR, 0),
                        new Fill(BoundingBox.sized(ctx.x() + 2, 72, ctx.z() + 2, 3, 3, 3), Fill.FLOOR, 0))),
                "overlapping pieces were accepted");
        expectState(new StructureType("test:far", 4, 1, 1L, b -> true, StructureType.HeightMode.SURFACE, 1,
                ctx -> List.of(new Fill(BoundingBox.sized(ctx.x() + 40, 70, ctx.z(), 2, 2, 2), Fill.FLOOR, 0))),
                "a piece past the radius was accepted");
        expectState(new StructureType("test:sky", 4, 1, 1L, b -> true, StructureType.HeightMode.SURFACE, 1,
                ctx -> List.of(new Fill(BoundingBox.sized(ctx.x(), Chunk.SIZE_Y - 1, ctx.z(), 1, 2, 1), Fill.FLOOR, 0))),
                "a piece above the world was accepted");
        check(StructureStart.create(new StructureType("test:none", 4, 1, 1L, b -> true,
                StructureType.HeightMode.SURFACE, 1, ctx -> List.of()), 1L, 0, 0, FLAT) == null, "an empty assembly started");
        check(StructureStart.create(new StructureType("test:desert", 4, 1, 1L, b -> b == Biome.DESERT,
                StructureType.HeightMode.SURFACE, 1, ctx -> {
                    throw new AssertionError("assembled in the wrong biome");
                }), 1L, 0, 0, FLAT) == null, "a start in the wrong biome");
        try {
            new StructureType("test:bad", 4, 4, 1L, b -> true, StructureType.HeightMode.SURFACE, 1, ctx -> List.of());
            throw new AssertionError("separation equal to spacing was accepted");
        } catch (IllegalArgumentException expected) {
            // refused
        }
    }

    private static void writerBounds() {
        Chunk c = new Chunk(2, -1);
        ChunkWriter w = new ChunkWriter(c);
        w.set(32, 10, -16, BlockType.STONE);
        w.set(47, 10, -1, BlockType.STONE, (byte) 3);
        w.set(48, 10, -1, BlockType.STONE);
        w.set(40, 10, 0, BlockType.STONE);
        w.set(40, Chunk.SIZE_Y, -8, BlockType.STONE);
        w.set(40, -1, -8, BlockType.STONE);
        check(w.refused() == 4, "refused " + w.refused());
        check(c.get(0, 10, 0) == BlockType.STONE && c.get(15, 10, 15) == BlockType.STONE
                && c.getMeta(15, 10, 15) == 3, "writes inside the chunk were lost");
        check(w.get(48, 10, -1) == BlockType.AIR && w.get(47, 10, -1) == BlockType.STONE, "reads");
        // A piece that ignores its clip writes its whole box; the writer keeps the chunk's part only.
        StructurePiece careless = new StructurePiece() {
            @Override public BoundingBox box() { return new BoundingBox(40, 20, -8, 60, 20, -8); }
            @Override public void place(StructureWriter out, BoundingBox clip) {
                for (int x = 40; x <= 60; x++)
                    out.set(x, 20, -8, BlockType.GLASS);
            }
        };
        ChunkWriter w2 = new ChunkWriter(new Chunk(2, -1));
        careless.place(w2, careless.box().intersection(w2.box()));
        check(w2.refused() == 60 - 47, "a careless piece's outside writes: " + w2.refused());
    }

    /**
     * V2 with structures2 on — as it will be once a type ships. Not through
     * {@code WorldGenSettings}, which rightly refuses a flag V2 does not have yet.
     */
    private static final GenPolicy WITH_STRUCTURES = new GenPolicy() {
        final GenFeatures features = new GenFeatures(false, false, true, false, false);

        @Override public WorldGenVersion versionAt(int cx, int cz) { return WorldGenVersion.V2; }
        @Override public GenFeatures featuresAt(int cx, int cz) { return features; }
    };

    private static World hallWorld(GenPolicy policy) {
        return hallWorld(policy, hall(1000, 1));
    }

    private static World hallWorld(GenPolicy policy, StructureType type) {
        World w = new World(20260925L, policy);
        w.setStructureTypes(List.of(type));
        return w;
    }

    private static final class Buffer implements StructureWriter {
        final Map<Long, BlockType> blocks = new HashMap<>();
        final Map<Long, Byte> meta = new HashMap<>();

        static long key(int x, int y, int z) {
            return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | y;
        }

        @Override public void set(int x, int y, int z, BlockType block, byte m) {
            blocks.put(key(x, y, z), block);
            meta.put(key(x, y, z), m);
        }

        @Override public BlockType get(int x, int y, int z) {
            return blocks.getOrDefault(key(x, y, z), BlockType.AIR);
        }
    }

    private static byte[] contents(Chunk c) {
        byte[] b = c.copyBlocks(), m = c.copyMeta(), out = Arrays.copyOf(b, b.length + m.length);
        System.arraycopy(m, 0, out, b.length, m.length);
        return out;
    }

    private static void acceptance() throws Exception {
        // A start in the middle of a large region, and one on the first chunk of
        // a region of four: that hall reaches into the previous region's chunks,
        // which must look for starts in the regions beside their own.
        assemblesWhole(hall(1000, 1));
        StructureType edge = hall(4, 3);
        StructureStart first = hallWorld(WITH_STRUCTURES, edge).structures().start(0, 0, 0);
        check(first.chunkX() == 0 && first.chunkZ() == 0, "the edge hall does not start its region");
        assemblesWhole(edge);
    }

    private static void assemblesWhole(StructureType type) throws Exception {
        StructureStart start = hallWorld(WITH_STRUCTURES, type).structures().start(0, 0, 0);
        check(start != null, "no hall in region 0,0");
        BoundingBox box = start.box();
        check(box.maxX() - box.minX() == 39 && box.maxZ() - box.minZ() == 39 && box.maxY() - box.minY() == 19,
                "hall size " + box);
        List<int[]> chunks = new ArrayList<>();
        for (int cx = start.chunkX() - 1; cx <= start.chunkX() + 1; cx++)
            for (int cz = start.chunkZ() - 1; cz <= start.chunkZ() + 1; cz++) {
                check(box.intersects(BoundingBox.chunk(cx, cz)), "the hall misses chunk " + cx + "," + cz);
                chunks.add(new int[] { cx, cz });
            }
        check(!box.intersects(BoundingBox.chunk(start.chunkX() + 2, start.chunkZ()))
                && !box.intersects(BoundingBox.chunk(start.chunkX(), start.chunkZ() - 2)), "the hall spans more than nine chunks");

        // Reference: every chunk generated in row order.
        Map<String, byte[]> reference = new HashMap<>();
        World w = hallWorld(WITH_STRUCTURES, type);
        for (int[] c : chunks)
            reference.put(c[0] + "," + c[1], contents(w.generateDetached(c[0], c[1])));
        // The same chunks in reverse and shuffled orders, each on a fresh world with a cold cache.
        List<int[]> reversed = new ArrayList<>(chunks);
        Collections.reverse(reversed);
        List<int[]> shuffled = new ArrayList<>(chunks);
        Collections.shuffle(shuffled, new Random(7));
        for (List<int[]> order : List.of(reversed, shuffled)) {
            World again = hallWorld(WITH_STRUCTURES, type);
            for (int[] c : order)
                check(Arrays.equals(reference.get(c[0] + "," + c[1]), contents(again.generateDetached(c[0], c[1]))),
                        "chunk " + c[0] + "," + c[1] + " differs with another order");
        }
        // Four threads at once, sharing one index.
        World shared = hallWorld(WITH_STRUCTURES, type);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<byte[]>> made = new ArrayList<>();
            for (int[] c : shuffled)
                made.add(pool.submit(() -> contents(shared.generateDetached(c[0], c[1]))));
            for (int i = 0; i < shuffled.size(); i++)
                check(Arrays.equals(reference.get(shuffled.get(i)[0] + "," + shuffled.get(i)[1]), made.get(i).get()),
                        "chunk differs when generated in parallel");
        } finally {
            pool.shutdownNow();
        }

        // The cuts add up to the whole: one placement into a buffer is what the nine chunks hold.
        Buffer whole = new Buffer();
        for (StructurePiece p : start.pieces())
            p.place(whole, p.box());
        check(whole.blocks.size() == box.volume() && box.volume() == 40L * 40 * 20,
                "the pieces do not fill the hall's box: " + whole.blocks.size());
        World check = hallWorld(WITH_STRUCTURES, type);
        Map<String, Chunk> generated = new HashMap<>();
        for (int[] c : chunks)
            generated.put(c[0] + "," + c[1], check.generateDetached(c[0], c[1]));
        int mismatches = 0, glass = 0;
        for (int x = box.minX(); x <= box.maxX(); x++)
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                Chunk c = generated.get(Math.floorDiv(x, 16) + "," + Math.floorDiv(z, 16));
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    long k = Buffer.key(x, y, z);
                    BlockType want = whole.blocks.get(k);
                    int lx = Math.floorMod(x, 16), lz = Math.floorMod(z, 16);
                    if (c.get(lx, y, lz) != want || c.getMeta(lx, y, lz) != whole.meta.get(k))
                        mismatches++;
                    if (want == BlockType.GLASS)
                        glass++;
                }
            }
        check(mismatches == 0, "the nine cuts differ from the whole in " + mismatches + " blocks");
        check(glass > 0, "the walls have no windows: the test hall is not exercising position choices");
        // Every chunk's pass writes inside its own chunk only.
        for (int[] c : chunks)
            check(StructurePass.place(check.structures(), new Chunk(c[0], c[1])) == 0, "a cut wrote outside its chunk");
    }

    private static void index() {
        World w = hallWorld(WITH_STRUCTURES);
        StructureStart start = w.structures().start(0, 0, 0);
        BoundingBox box = start.box();
        StructureIndex.Hit wall = w.structureAt(box.minX(), box.minY() + 5, box.minZ() + 10);
        check(wall != null && wall.type().id().equals("test:hall") && wall.start() == start
                && wall.piece().box().contains(box.minX(), box.minY() + 5, box.minZ() + 10), "the wall");
        StructureIndex.Hit inside = w.structureAt(box.minX() + 20, box.minY() + 10, box.minZ() + 20);
        check(inside != null && inside.piece() != wall.piece(), "the hall's inside is part of it");
        check(w.structureAt(box.minX() - 1, box.minY() + 5, box.minZ() + 10) == null
                && w.structureAt(box.minX(), box.maxY() + 1, box.minZ()) == null
                && w.structureAt(box.minX(), -1, box.minZ()) == null, "outside the hall");
        World plain = new World(20260925L, GenPolicy.fixed(WorldGenVersion.V2));
        plain.setStructureTypes(List.of(hall(1000, 1)));
        check(plain.structureAt(box.minX(), box.minY() + 5, box.minZ() + 10) == null,
                "a chunk without structures2 claimed the hall");
    }

    private static void gated() {
        check(StructureTypes.V2.isEmpty(), "a structure type shipped without switching structures2 on");
        check(!GenFeatures.V2.structures2() && !GenFeatures.V1.structures2(), "structures2 is on without a type");
        StructureStart start = hallWorld(WITH_STRUCTURES).structures().start(0, 0, 0);
        int cx = start.chunkX(), cz = start.chunkZ();
        // Without the flag the hall's chunk is the plain generator's.
        byte[] plain = contents(new World(20260925L, GenPolicy.fixed(WorldGenVersion.V2)).generateDetached(cx, cz));
        World off = new World(20260925L, GenPolicy.fixed(WorldGenVersion.V2));
        off.setStructureTypes(List.of(hall(1000, 1)));
        check(Arrays.equals(plain, contents(off.generateDetached(cx, cz))), "the pass ran without structures2");
        // With the flag but the shipped (empty) registry, nothing changes either.
        check(Arrays.equals(plain, contents(new World(20260925L, WITH_STRUCTURES).generateDetached(cx, cz))),
                "the empty registry changed a chunk");
        check(!Arrays.equals(plain, contents(hallWorld(WITH_STRUCTURES).generateDetached(cx, cz))),
                "the hall did not change its own chunk");
    }
}

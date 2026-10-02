package com.mineclone;

import com.mineclone.data.ResourceId;
import com.mineclone.save.ChunkSectionCodec;
import com.mineclone.save.ChunkSnapshot;
import com.mineclone.sim.*;
import com.mineclone.sim.ecology.*;
import com.mineclone.world.*;
import com.mineclone.world.damage.*;
import com.mineclone.world.entity.MobType;
import java.io.*;
import java.util.*;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/** Real loaded cave, light, abandoned-base and saved-candidate founding checks. */
public final class ColonizerTests {
    private static final int CELL = Chunk.idx(5, 40, 5);
    private static final LairTable.Definition SPIDER = new LairTable.Definition(ResourceId.of("spider_nest"), MobType.SPIDER, 6, 8, 1, 4, 24);
    public static void runAll(TestMain.Runner r) {
        r.run("lair discovery requires dark supported spider space below an opaque roof", ColonizerTests::cavity);
        r.run("lair candidate codec preserves tails and protects future chunk sections", ColonizerTests::codec);
        r.run("destroyed lone lair colonizes another loaded cave within three to ten days", ColonizerTests::recolonize);
        r.run("daily founding rejects active lairs and rechecks light and loaded terrain", ColonizerTests::exclusions);
        r.run("abandoned loaded bases protect caves without a participant nearby", ColonizerTests::base);
        r.run("colony paths respect line of sight expiry and bounded recent samples", ColonizerTests::trails);
        r.run("colony decision is independent of chunk publication order", ColonizerTests::determinism);
    }
    private static void check(boolean value, String why) { if (!value) throw new AssertionError(why); }
    private static Chunk cave(World world, int cx, int cz) throws IOException {
        Chunk c = new Chunk(cx, cz);
        // A sealed chamber with three blocks of headroom, not shadow from a tree canopy.
        for (int y = 39; y <= 43; y++) for (int z = 3; z <= 10; z++) for (int x = 3; x <= 10; x++)
            if (y == 39 || y == 43 || x == 3 || x == 10 || z == 3 || z == 10) c.set(x, y, z, BlockType.STONE);
        c.computeSkyLight(); world.publish(c);
        c.setExtraSection(LairSpots.SECTION, new LairSpots(new int[]{CELL}).encode());
        check(LairSpots.eligible(world, c, CELL), "fixture is not a dark supported cavity");
        return c;
    }
    private static EcologyRegion region() { return new EcologyRegion(0, 0, 0); }
    private static Colonizer.Spot initial(World w, List<Lair> lairs, Participants p) {
        return new Colonizer().select(w, region(), lairs, p, 0, true);
    }
    private static void cavity() throws Exception {
        World world = new World(71, GenProfile.FLAT); Chunk c = cave(world, 0, 0);
        LairSpots spots = LairSpots.discover(world, c);
        check(spots.count() > 0 && spots.count() <= 16, "dark chamber not discovered within bound");
        for (int n = 0; n < spots.count(); n++) check(LairSpots.eligible(world, c, spots.cellAt(n)), "discovery returned invalid space");
        c.setBlockLight(5, 40, 5, 1);
        check(!LairSpots.eligible(world, c, CELL), "dim torch light accepted"); c.setBlockLight(5, 40, 5, 0);
        c.set(6, 40, 6, BlockType.STONE);
        check(!LairSpots.eligible(world, c, CELL), "spider would overlap a wall"); c.set(6, 40, 6, BlockType.AIR);
        c.set(6, 39, 6, BlockType.AIR);
        check(!LairSpots.eligible(world, c, CELL), "unsupported footprint accepted"); c.set(6, 39, 6, BlockType.STONE);
        for (int z = 4; z <= 9; z++) for (int x = 4; x <= 9; x++) c.set(x, 43, z, BlockType.AIR);
        c.computeSkyLight(); check(!LairSpots.eligible(world, c, CELL), "surface skylight accepted");
        c.markReadOnly(); check(LairSpots.discover(world, c).count() == 0, "protected chunk inspected for mutation");
        world.removeChunk(0, 0); check(LairSpots.discover(world, c).count() == 0 && world.getChunkIfExists(0, 0) == null, "discovery regenerated terrain");
    }
    private static void codec() throws Exception {
        byte[] bytes = new LairSpots(new int[]{CELL, Chunk.idx(8, 40, 8)}).encode();
        byte[] withTail = Arrays.copyOf(bytes, bytes.length + 3); withTail[bytes.length] = 91; withTail[bytes.length + 2] = -7;
        check(Arrays.equals(withTail, LairSpots.decode(withTail).encode()), "opaque spot tail changed");
        try { new LairSpots(new int[]{CELL, CELL}); throw new AssertionError("duplicate accepted"); } catch (IllegalArgumentException expected) {}
        try { LairSpots.decode(Arrays.copyOf(bytes, 7)); throw new AssertionError("truncation accepted"); } catch (IOException expected) {}
        byte[] future = Arrays.copyOf(bytes, 2048); future[4] = 2;
        try { LairSpots.decode(future); throw new AssertionError("newer large layout accepted"); } catch (LairSpots.NewerVersionException expected) {}
        Chunk c = new Chunk(0, 0);
        ChunkSnapshot snapshot = new ChunkSnapshot(0, 0, c.copyBlocks(), c.copyMeta(), Map.of(), Map.of(), List.of(), Map.of(LairSpots.SECTION, withTail));
        var out = new ByteArrayOutputStream(); ChunkSectionCodec.write(new DataOutputStream(out), snapshot);
        var restored = ChunkSectionCodec.read(new DataInputStream(new ByteArrayInputStream(out.toByteArray())), 0, 0);
        check(Arrays.equals(withTail, restored.extra.get(LairSpots.SECTION)), "chunk roundtrip lost saved candidates/tail");
        byte[] payload = out.toByteArray(); int at = indexOf(payload, withTail); check(at >= 0, "encoded optional section not found"); payload[at + 4] = 2;
        try { ChunkSectionCodec.read(new DataInputStream(new ByteArrayInputStream(payload)), 0, 0); throw new AssertionError("future chunk section accepted"); }
        catch (LairSpots.NewerVersionException expected) {}
    }
    private static int indexOf(byte[] haystack, byte[] needle) {
        for (int n = 0; n <= haystack.length - needle.length; n++) {
            int k = 0; while (k < needle.length && haystack[n + k] == needle[k]) k++; if (k == needle.length) return n;
        }
        return -1;
    }
    private static void recolonize() throws Exception {
        World world = new World(42, GenProfile.FLAT); cave(world, 0, 0); cave(world, 5, 0);
        Lair old = new Lair(5, 40, 5, 0, SPIDER); old.destroy(0); Colonizer colonizer = new Colonizer();
        check(colonizer.select(world, region(), List.of(old), new Participants(), 1) == null
                && colonizer.select(world, region(), List.of(old), new Participants(), 2) == null, "old territory refounded too soon");
        Colonizer.Spot selected = null; long foundedDay = -1;
        for (long day = 3; day <= 10; day++) {
            selected = colonizer.select(world, region(), List.of(old), new Participants(), day);
            if (selected != null) { foundedDay = day; break; }
        }
        check(selected != null && selected.x() == 85 && foundedDay >= 3 && foundedDay <= 10, "eligible other cave not colonized within acceptance window");
        check(selected.equals(new Colonizer().select(world, region(), List.of(old), new Participants(), foundedDay)), "seed/day choice changed");
        check(colonizer.select(world, region(), List.of(old), new Participants(), 0, true).x() == 85, "initial founding ignored destroyed territory distance");
    }
    private static void exclusions() throws Exception {
        World world = new World(88, GenProfile.FLAT); Chunk c = cave(world, 5, 0); Participants participants = new Participants();
        Lair alive = new Lair(5, 40, 5, 0, SPIDER);
        check(new Colonizer().select(world, region(), List.of(alive), participants, 100) == null, "daily founding added a second living lair");
        check(initial(world, List.of(new Lair(50, 40, 5, 0, SPIDER)), participants) == null, "live territory spacing ignored");
        participants.put(new Actor(1, 85, 40, 5)); check(initial(world, List.of(), participants) == null, "participant base radius ignored"); participants.clear();
        c.setBlockLight(5, 40, 5, 1); check(initial(world, List.of(), participants) == null, "stale dark candidate ignored new light"); c.setBlockLight(5, 40, 5, 0);
        c.markReadOnly(); check(initial(world, List.of(), participants) == null, "read-only cave selected");
        world.removeChunk(5, 0); check(initial(world, List.of(), participants) == null && world.getChunkIfExists(5, 0) == null, "colony generated absent cave");
    }
    private static void base() throws Exception {
        World world = new World(91, GenProfile.FLAT); Chunk c = cave(world, 5, 0); Participants absent = new Participants();
        for (BlockType type : new BlockType[]{BlockType.CHEST, BlockType.FURNACE, BlockType.BEDROLL}) {
            c.set(12, 40, 5, type);
            check(initial(world, List.of(), absent) == null, "abandoned " + type + " did not protect a nearby cave");
            byte[] blocks = c.copyBlocks(), meta = c.copyMeta(); c.restore(blocks, meta); c.restore(blocks, meta);
            check(c.baseCellCount() == 1 && initial(world, List.of(), absent) == null, "restored base index lost protection");
            c.set(12, 40, 5, BlockType.AIR);
        }
        check(c.baseCellCount() == 0 && initial(world, List.of(), absent) != null, "removed base still prohibited founding");
    }
    private static void trails() throws Exception {
        World world = new World(12, GenProfile.FLAT); Chunk c = cave(world, 5, 0); Colonizer colonizer = new Colonizer();
        colonizer.recordTrail(85.5f, 39, 85.5f, 0); // Outside this cave: distance alone must not reject.
        check(colonizer.select(world, region(), List.of(), new Participants(), 0, true) != null, "hidden cave rejected without sight");
        colonizer.recordTrail(87, 39, 87, 0); // Still outside; the roof/walls occlude this sample.
        colonizer.recordTrail(87, 39, 7, 0); // Inside the chamber, eye at 40.62.
        check(colonizer.select(world, region(), List.of(), new Participants(), 0, true) == null, "visible recent cave path ignored");
        check(colonizer.select(world, region(), List.of(), new Participants(), 4, true) != null, "expired path blocked colony forever");
        for (int n = 0; n < 300; n++) colonizer.recordTrail(1000 + n * 4, 40, 1000, 0);
        check(colonizer.select(world, region(), List.of(), new Participants(), 0, true) != null, "unbounded old path history retained a replaced sample");
        check(LairSpots.eligible(world, c, CELL), "path decisions changed terrain");
    }
    private static void determinism() throws Exception {
        World one = new World(777, GenProfile.FLAT), two = new World(777, GenProfile.FLAT);
        cave(one, 2, 0); cave(one, 8, 0); cave(two, 8, 0); cave(two, 2, 0);
        for (int day = 0; day < 16; day++) {
            Colonizer.Spot a = new Colonizer().select(one, region(), List.of(), new Participants(), day, true);
            Colonizer.Spot b = new Colonizer().select(two, region(), List.of(), new Participants(), day, true);
            check(a != null && a.equals(b), "publication order changed deterministic colony");
        }
    }
    private record Actor(int id, float x, float y, float z) implements Participant {
        public Vector3fc position() { return new Vector3f(x, y, z); }
        public Vector3fc eye() { return new Vector3f(x, y + EYE_HEIGHT, z); }
        public GameMode mode() { return GameMode.SURVIVAL; }
        public boolean alive() { return true; }
        public ArmorView armor() { return ArmorView.NONE; }
        public boolean local() { return false; }
        public boolean damage(DamageSource source, float amount) { return false; }
    }
}

package com.mineclone;

import com.mineclone.data.ResourceId;
import com.mineclone.data.SectionCodec;
import com.mineclone.data.VarInt;
import com.mineclone.sim.*;
import com.mineclone.sim.ecology.*;
import com.mineclone.world.*;
import java.io.*;
import java.util.*;

/** Field food survives absent terrain and imports safely without rescanning whole chunks each tick. */
public final class FieldHabitatTests {
    private static final ResourceId RABBIT = ResourceId.of("rabbit");
    public static void runAll(TestMain.Runner r) {
        r.run("crop index follows planting type changes removal and restored blocks", FieldHabitatTests::index);
        r.run("field observations survive negative chunk coordinates and opaque codec tails", FieldHabitatTests::codec);
        r.run("unloaded fields retain rabbit food while removed loaded fields withdraw it", FieldHabitatTests::food);
        r.run("future field habitat disables ecology and preserves original bytes", FieldHabitatTests::future);
        r.run("damaged field habitat requires durable evidence before rebuilding", FieldHabitatTests::damage);
        r.run("maximal field habitat stays below section size and saturates food exactly", FieldHabitatTests::bound);
        r.run("chunk snapshot captures removed fields before terrain is evicted", FieldHabitatTests::eviction);
    }
    private static void check(boolean good, String why) { if (!good) throw new AssertionError(why); }
    private static Chunk crops(int cx, int cz, int count) {
        Chunk chunk = new Chunk(cx, cz);
        for (int i = 0; i < count; i++) chunk.set(i % 16, 65 + i / 256, i / 16 % 16, BlockType.CARROTS);
        return chunk;
    }
    private static void index() {
        Chunk c = crops(0, 0, 19);
        c.set(0, 65, 0, BlockType.CARROTS);
        c.set(0, 65, 0, BlockType.WHEAT);
        check(c.cropCellCount() == 19, "repeated/type-changed crop duplicated index");
        c.set(1, 65, 0, BlockType.AIR);
        int[] own = c.cropIndices(); own[0] = -1;
        check(c.cropCellCount() == 18 && c.cropCellAt(0) >= 0, "removal or defensive copy failed");
        byte[] blocks = c.copyBlocks(), meta = c.copyMeta();
        c.set(2, 65, 0, BlockType.STONE); c.restore(blocks, meta); c.restore(blocks, meta);
        Set<Integer> cells = new HashSet<>();
        for (int n = 0; n < c.cropCellCount(); n++) cells.add(c.cropCellAt(n));
        check(cells.size() == 18 && !cells.contains(Chunk.idx(1, 65, 0)), "restoring did not rebuild exact sparse index");
    }
    private static void codec() throws Exception {
        FieldHabitat fields = new FieldHabitat();
        fields.observe(crops(-1, -17, 32)); fields.observe(crops(16, 0, 256));
        long negative = EcologyRegion.key(-1, -2), positive = EcologyRegion.key(1, 0);
        byte[] bytes = fields.encode();
        byte[] opaque = Arrays.copyOf(bytes, bytes.length + 3);
        opaque[bytes.length] = 19; opaque[bytes.length + 2] = -1;
        for (int i = 0; i < 5; i++) fields = FieldHabitat.decode(fields.encode());
        check(fields.crops(negative) == 32 && fields.bonus(negative) == 8 && fields.bonus(positive) == 64,
                "coordinates or food bound changed");
        check(Arrays.equals(opaque, FieldHabitat.decode(opaque).encode()), "unknown tail was lost");
        try { FieldHabitat.decode(Arrays.copyOf(bytes, 7)); throw new AssertionError("truncated habitat accepted"); }
        catch (IOException expected) {}
    }
    private static final class Fixture {
        final World world = new World(3, GenProfile.FLAT);
        final Ecology ecology = new Ecology(world, new WorldClock(.5), new EntityStore(), new Participants());
        final Chunk field;
        Fixture() {
            field = world.publish(crops(0, 0, 32));
            ecology.regionAt(8, 8); ecology.setEnabled(true);
        }
        Map<String, byte[]> save() {
            Map<String, byte[]> out = new LinkedHashMap<>(); ecology.writeSections(out); return out;
        }
    }
    private static void food() throws Exception {
        Fixture f = new Fixture(); Map<String, byte[]> saved = f.save();
        check(f.ecology.regionAt(8, 8).foodBonus(RABBIT) == 8, "field did not feed rabbits");
        var absentWorld = new World(3, GenProfile.FLAT);
        var reopened = new Ecology(absentWorld, new WorldClock(.5), new EntityStore(), new Participants());
        reopened.restore(saved, bad -> { throw new AssertionError("valid field habitat damaged"); });
        reopened.advanceDays(1);
        check(reopened.regionAt(8, 8).foodBonus(RABBIT) == 8, "unloaded field lost its food estimate");
        absentWorld.publish(new Chunk(0, 0));
        reopened.advanceDays(1);
        check(reopened.regionAt(8, 8).foodBonus(RABBIT) == 0, "removed loaded field still fed rabbits");
        Fixture protectedField = new Fixture();
        protectedField.save(); protectedField.field.markReadOnly();
        check(protectedField.ecology.regionAt(8, 8).foodBonus(RABBIT) == 8, "protected field lost observation");
    }
    private static void future() throws Exception {
        Fixture f = new Fixture(); Map<String, byte[]> saved = f.save();
        byte[] before = saved.get(Ecology.SAVE_SECTION).clone(), future = saved.get(FieldHabitat.SECTION).clone();
        future[4] = 2; saved.put(FieldHabitat.SECTION, future);
        f.ecology.restore(saved, bad -> { throw new AssertionError("future data treated as corruption"); });
        check(!f.ecology.enabled() && !f.ecology.supported(), "future habitat ran current rules");
        Map<String, byte[]> after = f.save();
        check(Arrays.equals(before, after.get(Ecology.SAVE_SECTION))
                && Arrays.equals(future, after.get(FieldHabitat.SECTION)), "opaque ecology fields were rewritten");
    }
    private static void damage() throws Exception {
        Fixture f = new Fixture(); Map<String, byte[]> saved = f.save();
        byte[] bad = { 1, 3, 5 }; saved.put(FieldHabitat.SECTION, bad);
        List<byte[]> evidence = new ArrayList<>(); f.ecology.restore(saved, evidence::add);
        check(evidence.size() == 1 && Arrays.equals(bad, evidence.get(0)) && f.ecology.enabled(),
                "damage was rebuilt without preserved evidence");
        check(f.ecology.regionAt(8, 8).foodBonus(RABBIT) == 0, "damaged estimates survived rebuild");
        f.ecology.restore(saved, bytes -> { throw new IllegalStateException("cannot preserve"); });
        Map<String, byte[]> opaque = f.save();
        check(!f.ecology.enabled() && Arrays.equals(bad, opaque.get(FieldHabitat.SECTION)),
                "evidence failure discarded data");
    }
    private static void bound() throws Exception {
        FieldHabitat capped = new FieldHabitat(); capped.observe(crops(0, 0, 300));
        check(capped.crops(0) == 256 && capped.bonus(0) == 64, "food cap changed its saturated bonus");
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(0x46484C44); out.writeByte(FieldHabitat.VERSION); VarInt.write(out, FieldHabitat.MAX_REGIONS);
            for (int region = 0; region < FieldHabitat.MAX_REGIONS; region++) {
                out.writeInt(region); out.writeInt(0); VarInt.write(out, 256);
                for (int cell = 0; cell < 256; cell++) { out.writeByte(cell); VarInt.write(out, 256); }
            }
            out.write(new byte[4096]);
        }
        byte[] maximal = bytes.toByteArray();
        check(maximal.length < SectionCodec.MAX_SECTION_BYTES
                && Arrays.equals(maximal, FieldHabitat.decode(maximal).encode()), "maximal legal fields cannot be saved");
    }
    private static void eviction() throws Exception {
        World world = new World(3, GenProfile.FLAT);
        Chunk field = world.publish(crops(0, 0, 32));
        var session = new WorldSession(world, new WorldClock(.5),
                new com.mineclone.world.entity.MobSpawner(3), new EntityStore(), new Participants(),
                new WorldSession.Host() {
                    public org.joml.Vector3f mobFocus() { return new org.joml.Vector3f(8, 65, 8); }
                    public boolean spawnMobs() { return false; }
                }, WorldEvents.NONE);
        Ecology ecology = session.ecology(); ecology.regionAt(8, 8); ecology.setEnabled(true);
        for (int i = 0; i < 32; i++) field.set(i % 16, 65, i / 16, BlockType.AIR);
        session.snapshotChunk(field);
        check(ecology.regionAt(8, 8).foodBonus(RABBIT) == 0, "eviction retained the prior harvested field");
        session.forgetChunk(field); world.removeChunk(0, 0);
        Map<String, byte[]> saved = new LinkedHashMap<>(); ecology.writeSections(saved);
        check(FieldHabitat.decode(saved.get(FieldHabitat.SECTION)).bonus(0) == 0, "evicted field estimate was not persisted");
    }
}

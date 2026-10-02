package com.mineclone;

import com.mineclone.data.ResourceId;
import com.mineclone.sim.EntityStore;
import com.mineclone.sim.Participants;
import com.mineclone.sim.WorldClock;
import com.mineclone.sim.ecology.BurnScars;
import com.mineclone.sim.ecology.Ecology;
import com.mineclone.sim.ecology.EcologyCodec;
import com.mineclone.sim.ecology.EcologyModel;
import com.mineclone.sim.ecology.EcologyRegion;
import com.mineclone.sim.ecology.FieldHabitat;
import com.mineclone.sim.ecology.Lair;
import com.mineclone.sim.ecology.LairTable;
import com.mineclone.sim.ecology.SpeciesTable;
import com.mineclone.world.Biome;
import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.World;
import com.mineclone.world.behavior.DropSink;
import com.mineclone.world.entity.Mob;
import com.mineclone.world.entity.MobType;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Read-only ecology reports expose existing model history and bounded nearby known locations. */
public final class EcologyDiagnosticsTests {
    private static final ResourceId COW = ResourceId.of("cow");
    private static final ResourceId RABBIT = ResourceId.of("rabbit");

    public static void runAll(TestMain.Runner runner) {
        runner.run("ecology reports never initialize an unobserved region or generate terrain", EcologyDiagnosticsTests::unobserved);
        runner.run("repeated F3 reports leave loaded fields, protected terrain, schedules and save bytes untouched", EcologyDiagnosticsTests::readOnly);
        runner.run("population reports retain signed daily model trends, effective capacity and reserved bodies", EcologyDiagnosticsTests::restoredTrend);
        runner.run("reported three-day deltas equal actual daily model results", EcologyDiagnosticsTests::modelTrend);
        runner.run("direct body births and deaths stay separate from the explicitly labeled model trend", EcologyDiagnosticsTests::directEvents);
        runner.run("nearest known lairs cross region boundaries with stable ties and bounded search", EcologyDiagnosticsTests::nearestLair);
        runner.run("burn reports distinguish fresh history, planted-once sites and future dates without rewriting records", EcologyDiagnosticsTests::burnHistory);
        runner.run("ecology reports preserve unsupported bytes and show explicit viewport overflow", EcologyDiagnosticsTests::protectionAndViewport);
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static boolean has(String[] lines, String text) { return Arrays.stream(lines).anyMatch(line -> line.contains(text)); }
    private static void contains(String[] lines, String text) { check(has(lines, text), text + " missing from " + Arrays.toString(lines)); }
    private static String speciesLine(String[] lines, String type) {
        return Arrays.stream(lines).filter(line -> line.startsWith(type + ":")).findFirst().orElseThrow();
    }

    private static final class Fixture {
        final World world = new World(20261010L) {
            @Override public Chunk generateDetached(int cx, int cz) {
                Chunk chunk = new Chunk(cx, cz);
                for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) chunk.set(x, 40, z, BlockType.GRASS);
                chunk.computeSkyLight(); return chunk;
            }
        };
        final WorldClock clock = new WorldClock(.5);
        final EntityStore entities = new EntityStore();
        final Ecology ecology = new Ecology(world, clock, entities, new Participants());

        Fixture() { world.simulate(DropSink.NONE, clock.worldTicks()); }
        void day(long day) { clock.setGameTime(day * (Math.PI * 2) + .5); }
        void restore(List<EcologyRegion> regions) throws Exception { restore(regions, Map.of(), new byte[0]); }
        void restore(List<EcologyRegion> regions, Map<Long, EcologyCodec.Reservation> reservations, byte[] tail) throws Exception {
            ecology.restore(Map.of(Ecology.SAVE_SECTION, EcologyCodec.encode(new EcologyCodec.Snapshot(regions, reservations, tail))),
                    bytes -> { throw new AssertionError("valid report fixture requested recovery"); });
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return (T) field.get(owner);
    }

    /** Capture raw state directly: writeSections itself performs simulation-boundary maintenance. */
    private static byte[] state(Fixture fixture) throws Exception {
        Map<Long, EcologyRegion> regions = field(fixture.ecology, "regions");
        Map<Long, EcologyCodec.Reservation> reservations = field(fixture.ecology, "reservations");
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            blob(out, EcologyCodec.encode(new EcologyCodec.Snapshot(new ArrayList<>(regions.values()), reservations,
                    field(fixture.ecology, "sectionTail"))));
            blob(out, ((FieldHabitat) field(fixture.ecology, "fields")).encode());
            blob(out, field(fixture.ecology, "opaqueSection")); blob(out, field(fixture.ecology, "opaqueFields"));
            blob(out, field(fixture.ecology, "rules")); blob(out, fixture.clock.encode());
            out.writeBoolean(fixture.ecology.enabled()); out.writeBoolean(fixture.ecology.supported());
            out.writeLong(fixture.ecology.scars().revision());
            out.writeLong(field(fixture.ecology, "lastTickDay")); out.writeFloat(field(fixture.ecology, "scarTimer"));
            for (EcologyRegion region : regions.values()) {
                for (Lair lair : fixture.ecology.lairs().lairs(region.key())) blob(out, lair.encode());
                for (var scar : fixture.ecology.scars().scars(region.key())) blob(out, scar.encode());
            }
            List<Chunk> chunks = new ArrayList<>(); fixture.world.getLoadedChunks().forEach(chunks::add);
            chunks.sort(Comparator.comparingInt((Chunk c) -> c.cx).thenComparingInt(c -> c.cz));
            out.writeInt(chunks.size());
            for (Chunk chunk : chunks) {
                out.writeInt(chunk.cx); out.writeInt(chunk.cz); out.writeBoolean(chunk.isReadOnly()); out.writeBoolean(chunk.modified);
                blob(out, chunk.copyBlocks()); blob(out, chunk.copyMeta());
                out.writeLong(chunk.scheduledTicks().origin());
                long[] ticks = chunk.scheduledTicks().entries(); out.writeInt(ticks.length);
                for (long tick : ticks) out.writeLong(tick);
            }
        }
        return bytes.toByteArray();
    }

    private static void blob(DataOutputStream out, byte[] bytes) throws Exception {
        out.writeInt(bytes == null ? -1 : bytes.length); if (bytes != null) out.write(bytes);
    }

    private static void unobserved() throws Exception {
        Fixture fixture = new Fixture(); byte[] before = state(fixture);
        for (int i = 0; i < 30; i++) {
            contains(fixture.ecology.info(-.5f, -256.5f), "region -1, -2 | not observed");
            contains(fixture.ecology.info(-.5f, -256.5f), "unavailable until region is observed");
            contains(fixture.ecology.lairInfo(-.5f, -256.5f), "No known spider nest in this region");
        }
        contains(fixture.ecology.info(Float.NaN, 0), "invalid diagnostic position");
        contains(fixture.ecology.lairInfo(0, Float.POSITIVE_INFINITY), "invalid diagnostic position");
        check(Arrays.equals(before, state(fixture)), "reading an unseen or invalid position changed state");
    }

    private static void readOnly() throws Exception {
        Fixture fixture = new Fixture();
        fixture.ecology.regionAt(8, 8); fixture.ecology.setEnabled(true);
        fixture.world.getChunk(0, 0); fixture.world.getChunk(1, 0).markReadOnly();
        for (int x = 2; x < 10; x++) fixture.world.setBlock(x, 41, 2, BlockType.WHEAT, (byte) 7);
        fixture.world.scheduleTickAt(2, 41, 2, BlockType.WHEAT, 1234);
        byte[] before = state(fixture);
        for (int i = 0; i < 50; i++) {
            contains(fixture.ecology.info(8.5f, 8.5f), "Fields: 0 crop estimate | rabbit food +0");
            fixture.ecology.lairInfo(8.5f, 8.5f); fixture.ecology.info(4096.5f, -4096.5f);
        }
        check(Arrays.equals(before, state(fixture)), "diagnostics refreshed crops, allocated a region or altered terrain/ticks/clock");
    }

    private static void restoredTrend() throws Exception {
        Fixture fixture = new Fixture(); fixture.day(10);
        EcologyRegion region = new EcologyRegion(0, 0, 9);
        region.population(COW, 40); region.capacity(COW, 24); region.foodBonus(COW, 5);
        region.trend(COW, new int[] { -3, 2, 7 });
        region.population(RABBIT, SpeciesTable.MAX_POPULATION); region.capacity(RABBIT, SpeciesTable.MAX_POPULATION);
        region.foodBonus(RABBIT, 64); region.trend(RABBIT, new int[] { 1_000_000, -1_000_000, 1_000_000 });
        ResourceId unknown = ResourceId.of("future:fox"); region.population(unknown, 13);
        region.trend(unknown, new int[] { -2, 1, 3 }); region.tail(new byte[] { 9, 8, 7 });
        region.lairs(List.of(new byte[] { 2, 3, 4 })); region.scars(List.of(new byte[] { 5, 6, 7 }));
        Map<Long, EcologyCodec.Reservation> bodies = Map.of(701L, new EcologyCodec.Reservation(701, 0, 0, COW),
                702L, new EcologyCodec.Reservation(702, 0, 0, COW));
        fixture.restore(List.of(region), bodies, new byte[] { 55, 66 }); byte[] before = state(fixture);
        String[] lines = fixture.ecology.info(8, 8);
        check(speciesLine(lines, "COW").equals("COW: 40 / 29 | 2 | +6 [-3,+2,+7]"), "stored signed trend/capacity/body count changed");
        check(speciesLine(lines, "RABBIT").contains("1000000 / 1000000 | 0 | +1000000 [+1000000,-1000000,+1000000]"),
                "report failed the bounded full-width count or effective-capacity clamp");
        contains(lines, "snapshot day 9"); contains(lines, "Model trend starts with zero slots; direct births/deaths are excluded");
        check(Arrays.equals(before, state(fixture)), "report rewrote unknown species, tails or opaque records");
    }

    private static void modelTrend() throws Exception {
        SpeciesTable table = SpeciesTable.get();
        List<EcologyRegion> snapshots = List.of(EcologyRegion.initial(0, 0, 0, table, Biome.FOREST));
        int[] changes = new int[3];
        int initial = snapshots.get(0).population(COW);
        for (int day = 1; day <= 3; day++) {
            int previous = snapshots.get(0).population(COW);
            snapshots = EcologyModel.step(snapshots, table, day, 20261010L);
            changes[day - 1] = snapshots.get(0).population(COW) - previous;
        }
        check(Arrays.equals(changes, snapshots.get(0).trend(COW)), "model fixture does not expose its actual three daily deltas");
        Fixture fixture = new Fixture(); fixture.day(3); fixture.restore(snapshots);
        String expected = signed(snapshots.get(0).population(COW) - initial) + " ["
                + signed(changes[0]) + "," + signed(changes[1]) + "," + signed(changes[2]) + "]";
        check(speciesLine(fixture.ecology.info(8, 8), "COW").endsWith(expected), "report differs from actual model days");
    }

    private static String signed(int value) { return (value >= 0 ? "+" : "") + value; }

    private static void directEvents() throws Exception {
        Fixture fixture = new Fixture(); fixture.ecology.setEnabled(true);
        EcologyRegion region = fixture.ecology.regionAt(8, 8);
        region.population(COW, 10); region.trend(COW, new int[] { -1, 2, 4 });
        Mob parent = new Mob(MobType.COW, 8.5f, 41, 8.5f, new Random(1)); parent.persistent = true;
        fixture.entities.assignId(parent); fixture.ecology.adopted(parent);
        Mob child = new Mob(MobType.COW, 9.5f, 41, 8.5f, new Random(2)); child.persistent = true;
        fixture.ecology.born(child); fixture.ecology.died(parent);
        check(region.population(COW) == 10 && Arrays.equals(region.trend(COW), new int[] { -1, 2, 4 }),
                "direct event fixture changed the existing model trend");
        String[] lines = fixture.ecology.info(8, 8);
        check(speciesLine(lines, "COW").endsWith("+5 [-1,+2,+4]"), "direct events were fabricated into the model history");
        contains(lines, "direct births/deaths are excluded");
    }

    private static Lair lair(int x, int y, int z) {
        return new Lair(x, y, z, 0, LairTable.get().byType(MobType.SPIDER));
    }

    private static void nearestLair() throws Exception {
        Lair current = lair(-200, 60, 8), adjacent = lair(0, 30, 8); adjacent.claim(801);
        EcologyRegion west = new EcologyRegion(-1, 0, 0), east = new EcologyRegion(0, 0, 0);
        west.lairs(List.of(current.encode())); east.lairs(List.of(adjacent.encode()));
        Fixture fixture = new Fixture(); fixture.restore(List.of(west, east));
        String[] lines = fixture.ecology.info(-.5f, 8.5f);
        contains(lines, "Nearest nest: 0,30,8 | 1m | alive"); contains(lines, "Nest population: 6/8 | outside 1");
        contains(fixture.ecology.lairInfo(-.5f, 8.5f), "Nest -200,60,8 alive | spiders 6/8 | outside 0");
        check(Arrays.stream(lines).toList().indexOf("Nearest nest: 0,30,8 | 1m | alive") < 5, "essential nest detail is below species rows");
        for (boolean reverse : new boolean[] { false, true }) {
            Lair low = lair(-2, 30, 0), high = lair(0, 20, 0);
            west.lairs(List.of(low.encode())); east.lairs(List.of(high.encode()));
            fixture.restore(reverse ? List.of(east, west) : List.of(west, east));
            contains(fixture.ecology.info(-.5f, .5f), "Nearest nest: -2,30,0 | 1m");
        }
        EcologyRegion remote = new EcologyRegion(2, 0, 0); remote.lairs(List.of(lair(512, 60, 8).encode()));
        fixture.restore(List.of(remote));
        contains(fixture.ecology.info(255.5f, 8.5f), "Nearest nest: none known in nearby regions");
    }

    private static byte[] scar(int cx, int cz, long cursor, int[] sites, long[] days, boolean[] planted, byte[] tail) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(BurnScars.MAGIC); out.writeByte(BurnScars.VERSION); out.writeInt(cx); out.writeInt(cz);
            out.writeLong(cursor); out.writeByte(sites.length);
            for (int i = 0; i < sites.length; i++) { out.writeShort(sites[i] | (planted[i] ? 0x8000 : 0)); out.writeLong(days[i]); }
            out.write(tail);
        }
        return bytes.toByteArray();
    }

    private static void burnHistory() throws Exception {
        Fixture fixture = new Fixture(); fixture.day(2);
        EcologyRegion local = new EcologyRegion(0, 0, 2), adjacent = new EcologyRegion(1, 0, 2);
        local.scars(List.of(scar(0, 0, 2, new int[] { Chunk.idx(1, 40, 8), Chunk.idx(2, 40, 8) },
                new long[] { 0, 5 }, new boolean[] { true, false }, new byte[] { 11, 22 }), new byte[] { 7, 6, 5 }));
        adjacent.scars(List.of(scar(16, 0, 2, new int[] { Chunk.idx(0, 40, 8) },
                new long[] { 8 }, new boolean[] { false }, new byte[0])));
        fixture.restore(List.of(local, adjacent)); byte[] before = state(fixture);
        String[] lines = fixture.ecology.info(255.5f, 8.5f);
        contains(lines, "Burn history: 1 records | 2 sites | 1 fresh | 1 planted once");
        contains(lines, "Nearest scar: 256,40,8 | 1m | future day 8 | not planted");
        contains(fixture.ecology.info(1.5f, 8.5f), "Nearest scar: 1,40,8 | 0m | age 2d | planted once");
        check(Arrays.equals(before, state(fixture)), "scar query replayed a cursor or rewrote known/opaque tails");
        fixture.day(3); contains(fixture.ecology.info(1.5f, 8.5f), "2 sites | 0 fresh");
        fixture.day(1); contains(fixture.ecology.info(1.5f, 8.5f), "2 sites | 1 fresh");
    }

    private static void protectionAndViewport() throws Exception {
        Fixture fixture = new Fixture(); EcologyRegion region = new EcologyRegion(0, 0, 0);
        byte[] future = EcologyCodec.encode(new EcologyCodec.Snapshot(List.of(region), Map.of()));
        ByteBuffer.wrap(future).putInt(EcologyCodec.VERSION + 1).putInt(EcologyCodec.VERSION + 1);
        byte[] rules = { 1, 2, 3, 4, 5, 6, 7 };
        fixture.ecology.restore(Map.of(Ecology.SAVE_SECTION, future, "rules", rules),
                bytes -> { throw new AssertionError("newer diagnostic data requested rebuild"); });
        byte[] before = state(fixture);
        contains(fixture.ecology.info(8, 8), "Ecology unavailable"); contains(fixture.ecology.lairInfo(8, 8), "Ecology unavailable");
        check(Arrays.equals(before, state(fixture)), "unsupported report changed protected raw state");
        Map<String, byte[]> saved = new LinkedHashMap<>(); fixture.ecology.writeSections(saved);
        check(Arrays.equals(future, saved.get(Ecology.SAVE_SECTION)), "report did not retain newer ecology byte exact");
        var rows = Class.forName("com.mineclone.game.Hud").getDeclaredMethod("ecologyRows", String[].class, int.class);
        rows.setAccessible(true);
        String[] report = { "Ecology ON", "Known locations", "Nearest nest", "Burn history", "Nearest scar", "Species", "COW", "RABBIT" };
        String[] copy = report.clone();
        String[] shown = (String[]) rows.invoke(null, report, 6);
        check(shown.length == 6 && shown[4].equals("Nearest scar") && shown[5].equals("+3 more ecology rows"),
                "small viewport hid essential sites or omitted accurate overflow count");
        String[] tiny = (String[]) rows.invoke(null, report, 1);
        check(tiny.length == 1 && tiny[0].equals("Ecology ON (7 more rows)"), "minimum viewport lost title/overflow");
        check(Arrays.equals(report, copy), "viewport shortened the shared report");
    }
}

package com.mineclone;

import com.mineclone.data.DataPack;
import com.mineclone.data.JsonException;
import com.mineclone.data.ResourceId;
import com.mineclone.sim.ecology.EcologyCodec;
import com.mineclone.sim.ecology.EcologyRegion;
import com.mineclone.sim.ecology.Lair;
import com.mineclone.sim.ecology.LairTable;
import com.mineclone.world.Chunk;
import com.mineclone.world.entity.MobType;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Finite lair accounting, strict data and bounded typed records inside unchanged ecology blobs. */
public final class LairTests {
    private static final String JSON = """
            {"id":"spider_nest","type":"SPIDER","initial":6,"capacity":8,"growth":1,"emit":4,"radius":24}
            """;

    public static void runAll(TestMain.Runner runner) {
        runner.run("spider lair definitions load with bounded typed population and territory", LairTests::definitions);
        runner.run("lair daily growth matches ordinary days and never rewinds or overflows", LairTests::growth);
        runner.run("night claims and dawn returns conserve finite lair population and ids", LairTests::claimsAndReturns);
        runner.run("lair deaths consume a reserved member once and reject duplicate or foreign ids", LairTests::deaths);
        runner.run("destroyed lair hearts stop growth and emission while survivors retain accounting", LairTests::destroyed);
        runner.run("lair records roundtrip full-width ids and opaque suffixes inside existing ecology", LairTests::roundtrip);
        runner.run("opaque lair suffix bounds keep future claims within the existing record slot", LairTests::suffixBounds);
        runner.run("known damaged lair layouts are rejected and unknown or newer records stay opaque", LairTests::codecBounds);
        runner.run("lair JSON refuses invalid fields, types, ranges and duplicate definitions", LairTests::strictData);
    }

    private static LairTable.Definition definition() { return LairTable.get().byId(ResourceId.of("spider_nest")); }
    private static Lair lair() { return new Lair(-19, 34, 258, 0, definition()); }
    private static void check(boolean condition, String why) { if (!condition) throw new AssertionError(why); }

    private static void definitions() throws Exception {
        LairTable table = LairTable.get();
        check(table.definitions().size() == 1, "expected one spider nest definition");
        var definition = definition();
        check(definition.type() == MobType.SPIDER && table.byType(MobType.SPIDER).equals(definition), "wrong nest mob type");
        check(definition.initial() == 6 && definition.capacity() == 8 && definition.growth() == 1
                && definition.emit() == 4 && definition.radius() == 24, "wrong default finite nest parameters");
        Lair lair = lair();
        check(lair.x() == -19 && lair.y() == 34 && lair.z() == 258 && lair.lastDay() == 0
                && lair.alive() && lair.destroyedDay() == -1, "constructor lost location or heart state");
        check(lair.population() == 6 && lair.available() == 4 && lair.bodyIds().isEmpty(), "initial nest accounting");
        check(table.byType(MobType.RABBIT) == null && table.byId(ResourceId.of("mod:unknown")) == null, "missing definitions should stay missing");
        withData(Map.of("addon/ecology/lairs/nest.json", JSON.replace("spider_nest", "catacomb")), custom ->
                check(custom.byId(ResourceId.of("addon:catacomb")) != null, "data namespace was lost"));
    }

    private static void growth() throws Exception {
        var definition = new LairTable.Definition(ResourceId.of("mod:slow_nest"), MobType.SPIDER, 0, 32, 1, 4, 24);
        Lair daily = new Lair(1, 12, 1, 0, definition), caughtUp = new Lair(1, 12, 1, 0, definition);
        for (int day = 1; day <= 20; day++) daily.advance(day);
        caughtUp.advance(20);
        check(daily.population() == 20 && Arrays.equals(daily.encode(), caughtUp.encode()), "catch-up differs from ordinary growth");
        byte[] before = caughtUp.encode();
        caughtUp.advance(19); caughtUp.advance(20);
        check(Arrays.equals(before, caughtUp.encode()), "old or repeated day changed a lair");
        caughtUp.advance(Long.MAX_VALUE);
        check(caughtUp.population() == 32 && caughtUp.lastDay() == Long.MAX_VALUE, "large catch-up overflowed capacity or time");
        Lair nearLimit = new Lair(1, 12, 1, Long.MAX_VALUE - 1, definition());
        nearLimit.advance(Long.MAX_VALUE);
        check(nearLimit.population() == 7, "one day near the long limit was miscounted");
        badArgument(() -> lair().advance(-1));
    }

    private static void claimsAndReturns() throws Exception {
        Lair lair = lair(); long wideId = (1L << 40) + 9;
        check(lair.claim(wideId) && lair.claim(2) && lair.claim(3) && lair.claim(4), "night failed to represent four members");
        check(lair.population() == 6 && lair.available() == 0 && lair.bodyIds().size() == 4, "emission added animals or exceeded its limit");
        check(!lair.claim(wideId) && !lair.claim(5) && !lair.claim(0) && !lair.claim(-1), "duplicate, exhausted or invalid claim accepted");
        try { lair.bodyIds().clear(); throw new AssertionError("body ids expose mutable reservations"); }
        catch (UnsupportedOperationException expected) {}
        check(lair.returned(2) && !lair.returned(2) && !lair.returned(99) && lair.population() == 6,
                "dawn returns consumed population or counted twice");
        check(lair.claim(5), "a returned body did not free an emission slot");
        for (long id : lair.bodyIds()) check(lair.returned(id), "dawn failed to return a remaining body");
        lair.advance(1);
        check(lair.population() == 7 && lair.bodyIds().isEmpty() && lair.available() == 4, "day growth or next night budget incorrect");

        Lair small = new Lair(1, 12, 1, 0, new LairTable.Definition(ResourceId.of("mod:small"), MobType.SPIDER, 2, 8, 0, 4, 24));
        check(small.claim(1) && small.claim(2) && !small.claim(3), "emission exceeded the actual population");
        Lair maximum = new Lair(1, 12, 1, 0, new LairTable.Definition(ResourceId.of("mod:large"), MobType.SPIDER, 32, 32, 0, 32, 24));
        for (long id = 1; id <= 32; id++) check(maximum.claim(id), "bounded maximum claim failed");
        check(!maximum.claim(33) && Lair.decode(maximum.encode()).bodyIds().size() == 32, "body cap exceeded or failed roundtrip");
    }

    private static void deaths() {
        Lair lair = lair();
        for (long id = 1; id <= 4; id++) check(lair.claim(id), "death fixture could not claim bodies");
        check(lair.died(2) && lair.population() == 5 && lair.bodyIds().size() == 3 && lair.available() == 1,
                "death failed to consume exactly one represented member");
        check(!lair.died(2) && !lair.died(999) && lair.population() == 5, "duplicate or foreign death consumed an animal");
        check(lair.returned(3) && !lair.died(3) && lair.population() == 5, "stale death consumed a returned animal");
        check(lair.claim(8) && lair.claim(9) && !lair.claim(10), "death or return left the wrong emission budget");
    }

    private static void destroyed() throws Exception {
        Lair lair = lair(); check(lair.claim(1) && lair.claim(2), "destroy fixture claims failed");
        check(lair.destroy(3) && !lair.alive() && lair.destroyedDay() == 3 && lair.lastDay() == 3, "heart death was not recorded");
        check(lair.population() == 6 && lair.bodyIds().equals(Set.of(1L, 2L)), "heart destruction erased survivors");
        check(!lair.claim(3) && lair.available() == 0 && !lair.destroy(8), "dead heart emitted or was destroyed twice");
        lair.advance(6); lair.advance(1000);
        check(lair.population() == 6 && lair.destroyedDay() == 3 && lair.lastDay() == 1000,
                "destroyed nest regrew over later days or changed its destruction date");
        Lair restored = Lair.decode(lair.encode());
        check(!restored.alive() && restored.bodyIds().equals(Set.of(1L, 2L)), "destroyed nest lost survivors on reload");
        check(restored.returned(1) && restored.population() == 6 && restored.died(2) && restored.population() == 5,
                "survivor return/death after heart destruction lost accounting");
        check(!restored.died(2) && restored.available() == 0, "dead lair revived after its last emitted body died");
        badArgument(() -> restored.destroy(-1));
    }

    private static void roundtrip() throws Exception {
        Lair lair = lair();
        lair.claim((1L << 40) + 17); lair.claim(7); lair.claim(2); lair.advance(1);
        byte[] tail = {9, 4, 3, 1}; lair.tail(tail); tail[0] = 0;
        byte[] bytes = lair.encode(); Lair restored = Lair.decode(bytes);
        check(restored.id().equals(lair.id()) && restored.type() == MobType.SPIDER
                && restored.population() == 7 && restored.capacity() == 8 && restored.growth() == 1
                && restored.emit() == 4 && restored.radius() == 24 && restored.bodyIds().equals(lair.bodyIds()), "typed lair fields changed on reload");
        check(Arrays.equals(restored.tail(), new byte[]{9, 4, 3, 1}), "opaque suffix was not detached from its source");
        restored.tail()[0] = 0;
        for (int cycle = 0; cycle < 5; cycle++) restored = Lair.decode(restored.encode());
        check(Arrays.equals(bytes, restored.encode()), "repeated lair rewrite changed ids or opaque suffix");
        byte[] unknown = {1, 3, 5};
        EcologyRegion region = new EcologyRegion(-1, 1, 1); region.lairs(List.of(bytes, unknown));
        byte[] ecology = EcologyCodec.encode(new EcologyCodec.Snapshot(List.of(region), Map.of()));
        var snapshot = EcologyCodec.decode(ecology);
        check(Arrays.equals(ecology, EcologyCodec.encode(snapshot)), "existing ecology envelope changed typed or unknown lair blobs");
        var records = snapshot.regions().get(0).lairs();
        check(Arrays.equals(records.get(0), bytes) && Arrays.equals(records.get(1), unknown)
                && Lair.decode(records.get(1)) == null, "unrecognized lair data was interpreted or discarded");
        check(Lair.decode(null) == null && Lair.decode(new byte[0]) == null, "missing or unknown lair magic must stay opaque");
    }

    private static void suffixBounds() throws Exception {
        Lair lair = lair(); int base = lair.encode().length;
        byte[] suffix = new byte[Lair.MAX_RECORD_BYTES - base - Long.BYTES]; Arrays.fill(suffix, (byte) 73);
        lair.tail(suffix);
        check(lair.available() == 1 && lair.claim(1) && !lair.claim(2), "suffix did not constrain new reservations to the record slot");
        check(lair.encode().length == Lair.MAX_RECORD_BYTES, "bounded record did not fit its outer ecology slot");
        Lair restored = Lair.decode(lair.encode());
        check(Arrays.equals(restored.tail(), suffix) && restored.available() == 0, "full record suffix changed on decode");
        check(restored.returned(1) && restored.claim(2) && restored.encode().length == Lair.MAX_RECORD_BYTES,
                "return/reclaim overflowed the saved suffix record");
        byte[] before = restored.encode();
        badArgument(() -> restored.tail(new byte[Lair.MAX_RECORD_BYTES]));
        check(Arrays.equals(before, restored.encode()), "refused oversized suffix changed the record");
        Lair full = lair(); full.tail(new byte[Lair.MAX_RECORD_BYTES - base]);
        check(full.available() == 0 && !full.claim(1) && full.encode().length == Lair.MAX_RECORD_BYTES,
                "completely opaque suffix permitted an unsavable body claim");
    }

    private static void codecBounds() throws Exception {
        byte[] base = lair().encode();
        for (int length = 0; length < 4; length++) check(Lair.decode(Arrays.copyOf(base, length)) == null, "short unknown magic was interpreted");
        for (int length : new int[]{4, 5, base.length - 1}) rejected(Arrays.copyOf(base, length));
        rejected(Arrays.copyOf(base, Lair.MAX_RECORD_BYTES + 1));
        byte[] newer = base.clone(); newer[4] = 2; byte[] original = newer.clone();
        try { Lair.decode(newer); throw new AssertionError("newer lair layout was interpreted"); }
        catch (Lair.NewerVersionException expected) { check(expected.version() == 2, "future lair version was lost"); }
        check(Arrays.equals(original, newer), "decoding modified future bytes");
        byte[] bad = base.clone(); bad[4] = 0; rejected(bad);
        int count = base.length - 1, destroyed = count - Long.BYTES, alive = destroyed - 1, population = alive - 5 * Integer.BYTES;
        for (int[] invalid : new int[][]{{population, -1}, {population, 9}, {population + 4, 0}, {population + 4, 33},
                {population + 8, -1}, {population + 8, 9}, {population + 12, 0}, {population + 12, 9},
                {population + 16, 0}, {population + 16, 129}, {population - 16, -1}, {population - 16, Chunk.SIZE_Y}}) {
            bad = base.clone(); ByteBuffer.wrap(bad).putInt(invalid[0], invalid[1]); rejected(bad);
        }
        bad = base.clone(); ByteBuffer.wrap(bad).putLong(population - 8, -1); rejected(bad);
        bad = base.clone(); bad[alive] = 2; rejected(bad);
        bad = base.clone(); bad[alive] = 0; rejected(bad);
        bad = base.clone(); ByteBuffer.wrap(bad).putLong(destroyed, 0); rejected(bad);
        bad = base.clone(); bad[alive] = 0; ByteBuffer.wrap(bad).putLong(destroyed, 1); rejected(bad);
        bad = base.clone(); bad[count] = 33; rejected(bad);
        try (var in = new DataInputStream(new ByteArrayInputStream(base))) {
            in.readInt(); in.readUnsignedByte(); in.readUTF();
            int typeStart = base.length - in.available() + 2;
            bad = base.clone(); bad[typeStart] = '?'; rejected(bad);
            byte[] futureType = base.clone();
            System.arraycopy("BEETLE".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, futureType, typeStart, 6);
            try { Lair.decode(futureType); throw new AssertionError("valid future lair type was interpreted"); }
            catch (Lair.UnsupportedDefinitionException expected) { check(expected.getMessage().contains("BEETLE"), "unsupported type identity lost"); }
            try { Lair.decode(Arrays.copyOf(futureType, futureType.length - 1)); throw new AssertionError("truncated future type record accepted"); }
            catch (Lair.UnsupportedDefinitionException invalidClassification) { throw new AssertionError("malformed known layout classified as a valid future definition"); }
            catch (IOException expected) {}
        }
        Lair lair = lair(); lair.claim(1); lair.claim(2);
        byte[] bodies = lair.encode();
        bad = bodies.clone(); ByteBuffer.wrap(bad).putLong(base.length + Long.BYTES, 1); rejected(bad);
        bad = bodies.clone(); ByteBuffer.wrap(bad).putLong(base.length, 0); rejected(bad);
        bad = bodies.clone(); ByteBuffer.wrap(bad).putInt(population, 1); rejected(bad);
        badArgument(() -> new Lair(0, -1, 0, 0, definition()));
        badArgument(() -> new Lair(0, Chunk.SIZE_Y, 0, 0, definition()));
        badArgument(() -> new Lair(0, 12, 0, -1, definition()));
    }

    private static void strictData() throws Exception {
        for (String invalid : List.of(JSON.replace("\"SPIDER\"", "\"RABBIT\""), JSON.replace("\"SPIDER\"", "\"UNKNOWN\""),
                JSON.replace("\"capacity\":8", "\"capacity\":33"), JSON.replace("\"capacity\":8", "\"capacity\":8.5"),
                JSON.replace("\"initial\":6", "\"initial\":9"), JSON.replace("\"initial\":6", "\"initial\":-1"),
                JSON.replace("\"growth\":1", "\"growth\":9"), JSON.replace("\"emit\":4", "\"emit\":0"),
                JSON.replace("\"radius\":24", "\"radius\":129"), JSON.replace("\"radius\":24", "\"radius\":0"),
                JSON.replace("\"radius\":24", "\"radius\":24,\"typo\":true"), JSON.replace("\"initial\":6,", ""))) {
            refusedData(Map.of("mineclone/ecology/lairs/nest.json", invalid));
        }
        refusedData(Map.of());
        refusedData(Map.of("mineclone/ecology/lairs/one.json", JSON, "mineclone/ecology/lairs/two.json", JSON.replace("spider_nest", "another")));
        refusedData(Map.of("mineclone/ecology/lairs/one.json", JSON, "mineclone/ecology/lairs/two.json", JSON.replace("SPIDER", "ZOMBIE")));
        badArgument(() -> new LairTable.Definition(ResourceId.of("bad"), MobType.SPIDER, 0, 33, 1, 4, 24));
    }

    @FunctionalInterface private interface Action { void run() throws Exception; }
    @FunctionalInterface private interface DataCheck { void run(LairTable table) throws Exception; }
    private static void badArgument(Action action) throws Exception {
        try { action.run(); throw new AssertionError("invalid lair argument accepted"); }
        catch (IllegalArgumentException expected) {}
    }
    private static void rejected(byte[] bytes) throws Exception {
        try { Lair.decode(bytes); throw new AssertionError("known damaged lair record accepted"); }
        catch (IOException expected) {}
    }
    private static void refusedData(Map<String, String> files) throws Exception {
        try { withData(files, ignored -> {}); throw new AssertionError("invalid lair data accepted"); }
        catch (IllegalArgumentException | JsonException expected) {}
    }
    private static void withData(Map<String, String> files, DataCheck check) throws Exception {
        Path output = Path.of("out-test").toAbsolutePath(); Files.createDirectories(output);
        Path temp = Files.createTempDirectory(output, "lair-data-");
        try {
            for (var entry : files.entrySet()) {
                Path file = temp.resolve(entry.getKey()); Files.createDirectories(file.getParent()); Files.writeString(file, entry.getValue());
            }
            check.run(LairTable.load(new DataPack(temp)));
        } finally {
            check(temp.toAbsolutePath().startsWith(output), "temporary lair data escaped the test output directory");
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}

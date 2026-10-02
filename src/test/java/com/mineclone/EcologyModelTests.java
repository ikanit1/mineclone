package com.mineclone;

import com.mineclone.data.DataPack;
import com.mineclone.data.JsonException;
import com.mineclone.data.ResourceId;
import com.mineclone.sim.ecology.EcologyCodec;
import com.mineclone.sim.ecology.EcologyModel;
import com.mineclone.sim.ecology.EcologyRegion;
import com.mineclone.sim.ecology.SpeciesTable;
import com.mineclone.world.Biome;
import com.mineclone.world.entity.MobType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** LW-01: finite daily populations and food chains are deterministic, bounded snapshots. */
public final class EcologyModelTests {
    private static final ResourceId RABBIT = ResourceId.of("rabbit"), WOLF = ResourceId.of("wolf");
    public static void runAll(TestMain.Runner r) {
        r.run("ecology data covers all seven animals with strict biome and predator rules", EcologyModelTests::definitions);
        r.run("removing four wolves lets rabbits grow by at least half in ten days", EcologyModelTests::wolves);
        r.run("daily ecology is deterministic, input immutable and region-order independent", EcologyModelTests::determinism);
        r.run("regions at capacity stay stable without predation or events", EcologyModelTests::stable);
        r.run("simultaneous migration conserves animals and uses cardinal neighbours only", EcologyModelTests::migration);
        r.run("abstract losses and migration never consume represented bodies", EcologyModelTests::protectedBodies);
        r.run("sixty-day catchup matches daily steps and never rewinds future regions", EcologyModelTests::catchup);
        r.run("invalid species fields, rates and prey references fail strict loading", EcologyModelTests::strictData);
    }
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private static void definitions() {
        SpeciesTable table = SpeciesTable.get();
        check(table.definitions().size() == 7, "expected seven finite species");
        for (MobType type : MobType.values()) check((table.byType(type) != null) == !type.hostile, "wrong definition for " + type);
        check(table.byType(MobType.WOLF).capacity(Biome.FOREST) == 5, "forest wolves capacity");
        check(table.byType(MobType.RABBIT).capacity(Biome.FOREST) >= 30, "rabbits need recovery room");
        check(table.byType(MobType.WOLF).prey().contains(RABBIT), "missing wolves/rabbits chain");
        EcologyRegion region = EcologyRegion.initial(-1, -2, 12, table, Biome.FOREST);
        check(region.population(RABBIT) == (int) (table.byType(MobType.RABBIT).capacity(Biome.FOREST) * .7), "lazy 70% initialization");
        check(region.key() == EcologyRegion.key(-1, -2), "negative-region key");
    }
    private static void wolves() {
        SpeciesTable table = SpeciesTable.get();
        EcologyRegion hunted = EcologyRegion.initial(0, 0, 0, table, Biome.FOREST);
        hunted.population(RABBIT, 20); hunted.population(WOLF, 1);
        EcologyRegion intact = hunted.copy(); intact.population(WOLF, 5);
        List<EcologyRegion> a = List.of(hunted), b = List.of(intact);
        for (int day = 1; day <= 10; day++) { a = EcologyModel.step(a, table, day, 20260927); b = EcologyModel.step(b, table, day, 20260927); }
        check(a.get(0).population(RABBIT) >= 30, "rabbits did not grow by 50% after four wolf deaths: " + a.get(0).population(RABBIT));
        check(a.get(0).population(RABBIT) > b.get(0).population(RABBIT), "wolf losses do not affect rabbits");
    }
    private static void determinism() throws Exception {
        SpeciesTable table = SpeciesTable.get();
        var a = EcologyRegion.initial(-3, 7, 0, table, Biome.FOREST);
        var b = EcologyRegion.initial(-2, 7, 0, table, Biome.PLAINS);
        byte[] before = encoded(List.of(a, b));
        var first = EcologyModel.step(List.of(a, b), table, 1, 8888);
        var second = EcologyModel.step(List.of(b, a), table, 1, 8888);
        check(Arrays.equals(encoded(first), encoded(second)), "input iteration order changed model");
        check(Arrays.equals(before, encoded(List.of(a, b))), "pure daily step mutated input");
        check(Arrays.equals(encoded(first), encoded(EcologyModel.step(List.of(a, b), table, 1, 8888))), "repeat changed daily model");
    }
    private static void stable() {
        SpeciesTable table = SpeciesTable.get();
        var region = EcologyRegion.initial(0, 0, 0, table, Biome.PLAINS);
        for (SpeciesTable.Definition species : table.definitions()) region.population(species.id(), region.capacity(species.id()));
        Map<ResourceId, Integer> counts = Map.copyOf(region.populations());
        List<EcologyRegion> state = List.of(region);
        for (int day = 1; day <= 30; day++) state = EcologyModel.step(state, table, day, 123);
        check(state.get(0).populations().equals(counts), "capacity without predators is not stable");
    }
    private static void migration() throws Exception {
        withData("""
                {"id":"rabbit","type":"RABBIT","capacity":{"plains":100},"birthRate":0,
                 "migrationRate":1,"prey":[],"predationRate":0,"groupMin":1,"groupMax":1,"minimum":0}
                """, table -> {
            EcologyRegion source = new EcologyRegion(0, 0, 0); source.capacity(RABBIT, 10); source.population(RABBIT, 30);
            EcologyRegion east = new EcologyRegion(1, 0, 0); east.capacity(RABBIT, 10);
            EcologyRegion north = new EcologyRegion(0, 1, 0); north.capacity(RABBIT, 10);
            EcologyRegion diagonal = new EcologyRegion(1, 1, 0); diagonal.capacity(RABBIT, 100);
            var next = EcologyModel.step(List.of(source, east, north, diagonal), table, 1, 10);
            int total = next.stream().mapToInt(region -> region.population(RABBIT)).sum();
            check(total == 30, "migration changed total " + total);
            check(at(next, 0, 0).population(RABBIT) == 10, "excess did not migrate");
            check(at(next, 1, 0).population(RABBIT) == 10 && at(next, 0, 1).population(RABBIT) == 10, "neighbours did not receive excess");
            check(at(next, 1, 1).population(RABBIT) == 0, "migrants moved twice or diagonally in one day");
        });
    }
    private static void protectedBodies() {
        SpeciesTable table = SpeciesTable.get();
        EcologyRegion region = EcologyRegion.initial(0, 0, 0, table, Biome.FOREST);
        region.capacity(RABBIT, 2); region.population(RABBIT, 40); region.protectedCount(RABBIT, 40);
        region.population(WOLF, 5);
        EcologyRegion neighbour = EcologyRegion.initial(1, 0, 0, table, Biome.FOREST);
        List<EcologyRegion> state = List.of(region, neighbour);
        for (int day = 1; day <= 20; day++) state = EcologyModel.step(state, table, day, 9);
        check(at(state, 0, 0).population(RABBIT) == 40, "model consumed/migrated physical animals or bred over capacity");
        int[] trend = at(state, 0, 0).trend(RABBIT); check(Arrays.equals(trend, new int[3]), "protected count drifted");
    }
    private static void catchup() throws Exception {
        SpeciesTable table = SpeciesTable.get();
        var first = EcologyRegion.initial(0, 0, 0, table, Biome.FOREST);
        var second = EcologyRegion.initial(1, 0, 0, table, Biome.PLAINS);
        List<EcologyRegion> daily = List.of(first, second);
        for (int day = 1; day <= 20; day++) daily = EcologyModel.step(daily, table, day, 42);
        check(Arrays.equals(encoded(daily), encoded(EcologyModel.catchUp(List.of(first, second), table, 20, 42))), "catchup differs from ordinary days");
        var future = second.copy(); future.lastDay(30);
        byte[] saved = encoded(List.of(future));
        var mixed = EcologyModel.step(List.of(first, future), table, 1, 42);
        check(Arrays.equals(saved, encoded(List.of(at(mixed, 1, 0)))), "future region was changed by old day");
        var cap = EcologyModel.catchUp(List.of(first), table, 1000, 42);
        var limited = first.copy(); limited.lastDay(940);
        for (int day = 941; day <= 1000; day++) limited = EcologyModel.step(List.of(limited), table, day, 42).get(0);
        check(Arrays.equals(encoded(cap), encoded(List.of(limited))), "catchup exceeded 60 days");
    }
    private static void strictData() throws Exception {
        String valid = "{\"id\":\"rabbit\",\"type\":\"RABBIT\",\"capacity\":{\"plains\":100},\"birthRate\":0,\"migrationRate\":1,\"prey\":[],\"predationRate\":0,\"groupMin\":1,\"groupMax\":1,\"minimum\":0}";
        for (String invalid : List.of(valid.replace("\"birthRate\":0", "\"birthRate\":-1"),
                valid.replace("\"plains\"", "\"space\""), valid.replace("\"prey\":[]", "\"prey\":[\"unknown\"]"),
                valid.replace("\"minimum\":0", "\"minimum\":0,\"typo\":1"))) {
            final boolean[] failed = { false };
            try { withData(invalid, ignored -> {}); } catch (IllegalArgumentException | JsonException expected) { failed[0] = true; }
            check(failed[0], "strict loader accepted invalid data");
        }
    }
    private static EcologyRegion at(List<EcologyRegion> regions, int x, int z) {
        return regions.stream().filter(region -> region.x() == x && region.z() == z).findFirst().orElseThrow();
    }
    private static byte[] encoded(List<EcologyRegion> regions) throws Exception { return EcologyCodec.encode(new EcologyCodec.Snapshot(regions, Map.of())); }
    @FunctionalInterface private interface DataCheck { void run(SpeciesTable table) throws Exception; }
    private static void withData(String json, DataCheck check) throws Exception {
        Path temp = Files.createTempDirectory("mineclone-ecology-data-");
        try {
            Path file = temp.resolve("mineclone/ecology/species/rabbit.json"); Files.createDirectories(file.getParent()); Files.writeString(file, json);
            check.run(SpeciesTable.load(new DataPack(temp)));
        } finally { try (var files = Files.walk(temp)) { for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file); } }
    }
}

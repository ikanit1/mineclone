package com.mineclone.sim.ecology;

import com.mineclone.data.ResourceId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure deterministic daily births, predation and simultaneous cardinal-neighbour migration. */
public final class EcologyModel {
    private EcologyModel() {}
    private static final int[][] DIRECTIONS = { { 1, 0 }, { 0, 1 }, { -1, 0 }, { 0, -1 } };

    /** Input snapshots are untouched; regions already at or beyond this day are retained. */
    public static List<EcologyRegion> step(Collection<EcologyRegion> source, SpeciesTable table, long day, long seed) {
        if (day < 0) throw new IllegalArgumentException("negative ecology day");
        var regions = source.stream().sorted(Comparator.comparingInt(EcologyRegion::x).thenComparingInt(EcologyRegion::z))
                .map(EcologyRegion::copy).toList();
        var originals = new HashMap<Long, EcologyRegion>();
        var byKey = new LinkedHashMap<Long, EcologyRegion>();
        for (EcologyRegion region : source)
            if (originals.put(region.key(), region) != null) throw new IllegalArgumentException("duplicate ecology region");
        for (EcologyRegion region : regions) byKey.put(region.key(), region);

        for (EcologyRegion result : regions) {
            EcologyRegion before = originals.get(result.key());
            if (before.lastDay() >= day) continue;
            // Capacity is derived by the world facade; food belongs to the same budget.
            for (SpeciesTable.Definition species : table.definitions()) {
                ResourceId id = species.id();
                int n = before.population(id), cap = effectiveCapacity(before, id);
                int minimum = Math.max(before.protectedCount(id), Math.min(species.minimum(), cap));
                int births = n > 0 && n < cap
                        ? rounded(n * species.birthRate() * (1.0 - n / (double) cap), seed, before, day, id, 1) : 0;
                result.population(id, Math.max(minimum, Math.min(SpeciesTable.MAX_POPULATION, n + births)));
            }
            // Abstract predators can consume only the unrepresented population. Bodies are
            // killed by actual combat and accounted once by the world session instead.
            for (SpeciesTable.Definition predator : table.definitions()) {
                int hunters = Math.max(0, before.population(predator.id()) - before.protectedCount(predator.id()));
                if (hunters == 0) continue;
                for (ResourceId preyId : predator.prey()) {
                    SpeciesTable.Definition prey = table.byId(preyId);
                    int n = result.population(preyId), cap = effectiveCapacity(before, preyId);
                    int minimum = Math.max(before.protectedCount(preyId), Math.min(prey.minimum(), cap));
                    int edible = Math.max(0, n - minimum);
                    int loss = rounded(hunters * predator.predationRate(), seed, before, day, preyId, predator.id().hashCode());
                    result.population(preyId, n - Math.min(edible, loss));
                }
            }
            result.lastDay(day);
        }

        // Migration offers and room both come from the post-local-step snapshot. Incoming
        // animals cannot migrate again on this day; input order cannot change the result.
        var pending = new HashMap<Long, Map<ResourceId, Integer>>();
        var room = new HashMap<Long, Map<ResourceId, Integer>>();
        for (EcologyRegion region : regions) {
            var spare = new HashMap<ResourceId, Integer>();
            for (SpeciesTable.Definition species : table.definitions())
                spare.put(species.id(), Math.max(0, effectiveCapacity(region, species.id()) - region.population(species.id())));
            room.put(region.key(), spare);
        }
        for (EcologyRegion region : regions) {
            if (originals.get(region.key()).lastDay() >= day) continue;
            for (SpeciesTable.Definition species : table.definitions()) {
                ResourceId id = species.id();
                int n = region.population(id), excess = n - effectiveCapacity(region, id);
                int movable = n - Math.max(region.protectedCount(id), species.minimum());
                int offered = Math.min(Math.max(0, movable), rounded(Math.max(0, excess) * species.migrationRate(), seed, region, day, id, 3));
                if (offered <= 0) continue;
                int rotate = (int) Long.remainderUnsigned(hash(seed, region, day, id, 4), 4);
                for (int k = 0; k < 4 && offered > 0; k++) {
                    int[] offset = DIRECTIONS[(k + rotate) & 3];
                    long nextX = (long) region.x() + offset[0], nextZ = (long) region.z() + offset[1];
                    if (nextX < Integer.MIN_VALUE || nextX > Integer.MAX_VALUE || nextZ < Integer.MIN_VALUE || nextZ > Integer.MAX_VALUE) continue;
                    long key = EcologyRegion.key((int) nextX, (int) nextZ);
                    if (!byKey.containsKey(key) || originals.get(key).lastDay() >= day) continue;
                    Map<ResourceId, Integer> spare = room.get(key);
                    int moved = Math.min(offered, spare.getOrDefault(id, 0));
                    if (moved == 0) continue;
                    spare.put(id, spare.get(id) - moved);
                    add(pending, region.key(), id, -moved); add(pending, key, id, moved); offered -= moved;
                }
            }
        }
        for (EcologyRegion region : regions) {
            if (originals.get(region.key()).lastDay() >= day) continue;
            for (SpeciesTable.Definition species : table.definitions()) {
                ResourceId id = species.id();
                int next = region.population(id) + pending.getOrDefault(region.key(), Map.of()).getOrDefault(id, 0);
                region.population(id, next);
                int[] old = region.trend(id);
                region.trend(id, new int[] { old[1], old[2], next - originals.get(region.key()).population(id) });
            }
        }
        return regions;
    }

    /** Replay at most 60 missing daily steps; a later clock never causes an unbounded loop. */
    public static List<EcologyRegion> catchUp(Collection<EcologyRegion> source, SpeciesTable table, long targetDay, long seed) {
        if (targetDay < 0) throw new IllegalArgumentException("negative ecology day");
        List<EcologyRegion> regions = new ArrayList<>();
        for (EcologyRegion region : source) {
            EcologyRegion copy = region.copy();
            if (targetDay > copy.lastDay() && targetDay - copy.lastDay() > 60) copy.lastDay(targetDay - 60);
            regions.add(copy);
        }
        long first = targetDay;
        for (EcologyRegion region : regions) first = Math.min(first, region.lastDay());
        for (long day = first; day < targetDay; ) regions = step(regions, table, ++day, seed);
        return regions;
    }
    public static int effectiveCapacity(EcologyRegion region, ResourceId id) {
        return (int) Math.min(SpeciesTable.MAX_POPULATION, (long) region.capacity(id) + region.foodBonus(id));
    }
    private static void add(Map<Long, Map<ResourceId, Integer>> changes, long key, ResourceId id, int count) {
        changes.computeIfAbsent(key, ignored -> new HashMap<>()).merge(id, count, Integer::sum);
    }
    private static int rounded(double value, long seed, EcologyRegion region, long day, ResourceId species, int salt) {
        int floor = (int) Math.floor(Math.min(SpeciesTable.MAX_POPULATION, value));
        double roll = (hash(seed, region, day, species, salt) >>> 11) * 0x1.0p-53;
        return floor + (roll < value - floor && floor < SpeciesTable.MAX_POPULATION ? 1 : 0);
    }
    private static long hash(long seed, EcologyRegion region, long day, ResourceId species, int salt) {
        long n = seed ^ region.key() * 0x9E3779B97F4A7C15L ^ day * 0xD1B54A32D192ED03L
                ^ (long) species.hashCode() * 0x94D049BB133111EBL ^ salt;
        n = (n ^ (n >>> 30)) * 0xBF58476D1CE4E5B9L; n = (n ^ (n >>> 27)) * 0x94D049BB133111EBL;
        return n ^ (n >>> 31);
    }
}

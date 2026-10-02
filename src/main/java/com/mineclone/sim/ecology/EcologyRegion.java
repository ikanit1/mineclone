package com.mineclone.sim.ecology;

import com.mineclone.data.ResourceId;
import com.mineclone.world.Biome;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One 256-block region's population snapshot, including bodies reserved in saved chunks. */
public final class EcologyRegion {
    public enum LossCause { PLAYER, PREDATOR, FIRE, OTHER }
    public static final int SIZE = 256;
    public static final int GREENERY_CELLS = 256;
    private final int x, z;
    private long lastDay;
    private final Map<ResourceId, Integer> population = new LinkedHashMap<>(), capacity = new LinkedHashMap<>(),
            foodBonus = new LinkedHashMap<>(), protectedCounts = new LinkedHashMap<>();
    private final Map<ResourceId, int[]> trends = new LinkedHashMap<>();
    private byte[] greenery = new byte[GREENERY_CELLS];
    private List<byte[]> lairs = List.of(), scars = List.of();
    private byte[] tail = new byte[0];
    private final int[] losses = new int[LossCause.values().length];
    public EcologyRegion(int x, int z, long lastDay) {
        this.x = x; this.z = z; lastDay(lastDay); Arrays.fill(greenery, (byte) 255);
    }
    public static EcologyRegion initial(int x, int z, long day, SpeciesTable table, Biome biome) {
        EcologyRegion region = new EcologyRegion(x, z, day);
        for (SpeciesTable.Definition d : table.definitions()) {
            int cap = d.capacity(biome);
            region.capacity(d.id(), cap);
            region.population(d.id(), (int) Math.floor(cap * 0.7));
        }
        return region;
    }
    public int x() { return x; }
    public int z() { return z; }
    public long key() { return key(x, z); }
    public static long key(int x, int z) { return ((long) x << 32) | (z & 0xffff_ffffL); }
    public long lastDay() { return lastDay; }
    public void lastDay(long value) { if (value < 0) throw new IllegalArgumentException("negative ecology day"); lastDay = value; }
    public int population(ResourceId id) { return population.getOrDefault(id, 0); }
    public void population(ResourceId id, int value) { put(population, id, value); }
    public int capacity(ResourceId id) { return capacity.getOrDefault(id, 0); }
    public void capacity(ResourceId id, int value) { put(capacity, id, value); }
    public int foodBonus(ResourceId id) { return foodBonus.getOrDefault(id, 0); }
    public void foodBonus(ResourceId id, int value) { put(foodBonus, id, value); }
    public int protectedCount(ResourceId id) { return protectedCounts.getOrDefault(id, 0); }
    public void protectedCount(ResourceId id, int value) { put(protectedCounts, id, value); }
    public Map<ResourceId, Integer> populations() { return Collections.unmodifiableMap(population); }
    public Map<ResourceId, Integer> capacities() { return Collections.unmodifiableMap(capacity); }
    public Map<ResourceId, Integer> foodBonuses() { return Collections.unmodifiableMap(foodBonus); }
    public Map<ResourceId, Integer> protectedCounts() { return Collections.unmodifiableMap(protectedCounts); }
    public int[] trend(ResourceId id) { return trends.getOrDefault(id, new int[3]).clone(); }
    public void trend(ResourceId id, int[] values) {
        if (values.length != 3) throw new IllegalArgumentException("trend needs three days");
        for (int value : values) if (Math.abs((long) value) > SpeciesTable.MAX_POPULATION) throw new IllegalArgumentException("trend out of range");
        trends.put(id, values.clone());
    }
    public Map<ResourceId, int[]> trends() {
        var copy = new LinkedHashMap<ResourceId, int[]>(); trends.forEach((id, t) -> copy.put(id, t.clone()));
        return Collections.unmodifiableMap(copy);
    }
    public byte[] greenery() { return greenery.clone(); }
    public void greenery(byte[] values) {
        if (values.length != GREENERY_CELLS) throw new IllegalArgumentException("greenery needs 256 cells");
        greenery = values.clone();
    }
    public double averageGreenery() { int sum = 0; for (byte value : greenery) sum += value & 255; return sum / (255.0 * greenery.length); }
    public int lossCount(LossCause cause) { return losses[cause.ordinal()]; }
    public void lossCount(LossCause cause, int value) {
        if (value < 0 || value > SpeciesTable.MAX_POPULATION) throw new IllegalArgumentException("loss count out of range");
        losses[cause.ordinal()] = value;
    }
    public void addLoss(LossCause cause) { addLoss(cause, 1); }
    public void addLoss(LossCause cause, int count) {
        if (count < 0) throw new IllegalArgumentException("negative ecology losses");
        losses[cause.ordinal()] = (int) Math.min(SpeciesTable.MAX_POPULATION, (long) losses[cause.ordinal()] + count);
    }
    public List<byte[]> lairs() { return blobs(lairs, 4); }
    public void lairs(List<byte[]> values) { lairs = blobs(values, 4); }
    public List<byte[]> scars() { return blobs(scars, 16); }
    public void scars(List<byte[]> values) { scars = blobs(values, 16); }
    public byte[] tail() { return tail.clone(); }
    public void tail(byte[] value) { if (value.length > 4096) throw new IllegalArgumentException("region tail too large"); tail = value.clone(); }
    public EcologyRegion copy() {
        EcologyRegion copy = new EcologyRegion(x, z, lastDay);
        copy.population.putAll(population); copy.capacity.putAll(capacity); copy.foodBonus.putAll(foodBonus); copy.protectedCounts.putAll(protectedCounts);
        trends.forEach((id, trend) -> copy.trends.put(id, trend.clone())); copy.greenery = greenery.clone();
        System.arraycopy(losses, 0, copy.losses, 0, losses.length);
        copy.lairs(lairs); copy.scars(scars); copy.tail(tail);
        return copy;
    }
    private static void put(Map<ResourceId, Integer> into, ResourceId id, int value) {
        if (id == null || value < 0 || value > SpeciesTable.MAX_POPULATION) throw new IllegalArgumentException("ecology count out of range");
        into.put(id, value);
    }
    private static List<byte[]> blobs(List<byte[]> values, int max) {
        if (values.size() > max) throw new IllegalArgumentException("too many ecology records");
        return values.stream().map(bytes -> {
            if (bytes.length > 1024) throw new IllegalArgumentException("ecology record too large");
            return bytes.clone();
        }).toList();
    }
}

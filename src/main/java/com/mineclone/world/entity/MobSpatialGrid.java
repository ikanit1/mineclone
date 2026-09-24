package com.mineclone.world.entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Chunk-sized broadphase. Buckets are recycled between sense passes. */
public final class MobSpatialGrid {
    private final HashMap<Long, ArrayList<Mob>> cells = new HashMap<>();
    private final ArrayList<ArrayList<Mob>> pool = new ArrayList<>();
    private final ArrayList<Mob> found = new ArrayList<>();
    public void rebuild(List<Mob> mobs) {
        for (var bucket : cells.values()) { bucket.clear(); pool.add(bucket); }
        cells.clear();
        for (int i = 0; i < mobs.size(); i++) {
            Mob m = mobs.get(i);
            m.gridOrder = i;
            if (m.dead) continue;
            long key = com.mineclone.world.World.key(cell(m.position.x), cell(m.position.z));
            var bucket = cells.get(key);
            if (bucket == null) {
                bucket = pool.isEmpty() ? new ArrayList<>() : pool.remove(pool.size() - 1);
                cells.put(key, bucket);
            }
            bucket.add(m);
        }
    }
    public List<Mob> nearby(Mob m, float radius) {
        found.clear();
        for (int x = cell(m.position.x - radius); x <= cell(m.position.x + radius); x++)
            for (int z = cell(m.position.z - radius); z <= cell(m.position.z + radius); z++) {
                var bucket = cells.get(com.mineclone.world.World.key(x, z));
                if (bucket != null) found.addAll(bucket);
            }
        return found;
    }
    private static int cell(float x) { return (int)Math.floor(x / 16f); }

    /**
     * The mob's index in the list of the latest rebuild by any grid — the same
     * list, rebuilt right before use. Lets a pass handle each pair once without
     * a map from mob to index every frame.
     */
    public static int order(Mob m) { return m.gridOrder; }
}

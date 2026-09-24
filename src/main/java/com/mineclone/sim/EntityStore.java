package com.mineclone.sim;

import com.mineclone.world.entity.ItemEntity;
import com.mineclone.world.entity.Mob;
import com.mineclone.world.entity.Projectile;
import java.util.ArrayList;
import java.util.List;

/**
 * The entities of one world. Lists keep spawn order: iteration order is part of
 * the simulation (which pair of mobs is pushed apart first, who is sated, which
 * item is dropped first when there are too many), so nothing may re-sort them.
 *
 * <p>On a guest the store holds the host's snapshots and no session ticks it.
 */
public final class EntityStore {
    public final List<Mob> mobs = new ArrayList<>();
    /** Items lying in the world, oldest first. */
    public final List<ItemEntity> items = new ArrayList<>();
    /** Arrows in flight or stuck. */
    public final List<Projectile> projectiles = new ArrayList<>();

    public void clear() {
        mobs.clear();
        items.clear();
        projectiles.clear();
    }
}

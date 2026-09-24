package com.mineclone.sim;

import com.mineclone.world.entity.Mob;
import java.util.ArrayList;
import java.util.List;

/**
 * The entities of one world. Lists keep spawn order: iteration order is part of
 * the simulation (which pair of mobs is pushed apart first, who is sated), so
 * nothing may re-sort them. Items and projectiles join with SIM-04.
 *
 * <p>On a guest the store holds the host's snapshots and no session ticks it.
 */
public final class EntityStore {
    public final List<Mob> mobs = new ArrayList<>();

    public void clear() {
        mobs.clear();
    }
}

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

    /** Level section with the next mob id (MOB-02): one {@code VarLong}. */
    public static final String IDS_SECTION = "entity_ids";

    /** Legacy next-id section, bridged to the shared long counter in clock v2. */
    private long nextMobId = 1;
    private WorldClock allocatorClock;

    public void bindClock(WorldClock clock) {
        allocatorClock = clock;
        if (nextMobId > 1) clock.observeEntityId(nextMobId - 1);
        for (Mob mob : mobs) if (mob.id > 0) clock.observeEntityId(mob.id);
    }

    /** Gives every mob without an id its world id; a mob keeps it for life. */
    public void assignIds() {
        for (Mob m : mobs)
            if (m.id == 0)
                m.id = allocate();
            else reserveId(m.id);
    }

    /** Reserve an id before a population body enters the list. */
    public void assignId(Mob mob) { if (mob.id == 0) mob.id = allocate(); }
    /** A body saved outside the loaded terrain still owns its id. */
    public void reserveId(long id) {
        if (id <= 0) return;
        if (allocatorClock != null) allocatorClock.observeEntityId(id);
        if (id >= nextMobId) nextMobId = id == Long.MAX_VALUE ? Long.MAX_VALUE : id + 1;
    }

    private long allocate() {
        if (allocatorClock != null) return allocatorClock.nextEntityId();
        if (nextMobId == Long.MAX_VALUE) throw new IllegalStateException("entity ids exhausted");
        return nextMobId++;
    }

    private boolean live(long id) {
        for (Mob m : mobs)
            if (m.id == id)
                return true;
        return false;
    }

    /**
     * A saved mob enters the world with the id it was written with; the counter moves
     * past it. A mob whose id is already live — the same mob written into two chunks by
     * a crash between their writes — stays out.
     *
     * @return whether the mob entered
     */
    public boolean adopt(Mob m) {
        if (m.id <= 0 || live(m.id))
            return false;
        mobs.add(m);
        reserveId(m.id);
        return true;
    }

    public byte[] encodeIds() {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(bytes)) {
            long last = allocatorClock == null ? 0 : allocatorClock.entityCounter();
            long next = last == Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(nextMobId, last + 1);
            com.mineclone.data.VarLong.write(out, next);
        } catch (java.io.IOException impossible) {
            throw new java.io.UncheckedIOException(impossible);
        }
        return bytes.toByteArray();
    }

    /**
     * The counter a world was saved with. A world without the section starts at 1, and
     * so does an unreadable one: adoption then moves the counter past every saved mob.
     */
    public void restoreIds(byte[] section) {
        nextMobId = 1;
        if (section == null)
            return;
        try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(section))) {
            long next = com.mineclone.data.VarLong.read(in);
            if (next >= 1)
                nextMobId = next;
            if (allocatorClock != null && nextMobId > 1) allocatorClock.observeEntityId(nextMobId - 1);
        } catch (java.io.IOException unreadable) {
            // Start at 1; adopted mobs move the counter past themselves.
        }
    }
}

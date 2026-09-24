package com.mineclone.sim;

import com.mineclone.world.GameMode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The live list of participants, kept in ascending id order.
 *
 * <p>Queries run every tick for every mob, so they allocate nothing: they walk
 * the list by index and take the caller's predicate and action. Ordering by id
 * makes ties deterministic regardless of who joined first, which keeps a host
 * and a replayed test choosing the same target. Mutated and read on the
 * simulation thread only.
 */
public final class Participants {
    /** Who a hostile mob may pick: the living, outside creative mode. */
    public static final Predicate<Participant> HOSTILE_TARGETS =
            p -> p.alive() && p.mode() != GameMode.CREATIVE;

    private final List<Participant> byId = new ArrayList<>();

    public int size() { return byId.size(); }

    /** Participant at an index of the id-ordered list, for allocation-free iteration. */
    public Participant get(int index) { return byId.get(index); }

    public Participant getById(int id) {
        int at = indexOf(id);
        return at >= 0 ? byId.get(at) : null;
    }

    /** Add a participant, replacing an existing one with the same id. */
    public void put(Participant participant) {
        int at = indexOf(participant.id());
        if (at >= 0) byId.set(at, participant);
        else byId.add(-at - 1, participant);
    }

    public Participant remove(int id) {
        int at = indexOf(id);
        return at >= 0 ? byId.remove(at) : null;
    }

    public void clear() { byId.clear(); }

    /** Closest accepted participant by feet position; ties go to the smaller id. */
    public Participant nearest(float x, float y, float z, Predicate<? super Participant> filter) {
        Participant best = null;
        float bestDistance = Float.POSITIVE_INFINITY;
        for (int i = 0; i < byId.size(); i++) {
            Participant p = byId.get(i);
            if (!filter.test(p)) continue;
            float d = p.position().distanceSquared(x, y, z);
            if (d < bestDistance) {
                bestDistance = d;
                best = p;
            }
        }
        return best;
    }

    /**
     * Visit accepted participants whose feet are within {@code radius}, the
     * boundary included, in id order.
     *
     * @return how many were visited
     */
    public int forEachWithin(float x, float y, float z, float radius,
                             Predicate<? super Participant> filter, Consumer<? super Participant> action) {
        if (!Float.isFinite(radius) || radius < 0f)
            throw new IllegalArgumentException("radius must be finite and non-negative: " + radius);
        float limit = radius * radius;
        int visited = 0;
        for (int i = 0; i < byId.size(); i++) {
            Participant p = byId.get(i);
            if (!filter.test(p) || p.position().distanceSquared(x, y, z) > limit) continue;
            action.accept(p);
            visited++;
        }
        return visited;
    }

    /** Binary search by id: the index, or {@code -(insertion point) - 1}. */
    private int indexOf(int id) {
        int low = 0, high = byId.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int at = byId.get(mid).id();
            if (at < id) low = mid + 1;
            else if (at > id) high = mid - 1;
            else return mid;
        }
        return -low - 1;
    }
}

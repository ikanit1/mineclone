package com.mineclone.world;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Ticks that blocks of one chunk asked for (BLK-04, AC-07): "wake me in N
 * world ticks". Random ticks reach a cell about once in forty minutes — too
 * seldom for a crop's stage or a sapling; a scheduled tick comes when asked.
 *
 * <p>An entry is one {@code long}: the due world tick in the high 40 bits,
 * then the kind (8 bits: the block type that asked) and the cell's index in
 * the chunk (15 bits). A binary heap on that number yields ticks by due time,
 * and among equal times always in the same order, so a replay is the same.
 * Scheduling the same kind in the same cell again moves its time rather than
 * adding a second tick; the old heap entry is dropped when it surfaces.
 *
 * <p>Ticks restored from a save keep the tick they were written at
 * ({@link #origin()}) until the world adopts them; a kind this build does not
 * know is held — never run, written back as it came.
 *
 * <p>Owned by the simulation thread, like the chunk's containers.
 */
public final class ScheduledTicks {
    static final int INDEX_BITS = 15, KIND_BITS = 8;
    /** The latest due tick an entry can hold: 2^40, about 1700 years at 20 Hz. */
    public static final long MAX_DUE = (1L << 40) - 1;
    /** Waiting ticks a chunk may hold, one per cell on average; the save section's bound. */
    public static final int MAX_ENTRIES = 1 << INDEX_BITS;
    /** {@link #origin()} of ticks that are the world's own, not waiting for adoption. */
    public static final long ADOPTED = -1;

    private long[] heap = new long[8];
    private int size;
    /** (index, kind) → the due tick that counts; heap entries with another time are stale. */
    private final Map<Integer, Long> live = new HashMap<>();
    /** Restored ticks of kinds this build does not know, packed like the heap. */
    private long[] held = new long[0];
    private long origin = ADOPTED;

    /** A tick handler: the block at {@code index} of kind {@code kind} is due, {@code lateBy} ticks late. */
    @FunctionalInterface
    public interface Handler {
        void tick(int index, int kind, long lateBy);
    }

    /**
     * Asks for a tick of {@code kind} at cell {@code index} at world tick
     * {@code due}; replaces an earlier ask of the same kind there.
     *
     * @return false when the chunk already holds {@link #MAX_ENTRIES} other ticks
     */
    public boolean schedule(int index, int kind, long due) {
        check(index, kind);
        long at = Math.max(0, Math.min(MAX_DUE, due));
        Integer k = key(index, kind);
        if (!live.containsKey(k) && live.size() + held.length >= MAX_ENTRIES)
            return false;
        Long previous = live.put(k, at);
        if (previous == null || previous != at)
            push(pack(at, kind, index));
        return true;
    }

    /** Ticks waiting to run, stale heap entries and held kinds not counted. */
    public int size() {
        return live.size();
    }

    /** Nothing to run and nothing held. */
    public boolean isEmpty() {
        return live.isEmpty() && held.length == 0;
    }

    /** The due tick of {@code kind} at {@code index}, or -1 when none waits. */
    public long due(int index, int kind) {
        Long at = live.get(key(index, kind));
        return at == null ? -1 : at;
    }

    /**
     * Runs the ticks due by {@code now}, earliest first, at most {@code budget}.
     * The handler may schedule again; an ask due after {@code now} waits for a
     * later call.
     *
     * @return ticks run
     */
    public int runDue(long now, int budget, Handler handler) {
        int ran = 0;
        while (ran < budget && size > 0) {
            long top = heap[0];
            long due = top >>> (INDEX_BITS + KIND_BITS);
            if (due > now)
                break;
            pop();
            int index = (int) (top & ((1 << INDEX_BITS) - 1));
            int kind = (int) ((top >>> INDEX_BITS) & ((1 << KIND_BITS) - 1));
            Integer k = key(index, kind);
            Long current = live.get(k);
            if (current == null || current != due)
                continue;                       // replaced or already run
            live.remove(k);
            ran++;
            handler.tick(index, kind, now - due);
        }
        return ran;
    }

    /**
     * Takes ticks read from a save: {@code entries} as {@code {index, kind,
     * due}} triples, dues counted from world tick {@code writtenAt}. Kinds at
     * or above {@code knownKinds} are held. Replaces what this holds.
     */
    public void restore(long writtenAt, long[] entries, int knownKinds) {
        if (writtenAt < 0 || entries.length % 3 != 0)
            throw new IllegalArgumentException("invalid scheduled tick record");
        heap = new long[Math.max(8, entries.length / 3)];
        size = 0;
        live.clear();
        held = new long[0];
        long[] unknown = new long[entries.length / 3];
        int holding = 0;
        for (int i = 0; i < entries.length; i += 3) {
            int index = (int) entries[i], kind = (int) entries[i + 1];
            check(index, kind);
            if (kind < knownKinds)
                schedule(index, kind, entries[i + 2]);
            else
                unknown[holding++] = pack(Math.max(0, Math.min(MAX_DUE, entries[i + 2])), kind, index);
        }
        held = Arrays.copyOf(unknown, holding);
        origin = writtenAt;
    }

    /** The world tick restored dues count from; {@link #ADOPTED} once the world took them. */
    public long origin() {
        return origin;
    }

    /**
     * Makes restored dues the world's own at world tick {@code now}. Time
     * that passed since they were written counts — the ticks run late — but a
     * world whose clock is behind the save's (a level restored from a backup,
     * a lost clock section) shifts them back by the difference: a tick waits
     * no longer than it was asked to.
     */
    public void adopt(long now) {
        if (origin == ADOPTED)
            return;
        long shift = now - origin;
        origin = ADOPTED;
        if (shift >= 0)
            return;
        Map<Integer, Long> dues = new HashMap<>(live);
        heap = new long[Math.max(8, dues.size())];
        size = 0;
        live.clear();
        dues.keySet().stream().sorted().forEach(k ->
                schedule(k >>> KIND_BITS, k & ((1 << KIND_BITS) - 1), dues.get(k) + shift));
        for (int i = 0; i < held.length; i++) {
            long h = held[i];
            long at = Math.max(0, (h >>> (INDEX_BITS + KIND_BITS)) + shift);
            held[i] = pack(at, (int) ((h >>> INDEX_BITS) & ((1 << KIND_BITS) - 1)), (int) (h & ((1 << INDEX_BITS) - 1)));
        }
    }

    /**
     * The waiting ticks, held ones included, as {@code {index, kind, due}}
     * triples, earliest first — for saving.
     */
    public long[] entries() {
        long[] packed = new long[live.size() + held.length];
        int n = 0;
        for (Map.Entry<Integer, Long> e : live.entrySet())
            packed[n++] = pack(e.getValue(), e.getKey() & ((1 << KIND_BITS) - 1), e.getKey() >>> KIND_BITS);
        for (long h : held)
            packed[n++] = h;
        Arrays.sort(packed);
        long[] out = new long[packed.length * 3];
        for (int i = 0; i < packed.length; i++) {
            out[3 * i] = packed[i] & ((1 << INDEX_BITS) - 1);
            out[3 * i + 1] = (packed[i] >>> INDEX_BITS) & ((1 << KIND_BITS) - 1);
            out[3 * i + 2] = packed[i] >>> (INDEX_BITS + KIND_BITS);
        }
        return out;
    }

    private static void check(int index, int kind) {
        if (index < 0 || index >= 1 << INDEX_BITS || kind < 0 || kind >= 1 << KIND_BITS)
            throw new IllegalArgumentException("invalid scheduled tick " + index + "/" + kind);
    }

    private static Integer key(int index, int kind) {
        return index << KIND_BITS | kind;
    }

    static long pack(long due, int kind, int index) {
        return due << (INDEX_BITS + KIND_BITS) | (long) kind << INDEX_BITS | index;
    }

    private void push(long v) {
        if (size == heap.length)
            heap = Arrays.copyOf(heap, size * 2);
        int i = size++;
        heap[i] = v;
        while (i > 0) {
            int parent = (i - 1) >>> 1;
            if (heap[parent] <= heap[i])
                break;
            long t = heap[parent]; heap[parent] = heap[i]; heap[i] = t;
            i = parent;
        }
    }

    private void pop() {
        heap[0] = heap[--size];
        int i = 0;
        while (true) {
            int l = 2 * i + 1, r = l + 1, m = i;
            if (l < size && heap[l] < heap[m]) m = l;
            if (r < size && heap[r] < heap[m]) m = r;
            if (m == i)
                break;
            long t = heap[m]; heap[m] = heap[i]; heap[i] = t;
            i = m;
        }
    }
}

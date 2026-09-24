package com.mineclone.world.gen;

import com.mineclone.data.VarInt;
import com.mineclone.data.VarLong;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Which generator version each chunk of a world was first generated with (GEN-02).
 *
 * <p>Unedited chunks are regenerated on every load, so a chunk must keep the
 * version that first generated it, or a new generator would rewrite land a
 * player has already seen. A chunk missing from the ledger is the world's own
 * version: it has not been generated yet, or was generated before the ledger
 * existed in a world that has never been upgraded — everything there is V1.
 * {@link WorldGenUpgrade} pins the explored land of an upgraded world to its old
 * version.
 *
 * <p>Thread-safe: chunk workers ask and record while the main thread saves.
 *
 * <p>Format ({@code chunks/ledger.dat}, gzip): {@code int MAGIC, int FORMAT,
 * VarInt count}, the keys sorted ascending — the first zigzagged, then the
 * differences — as VarLongs, then the versions as runs of {@code VarInt length,
 * VarInt version id}.
 */
public final class ChunkLedger {
    public static final int MAGIC = 0x4D434C47; // "MCLG"
    public static final int FORMAT = 1;
    /** No world has anywhere near this many chunks; a count past it is damage. */
    private static final int MAX_ENTRIES = 1 << 24;

    private final HashMap<Long, WorldGenVersion> versions = new HashMap<>();
    private boolean dirty;

    /** The version a chunk was generated with, or {@code otherwise} when it is not recorded. */
    public synchronized WorldGenVersion versionAt(long key, WorldGenVersion otherwise) {
        WorldGenVersion recorded = versions.get(key);
        return recorded != null ? recorded : otherwise;
    }

    /** Records the version a chunk is generated with; false when it was already known. */
    public synchronized boolean record(long key, WorldGenVersion version) {
        if (versions.putIfAbsent(key, version) != null)
            return false;
        dirty = true;
        return true;
    }

    public synchronized int size() { return versions.size(); }

    public synchronized boolean contains(long key) { return versions.containsKey(key); }

    /** Whether anything was recorded since the last {@link #encodeIfDirty}. */
    public synchronized boolean dirty() { return dirty; }

    public synchronized Map<Long, WorldGenVersion> snapshot() { return new HashMap<>(versions); }

    /** The file's bytes (before gzip), or null when nothing changed since the last call. */
    public byte[] encodeIfDirty() {
        synchronized (this) {
            if (!dirty) return null;
            dirty = false;
        }
        // A chunk recorded from here on marks the ledger dirty again: the next
        // save writes it even if this encoding already caught it.
        return encode();
    }

    /**
     * The game encodes at autosave on its main thread, so only the copy happens
     * under the lock — chunk workers asking for versions do not wait while keys
     * are sorted and written — and nothing is boxed or locked per key.
     */
    public byte[] encode() {
        WorldGenVersion[] all = WorldGenVersion.values();
        long[][] byVersion = new long[all.length][];
        synchronized (this) {
            int[] count = new int[all.length];
            for (WorldGenVersion version : versions.values()) count[version.ordinal()]++;
            for (int v = 0; v < all.length; v++) byVersion[v] = new long[count[v]];
            int[] filled = new int[all.length];
            for (Map.Entry<Long, WorldGenVersion> entry : versions.entrySet()) {
                int v = entry.getValue().ordinal();
                byVersion[v][filled[v]++] = entry.getKey();
            }
        }
        int total = 0;
        for (long[] keys : byVersion) {
            Arrays.sort(keys);
            total += keys.length;
        }
        byte[] out = new byte[3 * Integer.BYTES + (total + 1) * 2 * VarLong.MAX_BYTES];
        int at = putInt(out, 0, MAGIC);
        at = putInt(out, at, FORMAT);
        at = VarLong.put(out, at, total);
        // Merging the per-version lists gives every key in order and its version.
        byte[] versionOf = new byte[total];
        int[] next = new int[all.length];
        long previous = 0;
        for (int i = 0; i < total; i++) {
            int pick = -1;
            for (int v = 0; v < all.length; v++)
                if (next[v] < byVersion[v].length
                        && (pick < 0 || byVersion[v][next[v]] < byVersion[pick][next[pick]]))
                    pick = v;
            long key = byVersion[pick][next[pick]++];
            at = VarLong.put(out, at, i == 0 ? VarLong.zigzag(key) : key - previous);
            previous = key;
            versionOf[i] = (byte) pick;
        }
        // Counts, runs and ids are small non-negative ints: as VarLongs they are
        // the very bytes VarInt reads back.
        for (int i = 0; i < total; ) {
            int run = 1;
            while (i + run < total && versionOf[i + run] == versionOf[i]) run++;
            at = VarLong.put(out, at, run);
            at = VarLong.put(out, at, all[versionOf[i]].id());
            i += run;
        }
        return Arrays.copyOf(out, at);
    }

    private static int putInt(byte[] out, int at, int value) {
        out[at] = (byte) (value >>> 24);
        out[at + 1] = (byte) (value >>> 16);
        out[at + 2] = (byte) (value >>> 8);
        out[at + 3] = (byte) value;
        return at + Integer.BYTES;
    }

    /**
     * @throws IOException for damage, an unknown format, or a version this build
     *         does not know (a newer world is refused by its level before this)
     */
    public static ChunkLedger decode(byte[] bytes) throws IOException {
        var in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != MAGIC) throw new IOException("not a chunk ledger");
        int format = in.readInt();
        if (format != FORMAT) throw new IOException("unknown chunk ledger format " + format);
        int count = VarInt.read(in);
        if (count < 0 || count > MAX_ENTRIES) throw new IOException("invalid chunk ledger count " + count);
        long[] keys = new long[count];
        for (int i = 0; i < count; i++) {
            long raw = VarLong.read(in);
            if (i == 0) keys[0] = VarLong.unzigzag(raw);
            else {
                // An unsigned difference; a real one never carries the key past the
                // largest long, so a smaller result is damage.
                keys[i] = keys[i - 1] + raw;
                if (raw == 0 || keys[i] <= keys[i - 1]) throw new IOException("chunk ledger keys are not ascending");
            }
        }
        ChunkLedger ledger = new ChunkLedger();
        for (int i = 0; i < count; ) {
            int run = VarInt.read(in);
            int id = VarInt.read(in);
            WorldGenVersion version = WorldGenVersion.byId(id);
            if (version == null) throw new IOException("unknown generator version " + id + " in chunk ledger");
            if (run <= 0 || run > count - i) throw new IOException("invalid chunk ledger run " + run);
            for (int k = 0; k < run; k++) ledger.versions.put(keys[i + k], version);
            i += run;
        }
        if (in.available() > 0) throw new IOException("trailing bytes after chunk ledger");
        return ledger;
    }
}

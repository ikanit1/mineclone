package com.mineclone.net;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongSupplier;

/**
 * Traffic by packet (NET-01): bytes and packets per code and direction,
 * transport messages and per-peer totals — since the start, and in one-second
 * slots of the last minute.
 *
 * <p>{@link #OUT} counts what this peer delivered: a packet for everyone counts
 * once per recipient, because the budget is per guest — a relay server carries
 * the fan-out, but every guest still downloads the bytes. {@link #IN} counts
 * what arrived. Payload is the packet bytes the game wrote; wire is what the
 * transport makes of a message ({@link NetTransport#wireBytes}): base64 in a
 * Photon JSON operation, a 13-byte frame on a direct socket. TLS, TCP and IP
 * are not counted.
 *
 * <p>Recording allocates nothing: the channel writes hundreds of packets a
 * tick. Windows sum whole seconds; the second in progress is in no window yet.
 *
 * <p>The cumulative {@link #sent}/{@link #received} counters are the PERF-01
 * benchmark's and stay as they were.
 */
public final class NetStats {
    public static final int OUT = 0, IN = 1;
    /** How far back the windows reach. */
    public static final int SECONDS = 60;
    /** One slot more than the longest window: the second in progress fills it. */
    private static final int SLOTS = SECONDS + 1;
    private static final int CODES = 256;
    /** A slot's or the totals' counters: codes' bytes and packets, then per direction the rest. */
    private static final int BYTES = 0, PACKETS = 2 * CODES, MESSAGES = 4 * CODES, DELIVERIES = MESSAGES + 2,
            PAYLOAD = MESSAGES + 4, WIRE = MESSAGES + 6, WIDTH = MESSAGES + 8;

    private final LongSupplier nanos;

    private long sentBytes, receivedBytes, sentPackets, receivedPackets;

    private final long[] slotSecond = new long[SLOTS];
    private final long[][] slots = new long[SLOTS][WIDTH];
    private final long[] totals = new long[WIDTH];
    private final TreeMap<Integer, long[]> peers = new TreeMap<>();

    public NetStats() { this(System::nanoTime); }

    /** Clock seam for tests and for tools that simulate time. */
    public NetStats(LongSupplier nanos) {
        this.nanos = nanos;
        Arrays.fill(slotSecond, Long.MIN_VALUE);
    }

    // ------------------------------------------------ PERF-01 bench counters

    public synchronized void sent(int bytes, int recipients) { sentBytes += (long) bytes * recipients; sentPackets += recipients; }
    public synchronized void received(int bytes) { receivedBytes += bytes; receivedPackets++; }
    public synchronized Snapshot snapshot() { return new Snapshot(sentBytes, receivedBytes, sentPackets, receivedPackets); }
    public record Snapshot(long sentBytes, long receivedBytes, long sentPackets, long receivedPackets) {}

    // ------------------------------------------------------------ recording

    /** One packet of {@code bytes} (its code byte included) delivered {@code copies} times. */
    public synchronized void packet(int dir, int code, int bytes, int copies) {
        long[] slot = slot();
        int at = dir * CODES + (code & 0xFF);
        long delivered = (long) bytes * copies;
        slot[BYTES + at] += delivered;
        slot[PACKETS + at] += copies;
        totals[BYTES + at] += delivered;
        totals[PACKETS + at] += copies;
    }

    /**
     * One transport message: {@code recipients} copies of {@code payloadBytes},
     * each {@code wireBytes} on the wire. A received message has one recipient.
     */
    public synchronized void message(int dir, int payloadBytes, int wireBytes, int recipients) {
        addMessage(slot(), dir, payloadBytes, wireBytes, recipients);
        addMessage(totals, dir, payloadBytes, wireBytes, recipients);
    }

    private static void addMessage(long[] row, int dir, int payloadBytes, int wireBytes, int recipients) {
        row[MESSAGES + dir]++;
        row[DELIVERIES + dir] += recipients;
        row[PAYLOAD + dir] += (long) payloadBytes * recipients;
        row[WIRE + dir] += (long) wireBytes * recipients;
    }

    /** Payload bytes exchanged with one peer, since the start. */
    public synchronized void peer(int actor, int dir, int bytes) {
        peers.computeIfAbsent(actor, a -> new long[2])[dir] += bytes;
    }

    private long[] slot() {
        long second = second();
        int slot = (int) Math.floorMod(second, SLOTS);
        if (slotSecond[slot] != second) {
            slotSecond[slot] = second;
            Arrays.fill(slots[slot], 0);
        }
        return slots[slot];
    }

    private long second() {
        return Math.floorDiv(nanos.getAsLong(), 1_000_000_000L);
    }

    // -------------------------------------------------------------- reading

    /** Everything since the start. */
    public synchronized Counts totals() {
        return new Counts(totals.clone());
    }

    /** The last {@code seconds} whole seconds, summed: divide by {@code seconds} for rates. */
    public synchronized Counts window(int seconds) {
        long[] sum = new long[WIDTH];
        addWindow(seconds, sum);
        return new Counts(sum);
    }

    /**
     * Per second over the last {@code seconds} whole seconds, into {@code out}
     * without allocating — for a line redrawn every frame: payload bytes out
     * and in, messages out and in, room messages ({@link Counts#roomMessages}).
     */
    public synchronized void rates(int seconds, double[] out) {
        long payOut = 0, payIn = 0, msgOut = 0, msgIn = 0, delOut = 0;
        long now = second();
        checkWindow(seconds);
        for (long s = now - seconds; s < now; s++) {
            int slot = (int) Math.floorMod(s, SLOTS);
            if (slotSecond[slot] != s)
                continue;
            long[] row = slots[slot];
            payOut += row[PAYLOAD + OUT];
            payIn += row[PAYLOAD + IN];
            msgOut += row[MESSAGES + OUT];
            msgIn += row[MESSAGES + IN];
            delOut += row[DELIVERIES + OUT];
        }
        out[0] = (double) payOut / seconds;
        out[1] = (double) payIn / seconds;
        out[2] = (double) msgOut / seconds;
        out[3] = (double) msgIn / seconds;
        out[4] = (double) (msgOut + delOut + 2 * msgIn) / seconds;
    }

    private void addWindow(int seconds, long[] sum) {
        checkWindow(seconds);
        long now = second();
        for (long s = now - seconds; s < now; s++) {
            int slot = (int) Math.floorMod(s, SLOTS);
            if (slotSecond[slot] != s)
                continue;
            for (int i = 0; i < WIDTH; i++)
                sum[i] += slots[slot][i];
        }
    }

    private static void checkWindow(int seconds) {
        if (seconds < 1 || seconds > SECONDS)
            throw new IllegalArgumentException("window of " + seconds + " s");
    }

    /** Payload bytes per peer since the start: {@code {out, in}} by actor. */
    public synchronized Map<Integer, long[]> peers() {
        Map<Integer, long[]> copy = new TreeMap<>();
        peers.forEach((actor, sums) -> copy.put(actor, sums.clone()));
        return copy;
    }

    /** Counters over some span: since the start, or a window of whole seconds. */
    public static final class Counts {
        private final long[] v;

        private Counts(long[] v) { this.v = v; }

        public long bytes(int dir, int code) { return v[BYTES + dir * CODES + (code & 0xFF)]; }
        public long packets(int dir, int code) { return v[PACKETS + dir * CODES + (code & 0xFF)]; }
        public long messages(int dir) { return v[MESSAGES + dir]; }
        /** Messages times their recipients. */
        public long deliveries(int dir) { return v[DELIVERIES + dir]; }
        public long payloadBytes(int dir) { return v[PAYLOAD + dir]; }
        public long wireBytes(int dir) { return v[WIRE + dir]; }

        /**
         * Photon's count for the room, as far as this peer can see it: every
         * message it sends is one sent plus one per delivery, every message it
         * receives was one sent and one delivered. Messages between other peers
         * never reach it and are missing.
         */
        public long roomMessages() {
            return messages(OUT) + deliveries(OUT) + 2 * messages(IN);
        }

        /** The {@code n} codes that carried the most bytes in {@code dir}, heaviest first: {@code {code, bytes, packets}}. */
        public List<long[]> top(int dir, int n) {
            List<long[]> rows = new ArrayList<>();
            for (int code = 0; code < CODES; code++)
                if (packets(dir, code) > 0)
                    rows.add(new long[] { code, bytes(dir, code), packets(dir, code) });
            rows.sort((a, b) -> a[1] != b[1] ? Long.compare(b[1], a[1]) : Long.compare(a[0], b[0]));
            return rows.size() > n ? new ArrayList<>(rows.subList(0, n)) : rows;
        }
    }

    // ------------------------------------------------------------------ csv

    public static final String CSV_HEADER = "second,dir,code,name,packets,bytes,messages,deliveries,wire";

    /**
     * Rows for every whole second after {@code afterSecond}: a packet row per
     * code that moved, then per direction a {@code messages} row ({@code code
     * -1}). Seconds older than the last minute are gone and skipped. Returns
     * the last second covered.
     */
    public synchronized long appendCsv(Appendable out, long afterSecond) throws IOException {
        long now = second();
        long last = afterSecond;
        for (long s = Math.max(afterSecond + 1, now - SECONDS); s < now; s++) {
            last = s;
            int slot = (int) Math.floorMod(s, SLOTS);
            if (slotSecond[slot] != s)
                continue;
            long[] row = slots[slot];
            for (int d = 0; d < 2; d++) {
                String dir = d == OUT ? "out" : "in";
                for (int code = 0; code < CODES; code++) {
                    long count = row[PACKETS + d * CODES + code];
                    if (count > 0)
                        out.append(String.format(Locale.ROOT, "%d,%s,%d,%s,%d,%d,,,%n", s, dir, code,
                                NetProto.name(code), count, row[BYTES + d * CODES + code]));
                }
                if (row[MESSAGES + d] > 0)
                    out.append(String.format(Locale.ROOT, "%d,%s,-1,messages,,%d,%d,%d,%d%n", s, dir,
                            row[PAYLOAD + d], row[MESSAGES + d], row[DELIVERIES + d], row[WIRE + d]));
            }
        }
        return last;
    }
}

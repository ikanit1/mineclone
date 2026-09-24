package com.mineclone.data;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * A long of variable length: seven bits a byte, the high bit meaning "more
 * follows". Unsigned; a signed value goes through {@link #zigzag} first, so
 * that small negative numbers stay short.
 */
public final class VarLong {
    /** The longest encoding: 64 bits in sevens. */
    public static final int MAX_BYTES = 10;

    private VarLong() {}

    public static void write(DataOutput out, long value) throws IOException {
        long v = value;
        while ((v & ~0x7FL) != 0) {
            out.writeByte((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        out.writeByte((int) v);
    }

    /**
     * The same bytes straight into an array, for encoders that write one per
     * entry and cannot afford a stream's per-byte locking; {@code buffer} needs
     * {@link #MAX_BYTES} free at {@code at}. Returns the position after them.
     */
    public static int put(byte[] buffer, int at, long value) {
        long v = value;
        while ((v & ~0x7FL) != 0) {
            buffer[at++] = (byte) ((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        buffer[at++] = (byte) v;
        return at;
    }

    public static long read(DataInput in) throws IOException {
        long result = 0;
        for (int shift = 0; shift < 70; shift += 7) {
            int b = in.readUnsignedByte();
            if (shift == 63 && (b & 0x7E) != 0)
                throw new IOException("varlong overflows 64 bits");
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0)
                return result;
        }
        throw new IOException("varlong is too long");
    }

    public static long zigzag(long value) { return (value << 1) ^ (value >> 63); }

    public static long unzigzag(long value) { return (value >>> 1) ^ -(value & 1); }
}

package com.mineclone.save;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/** Bounded byte runs; the enclosing save stream also applies GZIP. */
public final class RunLengthCodec {
    private RunLengthCodec() {}
    public static void write(DataOutput out, byte[] values) throws IOException {
        int runs = 0;
        for (int i = 0; i < values.length;) {
            int end = i + 1;
            while (end < values.length && end - i < 65535 && values[end] == values[i]) end++;
            runs++; i = end;
        }
        boolean compressed = runs * 3 < values.length;
        out.writeBoolean(compressed);
        if (!compressed) { out.write(values); return; }
        for (int start = 0; start < values.length;) {
            int end = start + 1;
            while (end < values.length && end - start < 65535 && values[end] == values[start]) end++;
            out.writeShort(end - start);
            out.writeByte(values[start]);
            start = end;
        }
    }
    public static void read(DataInput in, byte[] values) throws IOException {
        if (!in.readBoolean()) { in.readFully(values); return; }
        for (int start = 0; start < values.length;) {
            int count = in.readUnsignedShort();
            byte value = in.readByte();
            if (count == 0 || count > values.length - start) throw new IOException("Invalid voxel run");
            java.util.Arrays.fill(values, start, start + count, value);
            start += count;
        }
    }
}

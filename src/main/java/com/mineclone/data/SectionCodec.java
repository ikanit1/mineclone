package com.mineclone.data;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded named byte sections; unknown keys are preserved without interpreting payloads. */
public final class SectionCodec {
    public static final int MAX_SECTIONS = 64;
    public static final int MAX_SECTION_BYTES = 4 * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;
    private SectionCodec() {}

    public static Map<String, byte[]> read(DataInput in) throws IOException {
        int count = VarInt.read(in);
        if (count < 0 || count > MAX_SECTIONS) throw new IOException("invalid section count " + count);
        Map<String, byte[]> result = new LinkedHashMap<>();
        long total = 0;
        for (int i = 0; i < count; i++) {
            String key = in.readUTF();
            if (key.isBlank() || result.containsKey(key)) throw new IOException("invalid or duplicate section " + key);
            int size = VarInt.read(in);
            total += size;
            if (size < 0 || size > MAX_SECTION_BYTES || total > MAX_TOTAL_BYTES)
                throw new IOException("invalid section size " + size);
            byte[] payload = new byte[size];
            in.readFully(payload);
            result.put(key, payload);
        }
        return result;
    }

    public static void write(DataOutput out, Map<String, byte[]> sections) throws IOException {
        if (sections.size() > MAX_SECTIONS) throw new IOException("too many sections");
        long total = 0;
        VarInt.write(out, sections.size());
        for (var entry : sections.entrySet()) {
            byte[] bytes = entry.getValue();
            if (entry.getKey().isBlank() || bytes == null || bytes.length > MAX_SECTION_BYTES
                    || (total += bytes.length) > MAX_TOTAL_BYTES) throw new IOException("invalid section " + entry.getKey());
            out.writeUTF(entry.getKey());
            VarInt.write(out, bytes.length);
            out.write(bytes);
        }
    }
}

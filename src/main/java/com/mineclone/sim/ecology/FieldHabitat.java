package com.mineclone.sim.ecology;

import com.mineclone.data.SectionCodec;
import com.mineclone.data.VarInt;
import com.mineclone.world.Chunk;
import java.io.*;
import java.util.*;

/** Last observed field size per chunk, keeping rabbit food estimates when land is unloaded. */
public final class FieldHabitat {
    public static final String SECTION = "ecology_fields";
    public static final int VERSION = 1, MAX_REGIONS = EcologyCodec.MAX_REGIONS;
    public static final int CROPS_PER_FOOD = 4, MAX_FOOD = 64;
    public static final int MAX_CROPS_PER_CHUNK = CROPS_PER_FOOD * MAX_FOOD;
    private static final int MAGIC = 0x46484C44;
    private final Map<Long, int[]> cells = new LinkedHashMap<>();
    private byte[] tail = new byte[0];

    public void observe(Chunk chunk) {
        long key = EcologyRegion.key(Math.floorDiv(chunk.cx, 16), Math.floorDiv(chunk.cz, 16));
        if (!cells.containsKey(key) && cells.size() >= MAX_REGIONS) return;
        int[] region = cells.computeIfAbsent(key, ignored -> new int[256]);
        // Counts above the regional food ceiling are equivalent and must not inflate the save.
        region[Math.floorMod(chunk.cx, 16) * 16 + Math.floorMod(chunk.cz, 16)] =
                Math.min(MAX_CROPS_PER_CHUNK, chunk.cropCellCount());
    }
    public boolean known(long key) { return cells.containsKey(key); }
    public boolean empty() { return cells.isEmpty() && tail.length == 0; }
    public int crops(long key) {
        int count = 0;
        for (int n : cells.getOrDefault(key, EMPTY)) count += n;
        return count;
    }
    public int bonus(long key) { return Math.min(MAX_FOOD, crops(key) / CROPS_PER_FOOD); }
    private static final int[] EMPTY = new int[256];

    public byte[] encode() throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC); out.writeByte(VERSION); VarInt.write(out, cells.size());
            var entries = new ArrayList<>(cells.entrySet());
            entries.sort(Comparator.comparingInt((Map.Entry<Long, int[]> e) -> (int) (e.getKey() >> 32))
                    .thenComparingInt(e -> e.getKey().intValue()));
            for (var entry : entries) {
                out.writeInt((int) (entry.getKey() >> 32)); out.writeInt(entry.getKey().intValue());
                int occupied = 0;
                for (int n : entry.getValue()) if (n > 0) occupied++;
                VarInt.write(out, occupied);
                for (int i = 0; i < 256; i++) if (entry.getValue()[i] > 0) {
                    out.writeByte(i); VarInt.write(out, entry.getValue()[i]);
                }
            }
            out.write(tail);
        }
        if (bytes.size() > SectionCodec.MAX_SECTION_BYTES) throw new IOException("field habitat too large");
        return bytes.toByteArray();
    }

    public static final class NewerVersionException extends IOException {
        public NewerVersionException(int version) { super("newer field habitat version " + version); }
    }
    public static FieldHabitat decode(byte[] bytes) throws IOException {
        if (bytes.length > SectionCodec.MAX_SECTION_BYTES) throw new IOException("field habitat too large");
        var result = new FieldHabitat();
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC) throw new IOException("invalid field habitat magic");
            int version = in.readUnsignedByte();
            if (version > VERSION) throw new NewerVersionException(version);
            if (version != VERSION) throw new IOException("invalid field habitat version");
            int count = VarInt.read(in);
            if (count < 0 || count > MAX_REGIONS) throw new IOException("too many field regions");
            for (int r = 0; r < count; r++) {
                long key = EcologyRegion.key(in.readInt(), in.readInt());
                if (result.cells.containsKey(key)) throw new IOException("duplicate field region");
                int populated = VarInt.read(in);
                if (populated < 0 || populated > 256) throw new IOException("too many field cells");
                int[] region = new int[256];
                for (int i = 0; i < populated; i++) {
                    int index = in.readUnsignedByte(), crops = VarInt.read(in);
                    if (crops <= 0 || crops > MAX_CROPS_PER_CHUNK || region[index] != 0)
                        throw new IOException("invalid field cell");
                    region[index] = crops;
                }
                result.cells.put(key, region);
            }
            if (in.available() > 4096) throw new IOException("field habitat tail too large");
            result.tail = in.readAllBytes();
        }
        return result;
    }
}

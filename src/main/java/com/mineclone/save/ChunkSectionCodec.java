package com.mineclone.save;

import com.mineclone.data.SectionCodec;
import com.mineclone.world.DroppedItem;
import com.mineclone.world.Furnace;
import com.mineclone.world.ItemStack;
import java.io.*;
import java.util.*;

/** Version 7 chunk body: RLE terrain followed by bounded, extensible sections. */
public final class ChunkSectionCodec {
    private ChunkSectionCodec() {}
    @FunctionalInterface private interface Writer { void write(DataOutputStream out) throws IOException; }

    public static void write(DataOutputStream out, ChunkSnapshot snapshot) throws IOException {
        if (snapshot.blocks.length != SaveFormat.CHUNK_VOLUME || snapshot.meta.length != SaveFormat.CHUNK_VOLUME)
            throw new IOException("invalid chunk volume");
        RunLengthCodec.write(out, snapshot.blocks);
        RunLengthCodec.write(out, snapshot.meta);
        Map<String, byte[]> sections = new LinkedHashMap<>(snapshot.extra);
        sections.put("chests", bytes(data -> {
            data.writeInt(count(snapshot.chests.size()));
            for (var entry : snapshot.chests.entrySet()) {
                position(entry.getKey());
                data.writeInt(entry.getKey());
                ItemStack[] slots = entry.getValue();
                if (slots.length > 255) throw new IOException("too many chest slots");
                data.writeByte(slots.length);
                for (ItemStack stack : slots) ItemStackCodec.write(data, stack);
            }
        }));
        sections.put("furnaces", bytes(data -> {
            data.writeInt(count(snapshot.furnaces.size()));
            for (var entry : snapshot.furnaces.entrySet()) {
                position(entry.getKey());
                data.writeInt(entry.getKey());
                Furnace furnace = entry.getValue();
                ItemStackCodec.write(data, furnace.input);
                ItemStackCodec.write(data, furnace.fuel);
                ItemStackCodec.write(data, furnace.output);
                finite(furnace.burnLeft); finite(furnace.burnMax); finite(furnace.cook);
                data.writeFloat(furnace.burnLeft); data.writeFloat(furnace.burnMax); data.writeFloat(furnace.cook);
            }
        }));
        sections.put("items", bytes(data -> {
            data.writeInt(count(snapshot.items.size()));
            for (DroppedItem item : snapshot.items) {
                if (item.stack == null || item.stack.count <= 0) throw new IOException("empty dropped item");
                ItemStackCodec.write(data, item.stack);
                finite(item.x); finite(item.y); finite(item.z); finite(item.age);
                data.writeFloat(item.x); data.writeFloat(item.y); data.writeFloat(item.z); data.writeFloat(item.age);
            }
        }));
        SectionCodec.write(out, sections);
    }

    public static ChunkSnapshot read(DataInputStream in, int cx, int cz) throws IOException {
        byte[] blocks = new byte[SaveFormat.CHUNK_VOLUME], meta = new byte[blocks.length];
        RunLengthCodec.read(in, blocks);
        RunLengthCodec.read(in, meta);
        Map<String, byte[]> sections = SectionCodec.read(in);
        Map<Integer, ItemStack[]> chests = new LinkedHashMap<>();
        try (DataInputStream data = section(sections, "chests")) {
            int count = count(data.readInt());
            for (int i = 0; i < count; i++) {
                int key = position(data.readInt());
                ItemStack[] slots = new ItemStack[data.readUnsignedByte()];
                for (int j = 0; j < slots.length; j++) slots[j] = ItemStackCodec.read(data);
                if (chests.put(key, slots) != null) throw new IOException("duplicate chest position");
            }
            end(data);
        }
        Map<Integer, Furnace> furnaces = new LinkedHashMap<>();
        try (DataInputStream data = section(sections, "furnaces")) {
            int count = count(data.readInt());
            for (int i = 0; i < count; i++) {
                int key = position(data.readInt());
                Furnace furnace = new Furnace();
                furnace.input = ItemStackCodec.read(data);
                furnace.fuel = ItemStackCodec.read(data);
                furnace.output = ItemStackCodec.read(data);
                furnace.burnLeft = finite(data.readFloat());
                furnace.burnMax = finite(data.readFloat());
                furnace.cook = finite(data.readFloat());
                if (furnaces.put(key, furnace) != null) throw new IOException("duplicate furnace position");
            }
            end(data);
        }
        List<DroppedItem> items = new ArrayList<>();
        try (DataInputStream data = section(sections, "items")) {
            int count = count(data.readInt());
            for (int i = 0; i < count; i++) {
                ItemStack stack = ItemStackCodec.read(data);
                float x = finite(data.readFloat()), y = finite(data.readFloat()), z = finite(data.readFloat());
                float age = finite(data.readFloat());
                if (stack == null) throw new IOException("empty dropped item");
                items.add(new DroppedItem(stack, x, y, z, age));
            }
            end(data);
        }
        return new ChunkSnapshot(cx, cz, blocks, meta, chests, furnaces, items, sections);
    }

    private static byte[] bytes(Writer writer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(bytes)) { writer.write(data); }
        return bytes.toByteArray();
    }
    private static DataInputStream section(Map<String, byte[]> sections, String name) throws IOException {
        byte[] payload = sections.remove(name);
        if (payload == null) throw new IOException("missing chunk section " + name);
        return new DataInputStream(new ByteArrayInputStream(payload));
    }
    private static int count(int value) throws IOException {
        if (value < 0 || value > 4096) throw new IOException("invalid chunk collection count " + value);
        return value;
    }
    private static int position(int value) throws IOException {
        if (value < 0 || value >= SaveFormat.CHUNK_VOLUME) throw new IOException("invalid container position " + value);
        return value;
    }
    private static float finite(float value) throws IOException {
        if (!Float.isFinite(value)) throw new IOException("non-finite chunk state");
        return value;
    }
    private static void end(DataInputStream data) throws IOException {
        if (data.read() != -1) throw new IOException("trailing bytes in chunk section");
    }
}

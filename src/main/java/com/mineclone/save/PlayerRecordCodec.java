package com.mineclone.save;

import com.mineclone.data.SectionCodec;
import com.mineclone.data.VarInt;
import com.mineclone.world.Inventory;
import com.mineclone.world.ItemStack;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The byte form of a {@link PlayerRecord}: named sections through {@link SectionCodec}.
 *
 * <p>The same bytes are the host's {@code player} level section and the body of
 * a guest's file. Each section reads to its exact end, so a truncated or padded
 * payload fails instead of being half-applied. Item sections always fail closed:
 * a guessed inventory is worse than a refused one. The {@code effects} section
 * alone is lenient — damaged, it is kept verbatim and the items still load.
 * Protocol v7 does not use this codec; {@code net.PlayerData} adapts to it.
 */
public final class PlayerRecordCodec {
    private PlayerRecordCodec() {}

    public static final int MAGIC = 0x50524344; // "PRCD"
    /** Level section whose presence says the {@code player} section holds a record. */
    public static final String LEVEL_MARKER = "player_format";
    public static final Set<String> KNOWN = Set.of("format", "pose", "vitals", "inventory", "equipment",
            "pending", "effects", "spawn", "advancements", "recipes", "progress");

    /** A record whose minimum reader is newer than this build. */
    public static final class TooNewException extends IOException {
        public final int version, minReader;

        public TooNewException(int version, int minReader) {
            super("player record " + version + " requires reader " + minReader);
            this.version = version;
            this.minReader = minReader;
        }
    }

    public static byte[] encode(PlayerRecord record) throws IOException {
        return bytes(out -> write(out, record));
    }

    public static PlayerRecord decode(byte[] bytes) throws IOException {
        try (DataInputStream in = input(bytes)) {
            PlayerRecord record = read(in);
            end(in);
            return record;
        }
    }

    public static void write(DataOutput out, PlayerRecord r) throws IOException {
        Map<String, byte[]> sections = new LinkedHashMap<>();
        sections.put("format", bytes(o -> {
            o.writeInt(MAGIC);
            o.writeInt(PlayerRecord.VERSION);
            o.writeInt(PlayerRecord.VERSION); // minimum reader
        }));
        sections.put("pose", bytes(o -> {
            var p = r.pose();
            o.writeDouble(p.x()); o.writeDouble(p.y()); o.writeDouble(p.z());
            o.writeFloat(p.yaw()); o.writeFloat(p.pitch()); o.writeInt(p.selected());
        }));
        sections.put("vitals", bytes(o -> {
            var v = r.vitals();
            o.writeFloat(v.health()); o.writeFloat(v.hunger()); o.writeFloat(v.saturation());
            o.writeFloat(v.exhaustion()); o.writeFloat(v.air());
        }));
        sections.put("inventory", items(r.inventory()));
        sections.put("equipment", items(r.equipment()));
        sections.put("pending", items(r.pending()));
        byte[] damaged = r.damagedEffects();
        sections.put("effects", damaged != null ? damaged : bytes(o -> {
            VarInt.write(o, r.effects().size());
            for (var effect : r.effects()) {
                o.writeUTF(effect.id());
                o.writeLong(effect.remainingTicks());
                o.writeInt(effect.amplifier());
            }
        }));
        sections.put("spawn", bytes(o -> {
            var s = r.spawn();
            o.writeBoolean(s != null);
            if (s != null) { o.writeDouble(s.x()); o.writeDouble(s.y()); o.writeDouble(s.z()); }
        }));
        sections.put("advancements", names(r.advancements()));
        sections.put("recipes", names(r.recipes()));
        sections.put("progress", r.progress());
        sections.putAll(r.extraSections());
        SectionCodec.write(out, sections);
    }

    public static PlayerRecord read(DataInput in) throws IOException {
        Map<String, byte[]> sections = SectionCodec.read(in);
        try {
            try (DataInputStream format = required(sections, "format")) {
                if (format.readInt() != MAGIC) throw new IOException("invalid player record magic");
                int version = format.readInt(), minReader = format.readInt();
                if (version < 1 || minReader < 1 || minReader > version)
                    throw new IOException("invalid player record version " + version + "/" + minReader);
                if (minReader > PlayerRecord.VERSION) throw new TooNewException(version, minReader);
                end(format);
            }
            var b = PlayerRecord.builder();
            try (DataInputStream pose = required(sections, "pose")) {
                b.pose(pose.readDouble(), pose.readDouble(), pose.readDouble(),
                        pose.readFloat(), pose.readFloat(), pose.readInt());
                end(pose);
            }
            try (DataInputStream vitals = required(sections, "vitals")) {
                b.vitals(new PlayerRecord.Vitals(vitals.readFloat(), vitals.readFloat(), vitals.readFloat(),
                        vitals.readFloat(), vitals.readFloat()));
                end(vitals);
            }
            ItemStack[] inventory = readItems(requiredBytes(sections, "inventory"), Inventory.SIZE);
            if (inventory.length != Inventory.SIZE)
                throw new IOException("player inventory must have " + Inventory.SIZE + " slots");
            b.inventory(inventory);
            if (sections.containsKey("equipment"))
                b.equipment(readItems(sections.get("equipment"), PlayerRecord.MAX_EQUIPMENT));
            if (sections.containsKey("pending"))
                b.pending(readItems(sections.get("pending"), PlayerRecord.MAX_PENDING));
            if (sections.containsKey("spawn")) {
                try (DataInputStream spawn = input(sections.get("spawn"))) {
                    int present = spawn.readUnsignedByte();
                    if (present > 1) throw new IOException("invalid personal spawn flag");
                    if (present == 1)
                        b.spawn(new PlayerRecord.Spawn(spawn.readDouble(), spawn.readDouble(), spawn.readDouble()));
                    end(spawn);
                }
            }
            if (sections.containsKey("advancements")) b.advancements(readNames(sections.get("advancements")));
            if (sections.containsKey("recipes")) b.recipes(readNames(sections.get("recipes")));
            b.progress(sections.get("progress"));
            if (sections.containsKey("effects")) readEffects(sections.get("effects"), b);
            for (var section : sections.entrySet())
                if (!KNOWN.contains(section.getKey())) b.section(section.getKey(), section.getValue());
            return b.build();
        } catch (IllegalArgumentException invalid) {
            throw new IOException("invalid player record: " + invalid.getMessage(), invalid);
        }
    }

    private static void readEffects(byte[] bytes, PlayerRecord.Builder b) {
        try (DataInputStream in = input(bytes)) {
            int count = bounded(VarInt.read(in), PlayerRecord.MAX_EFFECTS);
            List<PlayerRecord.Effect> effects = new ArrayList<>();
            for (int i = 0; i < count; i++)
                effects.add(new PlayerRecord.Effect(in.readUTF(), in.readLong(), in.readInt()));
            end(in);
            b.effects(effects);
        } catch (IOException | IllegalArgumentException damaged) {
            // Effects are timers, not possessions: losing their meaning must not
            // lock a player out of their inventory. The bytes are still kept.
            b.damagedEffects(bytes, "effects section kept but not applied: " + damaged.getMessage());
        }
    }

    private static byte[] items(ItemStack[] items) throws IOException {
        return bytes(o -> {
            VarInt.write(o, items.length);
            for (ItemStack item : items) ItemStackCodec.write(o, item);
        });
    }

    private static ItemStack[] readItems(byte[] bytes, int limit) throws IOException {
        try (DataInputStream in = input(bytes)) {
            int count = bounded(VarInt.read(in), limit);
            ItemStack[] result = new ItemStack[count];
            for (int i = 0; i < count; i++) result[i] = ItemStackCodec.read(in);
            end(in);
            return result;
        }
    }

    private static byte[] names(List<String> names) throws IOException {
        return bytes(o -> {
            VarInt.write(o, names.size());
            for (String name : names) o.writeUTF(name);
        });
    }

    private static List<String> readNames(byte[] bytes) throws IOException {
        try (DataInputStream in = input(bytes)) {
            int count = bounded(VarInt.read(in), PlayerRecord.MAX_NAMES);
            List<String> result = new ArrayList<>(count);
            for (int i = 0; i < count; i++) result.add(in.readUTF());
            end(in);
            return result;
        }
    }

    private static int bounded(int count, int max) throws IOException {
        if (count < 0 || count > max) throw new IOException("invalid player section count " + count);
        return count;
    }

    private static byte[] requiredBytes(Map<String, byte[]> sections, String key) throws IOException {
        byte[] bytes = sections.get(key);
        if (bytes == null) throw new IOException("missing player section " + key);
        return bytes;
    }

    private static DataInputStream required(Map<String, byte[]> sections, String key) throws IOException {
        return input(requiredBytes(sections, key));
    }

    private static DataInputStream input(byte[] bytes) {
        return new DataInputStream(new ByteArrayInputStream(bytes));
    }

    private static void end(DataInputStream in) throws IOException {
        if (in.read() != -1) throw new IOException("trailing player section bytes");
    }

    @FunctionalInterface
    private interface Writer { void write(DataOutputStream out) throws IOException; }

    private static byte[] bytes(Writer writer) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) { writer.write(out); }
        return bytes.toByteArray();
    }
}

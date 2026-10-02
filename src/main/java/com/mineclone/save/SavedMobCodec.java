package com.mineclone.save;

import com.mineclone.data.VarInt;
import com.mineclone.data.VarLong;
import java.io.*;
import java.util.*;

/**
 * One bounded entity representation for both historical branches. Old opaque tails
 * stay opaque: structured breeding state lives before the tail in the versioned layout.
 */
public final class SavedMobCodec {
    private static final int MAGIC = 0xD94D4F42;
    private static final int VERSION = 2;
    public static final int MAX_RECORDS = 1024;
    public static final int MAX_RECORD_BYTES = 2048;
    private static final int MAX_SECTION_BYTES = MAX_RECORDS * (MAX_RECORD_BYTES + 2) + 16;
    private SavedMobCodec() {}

    public static final class UnsupportedVersion extends IOException {
        public UnsupportedVersion(int version) { super("unsupported entity schema " + version); }
    }

    public static byte[] encode(List<SavedMob> records) throws IOException {
        if (records.size() > MAX_RECORDS) throw new IOException("too many mobs in a chunk");
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            out.writeByte(VERSION);
            VarInt.write(out, records.size());
            var ids = new HashSet<Long>();
            for (SavedMob mob : records) {
                validate(mob);
                if (!ids.add(mob.id())) throw new IOException("duplicate mob id");
                var record = new ByteArrayOutputStream();
                try (var data = new DataOutputStream(record)) {
                    data.writeUTF(mob.type());
                    VarLong.write(data, mob.id());
                    data.writeFloat(mob.x()); data.writeFloat(mob.y()); data.writeFloat(mob.z());
                    data.writeFloat(mob.yaw()); data.writeFloat(mob.health());
                    data.writeFloat(mob.age()); data.writeBoolean(mob.persistent());
                    data.writeFloat(mob.loveCooldown()); data.writeFloat(mob.love());
                    data.write(mob.tail());
                }
                if (record.size() > MAX_RECORD_BYTES) throw new IOException("mob record too long");
                VarInt.write(out, record.size());
                record.writeTo(out);
            }
        }
        return bytes.toByteArray();
    }

    public static List<SavedMob> decode(byte[] payload) throws IOException {
        if (payload == null) return List.of();
        if (payload.length == 0 || payload.length > MAX_SECTION_BYTES)
            throw new IOException("invalid entity section size");
        try (var in = new DataInputStream(new ByteArrayInputStream(payload))) {
            boolean versioned = payload.length >= 4
                    && ((payload[0] & 255) << 24 | (payload[1] & 255) << 16
                    | (payload[2] & 255) << 8 | payload[3] & 255) == MAGIC;
            // The remote branch wrote byte version + int count. A one-record
            // headerless save has a nonzero record length as its second byte.
            boolean generationLayout = !versioned && payload.length >= 5 && payload[0] > 0 && payload[1] == 0;
            int count;
            if (versioned) {
                in.readInt();
                int version = in.readUnsignedByte();
                if (version > VERSION) throw new UnsupportedVersion(version);
                if (version != VERSION) throw new IOException("invalid entity schema " + version);
                count = VarInt.read(in);
            } else if (generationLayout) {
                int version = in.readUnsignedByte();
                if (version > 1) throw new UnsupportedVersion(version);
                count = in.readInt();
            } else count = VarInt.read(in);
            if (count < 0 || count > (versioned || generationLayout ? MAX_RECORDS : 256))
                throw new IOException("invalid mob count " + count);
            var result = new ArrayList<SavedMob>(count);
            var ids = new HashSet<Long>();
            for (int i = 0; i < count; i++) {
                SavedMob mob;
                if (generationLayout) {
                    long id = in.readLong();
                    String type = in.readUTF();
                    mob = new SavedMob(type, id, in.readFloat(), in.readFloat(), in.readFloat(),
                            in.readFloat(), in.readFloat(), in.readFloat(), in.readBoolean(),
                            in.readFloat(), in.readFloat(), new byte[0]);
                } else {
                    int length = VarInt.read(in);
                    if (length <= 0 || length > (versioned ? MAX_RECORD_BYTES : 1024))
                        throw new IOException("invalid mob record length " + length);
                    byte[] record = new byte[length];
                    in.readFully(record);
                    try (var data = new DataInputStream(new ByteArrayInputStream(record))) {
                        String type = data.readUTF();
                        long id = VarLong.read(data);
                        float x = data.readFloat(), y = data.readFloat(), z = data.readFloat();
                        float yaw = data.readFloat(), health = data.readFloat();
                        float age = versioned ? data.readFloat() : 0f;
                        boolean persistent = !versioned || data.readBoolean();
                        float cooldown = versioned ? data.readFloat() : 0f;
                        float love = versioned ? data.readFloat() : 0f;
                        mob = new SavedMob(type, id, x, y, z, yaw, health,
                                age, persistent, cooldown, love, data.readAllBytes());
                    }
                }
                validate(mob);
                if (!ids.add(mob.id())) throw new IOException("duplicate mob id");
                result.add(mob);
            }
            if (in.available() != 0) throw new IOException("trailing entity bytes");
            return List.copyOf(result);
        }
    }

    private static void validate(SavedMob mob) throws IOException {
        if (mob.type() == null || mob.type().isEmpty() || mob.id() <= 0)
            throw new IOException("invalid mob identity");
        if (!Float.isFinite(mob.x()) || !Float.isFinite(mob.y()) || !Float.isFinite(mob.z())
                || !Float.isFinite(mob.yaw()) || !Float.isFinite(mob.health()) || !(mob.health() > 0f)
                || !Float.isFinite(mob.age()) || mob.age() < -1200f
                || !Float.isFinite(mob.loveCooldown()) || mob.loveCooldown() < 0f || mob.loveCooldown() > 300f
                || !Float.isFinite(mob.love()) || mob.love() < 0f || mob.love() > 30f || !mob.persistent())
            throw new IOException("invalid mob state");
    }
}

package com.mineclone.sim.ecology;

import com.mineclone.data.ResourceId;
import com.mineclone.world.Chunk;
import com.mineclone.world.entity.MobType;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/** One finite hostile lair, persisted inside a region's existing opaque lair-record slot. */
public final class Lair {
    public static final int MAGIC = 0x4c414952, VERSION = 1, MAX_RECORD_BYTES = 1024, MAX_CATCHUP_DAYS = 60;
    private final ResourceId id;
    private final MobType type;
    private final int x, y, z, capacity, growth, emit, radius, baseBytes;
    private long lastDay, destroyedDay = -1;
    private int population;
    private boolean alive = true;
    private final TreeSet<Long> bodies = new TreeSet<>();
    private byte[] tail = new byte[0];

    public Lair(int x, int y, int z, long day, LairTable.Definition definition) {
        if (y < 0 || y >= Chunk.SIZE_Y) throw new IllegalArgumentException("lair height out of range");
        if (day < 0) throw new IllegalArgumentException("negative lair day");
        if (definition == null) throw new IllegalArgumentException("missing lair definition");
        this.x = x; this.y = y; this.z = z; lastDay = day;
        id = definition.id(); type = definition.type(); population = definition.initial();
        capacity = definition.capacity(); growth = definition.growth(); emit = definition.emit(); radius = definition.radius();
        // Both identifiers are ASCII, so modified UTF writes one byte per character.
        baseBytes = 59 + id.toString().length() + type.name().length();
    }

    public ResourceId id() { return id; }
    public MobType type() { return type; }
    public int x() { return x; }
    public int y() { return y; }
    public int z() { return z; }
    public long lastDay() { return lastDay; }
    public int population() { return population; }
    public int capacity() { return capacity; }
    public int growth() { return growth; }
    public int emit() { return emit; }
    public int radius() { return radius; }
    public boolean alive() { return alive; }
    public long destroyedDay() { return destroyedDay; }
    public Set<Long> bodyIds() { return Collections.unmodifiableSortedSet(new TreeSet<>(bodies)); }
    public int available() {
        if (!alive) return 0;
        int recordSlots = (MAX_RECORD_BYTES - baseBytes - tail.length) / Long.BYTES - bodies.size();
        return Math.min(recordSlots, Math.min(population - bodies.size(), emit - bodies.size()));
    }
    public byte[] tail() { return tail.clone(); }

    /** Claiming represents an existing member; it never adds a second animal. */
    public boolean claim(long bodyId) {
        if (bodyId <= 0 || bodies.contains(bodyId) || available() <= 0) return false;
        return bodies.add(bodyId);
    }

    /** Only the first death of a represented body consumes a population member. */
    public boolean died(long bodyId) {
        if (!bodies.remove(bodyId)) return false;
        population--;
        return true;
    }

    /** Dawn removes the representation, while the animal remains in the lair's population. */
    public boolean returned(long bodyId) { return bodies.remove(bodyId); }

    /** Surviving emitted bodies keep their identities and population after the heart is broken. */
    public boolean destroy(long day) {
        if (day < 0) throw new IllegalArgumentException("negative lair day");
        if (!alive) return false;
        alive = false;
        destroyedDay = Math.max(lastDay, day);
        lastDay = destroyedDay;
        return true;
    }

    /** Skip old catch-up days, as regional ecology does, and never rewind a future record. */
    public void advance(long targetDay) {
        if (targetDay < 0) throw new IllegalArgumentException("negative lair day");
        if (targetDay <= lastDay) return;
        long days = Math.min(MAX_CATCHUP_DAYS, targetDay - lastDay);
        if (alive) population = (int) Math.min(capacity, population + days * growth);
        lastDay = targetDay;
    }

    /** Unknown suffix bytes must fit together with the typed record in its existing 1-KiB slot. */
    public void tail(byte[] value) {
        if (value == null || value.length > MAX_RECORD_BYTES) throw new IllegalArgumentException("lair tail too large");
        try {
            if (knownBytes().length + value.length > MAX_RECORD_BYTES) throw new IllegalArgumentException("lair record too large");
        } catch (IOException impossible) { throw new IllegalStateException("cannot encode lair", impossible); }
        tail = value.clone();
    }

    public byte[] encode() throws IOException {
        byte[] known = knownBytes();
        if (known.length + tail.length > MAX_RECORD_BYTES) throw new IOException("lair record too large");
        var bytes = new ByteArrayOutputStream(known.length + tail.length);
        bytes.write(known); bytes.write(tail);
        return bytes.toByteArray();
    }

    private byte[] knownBytes() throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC); out.writeByte(VERSION);
            out.writeUTF(id.toString()); out.writeUTF(type.name());
            out.writeInt(x); out.writeInt(y); out.writeInt(z); out.writeLong(lastDay);
            out.writeInt(population); out.writeInt(capacity); out.writeInt(growth); out.writeInt(emit); out.writeInt(radius);
            out.writeBoolean(alive); out.writeLong(destroyedDay);
            out.writeByte(bodies.size());
            for (long bodyId : bodies) out.writeLong(bodyId);
        }
        return bytes.toByteArray();
    }

    /** The owner preserves the original blob and disables interpretation of this newer layout. */
    public static final class NewerVersionException extends IOException {
        private final int version;
        public NewerVersionException(int version) { super("newer lair record version " + version); this.version = version; }
        public int version() { return version; }
    }

    /** A valid saved definition this build cannot simulate must stay protected. */
    public static final class UnsupportedDefinitionException extends IOException {
        public UnsupportedDefinitionException(String definition) { super("unsupported lair definition " + definition); }
    }

    /** Unknown magic stays opaque; malformed records with our magic must never be silently replaced. */
    public static Lair decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 4) return null;
        int magic = (bytes[0] & 255) << 24 | (bytes[1] & 255) << 16 | (bytes[2] & 255) << 8 | bytes[3] & 255;
        if (magic != MAGIC) return null;
        if (bytes.length > MAX_RECORD_BYTES) throw new IOException("lair record too large");
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            in.readInt();
            int version = in.readUnsignedByte();
            if (version > VERSION) throw new NewerVersionException(version);
            if (version != VERSION) throw new IOException("unsupported lair record version " + version);
            ResourceId id = ResourceId.of(in.readUTF());
            String typeName = in.readUTF();
            boolean unsupportedType = false;
            MobType type;
            try { type = MobType.valueOf(typeName); }
            catch (IllegalArgumentException unknown) {
                if (!typeName.matches("[A-Z][A-Z0-9_]{0,63}")) throw new IOException("invalid lair mob type", unknown);
                // Validate the rest of this known layout before distinguishing a
                // supported record from a valid future hostile definition.
                type = MobType.SPIDER; unsupportedType = true;
            }
            int x = in.readInt(), y = in.readInt(), z = in.readInt();
            long day = in.readLong();
            int population = in.readInt(), capacity = in.readInt(), growth = in.readInt(), emit = in.readInt(), radius = in.readInt();
            Lair result = new Lair(x, y, z, day, new LairTable.Definition(id, type, population, capacity, growth, emit, radius));
            int alive = in.readUnsignedByte();
            long destroyedDay = in.readLong();
            if (alive > 1 || (alive == 1 ? destroyedDay != -1 : destroyedDay < 0 || destroyedDay > day))
                throw new IOException("invalid lair heart state");
            result.alive = alive == 1;
            result.destroyedDay = destroyedDay;
            int count = in.readUnsignedByte();
            if (count > Math.min(population, emit)) throw new IOException("too many lair bodies");
            for (int i = 0; i < count; i++) {
                long bodyId = in.readLong();
                if (bodyId <= 0 || !result.bodies.add(bodyId)) throw new IOException("invalid or duplicate lair body id");
            }
            result.tail = in.readAllBytes();
            if (unsupportedType) throw new UnsupportedDefinitionException(typeName);
            return result;
        } catch (IllegalArgumentException invalid) { throw new IOException("invalid lair record", invalid); }
    }
}

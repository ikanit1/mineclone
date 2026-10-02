package com.mineclone.sim.ecology;

import com.mineclone.data.ResourceId;
import com.mineclone.data.SectionCodec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Bounded level ecology snapshots preserving unknown species and future lair/scar payloads. */
public final class EcologyCodec {
    public static final String SAVE_SECTION = "ecology";
    public static final int VERSION = 1, MAX_REGIONS = 4096, MAX_RESERVATIONS = 65536;
    private EcologyCodec() {}
    public record Reservation(long mobId, int regionX, int regionZ, ResourceId species) {
        public Reservation {
            if (mobId <= 0 || species == null) throw new IllegalArgumentException("invalid ecology reservation");
        }
        public long regionKey() { return EcologyRegion.key(regionX, regionZ); }
    }
    public record Snapshot(List<EcologyRegion> regions, Map<Long, Reservation> reservations, byte[] tail) {
        public Snapshot {
            regions = regions.stream().map(EcologyRegion::copy).toList();
            reservations = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(reservations));
            tail = tail == null ? new byte[0] : tail.clone();
        }
        public Snapshot(List<EcologyRegion> regions, Map<Long, Reservation> reservations) { this(regions, reservations, new byte[0]); }
        @Override public List<EcologyRegion> regions() { return regions.stream().map(EcologyRegion::copy).toList(); }
        @Override public byte[] tail() { return tail.clone(); }
    }
    /** Caller keeps the original section opaque and turns ecology off. */
    public static final class NewerVersionException extends IOException {
        private final int version;
        public NewerVersionException(int version) { super("newer ecology section version " + version); this.version = version; }
        public int version() { return version; }
    }
    public static byte[] encode(Snapshot snapshot) throws IOException {
        if (snapshot.regions.size() > MAX_REGIONS || snapshot.reservations.size() > MAX_RESERVATIONS
                || snapshot.tail.length > 4096) throw new IOException("ecology snapshot exceeds bounds");
        TreeSet<ResourceId> ids = new TreeSet<>();
        var regions = snapshot.regions.stream().sorted(Comparator.comparingInt(EcologyRegion::x).thenComparingInt(EcologyRegion::z)).toList();
        var regionKeys = new java.util.HashSet<Long>();
        for (EcologyRegion region : regions) {
            if (!regionKeys.add(region.key())) throw new IOException("duplicate ecology region");
            ids.addAll(region.populations().keySet()); ids.addAll(region.capacities().keySet()); ids.addAll(region.foodBonuses().keySet());
            ids.addAll(region.protectedCounts().keySet()); ids.addAll(region.trends().keySet());
        }
        for (var entry : snapshot.reservations.entrySet()) {
            Reservation value = entry.getValue();
            if (entry.getKey() != value.mobId() || !regionKeys.contains(value.regionKey())) throw new IOException("orphan ecology reservation");
            ids.add(value.species());
        }
        if (ids.size() > SpeciesTable.MAX_SPECIES) throw new IOException("too many ecology species");
        validateOwnership(regions, snapshot.reservations);
        List<ResourceId> species = List.copyOf(ids);
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(VERSION); out.writeInt(VERSION);
            out.writeInt(species.size());
            for (ResourceId id : species) { if (id.toString().length() > 128) throw new IOException("ecology id too long"); out.writeUTF(id.toString()); }
            out.writeInt(regions.size());
            for (EcologyRegion region : regions) {
                out.writeInt(region.x()); out.writeInt(region.z()); out.writeLong(region.lastDay()); out.write(region.greenery());
                for (EcologyRegion.LossCause cause : EcologyRegion.LossCause.values()) out.writeInt(region.lossCount(cause));
                for (ResourceId id : species) {
                    out.writeInt(region.population(id)); out.writeInt(region.capacity(id)); out.writeInt(region.foodBonus(id));
                    out.writeInt(region.protectedCount(id)); for (int trend : region.trend(id)) out.writeInt(trend);
                }
                writeBlobs(out, region.lairs()); writeBlobs(out, region.scars()); writeBytes(out, region.tail());
                boundSize(bytes);
            }
            out.writeInt(snapshot.reservations.size());
            for (Reservation value : snapshot.reservations.values().stream().sorted(Comparator.comparingLong(Reservation::mobId)).toList()) {
                out.writeLong(value.mobId()); out.writeInt(value.regionX()); out.writeInt(value.regionZ()); out.writeByte(species.indexOf(value.species()));
                boundSize(bytes);
            }
            writeBytes(out, snapshot.tail); boundSize(bytes);
        }
        return bytes.toByteArray();
    }
    public static Snapshot decode(byte[] bytes) throws IOException {
        if (bytes == null) return new Snapshot(List.of(), Map.of());
        if (bytes.length > SectionCodec.MAX_SECTION_BYTES) throw new IOException("ecology section too large");
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int version = in.readInt(), minimumReader = in.readInt();
            if (version < 1 || minimumReader < 1 || minimumReader > version) throw new IOException("invalid ecology version");
            if (version > VERSION || minimumReader > VERSION) throw new NewerVersionException(version);
            int speciesCount = count(in, SpeciesTable.MAX_SPECIES, "species");
            List<ResourceId> species = new ArrayList<>();
            for (int i = 0; i < speciesCount; i++) {
                String name = in.readUTF(); if (name.length() > 128) throw new IOException("ecology id too long");
                ResourceId id = ResourceId.of(name); if (species.contains(id)) throw new IOException("duplicate ecology species"); species.add(id);
            }
            int regionCount = count(in, MAX_REGIONS, "regions");
            List<EcologyRegion> regions = new ArrayList<>();
            var regionKeys = new java.util.HashSet<Long>();
            for (int i = 0; i < regionCount; i++) {
                EcologyRegion region = new EcologyRegion(in.readInt(), in.readInt(), in.readLong());
                if (!regionKeys.add(region.key())) throw new IOException("duplicate ecology region");
                byte[] greenery = new byte[EcologyRegion.GREENERY_CELLS]; in.readFully(greenery); region.greenery(greenery);
                for (EcologyRegion.LossCause cause : EcologyRegion.LossCause.values()) region.lossCount(cause, count(in, SpeciesTable.MAX_POPULATION, "loss"));
                for (ResourceId id : species) {
                    region.population(id, count(in, SpeciesTable.MAX_POPULATION, "population"));
                    region.capacity(id, count(in, SpeciesTable.MAX_POPULATION, "capacity"));
                    region.foodBonus(id, count(in, SpeciesTable.MAX_POPULATION, "food bonus"));
                    region.protectedCount(id, count(in, SpeciesTable.MAX_POPULATION, "protected count"));
                    region.trend(id, new int[] { in.readInt(), in.readInt(), in.readInt() });
                }
                region.lairs(readBlobs(in, 4)); region.scars(readBlobs(in, 16)); region.tail(readBytes(in, 4096)); regions.add(region);
            }
            int reservationCount = count(in, MAX_RESERVATIONS, "reservations");
            Map<Long, Reservation> reservations = new LinkedHashMap<>();
            for (int i = 0; i < reservationCount; i++) {
                long id = in.readLong(); int x = in.readInt(), z = in.readInt(), speciesIndex = in.readUnsignedByte();
                if (speciesIndex >= species.size()) throw new IOException("invalid reservation species");
                Reservation reservation = new Reservation(id, x, z, species.get(speciesIndex));
                if (!regionKeys.contains(reservation.regionKey()) || reservations.put(id, reservation) != null) throw new IOException("duplicate or orphan ecology reservation");
            }
            byte[] tail = readBytes(in, 4096);
            if (in.read() != -1) throw new IOException("trailing ecology bytes");
            validateOwnership(regions, reservations);
            return new Snapshot(regions, reservations, tail);
        } catch (IllegalArgumentException e) { throw new IOException("invalid ecology data: " + e.getMessage(), e); }
    }
    private static int count(DataInputStream in, int max, String what) throws IOException {
        int n = in.readInt(); if (n < 0 || n > max) throw new IOException("invalid ecology " + what + " count"); return n;
    }
    private static void writeBlobs(DataOutputStream out, List<byte[]> values) throws IOException {
        out.writeInt(values.size()); for (byte[] value : values) writeBytes(out, value);
    }
    private static List<byte[]> readBlobs(DataInputStream in, int max) throws IOException {
        int count = count(in, max, "opaque records"); var values = new ArrayList<byte[]>();
        for (int i = 0; i < count; i++) values.add(readBytes(in, 1024)); return values;
    }
    private static void writeBytes(DataOutputStream out, byte[] value) throws IOException { out.writeInt(value.length); out.write(value); }
    private static byte[] readBytes(DataInputStream in, int max) throws IOException {
        int n = count(in, max, "opaque bytes"); byte[] value = new byte[n]; in.readFully(value); return value;
    }
    private static void boundSize(ByteArrayOutputStream bytes) throws IOException {
        if (bytes.size() > SectionCodec.MAX_SECTION_BYTES) throw new IOException("ecology section too large");
    }
    private static void validateOwnership(List<EcologyRegion> regions, Map<Long, Reservation> reservations) throws IOException {
        var byKey = new java.util.HashMap<Long, EcologyRegion>();
        for (EcologyRegion region : regions) {
            byKey.put(region.key(), region);
            for (var protectedCount : region.protectedCounts().entrySet())
                if (protectedCount.getValue() > region.population(protectedCount.getKey()))
                    throw new IOException("protected bodies exceed ecology population");
        }
        var counts = new java.util.HashMap<Long, Map<ResourceId, Integer>>();
        for (Reservation held : reservations.values()) {
            EcologyRegion region = byKey.get(held.regionKey());
            if (region == null) throw new IOException("orphan ecology reservation");
            int count = counts.computeIfAbsent(held.regionKey(), ignored -> new java.util.HashMap<>())
                    .merge(held.species(), 1, Integer::sum);
            if (count > region.population(held.species())) throw new IOException("reserved bodies exceed ecology population");
        }
    }
}

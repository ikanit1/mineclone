package com.mineclone.sim.ecology;

import com.mineclone.sim.WorldClock;
import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.World;
import com.mineclone.world.behavior.SaplingBehavior;
import com.mineclone.world.gen.TreeShapes;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Bounded host fire history, daily ground recovery and natural trees on recorded burned ground. */
public final class BurnScars {
    public static final int MAGIC = 0x4255524e, VERSION = 1, MAX_RECORD_BYTES = 1024;
    public static final int MAX_RECORDS = 16, MAX_SITES = 64, MAX_CATCHUP_DAYS = 60;
    public static final int GRASS_DAY = 2, SAPLING_DAY = 3, RECOVERY_DAYS = 7;
    public static final int GREENERY_LOSS_PER_SITE = 16, MAX_GREENERY_LOSS = 223, TREE_SPACING = 7;
    private static final int MAX_TREE_HEIGHT = 12;
    private static final int HEADER_BYTES = 22, SITE_BYTES = 10;
    private static final int MIN_CHUNK = Math.floorDiv(Integer.MIN_VALUE, Chunk.SIZE_X);
    private static final int MAX_CHUNK = Math.floorDiv(Integer.MAX_VALUE, Chunk.SIZE_X);

    /** A planted site cannot seed another tree after its first sapling is removed. */
    public record Site(int index, long burnDay, boolean plantedOnce) {
        public Site {
            if (index < 0 || index >= Chunk.SIZE_X * (Chunk.SIZE_Y - 1) * Chunk.SIZE_Z || burnDay < 0)
                throw new IllegalArgumentException("invalid burn scar site");
        }
        public int localX() { return index % 16; }
        public int groundY() { return index / 256; }
        public int localZ() { return index / 16 % 16; }
    }

    /** Detached queries expose no mutable record state; unknown suffixes remain byte exact. */
    public static final class Scar {
        private final int cx, cz;
        private long lastProcessedDay;
        private final TreeMap<Integer, Site> sites = new TreeMap<>();
        private byte[] tail = new byte[0];

        private Scar(int cx, int cz, long day) {
            if (cx < MIN_CHUNK || cx > MAX_CHUNK || cz < MIN_CHUNK || cz > MAX_CHUNK || day < 0)
                throw new IllegalArgumentException("invalid burn scar location or day");
            this.cx = cx; this.cz = cz; lastProcessedDay = day;
        }
        public int cx() { return cx; }
        public int cz() { return cz; }
        public long lastProcessedDay() { return lastProcessedDay; }
        public List<Site> sites() { return List.copyOf(sites.values()); }
        public byte[] tail() { return tail.clone(); }
        private Scar copy() {
            Scar result = new Scar(cx, cz, lastProcessedDay);
            result.sites.putAll(sites); result.tail = tail.clone(); return result;
        }
        private boolean room() {
            return sites.size() < MAX_SITES && HEADER_BYTES + (sites.size() + 1) * SITE_BYTES + tail.length <= MAX_RECORD_BYTES;
        }
        public byte[] encode() throws IOException {
            if (sites.isEmpty() || sites.size() > MAX_SITES || HEADER_BYTES + sites.size() * SITE_BYTES + tail.length > MAX_RECORD_BYTES)
                throw new IOException("burn scar record exceeds bounds");
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                out.writeInt(MAGIC); out.writeByte(VERSION); out.writeInt(cx); out.writeInt(cz);
                out.writeLong(lastProcessedDay); out.writeByte(sites.size());
                for (Site site : sites.values()) {
                    out.writeShort(site.index | (site.plantedOnce ? 0x8000 : 0)); out.writeLong(site.burnDay);
                }
                out.write(tail);
            }
            return bytes.toByteArray();
        }
        /** Unknown magic is historical opaque state; a known newer version is protected before parsing it. */
        public static Scar decode(byte[] bytes) throws IOException {
            if (bytes == null || bytes.length < 4) return null;
            int magic = (bytes[0] & 255) << 24 | (bytes[1] & 255) << 16 | (bytes[2] & 255) << 8 | bytes[3] & 255;
            if (magic != MAGIC) return null;
            if (bytes.length > MAX_RECORD_BYTES) throw new IOException("burn scar record too large");
            try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
                in.readInt(); int version = in.readUnsignedByte();
                if (version > VERSION) throw new NewerVersionException(version);
                if (version != VERSION) throw new IOException("unsupported burn scar version " + version);
                Scar result = new Scar(in.readInt(), in.readInt(), in.readLong());
                int count = in.readUnsignedByte();
                if (count < 1 || count > MAX_SITES) throw new IOException("invalid burn scar site count");
                for (int n = 0; n < count; n++) {
                    int bits = in.readUnsignedShort();
                    Site site = new Site(bits & 0x7fff, in.readLong(), (bits & 0x8000) != 0);
                    if (result.sites.putIfAbsent(site.index, site) != null) throw new IOException("duplicate burn scar site");
                }
                result.tail = in.readAllBytes(); return result;
            } catch (IllegalArgumentException invalid) { throw new IOException("invalid burn scar record", invalid); }
        }
    }

    public static final class NewerVersionException extends IOException {
        private final int version;
        public NewerVersionException(int version) { super("newer burn scar version " + version); this.version = version; }
        public int version() { return version; }
    }
    private record Slot(Scar scar, byte[] opaque) {}
    private final World world;
    private final WorldClock clock;
    private final Map<Long, List<Slot>> records = new LinkedHashMap<>();
    private long revision;

    public BurnScars(World world, WorldClock clock) { this.world = world; this.clock = clock; }
    public long revision() { return revision; }
    public void clear() { records.clear(); revision++; }

    public void restore(Collection<EcologyRegion> regions) throws IOException {
        clear();
        for (EcologyRegion region : regions) {
            List<Slot> slots = new ArrayList<>(); var positions = new HashSet<String>();
            for (byte[] bytes : region.scars()) {
                Scar scar = Scar.decode(bytes);
                if (scar != null) {
                    if (Math.floorDiv(scar.cx, 16) != region.x() || Math.floorDiv(scar.cz, 16) != region.z())
                        throw new IOException("burn scar belongs to another region");
                    for (Site site : scar.sites.values())
                        if (!positions.add(scar.cx + ":" + scar.cz + ":" + site.index))
                            throw new IOException("duplicate ground cell across burn scars");
                }
                slots.add(new Slot(scar, scar == null ? bytes.clone() : null));
            }
            if (!slots.isEmpty() && records.putIfAbsent(region.key(), slots) != null)
                throw new IOException("duplicate burn scar region");
        }
    }

    public List<Scar> scars(long regionKey) {
        List<Scar> result = new ArrayList<>();
        for (Slot slot : records.getOrDefault(regionKey, List.of())) if (slot.scar != null) result.add(slot.scar.copy());
        return List.copyOf(result);
    }

    /** Register first: when the bounded history is full, the caller must leave the ground alone. */
    public boolean burned(EcologyRegion region, int x, int y, int z, long day) {
        if (day < 0) throw new IllegalArgumentException("negative burn day");
        if (region == null || y < 0 || y >= Chunk.SIZE_Y - 1
                || Math.floorDiv(x, 256) != region.x() || Math.floorDiv(z, 256) != region.z()) return false;
        int cx = Math.floorDiv(x, 16), cz = Math.floorDiv(z, 16);
        Chunk chunk = world.getChunkIfExists(cx, cz);
        if (chunk == null || chunk.isReadOnly()) return false;
        BlockType ground = world.getBlock(x, y, z);
        if (ground != BlockType.GRASS && ground != BlockType.SNOWY_GRASS && ground != BlockType.DRY_GRASS
                && ground != BlockType.PODZOL && !(ground == BlockType.DIRT && knownAt(x, y, z))) return false;
        int index = Chunk.idx(Math.floorMod(x, 16), y, Math.floorMod(z, 16));
        List<Slot> slots = records.computeIfAbsent(region.key(), ignored -> new ArrayList<>());
        Scar available = null;
        for (Slot slot : slots) {
            Scar scar = slot.scar;
            if (scar == null || scar.cx != cx || scar.cz != cz) continue;
            Site old = scar.sites.get(index);
            if (old != null) {
                if (day < old.burnDay) return false;
                scar.sites.put(index, new Site(index, day, false)); revision++; return true;
            }
            if (available == null && scar.room()) available = scar;
        }
        if (available == null) {
            if (slots.size() >= MAX_RECORDS) {
                Slot reclaim = null;
                for (Slot slot : slots) if (slot.scar != null && completed(slot.scar, day)) {
                    reclaim = slot; break;
                }
                if (reclaim == null) return false;
                slots.remove(reclaim);
            }
            available = new Scar(cx, cz, day); slots.add(new Slot(available, null));
        }
        available.sites.put(index, new Site(index, day, false)); revision++; return true;
    }

    /** Dense healed ground need not plant a tree in every cell; spacing deliberately prevents that. */
    private boolean completed(Scar scar, long day) {
        if (scar.tail.length != 0) return false;
        Chunk chunk = world.getChunkIfExists(scar.cx, scar.cz);
        if (chunk == null || chunk.isReadOnly()) return false;
        for (Site site : scar.sites.values()) {
            if (day < site.burnDay || day - site.burnDay < RECOVERY_DAYS) return false;
            int x = scar.cx * 16 + site.localX(), y = site.groundY(), z = scar.cz * 16 + site.localZ();
            if (world.getBlock(x, y, z) != BlockType.GRASS) return false;
            BlockType above = world.getBlock(x, y + 1, z);
            if (above != BlockType.AIR && above != BlockType.WOOD && above != BlockType.LEAVES
                    && !(above == BlockType.SAPLING && (world.getBlockMeta(x, y + 1, z) & SaplingBehavior.NATURAL_SCAR) != 0)) return false;
        }
        return true;
    }

    public void stepDay(Collection<EcologyRegion> regions, long day) { tickLoaded(regions, day); }

    /** Loaded arrival and ordinary/replayed days share this sequence; absent records keep their cursor. */
    public void tickLoaded(Collection<EcologyRegion> regions, long day) {
        if (day < 0) throw new IllegalArgumentException("negative scar day");
        List<Scar> loaded = new ArrayList<>();
        for (EcologyRegion region : regions) for (Slot slot : records.getOrDefault(region.key(), List.of())) {
            Scar scar = slot.scar;
            if (scar == null || scar.lastProcessedDay >= day) continue;
            Chunk chunk = world.getChunkIfExists(scar.cx, scar.cz);
            if (chunk != null && !chunk.isReadOnly()) loaded.add(scar);
        }
        loaded.sort(Comparator.comparingInt((Scar s) -> s.cx).thenComparingInt(s -> s.cz)
                .thenComparingInt(s -> s.sites.firstKey()));
        long first = day;
        for (Scar scar : loaded) first = Math.min(first, Math.max(scar.lastProcessedDay, day - Math.min(day, MAX_CATCHUP_DAYS)));
        for (long next = first; next < day; ) {
            ++next;
            for (Scar scar : loaded) if (scar.lastProcessedDay < next) {
                for (Site site : scar.sites.values()) recover(scar, site, next);
                scar.lastProcessedDay = next; revision++;
            }
        }
    }

    private void recover(Scar scar, Site site, long day) {
        if (day < site.burnDay) return;
        long age = day - site.burnDay;
        int x = scar.cx * 16 + site.localX(), y = site.groundY(), z = scar.cz * 16 + site.localZ();
        if (site.plantedOnce || age < GRASS_DAY || Trample.pressureAt(world, x, y, z) > 0
                || world.getBlock(x, y + 1, z) != BlockType.AIR || !openGround(x, y, z)) return;
        BlockType ground = world.getBlock(x, y, z);
        if (ground == BlockType.DIRT) { world.setBlock(x, y, z, BlockType.GRASS); ground = BlockType.GRASS; }
        if (ground != BlockType.GRASS || age < SAPLING_DAY || (mix(world.seed ^ location(x, y, z) ^ day * 0xD1B54A32D192ED03L) & 7) != 0) return;
        int species = switch (world.biomes.biomeAt(x, z).treeType) {
            case OAK -> 0; case SPRUCE -> 1; case ACACIA -> 2; default -> -1;
        };
        if (species < 0 || !spaced(x, y, z) || !clearCrown(x, y, z, species)) return;
        world.setBlock(x, y + 1, z, BlockType.SAPLING, (byte) (species | SaplingBehavior.NATURAL_SCAR));
        if (world.getBlock(x, y + 1, z) != BlockType.SAPLING) return;
        scar.sites.put(site.index, new Site(site.index, site.burnDay, true));
        SaplingBehavior.scheduleNatural(world, x, y + 1, z, day, clock);
    }

    private boolean openGround(int x, int y, int z) {
        Chunk chunk = world.getChunkIfExists(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
        if (chunk == null || chunk.isReadOnly() || chunk.getSkyRaw(Math.floorMod(x, 16), y + 1, Math.floorMod(z, 16)) < 9) return false;
        for (int above = y + 2; above < Chunk.SIZE_Y; above++) {
            BlockType block = world.getBlock(x, above, z);
            if (block.solid || block == BlockType.WATER || block == BlockType.WATER_FLOW) return false;
        }
        return true;
    }

    private boolean spaced(int x, int y, int z) {
        // Every current TreeShapes crown fits radius three. Keep seven cells
        // between roots so a scheduled seed reserves its future crown as well
        // as a grown tree. This makes planting independent of dispatcher lag.
        for (int dx = -TREE_SPACING + 1; dx < TREE_SPACING; dx++)
            for (int dz = -TREE_SPACING + 1; dz < TREE_SPACING; dz++) {
                int wx = x + dx, wz = z + dz;
                Chunk chunk = world.getChunkIfExists(Math.floorDiv(wx, 16), Math.floorDiv(wz, 16));
                if (chunk == null) continue;
                int lx = Math.floorMod(wx, 16), lz = Math.floorMod(wz, 16);
                // Sloped ground can put the adjacent seed or trunk above or
                // below ours while the two eventual crowns still overlap.
                for (int height = Math.max(0, y - MAX_TREE_HEIGHT); height <= Math.min(127, y + MAX_TREE_HEIGHT); height++) {
                    BlockType block = chunk.get(lx, height, lz);
                    if (block == BlockType.SAPLING || block == BlockType.WOOD) return false;
                }
            }
        return true;
    }

    private boolean clearCrown(int x, int groundY, int z, int species) {
        long hash = world.seed ^ x * 341873128712L ^ z * 132897987541L ^ (groundY + 1) * 524287L;
        var cells = TreeShapes.cells(species, x, groundY, z, hash);
        if (cells.isEmpty()) return false;
        for (var cell : cells) {
            Chunk chunk = world.getChunkIfExists(Math.floorDiv(cell.x(), 16), Math.floorDiv(cell.z(), 16));
            if (chunk == null || chunk.isReadOnly() || world.getBlock(cell.x(), cell.y(), cell.z()) != BlockType.AIR
                    || !world.canGrowNaturalTreeAt(cell.x(), cell.y(), cell.z())) return false;
        }
        return true;
    }

    /** Canopy loss fades over seven absolute days, without erasing the durable fire history. */
    public int greeneryCeiling(int cx, int cz, long day) {
        int loss = 0;
        long key = EcologyRegion.key(Math.floorDiv(cx, 16), Math.floorDiv(cz, 16));
        for (Slot slot : records.getOrDefault(key, List.of())) {
            Scar scar = slot.scar;
            if (scar == null || scar.cx != cx || scar.cz != cz) continue;
            for (Site site : scar.sites.values()) if (day >= site.burnDay && day - site.burnDay < RECOVERY_DAYS) {
                long remaining = RECOVERY_DAYS - (day - site.burnDay);
                loss += (int) ((GREENERY_LOSS_PER_SITE * remaining + RECOVERY_DAYS - 1) / RECOVERY_DAYS);
                if (loss >= MAX_GREENERY_LOSS) return 255 - MAX_GREENERY_LOSS;
            }
        }
        return 255 - loss;
    }

    public boolean damagedAt(int x, int y, int z, long day) {
        if (y < 0 || y >= Chunk.SIZE_Y || day < 0) return false;
        int cx = Math.floorDiv(x, 16), cz = Math.floorDiv(z, 16), index = Chunk.idx(Math.floorMod(x, 16), y, Math.floorMod(z, 16));
        for (Slot slot : records.getOrDefault(EcologyRegion.key(Math.floorDiv(cx, 16), Math.floorDiv(cz, 16)), List.of())) {
            Scar scar = slot.scar;
            if (scar == null || scar.cx != cx || scar.cz != cz) continue;
            Site site = scar.sites.get(index);
            if (site != null && day >= site.burnDay && day - site.burnDay < SAPLING_DAY) return true;
        }
        return false;
    }

    /** Reburning our previously scorched dirt is allowed; ordinary player dirt is never adopted. */
    public boolean knownAt(int x, int y, int z) {
        if (y < 0 || y >= Chunk.SIZE_Y) return false;
        int cx = Math.floorDiv(x, 16), cz = Math.floorDiv(z, 16), index = Chunk.idx(Math.floorMod(x, 16), y, Math.floorMod(z, 16));
        for (Slot slot : records.getOrDefault(EcologyRegion.key(Math.floorDiv(cx, 16), Math.floorDiv(cz, 16)), List.of()))
            if (slot.scar != null && slot.scar.cx == cx && slot.scar.cz == cz && slot.scar.sites.containsKey(index)) return true;
        return false;
    }

    public void flush(Collection<EcologyRegion> regions) {
        for (EcologyRegion region : regions) {
            List<Slot> slots = records.get(region.key());
            if (slots == null) continue;
            List<byte[]> bytes = new ArrayList<>();
            try { for (Slot slot : slots) bytes.add(slot.scar == null ? slot.opaque.clone() : slot.scar.encode()); }
            catch (IOException invalid) { throw new java.io.UncheckedIOException(invalid); }
            region.scars(bytes);
        }
    }
    private static long location(int x, int y, int z) { return ((long) x << 32) ^ (z & 0xffff_ffffL) ^ y * 0x9e3779b97f4a7c15L; }
    private static long mix(long n) {
        n = (n ^ (n >>> 30)) * 0xbf58476d1ce4e5b9L; n = (n ^ (n >>> 27)) * 0x94d049bb133111ebL; return n ^ (n >>> 31);
    }
}

package com.mineclone.world;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Event-driven loose-block physics. Owned by one world; update runs on the game thread. */
public final class FallingBlocks {
    public static final int MAX_ACTIVE = 64;
    private static final int STARTS_PER_FRAME = 4;
    private final World world;
    private final Set<Long> columns = new LinkedHashSet<>();
    private final java.util.Map<Long, Falling> lowerInColumn = new java.util.HashMap<>();
    private final List<Falling> active = new ArrayList<>();
    private final List<DroppedItem> drops = new ArrayList<>();
    private final ConcurrentLinkedQueue<Chunk> scans = new ConcurrentLinkedQueue<>();
    private Chunk scanning;
    private int scanIndex;

    public static final class Falling {
        public final int x, z;
        public final BlockType block;
        public final byte meta;
        public float y, velocity;
        Falling(int x, int y, int z, BlockType block, byte meta) {
            this.x = x; this.y = y; this.z = z; this.block = block; this.meta = meta;
        }
    }

    FallingBlocks(World world) { this.world = world; }
    public List<Falling> active() { return java.util.Collections.unmodifiableList(active); }

    /** Called after snapshot restoration on a generation worker. Scanning is budgeted later. */
    public void scanRestored(Chunk c) { scans.offer(c); }

    public void changed(int x, int y, int z) {
        if (world.getBlock(x, y, z).hasGravity() || world.getBlock(x, y + 1, z).hasGravity())
            columns.add(World.key(x, z));
    }

    public static boolean fallThrough(BlockType b) {
        return b == BlockType.AIR || b == BlockType.WATER || b == BlockType.WATER_FLOW
                || b == BlockType.LAVA || b == BlockType.FIRE || b == BlockType.SNOW_LAYER
                || b == BlockType.TORCH || b == BlockType.WEB || b == BlockType.ROPE || b == BlockType.JOURNAL;
    }

    public void update(float dt) {
        if (!(dt > 0)) return;
        scanSome();
        // Process lower actors first so a whole stack can fall together, without overlap.
        active.sort(java.util.Comparator.comparingDouble(f -> f.y));
        lowerInColumn.clear();
        // Sweep every crossed voxel: even at terminal speed a block cannot tunnel through a floor.
        for (var it = active.iterator(); it.hasNext();) {
            Falling f = it.next();
            Chunk c = world.getChunkIfExists(Math.floorDiv(f.x, 16), Math.floorDiv(f.z, 16));
            if (c == null) continue; // normal eviction snapshots then forgets the actor
            c.modified = true;
            float step = Math.min(dt, 0.05f);
            f.velocity = Math.max(-32f, f.velocity - (f.block == BlockType.ASH ? 14f : 28f) * step);
            float next = f.y + f.velocity * step;
            long column = World.key(f.x, f.z);
            Falling below = lowerInColumn.get(column);
            if (below != null && next < below.y + 1) {
                next = below.y + 1;
                f.velocity = below.velocity;
            }
            int support = -1;
            for (int y = Math.min(127, (int) Math.floor(f.y)); y >= Math.max(0, (int) Math.floor(next)); y--) {
                if (!fallThrough(world.getBlock(f.x, y, f.z))) { support = y; break; }
            }
            if (support >= 0 || next < 0) {
                int landing = support + 1;
                if (landing < 128 && fallThrough(world.getBlock(f.x, landing, f.z)))
                    world.setBlock(f.x, landing, f.z, f.block, f.meta);
                else
                    drops.add(new DroppedItem(new ItemStack(f.block, 1), f.x + 0.5f,
                            Math.min(127, Math.max(0, f.y)), f.z + 0.5f, 0));
                columns.add(column);
                it.remove();
            } else {
                f.y = next;
                lowerInColumn.put(column, f);
            }
        }
        int starts = 0, examined = 0;
        // Removing a source queues the block above; detach stacks progressively within the budget.
        while (!columns.isEmpty() && starts < STARTS_PER_FRAME && examined++ < 32 && active.size() < MAX_ACTIVE) {
            var it = columns.iterator();
            long key = it.next(); it.remove();
            int x = (int) (key >> 32), z = (int) key;
            Chunk c = world.getChunkIfExists(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
            if (c == null) continue;
            for (int y = 1; y < 128; y++) {
                BlockType b = world.getBlock(x, y, z);
                if (!b.hasGravity() || !fallThrough(world.getBlock(x, y - 1, z))) continue;
                byte meta = world.getBlockMeta(x, y, z);
                active.add(new Falling(x, y, z, b, meta));
                world.setBlock(x, y, z, BlockType.AIR, (byte) 0);
                starts++;
                break;
            }
        }
    }

    private void scanSome() {
        for (int budget = 0; budget < 2048; budget++) {
            if (scanning == null) {
                scanning = scans.poll(); scanIndex = 0;
                if (scanning == null) return;
            }
            if (world.getChunkIfExists(scanning.cx, scanning.cz) != scanning) {
                scanning = null; continue;
            }
            int x = scanIndex % 16, z = (scanIndex / 16) % 16, y = scanIndex / 256;
            if (y > 0 && scanning.get(x, y, z).hasGravity() && fallThrough(scanning.get(x, y - 1, z)))
                columns.add(World.key(scanning.cx * 16 + x, scanning.cz * 16 + z));
            if (++scanIndex == 32768) scanning = null;
        }
    }

    /** Put moving blocks into the COPY, never the live chunk. Reload resumes their fall. */
    public void snapshot(Chunk c, byte[] blocks, byte[] meta, List<DroppedItem> items) {
        for (Falling f : active) {
            if (Math.floorDiv(f.x, 16) != c.cx || Math.floorDiv(f.z, 16) != c.cz) continue;
            int x = Math.floorMod(f.x, 16), z = Math.floorMod(f.z, 16);
            int y = Math.max(1, (int) Math.ceil(f.y));
            while (y < 128 && !fallThrough(BlockType.byId(blocks[Chunk.idx(x, y, z)]))) y++;
            if (y < 128) {
                int i = Chunk.idx(x, y, z);
                blocks[i] = (byte) f.block.ordinal(); meta[i] = f.meta;
            } else items.add(new DroppedItem(new ItemStack(f.block, 1), f.x + 0.5f, 127f, f.z + 0.5f, 0));
        }
    }

    public List<DroppedItem> drainDrops() {
        if (drops.isEmpty()) return List.of();
        var out = new ArrayList<>(drops); drops.clear(); return out;
    }

    public void forget(int cx, int cz) {
        active.removeIf(f -> {
            if (Math.floorDiv(f.x, 16) != cx || Math.floorDiv(f.z, 16) != cz) return false;
            return true;
        });
        columns.removeIf(k -> Math.floorDiv((int) (k >> 32), 16) == cx && Math.floorDiv((int) (long) k, 16) == cz);
    }

    /** Generation worker settles loose terrain once, without actors or main-thread relighting. */
    public static void settleGenerated(Chunk c) {
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 1; y < 128; y++) {
            BlockType b = c.get(x, y, z);
            if (!b.hasGravity()) continue;
            int to = y;
            while (to > 1 && fallThrough(c.get(x, to - 1, z))) to--;
            if (to == y) continue;
            byte meta = c.getMeta(x, y, z);
            c.set(x, y, z, BlockType.AIR); c.setMeta(x, y, z, (byte) 0);
            c.set(x, to, z, b); c.setMeta(x, to, z, meta);
        }
    }
}

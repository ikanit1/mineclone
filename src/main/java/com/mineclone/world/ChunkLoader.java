package com.mineclone.world;

import com.mineclone.render.MeshData;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Asynchronous chunk pipeline:
 *   missing  ->  GEN (bg)  ->  generated chunk in world
 *   generated && neighbours generated  ->  MESH (bg)  ->  MeshData in ready queue
 *   main thread polls ready queue -> uploads to GPU
 *
 * Player edits stay synchronous (Game's dirty rebuild path).
 */
public class ChunkLoader {
    public static final class Ready {
        public final long key;
        public final MeshData[] data;  // [0]=opaque, [1]=water
        Ready(long key, MeshData[] data) { this.key = key; this.data = data; }
    }

    private final World world;
    private final ChunkMesher mesher;
    private final ExecutorService genPool;
    private final ExecutorService meshPool;
    private final com.mineclone.save.SaveManager save;
    private final String worldId;

    private final Set<Long> pendingGen  = ConcurrentHashMap.newKeySet();
    private final Set<Long> pendingMesh = ConcurrentHashMap.newKeySet();
    /** Chunks for which a mesh upload has been produced (queued or applied). */
    private final Set<Long> meshed      = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<Ready> ready = new ConcurrentLinkedQueue<>();
    /**
     * Chunks restored from disk that need their block-light re-flooded from
     * any emitters they contain. blockLight is written only on the main
     * thread (per Chunk's concurrency contract), so applySnapshot — which
     * may run on the gen pool — defers the flood here. Game drains this on
     * the main thread per frame via {@link #drainLightFlood(int)}.
     */
    private final ConcurrentLinkedQueue<Long> pendingLightFlood = new ConcurrentLinkedQueue<>();

    public ChunkLoader(World world, ChunkMesher mesher,
                       com.mineclone.save.SaveManager save, String worldId) {
        this.world = world;
        this.mesher = mesher;
        this.save = save;
        this.worldId = worldId;
        this.genPool  = Executors.newFixedThreadPool(2, daemon("mineclone-gen"));
        this.meshPool = Executors.newFixedThreadPool(2, daemon("mineclone-mesh"));
    }

    /** Schedule generation/meshing for chunks within radius of (pcx,pcz). */
    public void ensureRadius(int pcx, int pcz, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int cx = pcx + dx, cz = pcz + dz;
                long k = World.key(cx, cz);
                if (world.getChunkIfExists(cx, cz) == null) {
                    submitGen(cx, cz, k);
                } else if (!pendingGen.contains(k) && !meshed.contains(k) && !pendingMesh.contains(k)) {
                    if (neighboursReady(cx, cz)) submitMesh(cx, cz, k);
                }
            }
        }
    }

    private boolean neighboursReady(int cx, int cz) {
        return world.getChunkIfExists(cx + 1, cz) != null
            && world.getChunkIfExists(cx - 1, cz) != null
            && world.getChunkIfExists(cx, cz + 1) != null
            && world.getChunkIfExists(cx, cz - 1) != null;
    }

    /**
     * Apply any saved snapshot over an already-generated chunk: restore
     * blocks+meta, recompute chunk-local sky light, flag for remesh, and
     * clear {@code modified} (a freshly-restored chunk matches disk). No-op
     * if the chunk has no saved snapshot. Safe on the gen pool or the main
     * thread; the only state it touches is the passed chunk and disk reads.
     */
    public void applySnapshot(Chunk c) {
        com.mineclone.save.ChunkSnapshot snap = save.loadChunk(worldId, c.cx, c.cz);
        if (snap != null) {
            c.restore(snap.blocks, snap.meta);
            c.computeSkyLight();
            c.dirty = true;
            c.modified = false;
            pendingLightFlood.offer(World.key(c.cx, c.cz));
        }
    }

    /**
     * Drain restored chunks needing block-light emitter flood. MUST be called
     * from the main thread (Chunk.blockLight is single-writer per the engine's
     * concurrency contract). For each queued chunk, scans every cell and
     * floods light from any block with {@code emittedLight > 0} via
     * {@link World#floodFillAdd}. Bounded by {@code maxPerFrame} chunks per
     * call to keep the per-frame cost predictable when many saved chunks
     * restore at once. floodFillAdd sets dirty on touched chunks, so the
     * existing dirty-remesh path picks up the updated light next frame.
     */
    public void drainLightFlood(int maxPerFrame) {
        for (int i = 0; i < maxPerFrame; i++) {
            Long key = pendingLightFlood.poll();
            if (key == null) break;
            int cx = (int) (key >> 32);
            int cz = key.intValue();
            Chunk c = world.getChunkIfExists(cx, cz);
            if (c == null) continue;
            int bx = cx * Chunk.SIZE_X;
            int bz = cz * Chunk.SIZE_Z;
            for (int lx = 0; lx < Chunk.SIZE_X; lx++) {
                for (int y = 0; y < Chunk.SIZE_Y; y++) {
                    for (int lz = 0; lz < Chunk.SIZE_Z; lz++) {
                        if (c.get(lx, y, lz).emittedLight > 0)
                            world.floodFillAdd(bx + lx, y, bz + lz);
                    }
                }
            }
        }
    }

    private void submitGen(int cx, int cz, long key) {
        if (!pendingGen.add(key)) return;
        genPool.submit(() -> {
            try {
                applySnapshot(world.getChunk(cx, cz));
            } finally {
                pendingGen.remove(key);
            }
        });
    }

    private void submitMesh(int cx, int cz, long key) {
        if (!pendingMesh.add(key)) return;
        meshPool.submit(() -> {
            try {
                Chunk c = world.getChunkIfExists(cx, cz);
                if (c == null) return;
                MeshData[] data = mesher.buildData(c);
                ready.offer(new Ready(key, data));
                meshed.add(key);
            } finally {
                pendingMesh.remove(key);
            }
        });
    }

    /** Drains up to maxPerFrame finished mesh builds; main thread uploads them. */
    public List<Ready> drainReady(int maxPerFrame) {
        List<Ready> out = new ArrayList<>(maxPerFrame);
        for (int i = 0; i < maxPerFrame; i++) {
            Ready r = ready.poll();
            if (r == null) break;
            out.add(r);
        }
        return out;
    }

    /** Mark a chunk as remeshed via the sync (main-thread) path so we don't re-mesh it in the background. */
    public void markMeshed(long key) {
        meshed.add(key);
    }

    public void forget(long key) {
        meshed.remove(key);
    }

    public void shutdown() {
        genPool.shutdownNow();
        meshPool.shutdownNow();
    }

    private static ThreadFactory daemon(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}

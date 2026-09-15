package com.mineclone.world;

import com.mineclone.render.MeshData;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Asynchronous chunk pipeline:
 *   missing  ->  GEN (bg)  ->  generated chunk in world
 *   generated && neighbours generated  ->  MESH (bg)  ->  MeshData in ready queue
 *   main thread polls ready queue -> uploads to GPU
 *
 * <p><b>Правки игрока идут этим же путём.</b> Раньше перестройка меша после
 * удара по блоку шла синхронно в главном потоке: обход 32 768 ячеек чанка с
 * расчётом AO на грань, до восьми чанков за кадр. Это и был главный источник
 * фризов. Теперь правка — такая же задача меш-пула, только с приоритетом:
 * очередь пула упорядочена по расстоянию до игрока, а правка идёт вперёд всех.
 * Задержка в кадр-другой между ударом и исчезновением блока не читается —
 * частицы, звук и снятие оверлея разрушения происходят немедленно.
 */
public class ChunkLoader {
    public static final class Ready {
        public final long key;
        public final MeshData[] data;  // [0]=opaque, [1]=water
        /**
         * Поколение содержимого чанка, из которого построен этот меш. Главный
         * поток по нему решает, не устарел ли меш и не пришёл ли он позже
         * более свежего: меш-потоков несколько, и порядок возврата ничем не
         * задан.
         */
        public final int version;

        Ready(long key, MeshData[] data, int version) {
            this.key = key;
            this.data = data;
            this.version = version;
        }
    }

    /**
     * Задача меширования с приоритетом. {@link ThreadPoolExecutor} с
     * {@link PriorityBlockingQueue} сравнивает сами Runnable, поэтому задача
     * обязана быть Comparable — обёртка {@code submit()} в FutureTask таким
     * не является, отсюда {@code execute()} и собственный тип.
     */
    private static final class MeshTask implements Runnable, Comparable<MeshTask> {
        /** Меньше — раньше. Правка игрока получает отрицательный. */
        final int priority;
        final long seq;
        final Runnable body;

        MeshTask(int priority, long seq, Runnable body) {
            this.priority = priority;
            this.seq = seq;
            this.body = body;
        }

        @Override public void run() { body.run(); }

        @Override public int compareTo(MeshTask o) {
            int c = Integer.compare(priority, o.priority);
            // При равном приоритете — в порядке поступления: иначе очередь
            // тасует равные задачи и ближний ряд чанков достраивается рвано.
            return c != 0 ? c : Long.compare(seq, o.seq);
        }
    }

    /** Приоритет правки игрока: раньше любого расстояния. */
    private static final int EDIT_PRIORITY = -1;

    private final World world;
    private final ChunkMesher mesher;
    private final ExecutorService genPool;
    private final ThreadPoolExecutor meshPool;
    private final com.mineclone.save.SaveManager save;
    private final String worldId;
    private final AtomicInteger meshSeq = new AtomicInteger();

    /** Чанк игрока — центр, от которого считается приоритет очереди. */
    private volatile int centerX = 0, centerZ = 0;

    private final Set<Long> pendingGen  = ConcurrentHashMap.newKeySet();
    private final Set<Long> pendingMesh = ConcurrentHashMap.newKeySet();
    /** Chunks for which a mesh upload has been produced (queued or applied). */
    private final Set<Long> meshed      = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<Ready> ready = new ConcurrentLinkedQueue<>();
    /**
     * Chunks that need their block-light re-flooded from any emitters they
     * contain. blockLight is written only on the main thread (per Chunk's
     * concurrency contract), so applySnapshot — which may run on the gen
     * pool — defers the flood here. Game drains this on the main thread per
     * frame via {@link #drainLightFlood(int)}.
     * <p>
     * Set semantics (not Queue): when chunk C loads we also enqueue its 8
     * loaded neighbours so their emitters re-flood and reach C. Without
     * dedup a chunk could appear up to 9× and we'd rescan its emitters
     * needlessly.
     */
    private final Set<Long> pendingLightFlood = ConcurrentHashMap.newKeySet();

    public ChunkLoader(World world, ChunkMesher mesher,
                       com.mineclone.save.SaveManager save, String worldId) {
        this.world = world;
        this.mesher = mesher;
        this.save = save;
        this.worldId = worldId;
        // Пулы по числу ядер, а не жёсткие 2+2: на восьмиядерной машине
        // прежние константы оставляли шесть ядер простаивать, пока игрок
        // ждал загрузки чанков. Одно ядро всегда оставляем главному потоку.
        int cores = Runtime.getRuntime().availableProcessors();
        int meshThreads = Math.max(2, Math.min(6, cores - 1));
        int genThreads  = Math.max(2, Math.min(4, cores / 2));
        this.genPool  = Executors.newFixedThreadPool(genThreads, daemon("mineclone-gen"));
        this.meshPool = new ThreadPoolExecutor(
                meshThreads, meshThreads, 0L, TimeUnit.MILLISECONDS,
                new PriorityBlockingQueue<>(64), daemon("mineclone-mesh"));
    }

    /**
     * Куда сместился игрок. Очередь меширования упорядочена по расстоянию до
     * этой точки: без неё дальний край радиуса обслуживался наравне с
     * ближним, и игрок смотрел в дыру, пока достраивался горизонт.
     */
    public void setPriorityCenter(int cx, int cz) {
        centerX = cx;
        centerZ = cz;
    }

    private int distancePriority(int cx, int cz) {
        int dx = cx - centerX, dz = cz - centerZ;
        return dx * dx + dz * dz;
    }

    /** Schedule generation/meshing for chunks within radius of (pcx,pcz). */
    public void ensureRadius(int pcx, int pcz, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int cx = pcx + dx, cz = pcz + dz;
                long k = World.key(cx, cz);
                if (world.getChunkIfExists(cx, cz) == null) {
                    submitGen(cx, cz, k);
                } else if (!pendingGen.contains(k) && !pendingLightFlood.contains(k)
                        && !meshed.contains(k) && !pendingMesh.contains(k)) {
                    if (neighboursReady(cx, cz)) submitMesh(cx, cz, k, false);
                }
            }
        }
    }

    public boolean neighboursReady(int cx, int cz) {
        return isFullyReady(cx + 1, cz) && isFullyReady(cx - 1, cz)
            && isFullyReady(cx, cz + 1) && isFullyReady(cx, cz - 1);
    }

    private boolean isFullyReady(int cx, int cz) {
        long k = World.key(cx, cz);
        return world.getChunkIfExists(cx, cz) != null
                && !pendingGen.contains(k)
                && !pendingLightFlood.contains(k);
    }

    public boolean isPendingGen(long key) {
        return pendingGen.contains(key);
    }

    public boolean hasPendingLightFlood(long key) {
        return pendingLightFlood.contains(key);
    }

    /** Сколько чанков ещё ждёт генерации — для прогресса загрузки. */
    public int pendingGenCount() {
        return pendingGen.size();
    }

    /** Сколько чанков ещё ждёт заливки света — для прогресса загрузки. */
    public int pendingLightCount() {
        return pendingLightFlood.size();
    }

    /** Сколько мешей строится или ждёт очереди — для прогресса загрузки. */
    public int pendingMeshCount() {
        return pendingMesh.size();
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
            // restore() сам пересобирает список излучателей и двигает версию.
            c.restore(snap.blocks, snap.meta);
            c.restoreChests(snap.chests);
            c.restoreFurnaces(snap.furnaces);
            c.setPendingItems(snap.items);
            c.computeSkyLight();
            c.modified = false;
        }
        // Always queue this chunk: flood its own emitters and inherit border light
        // from already-lit neighbours via injectNeighbourLight in drainLightFlood.
        pendingLightFlood.add(World.key(c.cx, c.cz));
        // Neighbours that were already meshed used stale sky light sampled from
        // this chunk (either MAX_LIGHT for unloaded, or pre-restore values).
        // Mark them dirty so they resample our finalised sky light next frame.
        invalidateNeighbours(c.cx, c.cz);
    }

    private void invalidateNeighbours(int cx, int cz) {
        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
        for (int[] d : dirs) {
            long k = World.key(cx + d[0], cz + d[1]);
            Chunk n = world.getChunkIfExists(cx + d[0], cz + d[1]);
            if (n != null && meshed.contains(k))
                n.markDirty();
        }
    }

    /**
     * Drain restored chunks needing block-light emitter flood. MUST be called
     * from the main thread (Chunk.blockLight is single-writer per the engine's
     * concurrency contract).
     * <p>
     * Излучатели берутся из списка чанка, а не перебором всех его ячеек.
     * Перебор стоил 32 768 чтений на чанк и до восьми чанков за кадр — четверть
     * миллиона обращений в главном потоке ради поиска нескольких факелов.
     * <p>
     * floodFillAdd sets dirty on touched chunks, so the existing dirty-remesh
     * path picks up the updated light next frame.
     */
    public void drainLightFlood(int maxPerFrame) {
        Iterator<Long> it = pendingLightFlood.iterator();
        int n = 0;
        while (it.hasNext() && n < maxPerFrame) {
            Long key = it.next();
            it.remove();
            int cx = (int) (key >> 32);
            int cz = key.intValue();
            Chunk c = world.getChunkIfExists(cx, cz);
            if (c == null) continue;
            int bx = cx * Chunk.SIZE_X;
            int bz = cz * Chunk.SIZE_Z;
            for (int i = 0; i < c.emitterCount(); i++) {
                int packed = c.emitterAt(i);
                // Обратная распаковка Chunk.idx: (y * SIZE_Z + z) * SIZE_X + x.
                int lx = packed % Chunk.SIZE_X;
                int rest = packed / Chunk.SIZE_X;
                int lz = rest % Chunk.SIZE_Z;
                int y = rest / Chunk.SIZE_Z;
                world.floodFillAdd(bx + lx, y, bz + lz);
            }
            // Inherit light from already-lit neighbours — re-flooding the
            // emitter in a neighbour doesn't work (BFS stops at cells that
            // already hold the correct value), so we read border values directly.
            world.injectNeighbourLight(cx, cz);
            n++;
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

    /**
     * Ставит чанк в очередь на перестройку меша.
     *
     * @param edit правка игрока — идёт вперёд очереди из дальних чанков
     * @return false, если задача уже в работе
     */
    public boolean submitMesh(int cx, int cz, long key, boolean edit) {
        if (!pendingMesh.add(key)) return false;
        int priority = edit ? EDIT_PRIORITY : distancePriority(cx, cz);
        meshPool.execute(new MeshTask(priority, meshSeq.incrementAndGet(), () -> {
            try {
                Chunk c = world.getChunkIfExists(cx, cz);
                if (c == null) return;
                // Версия снимается ДО постройки: меш описывает то состояние,
                // которое было на входе. Изменится оно во время сборки —
                // главный поток увидит расхождение и закажет ещё одну.
                int version = c.contentVersion();
                MeshData[] data = mesher.buildData(c);
                ready.offer(new Ready(key, data, version));
                meshed.add(key);
            } finally {
                pendingMesh.remove(key);
            }
        }));
        return true;
    }

    /** Drains up to maxPerFrame finished mesh builds; main thread uploads them. */
    public List<Ready> drainReady(int maxPerFrame) {
        List<Ready> out = new ArrayList<>(Math.min(maxPerFrame, 32));
        for (int i = 0; i < maxPerFrame; i++) {
            Ready r = ready.poll();
            if (r == null) break;
            out.add(r);
        }
        return out;
    }

    /** Есть ли что загружать на GPU — для бюджета кадра на экране загрузки. */
    public boolean hasReady() {
        return !ready.isEmpty();
    }

    /** Mark a chunk as remeshed via the sync (main-thread) path so we don't re-mesh it in the background. */
    public void markMeshed(long key) {
        meshed.add(key);
    }

    public void forget(long key) {
        meshed.remove(key);
        pendingLightFlood.remove(key);
    }

    public void shutdown() {
        genPool.shutdownNow();
        meshPool.shutdownNow();
        try {
            genPool.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
            meshPool.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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

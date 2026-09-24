package com.mineclone.world;

import java.util.ArrayDeque;

public class Chunk {
    public static final int SIZE_X = 16;
    public static final int SIZE_Y = 128;
    public static final int SIZE_Z = 16;
    public static final int MAX_LIGHT = 15;

    public final int cx, cz;
    private volatile int meshLod;
    public int meshLod() { return meshLod; }
    public synchronized void setMeshLod(int lod) {
        if (meshLod == lod) return;
        meshLod = lod;
        markDirty();
    }
    private final PaletteStorage blocks = new PaletteStorage(SIZE_X * SIZE_Y * SIZE_Z);
    private int[] waterCells = new int[64];
    private int waterCount;
    private int[] lavaCells = new int[32];
    private int lavaCount;
    private final long[] solidMask = new long[SIZE_X * SIZE_Y * SIZE_Z / 64];
    /**
     * Пропускает ли ячейка небесный свет. Та же идея, что у {@code solidMask},
     * но предикат другой ({@code AIR || transparent}), поэтому и маска своя.
     * Без неё заливка света заново строила {@code boolean[32768]} и дёргала
     * палитру на каждую ячейку.
     */
    private final long[] clearMask = new long[SIZE_X * SIZE_Y * SIZE_Z / 64];
    /**
     * Небесный свет до размытия — то, что считает BFS. Игра и меш читают
     * размытую копию {@link #skyLight}, а продолжать заливку по ней нельзя:
     * размытие необратимо. Две копии — цена инкрементального света.
     */
    private final byte[] skyRaw = new byte[SIZE_X * SIZE_Y * SIZE_Z];
    private final byte[] skyLight = new byte[SIZE_X * SIZE_Y * SIZE_Z];
    // blockLight is written on the main thread (flood fill) and read on mesh
    // threads.
    // No explicit synchronization: worst-case is one mesh frame with stale values,
    // which self-corrects when the dirty flag triggers a rebuild — same trade-off
    // as skyLight.
    private final byte[] blockLight = new byte[SIZE_X * SIZE_Y * SIZE_Z];
    private final byte[] meta = new byte[SIZE_X * SIZE_Y * SIZE_Z];

    /**
     * Меш чанка устарел и должен быть перестроен. Флаг и версия содержимого
     * ходят парой, поэтому оба меняются только через {@link #markDirty()}:
     * версия — это то, чем главный поток отличает свежий меш от протухшего,
     * и прямое присваивание флага мимо неё сломало бы защиту.
     */
    private volatile boolean dirty = true;

    /**
     * Номер поколения содержимого: растёт при каждой правке блоков, меты или
     * света. Меш строится в фоновом потоке, и без этого номера порядок
     * возврата ничем не задан — сломав два блока подряд, можно получить меш
     * от первой правки поверх меша от второй, то есть вернувшийся на место
     * блок. Ошибка редкая и невоспроизводимая, поэтому защита стоит сразу.
     */
    private final java.util.concurrent.atomic.AtomicInteger contentVersion =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Версия, с которой построен последний загруженный на GPU меш. Только
     * главный поток. Загрузка принимается лишь строго новее этой — иначе два
     * меш-потока, закончив вразнобой, кладут старое поверх нового.
     */
    private int uploadedVersion = -1;

    /**
     * Позиции блоков-излучателей, упакованные через {@link #idx}. Заливка
     * света раньше искала их перебором всех 32 768 ячеек чанка — в главном
     * потоке и каждый кадр. Факелов в чанке единицы, поэтому список заменяет
     * перебор куба обходом десятка чисел.
     *
     * Ведётся в {@link #set}, целиком пересобирается в {@link #rebuildEmitters}
     * после генерации и после восстановления из сейва — там блоки пишутся
     * массивом мимо {@code set}.
     */
    private int[] emitters = new int[8];
    private int emitterCount = 0;
    /**
     * True once a block changed AFTER initial generation (player/command/water
     * edits go through World.setBlock, which sets this). Distinct from
     * {@link #dirty}, which is the mesh-rebuild flag. Drives whole-chunk save.
     */
    public volatile boolean modified = false;

    /** A newer or unpreserved save file must never be overwritten by this session. */
    private volatile boolean readOnly;
    private volatile Thread publishedOwner;
    private final boolean checkThreadOwnership = threadChecksEnabled();

    public boolean isReadOnly() { return readOnly; }
    public void markReadOnly() { readOnly = true; }

    static boolean threadChecksEnabled() {
        return Chunk.class.desiredAssertionStatus() || Boolean.getBoolean("mineclone.checkThreadOwnership");
    }

    void publishTo(Thread owner) {
        if (publishedOwner != null && publishedOwner != owner)
            throw new IllegalStateException("Chunk already belongs to another world thread");
        publishedOwner = owner;
    }

    /** Detached chunks belong to their loading worker; published containers to the world thread. */
    public void assertMainThread() {
        Thread owner = publishedOwner;
        if (checkThreadOwnership && owner != null && owner != Thread.currentThread())
            throw new IllegalStateException("Published chunk " + cx + "," + cz + " accessed outside its main thread");
    }

    public boolean isPublished() { return publishedOwner != null; }

    public Chunk(int cx, int cz) {
        this.cx = cx;
        this.cz = cz;
        // Пустой чанк — сплошной воздух, а воздух прозрачен. Нулевая маска
        // означала бы «всё глухое», и первый же расчёт света дал бы чёрный чанк:
        // set() на неизменившемся типе выходит рано и маску не трогает.
        java.util.Arrays.fill(clearMask, ~0L);
    }

    public static int idx(int x, int y, int z) {
        return (y * SIZE_Z + z) * SIZE_X + x;
    }

    public boolean inBounds(int x, int y, int z) {
        return x >= 0 && x < SIZE_X && y >= 0 && y < SIZE_Y && z >= 0 && z < SIZE_Z;
    }

    public byte getRaw(int x, int y, int z) {
        if (!inBounds(x, y, z))
            return 0;
        return blocks.get(idx(x, y, z));
    }

    public boolean isSolid(int x, int y, int z) {
        if (!inBounds(x,y,z)) return false;
        int i = idx(x,y,z);
        return (solidMask[i >>> 6] & (1L << (i & 63))) != 0;
    }

    public BlockType get(int x, int y, int z) {
        return BlockType.byId(getRaw(x, y, z));
    }

    public void set(int x, int y, int z, BlockType t) {
        assertMainThread();
        if (!inBounds(x, y, z))
            return;
        int i = idx(x, y, z);
        BlockType old = BlockType.byId(blocks.get(i));
        if (old == t) return;
        blocks.set(i, (byte) t.ordinal());
        boolean oldWater = old == BlockType.WATER || old == BlockType.WATER_FLOW;
        boolean newWater = t == BlockType.WATER || t == BlockType.WATER_FLOW;
        if (!oldWater && newWater) {
            if (waterCount == waterCells.length) waterCells = java.util.Arrays.copyOf(waterCells, waterCount * 2);
            waterCells[waterCount++] = i;
        } else if (oldWater && !newWater) {
            for (int n = 0; n < waterCount; n++) if (waterCells[n] == i) { waterCells[n] = waterCells[--waterCount]; break; }
        }
        if (old != BlockType.LAVA && t == BlockType.LAVA) {
            if (lavaCount == lavaCells.length) lavaCells = java.util.Arrays.copyOf(lavaCells, lavaCount * 2);
            lavaCells[lavaCount++] = i;
        } else if (old == BlockType.LAVA && t != BlockType.LAVA) {
            for (int n = 0; n < lavaCount; n++) if (lavaCells[n] == i) { lavaCells[n] = lavaCells[--lavaCount]; break; }
        }
        if (t.solid) solidMask[i >>> 6] |= 1L << (i & 63);
        else solidMask[i >>> 6] &= ~(1L << (i & 63));
        if (transparent(t)) clearMask[i >>> 6] |= 1L << (i & 63);
        else clearMask[i >>> 6] &= ~(1L << (i & 63));
        if (old.emittedLight > 0 && t.emittedLight <= 0)
            removeEmitter(i);
        else if (old.emittedLight <= 0 && t.emittedLight > 0)
            addEmitter(i);
        markDirty();
    }

    /** Помечает меш устаревшим и двигает поколение содержимого. */
    public synchronized void markDirty() {
        contentVersion.incrementAndGet();
        dirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    /** Поколение содержимого на этот момент — снимается перед постройкой меша. */
    public int contentVersion() {
        return contentVersion.get();
    }

    /**
     * Снимает флаг перестройки, если с версии {@code v} содержимое не
     * менялось. Иначе флаг остаётся: меш, построенный из {@code v}, уже
     * отстал, и за ним обязан прийти следующий.
     *
     * @return true, если флаг снят
     */
    public synchronized boolean clearDirtyIfCurrent(int v) {
        if (contentVersion.get() != v)
            return false;
        dirty = false;
        return true;
    }

    /**
     * Разрешает загрузить на GPU меш, построенный из версии {@code v}.
     * Отказ означает, что меш новее уже загружен — такое бывает, когда два
     * меш-потока заканчивают вразнобой. Только главный поток.
     */
    public boolean acceptMeshVersion(int v) {
        if (v <= uploadedVersion)
            return false;
        uploadedVersion = v;
        return true;
    }

    /** Меш выгружен из памяти — следующая версия снова любая. */
    public void forgetUploadedMesh() {
        uploadedVersion = -1;
    }

    // ---- излучатели --------------------------------------------------------

    /** Сколько блоков-излучателей в чанке. */
    public int emitterCount() {
        return emitterCount;
    }

    /** Упакованный {@link #idx} i-го излучателя. */
    public int emitterAt(int i) {
        return emitters[i];
    }

    // Распаковка idx живёт здесь, рядом с упаковкой: разойдись они — и
    // свет пойдёт из случайных точек, а найти такое глазами почти невозможно.

    /** Локальный x i-го излучателя. */
    public int emitterX(int i) {
        return emitters[i] % SIZE_X;
    }

    /** Локальный y i-го излучателя. */
    public int emitterY(int i) {
        return emitters[i] / SIZE_X / SIZE_Z;
    }

    /** Локальный z i-го излучателя. */
    public int emitterZ(int i) {
        return (emitters[i] / SIZE_X) % SIZE_Z;
    }

    private void addEmitter(int i) {
        if (emitterCount == emitters.length)
            emitters = java.util.Arrays.copyOf(emitters, emitters.length * 2);
        emitters[emitterCount++] = i;
    }

    private void removeEmitter(int i) {
        for (int k = 0; k < emitterCount; k++) {
            if (emitters[k] == i) {
                emitters[k] = emitters[--emitterCount];
                return;
            }
        }
    }

    /**
     * Пересобирает список излучателей полным обходом. Зовётся там, где блоки
     * пишутся мимо {@link #set}: в конце генерации и после восстановления из
     * сейва. Оба места и так проходят чанк целиком и живут вне главного
     * потока, так что скан здесь ничего не стоит — в отличие от того же
     * скана в кадре.
     */
    public void rebuildEmitters() {
        emitterCount = 0;
        for (int i = 0; i < blocks.length; i++)
            if (BlockType.byId(blocks.get(i)).emittedLight > 0)
                addEmitter(i);
    }

    public int getSkyLight(int x, int y, int z) {
        if (!inBounds(x, y, z))
            return MAX_LIGHT;
        return skyLight[idx(x, y, z)] & 0xFF;
    }

    public int getBlockLight(int x, int y, int z) {
        if (!inBounds(x, y, z))
            return 0;
        return blockLight[idx(x, y, z)] & 0xFF;
    }

    /**
     * Есть ли в чанке хоть одна светящаяся ячейка.
     *
     * <p>Флаг взводится и не снимается: он нужен только чтобы дёшево ответить
     * «здесь света не было никогда». Именно этот ответ и важен — в свежем
     * мире без факелов подсев света от соседей перебирал 8 192 граничные
     * ячейки на каждый загруженный чанк, чтобы найти там нули.
     */
    private volatile boolean anyBlockLight;

    public boolean hasBlockLight() {
        return anyBlockLight;
    }

    public void setBlockLight(int x, int y, int z, int val) {
        if (!inBounds(x, y, z))
            return;
        int v = Math.max(0, Math.min(15, val));
        blockLight[idx(x, y, z)] = (byte) v;
        if (v > 0)
            anyBlockLight = true;
    }

    /**
     * Гасит блочный свет в локальной коробке и помечает чанк грязным один раз.
     *
     * <p>Раньше это делал мир по одной ячейке: куб 31×31×31 — почти тридцать
     * тысяч вызовов, в каждом {@code floorDiv}, поиск чанка в
     * {@code ConcurrentHashMap} с упаковкой ключа в {@code Long} и
     * {@code markDirty()} под монитором. В освещённой пещере один удар киркой
     * стоил тысяч таких проходов.
     *
     * @return true, если хоть одна ячейка погасла
     */
    public boolean clearBlockLightBox(int x0, int x1, int y0, int y1, int z0, int z1) {
        x0 = Math.max(0, x0); x1 = Math.min(SIZE_X - 1, x1);
        y0 = Math.max(0, y0); y1 = Math.min(SIZE_Y - 1, y1);
        z0 = Math.max(0, z0); z1 = Math.min(SIZE_Z - 1, z1);
        boolean any = false;
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                int row = (y * SIZE_Z + z) * SIZE_X;
                for (int x = x0; x <= x1; x++) {
                    if (blockLight[row + x] != 0) {
                        blockLight[row + x] = 0;
                        any = true;
                    }
                }
            }
        }
        if (any)
            markDirty();
        return any;
    }

    public byte getMeta(int x, int y, int z) {
        if (!inBounds(x, y, z))
            return 0;
        return meta[idx(x, y, z)];
    }

    public void setMeta(int x, int y, int z, byte val) {
        assertMainThread();
        if (!inBounds(x, y, z))
            return;
        int i = idx(x, y, z);
        if (meta[i] == val) return;
        meta[i] = val;
        markDirty();
    }

    /**
     * Полная заливка небесного света чанка: вертикальный посев, BFS вширь и
     * одно размытие. Зовётся при генерации и после восстановления из сейва —
     * то есть один раз на чанк, в фоновом потоке.
     *
     * <p>На каждый удар по блоку она больше НЕ зовётся: там работает
     * {@link #updateSkyLightAt}, которая трогает только окрестность правки.
     * Полный проход стоил 5–15 мс в главном потоке, и это был самый заметный
     * рывок в игре — по одному на каждый сломанный блок.
     *
     * <p>Результат BFS лежит в {@link #skyRaw}, а размытая копия — в
     * {@link #skyLight}, которую и читают меш и игра. Две копии нужны ровно
     * ради инкрементальности: размытие необратимо, и по сглаженным числам
     * продолжить заливку нельзя.
     */
    public void computeSkyLight() {
        final int planeXZ = SIZE_X * SIZE_Z;
        java.util.Arrays.fill(skyRaw, (byte) 0);

        int[] queue = new int[8192];
        int head = 0, tail = 0;

        // Direct sky does not decay in air, but translucent materials consume
        // their own amount per block (water, leaves, glass, thin ice).
        for (int x = 0; x < SIZE_X; x++) {
            for (int z = 0; z < SIZE_Z; z++) {
                int level = MAX_LIGHT;
                for (int y = SIZE_Y - 1; y >= 0; y--) {
                    int i = idx(x, y, z);
                    if (!clearAt(i))
                        break;
                    level -= skyAttenuation(BlockType.byId(blocks.get(i)));
                    if (level <= 0)
                        break;
                    skyRaw[i] = (byte) level;
                    if (tail == queue.length)
                        queue = java.util.Arrays.copyOf(queue, queue.length * 2);
                    queue[tail++] = i;
                }
            }
        }

        // BFS horizontal propagation. Each step costs 1 light level.
        while (head < tail) {
            // Запас на шесть соседей берётся ДО шага: проверять после значило
            // бы выйти за границу массива ровно тогда, когда он заполнен.
            if (tail + 6 > queue.length)
                queue = java.util.Arrays.copyOf(queue, queue.length * 2);

            int i = queue[head++];
            int source = skyRaw[i] & 0xFF;
            if (source <= 1)
                continue;
            // Распаковка idx: (y * SIZE_Z + z) * SIZE_X + x.
            int x = i % SIZE_X;
            int rest = i / SIZE_X;
            int z = rest % SIZE_Z;
            int y = rest / SIZE_Z;
            if (x + 1 < SIZE_X && lift(i + 1, source))        queue[tail++] = i + 1;
            if (x - 1 >= 0     && lift(i - 1, source))        queue[tail++] = i - 1;
            if (z + 1 < SIZE_Z && lift(i + SIZE_X, source))   queue[tail++] = i + SIZE_X;
            if (z - 1 >= 0     && lift(i - SIZE_X, source))   queue[tail++] = i - SIZE_X;
            if (y + 1 < SIZE_Y && lift(i + planeXZ, source))  queue[tail++] = i + planeXZ;
            if (y - 1 >= 0     && lift(i - planeXZ, source))  queue[tail++] = i - planeXZ;
        }

        blurSkyLight(0, SIZE_X - 1, 0, SIZE_Y - 1, 0, SIZE_Z - 1);
    }

    /**
     * Прозрачна ли ячейка для небесного света. Маска ведётся в {@link #set} и
     * {@code restore} рядом с {@code solidMask}: раньше заливка строила
     * {@code boolean[32768]} заново на каждый вызов и тянула тип блока из
     * палитры на каждую ячейку — это и была половина её стоимости.
     */
    private boolean clearAt(int i) {
        return (clearMask[i >>> 6] & (1L << (i & 63))) != 0;
    }

    /**
     * Поднимает свет в ячейке до {@code next}, если та прозрачна и там сейчас
     * темнее. Возвращает true, если ячейку надо поставить в очередь.
     */
    private boolean lift(int i, int source) {
        int next = source - Math.max(1, skyAttenuation(BlockType.byId(blocks.get(i))));
        if (!clearAt(i) || (skyRaw[i] & 0xFF) >= next)
            return false;
        skyRaw[i] = (byte) next;
        return true;
    }

    // ---- инкрементальный небесный свет -------------------------------------

    /**
     * Очередь локальной заливки. Только главный поток: правки блоков идут
     * через {@code World.setBlock}, а он главный поток и есть.
     * Переиспользуется между вызовами — копать игрок может несколько раз в
     * секунду.
     */
    private int[] skyQueue = new int[1024];
    private int[] skyReadd = new int[256];

    /** Границы изменённой области — по ним размывается ровно то, что поехало. */
    private int dirtyMinX, dirtyMaxX, dirtyMinY, dirtyMaxY, dirtyMinZ, dirtyMaxZ;

    /**
     * Небесный свет после смены прозрачности блока в (x, y, z).
     *
     * <p>Вместо полной перезаливки чанка — снятие света ограниченным BFS и
     * возврат его от уцелевших соседей. Небо особенное: прямой столб идёт вниз
     * без затухания, поэтому вниз свет и снимается, и возвращается целиком,
     * пока уровень равен {@link #MAX_LIGHT}. Без этого поставленный блок
     * оставлял бы под собой светящуюся шахту.
     */
    public void updateSkyLightAt(int x, int y, int z) {
        if (!inBounds(x, y, z))
            return;
        int i = idx(x, y, z);
        dirtyMinX = dirtyMaxX = x;
        dirtyMinY = dirtyMaxY = y;
        dirtyMinZ = dirtyMaxZ = z;
        if (clearAt(i))
            addSkyFrom(i);
        else
            removeSkyFrom(i);
        blurSkyLight(Math.max(0, dirtyMinX - 1), Math.min(SIZE_X - 1, dirtyMaxX + 1),
                Math.max(0, dirtyMinY - 1), Math.min(SIZE_Y - 1, dirtyMaxY + 1),
                Math.max(0, dirtyMinZ - 1), Math.min(SIZE_Z - 1, dirtyMaxZ + 1));
    }

    private void touch(int i) {
        int x = i % SIZE_X;
        int rest = i / SIZE_X;
        int z = rest % SIZE_Z;
        int y = rest / SIZE_Z;
        if (x < dirtyMinX) dirtyMinX = x;
        if (x > dirtyMaxX) dirtyMaxX = x;
        if (y < dirtyMinY) dirtyMinY = y;
        if (y > dirtyMaxY) dirtyMaxY = y;
        if (z < dirtyMinZ) dirtyMinZ = z;
        if (z > dirtyMaxZ) dirtyMaxZ = z;
    }

    /** Ячейка стала непрозрачной: гасим её и всё, что питалось от неё. */
    private void removeSkyFrom(int origin) {
        int level = skyRaw[origin] & 0xFF;
        skyRaw[origin] = 0;
        touch(origin);
        if (level == 0)
            return;
        int head = 0, tail = 0, readd = 0;
        skyQueue = push(skyQueue, tail, origin | (level << 16));
        tail++;
        final int planeXZ = SIZE_X * SIZE_Z;
        while (head < tail) {
            int e = skyQueue[head++];
            int j = e & 0xFFFF;
            int lvl = e >>> 16;
            int x = j % SIZE_X;
            int rest = j / SIZE_X;
            int zz = rest % SIZE_Z;
            int yy = rest / SIZE_Z;
            for (int d = 0; d < 6; d++) {
                int k;
                if (d == 0)      { if (x + 1 >= SIZE_X) continue;  k = j + 1; }
                else if (d == 1) { if (x - 1 < 0) continue;        k = j - 1; }
                else if (d == 2) { if (zz + 1 >= SIZE_Z) continue; k = j + SIZE_X; }
                else if (d == 3) { if (zz - 1 < 0) continue;       k = j - SIZE_X; }
                else if (d == 4) { if (yy + 1 >= SIZE_Y) continue; k = j + planeXZ; }
                else             { if (yy - 1 < 0) continue;       k = j - planeXZ; }
                if (!clearAt(k))
                    continue;
                int nl = skyRaw[k] & 0xFF;
                if (nl == 0)
                    continue;
                // Вниз по прямому столбу свет не затухает, поэтому и гаснет он
                // там весь, а не «на уровень ниже».
                boolean column = d == 5 && lvl == MAX_LIGHT && nl == MAX_LIGHT;
                if (nl < lvl || column) {
                    skyRaw[k] = 0;
                    touch(k);
                    skyQueue = push(skyQueue, tail, k | (nl << 16));
                    tail++;
                } else {
                    skyReadd = push(skyReadd, readd, k);
                    readd++;
                }
            }
        }
        // Уцелевшие соседи заливают погасшее обратно.
        if (readd > 0)
            spread(skyReadd, readd);
    }

    /** Ячейка стала прозрачной: берём максимум у соседей и разливаем дальше. */
    private void addSkyFrom(int origin) {
        final int planeXZ = SIZE_X * SIZE_Z;
        int x = origin % SIZE_X;
        int rest = origin / SIZE_X;
        int z = rest % SIZE_Z;
        int y = rest / SIZE_Z;
        int best = 0;
        if (y + 1 >= SIZE_Y)
            best = MAX_LIGHT;                                  // прямо под небом
        else if (clearAt(origin + planeXZ) && (skyRaw[origin + planeXZ] & 0xFF) == MAX_LIGHT)
            best = MAX_LIGHT;                                  // продолжение столба
        if (x + 1 < SIZE_X && clearAt(origin + 1))       best = Math.max(best, (skyRaw[origin + 1] & 0xFF) - 1);
        if (x - 1 >= 0     && clearAt(origin - 1))       best = Math.max(best, (skyRaw[origin - 1] & 0xFF) - 1);
        if (z + 1 < SIZE_Z && clearAt(origin + SIZE_X))  best = Math.max(best, (skyRaw[origin + SIZE_X] & 0xFF) - 1);
        if (z - 1 >= 0     && clearAt(origin - SIZE_X))  best = Math.max(best, (skyRaw[origin - SIZE_X] & 0xFF) - 1);
        if (y + 1 < SIZE_Y && clearAt(origin + planeXZ)) best = Math.max(best, (skyRaw[origin + planeXZ] & 0xFF) - 1);
        if (y - 1 >= 0     && clearAt(origin - planeXZ)) best = Math.max(best, (skyRaw[origin - planeXZ] & 0xFF) - 1);
        skyRaw[origin] = (byte) Math.max(0, best);
        touch(origin);
        skyReadd = push(skyReadd, 0, origin);
        spread(skyReadd, 1);
    }

    /** BFS-долив: из перечисленных ячеек свет расходится, пока кому-то темнее. */
    private void spread(int[] seeds, int count) {
        final int planeXZ = SIZE_X * SIZE_Z;
        int head = 0, tail = 0;
        for (int n = 0; n < count; n++) {
            skyQueue = push(skyQueue, tail, seeds[n]);
            tail++;
        }
        while (head < tail) {
            if (tail + 6 > skyQueue.length)
                skyQueue = java.util.Arrays.copyOf(skyQueue, skyQueue.length * 2);
            int j = skyQueue[head++];
            int lvl = skyRaw[j] & 0xFF;
            if (lvl <= 1)
                continue;
            int x = j % SIZE_X;
            int rest = j / SIZE_X;
            int zz = rest % SIZE_Z;
            int yy = rest / SIZE_Z;
            int next = lvl - 1;
            if (x + 1 < SIZE_X  && raise(j + 1, next))       skyQueue[tail++] = j + 1;
            if (x - 1 >= 0      && raise(j - 1, next))       skyQueue[tail++] = j - 1;
            if (zz + 1 < SIZE_Z && raise(j + SIZE_X, next))  skyQueue[tail++] = j + SIZE_X;
            if (zz - 1 >= 0     && raise(j - SIZE_X, next))  skyQueue[tail++] = j - SIZE_X;
            if (yy + 1 < SIZE_Y && raise(j + planeXZ, next)) skyQueue[tail++] = j + planeXZ;
            // Вниз прямой столб идёт без затухания — как в вертикальном посеве.
            if (yy - 1 >= 0 && raise(j - planeXZ, lvl == MAX_LIGHT ? MAX_LIGHT : next))
                skyQueue[tail++] = j - planeXZ;
        }
    }

    private boolean raise(int i, int next) {
        if (next <= 0 || !clearAt(i) || (skyRaw[i] & 0xFF) >= next)
            return false;
        skyRaw[i] = (byte) next;
        touch(i);
        return true;
    }

    private static int[] push(int[] arr, int at, int value) {
        if (at == arr.length)
            arr = java.util.Arrays.copyOf(arr, arr.length * 2);
        arr[at] = value;
        return arr;
    }

    /**
     * Одно самовзвешенное размытие {@link #skyRaw} в {@link #skyLight} по
     * коробке. Границы задаёт вызывающий: полная заливка размывает чанк
     * целиком, точечная правка — только то, что сдвинулось, плюс ячейка
     * вокруг (размытие читает соседей).
     */
    private void blurSkyLight(int x0, int x1, int y0, int y1, int z0, int z1) {
        final int planeXZ = SIZE_X * SIZE_Z;
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    int i = idx(x, y, z);
                    if (!clearAt(i)) {
                        skyLight[i] = 0;
                        continue;
                    }
                    int sum = 2 * (skyRaw[i] & 0xFF);
                    int cnt = 2;
                    if (x + 1 < SIZE_X && clearAt(i + 1))       { sum += skyRaw[i + 1] & 0xFF; cnt++; }
                    if (x - 1 >= 0     && clearAt(i - 1))       { sum += skyRaw[i - 1] & 0xFF; cnt++; }
                    if (z + 1 < SIZE_Z && clearAt(i + SIZE_X))  { sum += skyRaw[i + SIZE_X] & 0xFF; cnt++; }
                    if (z - 1 >= 0     && clearAt(i - SIZE_X))  { sum += skyRaw[i - SIZE_X] & 0xFF; cnt++; }
                    if (y + 1 < SIZE_Y && clearAt(i + planeXZ)) { sum += skyRaw[i + planeXZ] & 0xFF; cnt++; }
                    if (y - 1 >= 0     && clearAt(i - planeXZ)) { sum += skyRaw[i - planeXZ] & 0xFF; cnt++; }
                    skyLight[i] = (byte) ((sum + cnt / 2) / cnt);
                }
            }
        }
    }

    /**
     * Пропускает ли блок небесный свет.
     *
     * <p>Публичная, потому что то же правило обязан знать {@link World#setBlock}:
     * пересчитывать свет надо ровно тогда, когда меняется этот ответ. Раньше он
     * решал по своей мерке («непрозрачный» без учёта cutout), и поставленная в
     * воздухе листва не гасила под собой ничего — расхождение всплывало только
     * при следующей полной перезаливке чанка.
     */
    public static boolean letsSkyThrough(BlockType bt) {
        if (bt == BlockType.AIR || bt.transparent)
            return true;
        return bt == BlockType.LEAVES || bt == BlockType.GLASS
                || bt == BlockType.ROPE || bt == BlockType.CHAIN || bt == BlockType.WEB;
    }

    /** Light levels absorbed when direct or propagated skylight enters a block. */
    public static int skyAttenuation(BlockType bt) {
        if (!letsSkyThrough(bt)) return MAX_LIGHT;
        return switch (bt) {
            case WATER, WATER_FLOW -> 2;
            case LAVA, LEAVES -> 3;
            case WEB -> 2;
            case GLASS, THIN_ICE, SNOW_LAYER -> 1;
            default -> 0;
        };
    }

    private static boolean transparent(BlockType bt) {
        return letsSkyThrough(bt);
    }

    // ---- сундуки -----------------------------------------------------------

    /** Сколько стопок держит один сундук. */
    public static final int CHEST_SLOTS = 27;

    /**
     * Содержимое сундуков этого чанка: ключ — {@link #idx} позиции блока.
     *
     * Хранить в самом блоке нельзя: в id помещается один байт, а в сундук
     * двадцать семь стопок. Хранить в отдельной глобальной карте — значит
     * заводить вторую систему выгрузки и сохранения, ровно ту же, что уже
     * есть у чанков.
     */
    // After publication this map and its mutable values are main-thread only.
    // Background save jobs receive deep copies taken on that thread.
    private final java.util.HashMap<Integer, ItemStack[]> chests = new java.util.HashMap<>();

    /** Содержимое сундука или null, если сундука там нет. */
    public ItemStack[] getChest(int x, int y, int z) {
        assertMainThread();
        if (!inBounds(x, y, z))
            return null;
        return chests.get(idx(x, y, z));
    }

    /** Заводит пустой сундук, если его ещё нет, и отдаёт содержимое. */
    public ItemStack[] createChest(int x, int y, int z) {
        assertMainThread();
        if (!inBounds(x, y, z))
            return null;
        return chests.computeIfAbsent(idx(x, y, z), k -> new ItemStack[CHEST_SLOTS]);
    }

    /** Убирает сундук и отдаёт то, что в нём лежало (или null). */
    public ItemStack[] removeChest(int x, int y, int z) {
        assertMainThread();
        if (!inBounds(x, y, z))
            return null;
        ItemStack[] out = chests.remove(idx(x, y, z));
        if (out != null)
            dirty = true;
        return out;
    }

    /** Все сундуки чанка — для сохранения. Ключ это {@link #idx}. */
    public java.util.Map<Integer, ItemStack[]> chests() {
        assertMainThread();
        return chests;
    }

    /**
     * Печи этого чанка: ключ — {@link #idx} позиции блока.
     *
     * Вторая карта рядом с сундуками, а не общая система «блок-сущностей».
     * Общая обойдётся дороже, чем экономит, пока видов ровно два: ей нужны
     * тег типа, реестр, ветвление при чтении сейва — и всё это ради того,
     * чтобы две разные структуры лежали в одном ящике.
     */
    // Same single-writer ownership as chests; meshing never reads these maps.
    private final java.util.HashMap<Integer, Furnace> furnaces = new java.util.HashMap<>();

    /** Печь в этой клетке или null. */
    public Furnace getFurnace(int x, int y, int z) {
        assertMainThread();
        if (!inBounds(x, y, z))
            return null;
        return furnaces.get(idx(x, y, z));
    }

    /** Заводит печь, если её ещё нет, и отдаёт её состояние. */
    public Furnace createFurnace(int x, int y, int z) {
        assertMainThread();
        if (!inBounds(x, y, z))
            return null;
        return furnaces.computeIfAbsent(idx(x, y, z), k -> new Furnace());
    }

    /** Убирает печь и отдаёт то, что в ней было. */
    public Furnace removeFurnace(int x, int y, int z) {
        assertMainThread();
        if (!inBounds(x, y, z))
            return null;
        Furnace out = furnaces.remove(idx(x, y, z));
        if (out != null)
            dirty = true;
        return out;
    }

    /** Все печи чанка — для сохранения и тиков. */
    public java.util.Map<Integer, Furnace> furnaces() {
        assertMainThread();
        return furnaces;
    }

    // ---- предметы на земле ---------------------------------------------------

    /**
     * Предметы, прочитанные из сейва и ещё не поднятые в мир. Сами сущности
     * живут в Game, а не в чанке: они перелетают через границы чанков, и
     * держать их здесь значило бы перекладывать при каждом шаге.
     */
    private java.util.List<DroppedItem> pendingItems = java.util.List.of();
    private final java.util.Map<String, byte[]> extraSections = new java.util.LinkedHashMap<>();

    public void restoreExtraSections(java.util.Map<String, byte[]> sections) {
        assertMainThread();
        extraSections.clear();
        sections.forEach((key, value) -> extraSections.put(key, value.clone()));
    }

    public java.util.Map<String, byte[]> copyExtraSections() {
        assertMainThread();
        java.util.Map<String, byte[]> copy = new java.util.LinkedHashMap<>();
        extraSections.forEach((key, value) -> copy.put(key, value.clone()));
        return copy;
    }
    /** Сколько предметов ушло в последнюю запись: был хоть один — запись обязательна. */
    public int savedItems;

    public synchronized void setPendingItems(java.util.List<DroppedItem> items) {
        assertMainThread();
        pendingItems = items == null ? java.util.List.of() : new java.util.ArrayList<>(items);
        savedItems = pendingItems.size();
    }

    /** Отдаёт предметы из сейва ровно один раз. */
    public synchronized java.util.List<DroppedItem> takePendingItems() {
        assertMainThread();
        java.util.List<DroppedItem> out = pendingItems;
        pendingItems = java.util.List.of();
        return out;
    }

    /**
     * Копия ещё не поднятых предметов. Чанк может уйти в запись раньше, чем
     * его меш готов и предметы подняты, — без этого запись молча стёрла бы их.
     */
    public synchronized java.util.List<DroppedItem> copyPendingItems() {
        assertMainThread();
        java.util.List<DroppedItem> out = new java.util.ArrayList<>(pendingItems.size());
        for (DroppedItem d : pendingItems)
            out.add(new DroppedItem(d.stack.copy(), d.x, d.y, d.z, d.age));
        return out;
    }

    /** Заменяет набор печей целиком — при восстановлении из сейва. */
    public void restoreFurnaces(java.util.Map<Integer, Furnace> src) {
        assertMainThread();
        furnaces.clear();
        if (src != null)
            furnaces.putAll(src);
    }

    /** Копия печей для фоновой записи — см. {@link #copyChests}. */
    public java.util.Map<Integer, Furnace> copyFurnaces() {
        assertMainThread();
        java.util.HashMap<Integer, Furnace> out = new java.util.HashMap<>();
        for (var e : furnaces.entrySet()) {
            Furnace src = e.getValue();
            Furnace dst = new Furnace();
            dst.input = src.input == null ? null : src.input.copy();
            dst.fuel = src.fuel == null ? null : src.fuel.copy();
            dst.output = src.output == null ? null : src.output.copy();
            dst.burnLeft = src.burnLeft;
            dst.burnMax = src.burnMax;
            dst.cook = src.cook;
            out.put(e.getKey(), dst);
        }
        return out;
    }

    /**
     * Глубокая копия сундуков для фоновой записи.
     *
     * Массивы и сами стопки копируются: запись идёт в другом потоке, а игрок
     * в это время продолжает перекладывать предметы. Отдать живую карту —
     * значит однажды сохранить полустопку.
     */
    public java.util.Map<Integer, ItemStack[]> copyChests() {
        assertMainThread();
        java.util.HashMap<Integer, ItemStack[]> out = new java.util.HashMap<>();
        for (var e : chests.entrySet()) {
            ItemStack[] src = e.getValue();
            ItemStack[] dst = new ItemStack[src.length];
            for (int i = 0; i < src.length; i++)
                dst[i] = src[i] == null ? null : src[i].copy();
            out.put(e.getKey(), dst);
        }
        return out;
    }

    /** Заменяет набор сундуков целиком — при восстановлении из сейва. */
    public void restoreChests(java.util.Map<Integer, ItemStack[]> src) {
        assertMainThread();
        chests.clear();
        if (src != null)
            chests.putAll(src);
    }

    /** Defensive copy of the raw block array (length SIZE_X*SIZE_Y*SIZE_Z). */
    public int blockStorageBytes() { return blocks.payloadBytes(); }
    public int waterCellCount() { return waterCount; }
    public int waterCellAt(int index) { return waterCells[index]; }
    public int lavaCellCount() { return lavaCount; }
    public int lavaCellAt(int index) { return lavaCells[index]; }

    public byte[] copyBlocks() {
        return blocks.copy();
    }

    /** Defensive copy of the raw meta array. */
    public byte[] copyMeta() {
        return meta.clone();
    }

    /**
     * Overwrite this chunk's blocks+meta from a saved snapshot. Does NOT
     * touch lighting or flags — the caller recomputes sky light, sets
     * {@code dirty} for remeshing, and leaves {@code modified=false} (a
     * freshly-restored chunk matches disk).
     */
    public void restore(byte[] srcBlocks, byte[] srcMeta) {
        assertMainThread();
        blocks.restore(srcBlocks);
        waterCount = 0;
        lavaCount = 0;
        for (int i = 0; i < srcBlocks.length; i++) {
            BlockType bt = BlockType.byId(srcBlocks[i]);
            if (bt == BlockType.WATER || bt == BlockType.WATER_FLOW) {
                if (waterCount == waterCells.length) waterCells = java.util.Arrays.copyOf(waterCells, waterCount * 2);
                waterCells[waterCount++] = i;
            }
            if (bt == BlockType.LAVA) {
                if (lavaCount == lavaCells.length) lavaCells = java.util.Arrays.copyOf(lavaCells, lavaCount * 2);
                lavaCells[lavaCount++] = i;
            }
        }
        java.util.Arrays.fill(solidMask, 0L);
        java.util.Arrays.fill(clearMask, 0L);
        for (int i = 0; i < srcBlocks.length; i++) {
            BlockType bt = BlockType.byId(srcBlocks[i]);
            if (bt.solid) solidMask[i >>> 6] |= 1L << (i & 63);
            if (transparent(bt)) clearMask[i >>> 6] |= 1L << (i & 63);
        }
        System.arraycopy(srcMeta, 0, meta, 0, meta.length);
        // Блоки пришли массивом мимо set(), инкрементальный учёт их не видел.
        rebuildEmitters();
        markDirty();
    }
}

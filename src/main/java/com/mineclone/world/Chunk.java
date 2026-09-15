package com.mineclone.world;

import java.util.ArrayDeque;

public class Chunk {
    public static final int SIZE_X = 16;
    public static final int SIZE_Y = 128;
    public static final int SIZE_Z = 16;
    public static final int MAX_LIGHT = 15;

    public final int cx, cz;
    private final byte[] blocks = new byte[SIZE_X * SIZE_Y * SIZE_Z];
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
    public boolean modified = false;

    public Chunk(int cx, int cz) {
        this.cx = cx;
        this.cz = cz;
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
        return blocks[idx(x, y, z)];
    }

    public BlockType get(int x, int y, int z) {
        return BlockType.byId(getRaw(x, y, z));
    }

    public void set(int x, int y, int z, BlockType t) {
        if (!inBounds(x, y, z))
            return;
        int i = idx(x, y, z);
        BlockType old = BlockType.byId(blocks[i]);
        blocks[i] = (byte) t.ordinal();
        if (old.emittedLight > 0 && t.emittedLight <= 0)
            removeEmitter(i);
        else if (old.emittedLight <= 0 && t.emittedLight > 0)
            addEmitter(i);
        markDirty();
    }

    /** Помечает меш устаревшим и двигает поколение содержимого. */
    public void markDirty() {
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
    public boolean clearDirtyIfCurrent(int v) {
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
            if (BlockType.byId(blocks[i]).emittedLight > 0)
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

    public void setBlockLight(int x, int y, int z, int val) {
        if (!inBounds(x, y, z))
            return;
        blockLight[idx(x, y, z)] = (byte) Math.max(0, Math.min(15, val));
    }

    public byte getMeta(int x, int y, int z) {
        if (!inBounds(x, y, z))
            return 0;
        return meta[idx(x, y, z)];
    }

    public void setMeta(int x, int y, int z, byte val) {
        if (!inBounds(x, y, z))
            return;
        meta[idx(x, y, z)] = val;
    }

    /**
     * Chunk-local sky light flood. Cheap and gives the closed-box-is-dark behavior.
     *
     * <p>Зовётся не только при генерации, но и на каждый удар по блоку,
     * меняющий прозрачность — а на границе чанка сразу для трёх чанков, в
     * главном потоке. Отсюда две вещи, без которых копание заметно дёргается:
     * очередь хранит упакованные индексы в {@code int[]}, а не объекты {@code int[3]}
     * — в открытом чанке их набиралось около восемнадцати тысяч на один
     * вызов; а прозрачность снимается в маску один раз, а не по семь раз на ячейку
     * в размытии. Результат бит в бит тот же.
     */
    public void computeSkyLight() {
        final int len = blocks.length;
        final int planeXZ = SIZE_X * SIZE_Z;
        java.util.Arrays.fill(skyLight, (byte) 0);

        // Маска прозрачности: дальше она читается шесть раз на шаг BFS и семь
        // на ячейку в размытии. Поиск типа блока каждый раз был самой
        // дорогой частью функции.
        boolean[] clear = new boolean[len];
        for (int i = 0; i < len; i++)
            clear[i] = transparent(BlockType.byId(blocks[i]));

        int[] queue = new int[8192];
        int head = 0, tail = 0;

        // Vertical pass: each column gets 15 from the top down through transparent
        // blocks.
        for (int x = 0; x < SIZE_X; x++) {
            for (int z = 0; z < SIZE_Z; z++) {
                for (int y = SIZE_Y - 1; y >= 0; y--) {
                    int i = idx(x, y, z);
                    if (!clear[i])
                        break;
                    skyLight[i] = MAX_LIGHT;
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
            int next = (skyLight[i] & 0xFF) - 1;
            if (next <= 0)
                continue;
            // Распаковка idx: (y * SIZE_Z + z) * SIZE_X + x.
            int x = i % SIZE_X;
            int rest = i / SIZE_X;
            int z = rest % SIZE_Z;
            int y = rest / SIZE_Z;
            if (x + 1 < SIZE_X && lift(i + 1, next, clear))        queue[tail++] = i + 1;
            if (x - 1 >= 0     && lift(i - 1, next, clear))        queue[tail++] = i - 1;
            if (z + 1 < SIZE_Z && lift(i + SIZE_X, next, clear))   queue[tail++] = i + SIZE_X;
            if (z - 1 >= 0     && lift(i - SIZE_X, next, clear))   queue[tail++] = i - SIZE_X;
            if (y + 1 < SIZE_Y && lift(i + planeXZ, next, clear))  queue[tail++] = i + planeXZ;
            if (y - 1 >= 0     && lift(i - planeXZ, next, clear))  queue[tail++] = i - planeXZ;
        }

        smoothSkyLight(clear);
    }

    /**
     * Поднимает свет в ячейке до {@code next}, если та прозрачна и там сейчас
     * темнее. Возвращает true, если ячейку надо поставить в очередь.
     */
    private boolean lift(int i, int next, boolean[] clear) {
        if (!clear[i] || (skyLight[i] & 0xFF) >= next)
            return false;
        skyLight[i] = (byte) next;
        return true;
    }

    /**
     * One self-weighted blur pass over transparent cells to soften the BFS step
     * gradient.
     */
    private void smoothSkyLight(boolean[] clear) {
        byte[] src = skyLight.clone();
        final int planeXZ = SIZE_X * SIZE_Z;
        for (int x = 0; x < SIZE_X; x++) {
            for (int y = 0; y < SIZE_Y; y++) {
                for (int z = 0; z < SIZE_Z; z++) {
                    int i = idx(x, y, z);
                    if (!clear[i])
                        continue;
                    int sum = 2 * (src[i] & 0xFF);
                    int cnt = 2;
                    if (x + 1 < SIZE_X && clear[i + 1])       { sum += src[i + 1] & 0xFF; cnt++; }
                    if (x - 1 >= 0     && clear[i - 1])       { sum += src[i - 1] & 0xFF; cnt++; }
                    if (z + 1 < SIZE_Z && clear[i + SIZE_X])  { sum += src[i + SIZE_X] & 0xFF; cnt++; }
                    if (z - 1 >= 0     && clear[i - SIZE_X])  { sum += src[i - SIZE_X] & 0xFF; cnt++; }
                    if (y + 1 < SIZE_Y && clear[i + planeXZ]) { sum += src[i + planeXZ] & 0xFF; cnt++; }
                    if (y - 1 >= 0     && clear[i - planeXZ]) { sum += src[i - planeXZ] & 0xFF; cnt++; }
                    skyLight[i] = (byte) ((sum + cnt / 2) / cnt);
                }
            }
        }
    }

    private static boolean transparent(BlockType bt) {
        return bt == BlockType.AIR || bt.transparent;
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
    private final java.util.HashMap<Integer, ItemStack[]> chests = new java.util.HashMap<>();

    /** Содержимое сундука или null, если сундука там нет. */
    public ItemStack[] getChest(int x, int y, int z) {
        if (!inBounds(x, y, z))
            return null;
        return chests.get(idx(x, y, z));
    }

    /** Заводит пустой сундук, если его ещё нет, и отдаёт содержимое. */
    public ItemStack[] createChest(int x, int y, int z) {
        if (!inBounds(x, y, z))
            return null;
        return chests.computeIfAbsent(idx(x, y, z), k -> new ItemStack[CHEST_SLOTS]);
    }

    /** Убирает сундук и отдаёт то, что в нём лежало (или null). */
    public ItemStack[] removeChest(int x, int y, int z) {
        if (!inBounds(x, y, z))
            return null;
        ItemStack[] out = chests.remove(idx(x, y, z));
        if (out != null)
            dirty = true;
        return out;
    }

    /** Все сундуки чанка — для сохранения. Ключ это {@link #idx}. */
    public java.util.Map<Integer, ItemStack[]> chests() {
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
    private final java.util.HashMap<Integer, Furnace> furnaces = new java.util.HashMap<>();

    /** Печь в этой клетке или null. */
    public Furnace getFurnace(int x, int y, int z) {
        if (!inBounds(x, y, z))
            return null;
        return furnaces.get(idx(x, y, z));
    }

    /** Заводит печь, если её ещё нет, и отдаёт её состояние. */
    public Furnace createFurnace(int x, int y, int z) {
        if (!inBounds(x, y, z))
            return null;
        return furnaces.computeIfAbsent(idx(x, y, z), k -> new Furnace());
    }

    /** Убирает печь и отдаёт то, что в ней было. */
    public Furnace removeFurnace(int x, int y, int z) {
        if (!inBounds(x, y, z))
            return null;
        Furnace out = furnaces.remove(idx(x, y, z));
        if (out != null)
            dirty = true;
        return out;
    }

    /** Все печи чанка — для сохранения и тиков. */
    public java.util.Map<Integer, Furnace> furnaces() {
        return furnaces;
    }

    // ---- предметы на земле ---------------------------------------------------

    /**
     * Предметы, прочитанные из сейва и ещё не поднятые в мир. Сами сущности
     * живут в Game, а не в чанке: они перелетают через границы чанков, и
     * держать их здесь значило бы перекладывать при каждом шаге.
     */
    private java.util.List<DroppedItem> pendingItems = java.util.List.of();
    /** Сколько предметов ушло в последнюю запись: был хоть один — запись обязательна. */
    public int savedItems;

    public synchronized void setPendingItems(java.util.List<DroppedItem> items) {
        pendingItems = items == null ? java.util.List.of() : new java.util.ArrayList<>(items);
        savedItems = pendingItems.size();
    }

    /** Отдаёт предметы из сейва ровно один раз. */
    public synchronized java.util.List<DroppedItem> takePendingItems() {
        java.util.List<DroppedItem> out = pendingItems;
        pendingItems = java.util.List.of();
        return out;
    }

    /**
     * Копия ещё не поднятых предметов. Чанк может уйти в запись раньше, чем
     * его меш готов и предметы подняты, — без этого запись молча стёрла бы их.
     */
    public synchronized java.util.List<DroppedItem> copyPendingItems() {
        java.util.List<DroppedItem> out = new java.util.ArrayList<>(pendingItems.size());
        for (DroppedItem d : pendingItems)
            out.add(new DroppedItem(d.stack.copy(), d.x, d.y, d.z, d.age));
        return out;
    }

    /** Заменяет набор печей целиком — при восстановлении из сейва. */
    public void restoreFurnaces(java.util.Map<Integer, Furnace> src) {
        furnaces.clear();
        if (src != null)
            furnaces.putAll(src);
    }

    /** Копия печей для фоновой записи — см. {@link #copyChests}. */
    public java.util.Map<Integer, Furnace> copyFurnaces() {
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
        chests.clear();
        if (src != null)
            chests.putAll(src);
    }

    /** Defensive copy of the raw block array (length SIZE_X*SIZE_Y*SIZE_Z). */
    public byte[] copyBlocks() {
        return blocks.clone();
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
        System.arraycopy(srcBlocks, 0, blocks, 0, blocks.length);
        System.arraycopy(srcMeta, 0, meta, 0, meta.length);
        // Блоки пришли массивом мимо set(), инкрементальный учёт их не видел.
        rebuildEmitters();
        markDirty();
    }
}

package com.mineclone.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;

public class World {
    public static final int SEA_LEVEL = 50;

    private final Map<Long, Chunk> chunks = new ConcurrentHashMap<>();
    /** Gameplay/container mutation belongs to the thread that created this world. */
    private final Thread mainThread = Thread.currentThread();
    private volatile boolean savedChunkSource;
    private final LinkedHashSet<Long> pendingSkyRelights = new LinkedHashSet<>();
    private final PerlinNoise heightNoise;
    private final PerlinNoise detailNoise;
    public final BiomeProvider biomes;
    private final Caves caves;
    public final Rivers rivers;
    public final long seed;
    private final GenFeatures genFeatures;
    public final FallingBlocks falling = new FallingBlocks(this);

    public World(long seed) {
        this(seed, GenFeatures.benchmarkProfile());
    }

    public World(long seed, GenFeatures genFeatures) {
        this.seed = seed;
        this.genFeatures = java.util.Objects.requireNonNull(genFeatures);
        this.heightNoise = new PerlinNoise(seed);
        this.detailNoise = new PerlinNoise(seed ^ 0x9E3779B97F4A7C15L);
        this.biomes = new BiomeProvider(seed);
        this.caves = new Caves(seed);
        this.rivers = new Rivers(seed);
    }

    public static long key(int cx, int cz) {
        return (((long) cx) << 32) ^ (cz & 0xFFFFFFFFL);
    }

    /**
     * Кто-то смотрит за правками блоков.
     *
     * <p>Единственный, кому это нужно, — сетевая сессия: любая правка,
     * откуда бы она ни пришла (кирка, растёкшаяся вода, выросший кактус),
     * обязана уехать остальным игрокам. Ловить их по всем вызывающим было бы
     * ошибкой на каждый новый вызов {@code setBlock}, а тут воронка одна.
     */
    public interface BlockObserver {
        void changed(int wx, int wy, int wz, BlockType old, BlockType now, byte meta);
    }

    private BlockObserver observer;
    /**
     * Правка с meta идёт в два приёма, и наблюдателя зовёт только второй:
     * иначе сеть увидела бы блок со старой meta, а следом — ничего.
     */
    private boolean deferObserver;

    public void setBlockObserver(BlockObserver o) {
        this.observer = o;
    }

    public BlockObserver blockObserver() {
        return observer;
    }

    private void notifyChanged(int wx, int wy, int wz, BlockType old, BlockType now, byte meta) {
        if (observer != null)
            observer.changed(wx, wy, wz, old, now, meta);
    }


    /**
     * Generate a pristine chunk for menu worlds, delta baselines and tests.
     * Saved worlds must use {@link ChunkLoader#loadNow(int, int)}: publishing
     * terrain here would make it visible before its saved edits were restored.
     */
    public Chunk getChunk(int cx, int cz) {
        if (savedChunkSource && Chunk.threadChecksEnabled())
            throw new IllegalStateException("Saved worlds must load chunks through ChunkLoader.loadNow");
        Chunk existing = getChunkIfExists(cx, cz);
        return existing != null ? existing : publish(generateDetached(cx, cz));
    }

    void useSavedChunkSource() { savedChunkSource = true; }

    /**
     * The sole visibility boundary. All terrain, saved containers and local
     * light must be complete before this call; a losing candidate is discarded.
     * ConcurrentHashMap establishes the happens-before edge for readers.
     */
    public Chunk publish(Chunk chunk) {
        chunk.publishTo(mainThread);
        Chunk existing = chunks.putIfAbsent(key(chunk.cx, chunk.cz), chunk);
        return existing != null ? existing : chunk;
    }

    void assertMainThread() {
        if (Chunk.threadChecksEnabled() && Thread.currentThread() != mainThread)
            throw new IllegalStateException("World mutation outside its main thread");
    }

    public Chunk getChunkIfExists(int cx, int cz) {
        return chunks.get(key(cx, cz));
    }

    public Chunk removeChunk(int cx, int cz) {
        falling.forget(cx, cz);
        pendingSkyRelights.remove(key(cx, cz));
        return chunks.remove(key(cx, cz));
    }

    public Iterable<Chunk> getLoadedChunks() {
        return chunks.values();
    }

    /**
     * Высота поверхности в колонне.
     *
     * Вынесена отдельно, потому что её считает не только проход рельефа:
     * растительность заглядывает за границу чанка, где массива высот нет, и
     * обязана получить ровно то же число — иначе дерево, которое сосед
     * поставил на свою землю, у нас повиснет в воздухе или уйдёт в грунт.
     */
    private int columnHeight(int wx, int wz, Biome[][] grid, int gi, int gj) {
        // Continuous tent filter: the previous equal-weight grid jumped every four blocks.
        double base = 0, amp = 0, weight = 0, alpine = 0, mesa = 0, volcanic = 0;
        double fx = Math.floorMod(wx, 4) / 4.0, fz = Math.floorMod(wz, 4) / 4.0;
        for (int ox = -2; ox <= 3; ox++)
            for (int oz = -2; oz <= 3; oz++) {
                Biome b = grid[gi + ox][gj + oz];
                double w = Math.max(0, 3 - Math.abs(ox - fx)) * Math.max(0, 3 - Math.abs(oz - fz));
                base += b.baseHeight * w;
                amp += b.amplitude * w;
                weight += w;
                if (b == Biome.ALPINE) alpine += w;
                if (b == Biome.BADLANDS) mesa += w;
                if (b == Biome.VOLCANIC) volcanic += w;
            }
        base /= weight;
        amp /= weight;
        double n = heightNoise.fbm(wx * 0.012, wz * 0.012, 5, 2.0, 0.5);
        double d = detailNoise.fbm(wx * 0.05, wz * 0.05, 3, 2.0, 0.5);
        double ridge = 1 - Math.abs(heightNoise.fbm((wx + 531) * 0.008, (wz - 713) * 0.008, 3, 2, 0.5));
        double relief = base + n * 22 * amp + d * 3 * Math.min(1, amp);
        relief += (alpine * 23 + volcanic * 12) / weight * ridge * ridge * ridge;
        // Broad flat-topped mesas, blended continuously into surrounding biomes.
        double terrace = Math.floor(relief / 7) * 7 + 7 * smooth((relief % 7) / 7);
        relief += (terrace - relief) * mesa / weight;
        int height = (int) Math.round(relief);
        height = Math.max(2, Math.min(Chunk.SIZE_Y - 4, height));
        // Реки и озёра — это размыв колонны, а не отдельный блок: высота
        // опускается ниже уровня моря, и вода наливается тем же правилом,
        // что наполняет океан. Делается до всего остального, потому что
        // дальше все проходы читают heights.
        return rivers.carve(wx, wz, height, SEA_LEVEL);
    }

    private static double smooth(double t) { return t * t * (3 - 2 * t); }

    /** Same height function used for generation and structure-site validation. */
    public int terrainHeight(int wx, int wz) {
        int gx = Math.floorDiv(wx, 4), gz = Math.floorDiv(wz, 4);
        Biome[][] grid = new Biome[6][6];
        for (int x = 0; x < 6; x++) for (int z = 0; z < 6; z++)
            grid[x][z] = biomes.biomeAtGrid(gx + x - 2, gz + z - 2);
        return columnHeight(wx, wz, grid, 2, 2);
    }

    public static BlockType surfaceFor(Biome biome, int height) {
        if (biome == Biome.SWAMP) return BlockType.PEAT;
        if (height <= SEA_LEVEL + 1) return BlockType.SAND;
        if (biome == Biome.ALPINE && height >= 96) return BlockType.SNOWY_GRASS;
        return biome.surfaceBlock;
    }

    /**
     * На сколько блоков крона выходит за ствол. Ровно на столько же
     * растительность заглядывает за границу чанка.
     */
    private static final int LEAF_REACH = 3;
    /**
     * Поправка плотности леса: раньше ствол могли нести 144 колонны из 256,
     * остальные съедал отступ от края чанка. Теперь годятся все.
     *
     * Сравнение идёт по широкому диапазону, а не по семи битам хэша: на
     * редких биомах {@code treesPer128} равен единице, и от деления с
     * округлением вверх лес там становился вдвое гуще прежнего.
     */
    private static final int TREE_DENSITY_NUM = 144;
    private static final long TREE_ROLL_RANGE = 256L * 128L;

    /** Generate terrain privately; this method never inserts the chunk in the world. */
    public Chunk generateDetached(int cx, int cz) {
        Chunk c = new Chunk(cx, cz);
        if (genFeatures == GenFeatures.FLAT) {
            for (int x = 0; x < Chunk.SIZE_X; x++) for (int z = 0; z < Chunk.SIZE_Z; z++)
                for (int y = 0; y <= 64; y++)
                    c.set(x, y, z, y == 0 ? BlockType.BEDROCK : y == 64 ? BlockType.GRASS
                            : y >= 61 ? BlockType.DIRT : BlockType.STONE);
            c.computeSkyLight();
            c.modified = false;
            return c;
        }
        int[][] heights = new int[Chunk.SIZE_X][Chunk.SIZE_Z];

        // Biome grid: 11x11 array covering the chunk (16 cols / 4 = 4 cells) plus
        // margins for the 6x6 filter and crowns originating outside this chunk.
        final int G = 11;
        int gx0 = cx * 4 - 3, gz0 = cz * 4 - 3;
        Biome[][] grid = new Biome[G][G];
        for (int gx = 0; gx < G; gx++)
            for (int gz = 0; gz < G; gz++)
                grid[gx][gz] = biomes.biomeAtGrid(gx0 + gx, gz0 + gz);

        // Pass 1: terrain only — no trees yet so leaves are never overwritten by later
        // columns.
        for (int x = 0; x < Chunk.SIZE_X; x++) {
            for (int z = 0; z < Chunk.SIZE_Z; z++) {
                int wx = cx * Chunk.SIZE_X + x;
                int wz = cz * Chunk.SIZE_Z + z;

                // Smooth base height/amplitude with a continuous 6x6 tent filter so biome
                // borders slope instead of forming cliffs.
                int gi = Math.floorDiv(x, 4) + 3, gj = Math.floorDiv(z, 4) + 3;
                int height = columnHeight(wx, wz, grid, gi, gj);
                heights[x][z] = height;

                // Exact point biome picks the surface; wetlands retain their peat at the waterline.
                Biome biome = biomes.biomeAt(wx, wz);
                boolean beach = height <= SEA_LEVEL + 1 && biome != Biome.SWAMP;
                BlockType surface = surfaceFor(biome, height);
                BlockType filler  = beach ? BlockType.SAND : biome.fillerBlock;

                for (int y = 0; y < Chunk.SIZE_Y; y++) {
                    BlockType t;
                    if (y == 0)
                        t = BlockType.BEDROCK;
                    else if (y < height - 4) {
                        t = BlockType.STONE;
                        if (y >= height - 14) {
                            if (biome == Biome.VOLCANIC) t = BlockType.BASALT;
                            if (biome == Biome.ALPINE) t = BlockType.LIMESTONE;
                            if (biome == Biome.BADLANDS) t = y % 9 == 0 ? BlockType.LIMESTONE : BlockType.TERRACOTTA;
                        }
                    }
                    else if (y < height)
                        t = filler;
                    else if (y == height)
                        t = surface;
                    else if (y <= SEA_LEVEL)
                        // Холодные озёра и реки стоят подо льдом: верхний
                        // слой воды в тундре замерзает. Океанский биом не
                        // замерзает никогда — иначе у тундрового побережья
                        // ледяная кромка уходила бы на много чанков в море.
                        t = y == SEA_LEVEL && biome.isCold() ? BlockType.ICE : BlockType.WATER;
                    else
                        t = BlockType.AIR;
                    c.set(x, y, z, t);
                }
            }
        }

        // Pass 2: caves — режем до растительности, иначе деревья повиснут над
        // собственной пещерой. Руда ставится после выреза: в пустоте её нет.
        caves.carve(c, heights, SEA_LEVEL);
        OreGenerator.place(c, seed);
        // Gravel lenses in exposed underground stone; no free-floating loose ceilings.
        for (int x = 0; x < Chunk.SIZE_X; x++) for (int z = 0; z < Chunk.SIZE_Z; z++) {
            long rock = mix(cx * 16 + x, cz * 16 + z, seed ^ 0x674B4CL);
            if ((rock & 15) == 0) {
                int y = 5 + (int) ((rock >>> 8) % 32);
                if (c.get(x, y, z) == BlockType.STONE && c.get(x, y - 1, z).solid)
                    c.set(x, y, z, BlockType.GRAVEL);
            }
        }
        FallingBlocks.settleGenerated(c);

        Structures.Site site = Structures.plan(c, heights, seed, SEA_LEVEL);

        // Pass 3: растительность — рельеф уже весь есть, листва ложится верно.
        //
        // Обход идёт с запасом за границу чанка. Крона шире, чем прежний
        // отступ от края, и дерево соседа обязано дотянуться листвой к нам.
        // Писать в чужой чанк нельзя (генерация идёт в фоновых потоках и
        // сосед может быть уже смешан), поэтому мы пересчитываем его дерево
        // сами и рисуем только ту часть, что попала в наши границы.
        for (int x = -LEAF_REACH; x < Chunk.SIZE_X + LEAF_REACH; x++) {
            for (int z = -LEAF_REACH; z < Chunk.SIZE_Z + LEAF_REACH; z++) {
                int gi = Math.floorDiv(x, 4) + 3, gj = Math.floorDiv(z, 4) + 3;
                int wx = cx * Chunk.SIZE_X + x;
                int wz = cz * Chunk.SIZE_Z + z;
                Biome biome = biomes.biomeAt(wx, wz);
                if (biome.treeType == Biome.TreeType.NONE || Structures.reservesTrees(wx, wz, seed))
                    continue;
                long h = mix(wx, wz, seed);
                long roll = (h >>> 17) % TREE_ROLL_RANGE;
                if (roll >= (long) biome.treesPer128 * TREE_DENSITY_NUM)
                    continue;
                boolean inside = x >= 0 && x < Chunk.SIZE_X && z >= 0 && z < Chunk.SIZE_Z;
                int height = inside ? heights[x][z] : columnHeight(wx, wz, grid, gi, gj);
                if (height < (biome == Biome.SWAMP ? SEA_LEVEL : SEA_LEVEL + 2))
                    continue;
                // Пещера, вскрывшая поверхность, отменяет дерево. Проверка
                // одна на свои и чужие колонны намеренно: реши мы её по
                // блоку, у соседа она дала бы другой ответ, и крона оказалась
                // бы нарисована только с одной стороны границы.
                if (caves.isCave(wx, height, wz))
                    continue;
                switch (biome.treeType) {
                    case OAK    -> placeOak(c, x, height, z, h);
                    case SPRUCE -> placeSpruce(c, x, height, z, h);
                    case CACTUS -> placeCactus(c, x, height, z, h);
                    case ACACIA -> placeAcacia(c, x, height, z, h);
                    case NONE   -> { }
                }
            }
        }

        // Pass 4: постройки — последними, на заранее зарезервированной площадке.
        Structures.place(c, site, seed);

        c.computeSkyLight();
        // Проходы генерации пишут блоки и через set(), и массивами; список
        // излучателей собирается один раз здесь, в фоновом потоке генерации,
        // а не перебором куба в кадре.
        c.rebuildEmitters();
        c.markDirty();
        return c;
    }

    private static void placeAcacia(Chunk c, int x, int height, int z, long h) {
        int top = height + 5 + (int) ((h >>> 8) & 1);
        if (top + 2 >= Chunk.SIZE_Y) return;
        for (int y = height + 1; y <= top; y++) if (c.inBounds(x, y, z))
            c.set(x, y, z, BlockType.WOOD);
        leafDisc(c, x, top, z, 3, h);
        leafDisc(c, x, top + 1, z, 2, h >>> 4);
    }

    /**
     * Лиственное дерево: ствол и округлая крона в пять слоёв.
     *
     * Прежняя крона была ромбом в три слоя и не доставала даже до середины
     * ствола — дерево читалось палкой с комком на конце. Здесь крона шире
     * ствола втрое и опускается на три блока ниже вершины, так что ствол
     * виден ровно настолько, насколько он и должен быть виден у взрослого
     * дерева: нижней третью.
     *
     * Форма кроны — круг, а не ромб: у ромба углы срезаны по диагоналям, и
     * сверху он выдаёт сетку. Каждое дерево дополнительно обкусывается по
     * углам своим же хэшем, поэтому подряд стоящие деревья не выглядят
     * отштампованными.
     */
    private static void placeOak(Chunk c, int x, int height, int z, long h) {
        // Ствол не ниже шести: крона начинается на три блока ниже вершины, а
        // листва в движке твёрдая. У пятиблочного ствола крона садилась на
        // высоту роста, и сквозь лес было не пройти.
        int th = 6 + (int) ((h >>> 7) & 0x3);            // 6..9
        int top = height + th;
        if (top + 2 >= Chunk.SIZE_Y)
            return;
        for (int i = 1; i <= th; i++)
            if (c.inBounds(x, height + i, z))
                c.set(x, height + i, z, BlockType.WOOD);

        // Радиусы снизу вверх: узкое основание, широкая середина, шапка.
        int[] radii = { 2, 3, 3, 2, 1 };
        for (int level = 0; level < radii.length; level++)
            leafDisc(c, x, top - 3 + level, z, radii[level], h + level * 31L);
    }

    /**
     * Хвойное: ярусы от самого низа ствола к вершине, через один поджатые.
     *
     * Ель — это юбка, а не шар: крона начинается у земли и сужается кверху.
     * Через ярус радиус уменьшается на единицу — отсюда ступенчатый силуэт,
     * по которому хвойное отличается от лиственного с любого расстояния.
     */
    private static void placeSpruce(Chunk c, int x, int height, int z, long h) {
        int th = 7 + (int) ((h >>> 7) & 0x3);            // 7..10
        int top = height + th;
        if (top + 2 >= Chunk.SIZE_Y)
            return;
        for (int i = 1; i <= th; i++)
            if (c.inBounds(x, height + i, z))
                c.set(x, height + i, z, BlockType.WOOD);

        // Юбка начинается примерно с трети ствола: у самой земли она
        // превращала бы одно дерево в стену семь на семь — листва твёрдая.
        int bottom = height + 1 + th / 3;
        int span = Math.max(1, top - bottom);
        for (int y = bottom; y <= top; y++) {
            float t = (y - bottom) / (float) span;       // 0 внизу юбки, 1 у вершины
            int rad = Math.round(2.4f * (1f - t));
            if (((y - bottom) & 1) == 1)
                rad--;                                   // поджатый ярус
            if (rad < 0)
                continue;
            leafDisc(c, x, y, z, rad, h + y * 17L);
        }
        if (c.inBounds(x, top + 1, z) && c.get(x, top + 1, z) == BlockType.AIR)
            c.set(x, top + 1, z, BlockType.LEAVES);
    }

    /**
     * Один слой кроны: круг радиуса rad с обкусанными углами.
     *
     * Допуск {@code +0.2 * rad} расширяет круг ровно настолько, чтобы на
     * малых радиусах он не вырождался в крест. Углы отбрасываются по битам
     * хэша — это и даёт разнообразие, и не стоит ни одного вызова генератора
     * случайных чисел.
     */
    private static void leafDisc(Chunk c, int x, int y, int z, int rad, long h) {
        if (rad < 0)
            return;
        float limit = rad * rad + rad * 0.2f;
        int bit = 0;
        for (int dx = -rad; dx <= rad; dx++)
            for (int dz = -rad; dz <= rad; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 > limit)
                    continue;
                // Обкусываем только самый край и только у широких слоёв:
                // дырки в середине кроны видны насквозь.
                if (rad >= 2 && d2 >= (rad - 0.5f) * (rad - 0.5f)
                        && ((h >>> (bit++ & 31)) & 1L) == 0L)
                    continue;
                int lx = x + dx, lz = z + dz;
                if (c.inBounds(lx, y, lz) && c.get(lx, y, lz) == BlockType.AIR)
                    c.set(lx, y, lz, BlockType.LEAVES);
            }
    }

    private static void placeCactus(Chunk c, int x, int height, int z, long h) {
        // Sand can settle into a cave during generation. Do not plant above its old height.
        // Cacti have no crown crossing a chunk boundary, so only their owning chunk places them.
        if (!c.inBounds(x, height, z)) return;
        BlockType ground = c.get(x, height, z);
        if (ground != BlockType.SAND && ground != BlockType.RED_SAND) return;
        int ch = 1 + (int) ((h >>> 7) % 3);
        if (height + ch + 1 >= Chunk.SIZE_Y)
            return;
        for (int i = 1; i <= ch; i++)
            c.set(x, height + i, z, BlockType.CACTUS);
    }

    public int getSkyLight(int wx, int wy, int wz) {
        if (wy < 0)
            return 0;
        if (wy >= Chunk.SIZE_Y)
            return Chunk.MAX_LIGHT;
        int cx = Math.floorDiv(wx, Chunk.SIZE_X);
        int cz = Math.floorDiv(wz, Chunk.SIZE_Z);
        Chunk c = getChunkIfExists(cx, cz);
        if (c == null)
            return Chunk.MAX_LIGHT; // assume sky-lit for not-yet-loaded chunks
        int lx = Math.floorMod(wx, Chunk.SIZE_X);
        int lz = Math.floorMod(wz, Chunk.SIZE_Z);
        return c.getSkyLight(lx, wy, lz);
    }

    public int getBlockLightWorld(int wx, int wy, int wz) {
        if (wy < 0 || wy >= Chunk.SIZE_Y)
            return 0;
        int cx = Math.floorDiv(wx, Chunk.SIZE_X);
        int cz = Math.floorDiv(wz, Chunk.SIZE_Z);
        Chunk c = getChunkIfExists(cx, cz);
        if (c == null)
            return 0;
        return c.getBlockLight(Math.floorMod(wx, Chunk.SIZE_X), wy, Math.floorMod(wz, Chunk.SIZE_Z));
    }

    private void setBlockLightWorld(int wx, int wy, int wz, int val) {
        if (wy < 0 || wy >= Chunk.SIZE_Y)
            return;
        int cx = Math.floorDiv(wx, Chunk.SIZE_X);
        int cz = Math.floorDiv(wz, Chunk.SIZE_Z);
        Chunk c = getChunkIfExists(cx, cz);
        if (c == null)
            return;
        c.setBlockLight(Math.floorMod(wx, Chunk.SIZE_X), wy, Math.floorMod(wz, Chunk.SIZE_Z), val);
        c.markDirty();
    }

    /**
     * Очередь заливки блочного света: плоский {@code int[]} по четыре числа на
     * ячейку (x, y, z, уровень). Раньше здесь стоял {@code ArrayDeque<int[]>} —
     * факел с уровнем 14 обходит порядка десяти тысяч ячеек, и на каждую
     * приходились массив из четырёх чисел и узел очереди. Мусор с главного
     * потока возвращался паузами сборщика, то есть теми же рывками.
     */
    private int[] lightQueue = new int[4096];
    private int lightHead, lightTail;
    /**
     * Последний найденный чанк. BFS света идёт сплошным пятном, поэтому
     * подряд идущие ячейки почти всегда лежат в одном чанке, а поиск в
     * {@code ConcurrentHashMap} упаковывает ключ в {@code Long} — то есть
     * выделяет объект на каждый запрос.
     */
    private Chunk lightCacheChunk;
    private int lightCacheCx = Integer.MIN_VALUE, lightCacheCz = Integer.MIN_VALUE;

    private Chunk lightChunk(int cx, int cz) {
        if (cx == lightCacheCx && cz == lightCacheCz)
            return lightCacheChunk;
        Chunk c = getChunkIfExists(cx, cz);
        lightCacheCx = cx;
        lightCacheCz = cz;
        lightCacheChunk = c;
        return c;
    }

    /** Сбрасывает кэш чанка: мир мог выгрузить тот, что в нём лежит. */
    private void resetLightCache() {
        lightCacheCx = Integer.MIN_VALUE;
        lightCacheCz = Integer.MIN_VALUE;
        lightCacheChunk = null;
    }

    private void lightPush(int wx, int wy, int wz, int val) {
        if (lightTail + 4 > lightQueue.length)
            lightQueue = java.util.Arrays.copyOf(lightQueue, lightQueue.length * 2);
        lightQueue[lightTail++] = wx;
        lightQueue[lightTail++] = wy;
        lightQueue[lightTail++] = wz;
        lightQueue[lightTail++] = val;
    }

    public void floodFillAdd(int wx, int wy, int wz) {
        int emitted = getBlock(wx, wy, wz).emittedLight;
        if (emitted <= 0)
            return;
        setBlockLightWorld(wx, wy, wz, emitted);
        lightHead = lightTail = 0;
        resetLightCache();
        if (emitted > 1)
            seedNeighbours(wx, wy, wz, emitted - 1);
        drainLightQueue();
    }

    private void seedNeighbours(int wx, int wy, int wz, int val) {
        enqueueBlockLight(wx + 1, wy, wz, val);
        enqueueBlockLight(wx - 1, wy, wz, val);
        enqueueBlockLight(wx, wy + 1, wz, val);
        enqueueBlockLight(wx, wy - 1, wz, val);
        enqueueBlockLight(wx, wy, wz + 1, val);
        enqueueBlockLight(wx, wy, wz - 1, val);
    }

    private void drainLightQueue() {
        while (lightHead < lightTail) {
            int x = lightQueue[lightHead++];
            int y = lightQueue[lightHead++];
            int z = lightQueue[lightHead++];
            int val = lightQueue[lightHead++];
            if (val > 1)
                seedNeighbours(x, y, z, val - 1);
        }
        lightHead = lightTail = 0;
    }

    private void enqueueBlockLight(int wx, int wy, int wz, int val) {
        if (wy < 0 || wy >= Chunk.SIZE_Y)
            return;
        Chunk c = lightChunk(Math.floorDiv(wx, Chunk.SIZE_X), Math.floorDiv(wz, Chunk.SIZE_Z));
        if (c == null)
            return;
        int lx = Math.floorMod(wx, Chunk.SIZE_X);
        int lz = Math.floorMod(wz, Chunk.SIZE_Z);
        BlockType bt = c.get(lx, wy, lz);
        if (bt.solid && !bt.transparent && !bt.cutout)
            return;
        if (c.getBlockLight(lx, wy, lz) >= val)
            return;
        c.setBlockLight(lx, wy, lz, val);
        c.markDirty();
        lightPush(wx, wy, wz, val);
    }

    /**
     * Seed block-light into chunk (cx,cz) from the border cells of its loaded
     * neighbours.  Called when a chunk first loads so it inherits torch light
     * that was already propagated in adjacent chunks.  Re-flooding the emitter
     * in the neighbour would fail (BFS stops at cells that already hold the
     * correct value); reading the border directly and pushing inward avoids that.
     */
    public void injectNeighbourLight(int cx, int cz) {
        int bx = cx * Chunk.SIZE_X;
        int bz = cz * Chunk.SIZE_Z;
        lightHead = lightTail = 0;
        resetLightCache();

        // Сосед без единого светящегося блока перебирать незачем: до этой
        // проверки загрузка чанка стоила 8 192 чтений на каждую сторону, и в
        // мире без факелов все они возвращали ноль.
        Chunk nPX = getChunkIfExists(cx + 1, cz);
        if (nPX != null && nPX.hasBlockLight())
            for (int y = 0; y < Chunk.SIZE_Y; y++)
                for (int lz = 0; lz < Chunk.SIZE_Z; lz++) {
                    int nv = nPX.getBlockLight(0, y, lz);
                    if (nv > 1) enqueueBlockLight(bx + Chunk.SIZE_X - 1, y, bz + lz, nv - 1);
                }
        Chunk nNX = getChunkIfExists(cx - 1, cz);
        if (nNX != null && nNX.hasBlockLight())
            for (int y = 0; y < Chunk.SIZE_Y; y++)
                for (int lz = 0; lz < Chunk.SIZE_Z; lz++) {
                    int nv = nNX.getBlockLight(Chunk.SIZE_X - 1, y, lz);
                    if (nv > 1) enqueueBlockLight(bx, y, bz + lz, nv - 1);
                }
        Chunk nPZ = getChunkIfExists(cx, cz + 1);
        if (nPZ != null && nPZ.hasBlockLight())
            for (int y = 0; y < Chunk.SIZE_Y; y++)
                for (int lx = 0; lx < Chunk.SIZE_X; lx++) {
                    int nv = nPZ.getBlockLight(lx, y, 0);
                    if (nv > 1) enqueueBlockLight(bx + lx, y, bz + Chunk.SIZE_Z - 1, nv - 1);
                }
        Chunk nNZ = getChunkIfExists(cx, cz - 1);
        if (nNZ != null && nNZ.hasBlockLight())
            for (int y = 0; y < Chunk.SIZE_Y; y++)
                for (int lx = 0; lx < Chunk.SIZE_X; lx++) {
                    int nv = nNZ.getBlockLight(lx, y, Chunk.SIZE_Z - 1);
                    if (nv > 1) enqueueBlockLight(bx + lx, y, bz, nv - 1);
                }

        drainLightQueue();
    }

    /**
     * Гасит блочный свет вокруг изменённой точки и разливает его заново от
     * уцелевших источников.
     *
     * <p>Вызывается на каждый удар по блоку, меняющий прозрачность. Раньше
     * безусловно перебирал куб 31×31×31 — 29 791 ячейка, и на каждую два
     * поиска чанка с {@code floorDiv}/{@code floorMod}. На поверхности среди бела
     * дня вся эта работа находила ноль источников и ноль света.
     *
     * <p>Блочный свет берётся только от излучателей, а где они стоят, чанк
     * теперь знает списком. Нет ни одного в радиусе — гасить нечего и
     * разливать нечего, и куб не трогается вовсе. Попутно это чинит
     * старое поведение, при котором пустой список источников гасил свет от
     * факела чуть дальше радиуса и больше не зажигал его обратно.
     *
     * <p>Сам куб гасится по чанкам, а не по ячейкам мира: чанк находится один
     * раз на свою долю коробки и один раз помечается грязным.
     */
    public void floodFillRemove(int wx, int wy, int wz) {
        final int R = 15;
        List<int[]> sources = emittersNear(wx, wy, wz, R);
        // Снятый факел в список уже не попадает — его блок заменён до этого
        // вызова, но его свет в буфере остался и обязан быть погашен. Без этой
        // проверки последний снятый источник светил бы вечно.
        if (sources.isEmpty() && getBlockLightWorld(wx, wy, wz) == 0)
            return;
        int cx0 = Math.floorDiv(wx - R, Chunk.SIZE_X), cx1 = Math.floorDiv(wx + R, Chunk.SIZE_X);
        int cz0 = Math.floorDiv(wz - R, Chunk.SIZE_Z), cz1 = Math.floorDiv(wz + R, Chunk.SIZE_Z);
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                Chunk c = getChunkIfExists(cx, cz);
                if (c == null)
                    continue;
                int bx = cx * Chunk.SIZE_X, bz = cz * Chunk.SIZE_Z;
                c.clearBlockLightBox(wx - R - bx, wx + R - bx, wy - R, wy + R, wz - R - bz, wz + R - bz);
            }
        }
        for (int[] src : sources)
            floodFillAdd(src[0], src[1], src[2]);
    }

    /**
     * Излучатели в кубе радиуса {@code r} вокруг точки, кроме самой точки.
     * Идёт по спискам излучателей перекрываемых чанков, а не по ячейкам.
     */
    private List<int[]> emittersNear(int wx, int wy, int wz, int r) {
        List<int[]> out = new ArrayList<>();
        int cx0 = Math.floorDiv(wx - r, Chunk.SIZE_X), cx1 = Math.floorDiv(wx + r, Chunk.SIZE_X);
        int cz0 = Math.floorDiv(wz - r, Chunk.SIZE_Z), cz1 = Math.floorDiv(wz + r, Chunk.SIZE_Z);
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                Chunk c = getChunkIfExists(cx, cz);
                if (c == null)
                    continue;
                int bx = cx * Chunk.SIZE_X, bz = cz * Chunk.SIZE_Z;
                for (int i = 0; i < c.emitterCount(); i++) {
                    int ex = bx + c.emitterX(i);
                    int ey = c.emitterY(i);
                    int ez = bz + c.emitterZ(i);
                    if (Math.abs(ex - wx) > r || Math.abs(ey - wy) > r || Math.abs(ez - wz) > r)
                        continue;
                    if (ex == wx && ey == wy && ez == wz)
                        continue;
                    out.add(new int[] { ex, ey, ez });
                }
            }
        }
        return out;
    }

    private static long mix(int a, int b, long seed) {
        long h = seed ^ (a * 0x9E3779B97F4A7C15L) ^ (b * 0xBF58476D1CE4E5B9L);
        h ^= h >>> 30;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 27;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return h;
    }

    // ---- world-space accessors ----
    public boolean isSolid(int wx, int wy, int wz) {
        if (wy < 0 || wy >= Chunk.SIZE_Y) return false;
        Chunk c = getChunkIfExists(wx >> 4, wz >> 4);
        return c != null && c.isSolid(wx & 15, wy, wz & 15);
    }

    public BlockType getBlock(int wx, int wy, int wz) {
        if (wy < 0 || wy >= Chunk.SIZE_Y)
            return BlockType.AIR;
        int cx = Math.floorDiv(wx, Chunk.SIZE_X);
        int cz = Math.floorDiv(wz, Chunk.SIZE_Z);
        Chunk c = getChunkIfExists(cx, cz);
        if (c == null)
            return BlockType.AIR;
        int lx = Math.floorMod(wx, Chunk.SIZE_X);
        int lz = Math.floorMod(wz, Chunk.SIZE_Z);
        return c.get(lx, wy, lz);
    }

    public void setBlock(int wx, int wy, int wz, BlockType t) {
        if (wy < 0 || wy >= Chunk.SIZE_Y)
            return;
        int cx = Math.floorDiv(wx, Chunk.SIZE_X);
        int cz = Math.floorDiv(wz, Chunk.SIZE_Z);
        // Never force-generate here: chunk generation is heavy (terrain + trees +
        // sky-light BFS) and would stall the game thread mid-frame. Edits only ever
        // target already-loaded chunks (player raycast hits / fills around the
        // player), so a null means "not loaded" — silently ignore rather than
        // synchronously generating on the loop.
        Chunk c = getChunkIfExists(cx, cz);
        if (c == null)
            return;
        int lx = Math.floorMod(wx, Chunk.SIZE_X);
        int lz = Math.floorMod(wz, Chunk.SIZE_Z);
        BlockType old = c.get(lx, wy, lz);
        if (old == t)
            return;
        c.set(lx, wy, lz, t);
        c.modified = true;
        // Сундук перестал быть сундуком — забываем содержимое, иначе оно
        // всплывёт у следующего сундука, поставленного на то же место.
        if (old == BlockType.CHEST && t != BlockType.CHEST)
            c.removeChest(lx, wy, lz);
        if (old == BlockType.FURNACE && t != BlockType.FURNACE)
            c.removeFurnace(lx, wy, lz);

        // Что именно поменялось, решает, какую тяжёлую работу вообще делать.
        // Замена вида «земля -> дёрн» не меняет ни проходимость, ни
        // прозрачность: там достаточно перестроить меш, который уже помечен
        // грязным в c.set().
        boolean wasOpaque = old != BlockType.AIR && !old.transparent && !old.cutout;
        boolean isOpaque = t != BlockType.AIR && !t.transparent && !t.cutout;
        boolean opacityChanged = wasOpaque != isOpaque;

        // Воду будим не только когда меняется проходимость: снятие самого
        // источника (WATER -> AIR) проходимость не трогает, а бассейн после
        // него обязан стечь.
        boolean waterInvolved = isWater(old) || isWater(t);
        if (old.solid != t.solid || waterInvolved)
            WaterSimulator.activateAround(this, wx, wz);
        boolean lavaInvolved = old == BlockType.LAVA || t == BlockType.LAVA;
        if (old.solid != t.solid || lavaInvolved || waterInvolved)
            LavaSimulator.activateAround(this, wx, wz);
        // Небесный свет меряет прозрачность своей меркой (cutout его держит),
        // поэтому и условие своё: листва, поставленная в воздухе, обязана
        // затенить то, что под ней.
        //
        // Правка локальная, а не перезаливка чанка: полный проход стоил
        // 5–15 мс в кадре и был главным рывком при копании.
        if (Chunk.letsSkyThrough(old) != Chunk.letsSkyThrough(t))
            c.updateSkyLightAt(lx, wy, lz);
        if (Chunk.skyAttenuation(old) != Chunk.skyAttenuation(t))
            pendingSkyRelights.add(key(cx, cz));
        // mark neighbors dirty if on edge so their borders update
        if (lx == 0)
            remeshNeighbour(cx - 1, cz);
        if (lx == Chunk.SIZE_X - 1)
            remeshNeighbour(cx + 1, cz);
        if (lz == 0)
            remeshNeighbour(cx, cz - 1);
        if (lz == Chunk.SIZE_Z - 1)
            remeshNeighbour(cx, cz + 1);
        // Block must be committed (c.set called above) before flood fill —
        // floodFillAdd reads the new block's emittedLight via getBlock().
        //
        // Re-propagate whenever:
        // • a light source is removed/changed (old had emittedLight)
        // • a block's opacity flipped (solid placed/removed in a lit region)
        if (old.emittedLight > 0 || opacityChanged)
            floodFillRemove(wx, wy, wz);
        if (t.emittedLight > 0)
            floodFillAdd(wx, wy, wz);
        falling.changed(wx, wy, wz);
        if (!deferObserver)
            notifyChanged(wx, wy, wz, old, t, c.getMeta(lx, wy, lz));
    }

    /**
     * Rebuilds a bounded number of chunks whose translucent material changed.
     * Multiple water edits in the same chunk collapse into one full reflood.
     */
    public int processPendingSkyRelights(int budget) {
        int done = 0;
        java.util.Iterator<Long> it = pendingSkyRelights.iterator();
        while (it.hasNext() && done < Math.max(0, budget)) {
            long k = it.next();
            it.remove();
            Chunk chunk = chunks.get(k);
            if (chunk == null)
                continue;
            chunk.computeSkyLight();
            chunk.markDirty();
            done++;
        }
        return done;
    }

    public byte getBlockMeta(int wx, int wy, int wz) {
        if (wy < 0 || wy >= Chunk.SIZE_Y)
            return 0;
        int cx = Math.floorDiv(wx, Chunk.SIZE_X);
        int cz = Math.floorDiv(wz, Chunk.SIZE_Z);
        Chunk c = getChunkIfExists(cx, cz);
        if (c == null)
            return 0;
        return c.getMeta(Math.floorMod(wx, Chunk.SIZE_X), wy, Math.floorMod(wz, Chunk.SIZE_Z));
    }

    // ---- сундуки -----------------------------------------------------------

    /** Содержимое сундука в мировых координатах или null. */
    public ItemStack[] getChest(int wx, int wy, int wz) {
        Chunk c = chunkAt(wx, wz);
        return c == null ? null : c.getChest(Math.floorMod(wx, Chunk.SIZE_X), wy,
                Math.floorMod(wz, Chunk.SIZE_Z));
    }

    /** Заводит пустой сундук, если его ещё нет. */
    public ItemStack[] createChest(int wx, int wy, int wz) {
        Chunk c = chunkAt(wx, wz);
        if (c == null)
            return null;
        c.modified = true;
        return c.createChest(Math.floorMod(wx, Chunk.SIZE_X), wy,
                Math.floorMod(wz, Chunk.SIZE_Z));
    }

    /** Убирает сундук и отдаёт содержимое. */
    public ItemStack[] removeChest(int wx, int wy, int wz) {
        Chunk c = chunkAt(wx, wz);
        if (c == null)
            return null;
        c.modified = true;
        return c.removeChest(Math.floorMod(wx, Chunk.SIZE_X), wy,
                Math.floorMod(wz, Chunk.SIZE_Z));
    }

    /** Печь в мировых координатах или null. */
    public Furnace getFurnace(int wx, int wy, int wz) {
        Chunk c = chunkAt(wx, wz);
        return c == null ? null : c.getFurnace(Math.floorMod(wx, Chunk.SIZE_X), wy,
                Math.floorMod(wz, Chunk.SIZE_Z));
    }

    /** Заводит печь, если её ещё нет. */
    public Furnace createFurnace(int wx, int wy, int wz) {
        Chunk c = chunkAt(wx, wz);
        if (c == null)
            return null;
        c.modified = true;
        return c.createFurnace(Math.floorMod(wx, Chunk.SIZE_X), wy,
                Math.floorMod(wz, Chunk.SIZE_Z));
    }

    /** Убирает печь и отдаёт её состояние. */
    public Furnace removeFurnace(int wx, int wy, int wz) {
        Chunk c = chunkAt(wx, wz);
        if (c == null)
            return null;
        c.modified = true;
        return c.removeFurnace(Math.floorMod(wx, Chunk.SIZE_X), wy,
                Math.floorMod(wz, Chunk.SIZE_Z));
    }

    /**
     * Помечает чанк изменённым после правки содержимого сундука.
     *
     * Сундук правится через живой массив слотов, мимо {@code setBlock}, и без
     * этой пометки чанк не считается изменённым — перекладывание предметов
     * просто не попадёт в сейв.
     */
    public void markChestDirty(int wx, int wz) {
        Chunk c = chunkAt(wx, wz);
        if (c != null)
            c.modified = true;
    }

    private Chunk chunkAt(int wx, int wz) {
        if (wx == Integer.MIN_VALUE)
            return null;
        return getChunkIfExists(Math.floorDiv(wx, Chunk.SIZE_X),
                Math.floorDiv(wz, Chunk.SIZE_Z));
    }

    public void setBlock(int wx, int wy, int wz, BlockType t, byte meta) {
        BlockType old = getBlock(wx, wy, wz);
        deferObserver = true;
        try {
            setBlock(wx, wy, wz, t);
        } finally {
            deferObserver = false;
        }
        if (wy < 0 || wy >= Chunk.SIZE_Y)
            return;
        int cx = Math.floorDiv(wx, Chunk.SIZE_X);
        int cz = Math.floorDiv(wz, Chunk.SIZE_Z);
        Chunk c = getChunkIfExists(cx, cz);
        if (c == null)
            return;
        c.setMeta(Math.floorMod(wx, Chunk.SIZE_X), wy, Math.floorMod(wz, Chunk.SIZE_Z), meta);
        if (isWater(t)) WaterSimulator.activateAround(this, wx, wz);
        if (t == BlockType.LAVA) LavaSimulator.activateAround(this, wx, wz);
        // Блок мог не измениться — а meta изменилась: дверь, ступень,
        // спальник. Ранний выход внутри setBlock про это не знает.
        notifyChanged(wx, wy, wz, old, t, meta);
    }

    /**
     * Меняет только толщину слоя. Через setBlock это не проходит: там стоит
     * ранний выход, когда тип блока не изменился, — а меш всё равно обязан
     * перестроиться, потому что высота слоя берётся из meta.
     */
    public void setSnowLevel(int wx, int wy, int wz, int level) {
        if (wy < 0 || wy >= Chunk.SIZE_Y)
            return;
        Chunk c = getChunkIfExists(Math.floorDiv(wx, Chunk.SIZE_X), Math.floorDiv(wz, Chunk.SIZE_Z));
        if (c == null)
            return;
        int lx = Math.floorMod(wx, Chunk.SIZE_X), lz = Math.floorMod(wz, Chunk.SIZE_Z);
        byte m = (byte) Math.max(0, Math.min(7, level));
        c.setMeta(lx, wy, lz, m);
        c.modified = true;
        c.markDirty();
        notifyChanged(wx, wy, wz, c.get(lx, wy, lz), c.get(lx, wy, lz), m);
    }

    private static boolean isWater(BlockType t) {
        return t == BlockType.WATER || t == BlockType.WATER_FLOW;
    }

    /**
     * Сосед обязан перестроить меш: его грани на шве смотрят в наш чанк.
     *
     * <p>А вот свет ему пересчитывать не надо, хотя раньше здесь стоял вызов
     * {@code computeSkyLight()}: заливка небесного света строго чанк-локальная — BFS
     * не выходит за {@code inBounds}, и чужие блоки на её результат не влияют.
     * Мешер читает на шве наш свет, а не свой пересчитанный. Проверено
     * прямо: снос всего столба блоков у границы меняет у соседа ноль ячеек.
     * Удар по блоку у края стоил из-за этого трёх полных пересчётов вместо одного.
     */
    private void remeshNeighbour(int cx, int cz) {
        Chunk c = getChunkIfExists(cx, cz);
        if (c != null)
            c.markDirty();
    }

}

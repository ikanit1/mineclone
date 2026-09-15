package com.mineclone.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;

public class World {
    public static final int SEA_LEVEL = 50;

    private final Map<Long, Chunk> chunks = new ConcurrentHashMap<>();
    private final PerlinNoise heightNoise;
    private final PerlinNoise detailNoise;
    public final BiomeProvider biomes;
    private final Caves caves;
    public final Rivers rivers;
    public final long seed;

    public World(long seed) {
        this.seed = seed;
        this.heightNoise = new PerlinNoise(seed);
        this.detailNoise = new PerlinNoise(seed ^ 0x9E3779B97F4A7C15L);
        this.biomes = new BiomeProvider(seed);
        this.caves = new Caves(seed);
        this.rivers = new Rivers(seed);
    }

    public static long key(int cx, int cz) {
        return (((long) cx) << 32) ^ (cz & 0xFFFFFFFFL);
    }

    public Chunk getChunk(int cx, int cz) {
        return chunks.computeIfAbsent(key(cx, cz), k -> generate(cx, cz));
    }

    public Chunk getChunkIfExists(int cx, int cz) {
        return chunks.get(key(cx, cz));
    }

    public Chunk removeChunk(int cx, int cz) {
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
        // Сглаживание по окну 5x5: границы биомов дают склон, а не обрыв.
        double base = 0, amp = 0;
        for (int ox = -2; ox <= 2; ox++)
            for (int oz = -2; oz <= 2; oz++) {
                Biome b = grid[gi + ox][gj + oz];
                base += b.baseHeight;
                amp += b.amplitude;
            }
        base /= 25.0;
        amp /= 25.0;
        double n = heightNoise.fbm(wx * 0.012, wz * 0.012, 5, 2.0, 0.5);
        double d = detailNoise.fbm(wx * 0.05, wz * 0.05, 3, 2.0, 0.5);
        int height = (int) (base + n * 22 * amp + d * 4);
        height = Math.max(2, Math.min(Chunk.SIZE_Y - 4, height));
        // Реки и озёра — это размыв колонны, а не отдельный блок: высота
        // опускается ниже уровня моря, и вода наливается тем же правилом,
        // что наполняет океан. Делается до всего остального, потому что
        // дальше все проходы читают heights.
        return rivers.carve(wx, wz, height, SEA_LEVEL);
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

    private Chunk generate(int cx, int cz) {
        Chunk c = new Chunk(cx, cz);
        int[][] heights = new int[Chunk.SIZE_X][Chunk.SIZE_Z];

        // Biome grid: 10x10 array covering the chunk (16 cols / 4 = 4 cells) plus
        // a 3-cell margin on each side. Двух хватало на окно сглаживания 5x5;
        // третья нужна растительности — она заглядывает за границу чанка,
        // чтобы крона соседнего дерева дотянулась к нам.
        final int G = 10;
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

                // Smooth base height/amplitude over a 5x5 grid window so biome
                // borders slope instead of forming cliffs.
                int gi = Math.floorDiv(x, 4) + 3, gj = Math.floorDiv(z, 4) + 3;
                int height = columnHeight(wx, wz, grid, gi, gj);
                heights[x][z] = height;

                // Point biome (4x4 quantised) picks the surface blocks; the
                // beach rule overrides every biome at the waterline.
                Biome biome = grid[gi][gj];
                boolean beach = height <= SEA_LEVEL + 1;
                BlockType surface = beach ? BlockType.SAND : biome.surfaceBlock;
                BlockType filler  = beach ? BlockType.SAND : biome.fillerBlock;

                for (int y = 0; y < Chunk.SIZE_Y; y++) {
                    BlockType t;
                    if (y == 0)
                        t = BlockType.BEDROCK;
                    else if (y < height - 4)
                        t = BlockType.STONE;
                    else if (y < height)
                        t = filler;
                    else if (y == height)
                        t = surface;
                    else if (y <= SEA_LEVEL)
                        // Холодные озёра и реки стоят подо льдом: верхний
                        // слой воды в тундре замерзает. Океанский биом не
                        // замерзает никогда — иначе у тундрового побережья
                        // ледяная кромка уходила бы на много чанков в море.
                        t = y == SEA_LEVEL && biome == Biome.TUNDRA ? BlockType.ICE : BlockType.WATER;
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
                Biome biome = grid[gi][gj];
                if (biome.treeType == Biome.TreeType.NONE)
                    continue;
                int wx = cx * Chunk.SIZE_X + x;
                int wz = cz * Chunk.SIZE_Z + z;
                long h = mix(wx, wz, seed);
                long roll = (h >>> 17) % TREE_ROLL_RANGE;
                if (roll >= (long) biome.treesPer128 * TREE_DENSITY_NUM)
                    continue;
                boolean inside = x >= 0 && x < Chunk.SIZE_X && z >= 0 && z < Chunk.SIZE_Z;
                int height = inside ? heights[x][z] : columnHeight(wx, wz, grid, gi, gj);
                if (height <= SEA_LEVEL + 1)
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
                    case NONE   -> { }
                }
            }
        }

        // Pass 4: постройки — последними. Строение имеет право снести
        // дерево, выросшее на его месте, но не наоборот.
        Structures.place(c, heights, seed, SEA_LEVEL);

        c.computeSkyLight();
        // Проходы генерации пишут блоки и через set(), и массивами; список
        // излучателей собирается один раз здесь, в фоновом потоке генерации,
        // а не перебором куба в кадре.
        c.rebuildEmitters();
        c.markDirty();
        return c;
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

    public void floodFillAdd(int wx, int wy, int wz) {
        int emitted = getBlock(wx, wy, wz).emittedLight;
        if (emitted <= 0)
            return;
        setBlockLightWorld(wx, wy, wz, emitted);

        int[][] dirs = { { 1, 0, 0 }, { -1, 0, 0 }, { 0, 1, 0 }, { 0, -1, 0 }, { 0, 0, 1 }, { 0, 0, -1 } };
        Queue<int[]> queue = new ArrayDeque<>();
        if (emitted > 1)
            for (int[] d : dirs)
                enqueueBlockLight(queue, wx + d[0], wy + d[1], wz + d[2], emitted - 1);

        while (!queue.isEmpty()) {
            int[] cur = queue.poll();
            int x = cur[0], y = cur[1], z = cur[2], val = cur[3];
            if (val > 1)
                for (int[] d : dirs)
                    enqueueBlockLight(queue, x + d[0], y + d[1], z + d[2], val - 1);
        }
    }

    private void enqueueBlockLight(Queue<int[]> queue, int wx, int wy, int wz, int val) {
        if (wy < 0 || wy >= Chunk.SIZE_Y)
            return;
        int cx = Math.floorDiv(wx, Chunk.SIZE_X);
        int cz = Math.floorDiv(wz, Chunk.SIZE_Z);
        Chunk c = getChunkIfExists(cx, cz);
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
        queue.add(new int[] { wx, wy, wz, val });
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
        Queue<int[]> queue = new ArrayDeque<>();
        int[][] dirs = { {1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1} };

        Chunk nPX = getChunkIfExists(cx + 1, cz);
        if (nPX != null)
            for (int y = 0; y < Chunk.SIZE_Y; y++)
                for (int lz = 0; lz < Chunk.SIZE_Z; lz++) {
                    int nv = nPX.getBlockLight(0, y, lz);
                    if (nv > 1) enqueueBlockLight(queue, bx + Chunk.SIZE_X - 1, y, bz + lz, nv - 1);
                }
        Chunk nNX = getChunkIfExists(cx - 1, cz);
        if (nNX != null)
            for (int y = 0; y < Chunk.SIZE_Y; y++)
                for (int lz = 0; lz < Chunk.SIZE_Z; lz++) {
                    int nv = nNX.getBlockLight(Chunk.SIZE_X - 1, y, lz);
                    if (nv > 1) enqueueBlockLight(queue, bx, y, bz + lz, nv - 1);
                }
        Chunk nPZ = getChunkIfExists(cx, cz + 1);
        if (nPZ != null)
            for (int y = 0; y < Chunk.SIZE_Y; y++)
                for (int lx = 0; lx < Chunk.SIZE_X; lx++) {
                    int nv = nPZ.getBlockLight(lx, y, 0);
                    if (nv > 1) enqueueBlockLight(queue, bx + lx, y, bz + Chunk.SIZE_Z - 1, nv - 1);
                }
        Chunk nNZ = getChunkIfExists(cx, cz - 1);
        if (nNZ != null)
            for (int y = 0; y < Chunk.SIZE_Y; y++)
                for (int lx = 0; lx < Chunk.SIZE_X; lx++) {
                    int nv = nNZ.getBlockLight(lx, y, Chunk.SIZE_Z - 1);
                    if (nv > 1) enqueueBlockLight(queue, bx + lx, y, bz, nv - 1);
                }

        while (!queue.isEmpty()) {
            int[] cur = queue.poll();
            int x = cur[0], y = cur[1], z = cur[2], val = cur[3];
            if (val > 1)
                for (int[] d : dirs)
                    enqueueBlockLight(queue, x + d[0], y + d[1], z + d[2], val - 1);
        }
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
     */
    public void floodFillRemove(int wx, int wy, int wz) {
        final int R = 15;
        List<int[]> sources = emittersNear(wx, wy, wz, R);
        // Снятый факел в список уже не попадает — его блок заменён до этого
        // вызова, но его свет в буфере остался и обязан быть погашен. Без этой
        // проверки последний снятый источник светил бы вечно.
        if (sources.isEmpty() && getBlockLightWorld(wx, wy, wz) == 0)
            return;
        for (int x = wx - R; x <= wx + R; x++) {
            for (int y = Math.max(0, wy - R); y <= Math.min(Chunk.SIZE_Y - 1, wy + R); y++) {
                for (int z = wz - R; z <= wz + R; z++) {
                    if (getBlockLightWorld(x, y, z) > 0)
                        setBlockLightWorld(x, y, z, 0);
                }
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
        if (opacityChanged)
            c.computeSkyLight();
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
        setBlock(wx, wy, wz, t);
        if (wy < 0 || wy >= Chunk.SIZE_Y)
            return;
        int cx = Math.floorDiv(wx, Chunk.SIZE_X);
        int cz = Math.floorDiv(wz, Chunk.SIZE_Z);
        Chunk c = getChunkIfExists(cx, cz);
        if (c == null)
            return;
        c.setMeta(Math.floorMod(wx, Chunk.SIZE_X), wy, Math.floorMod(wz, Chunk.SIZE_Z), meta);
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
        c.setMeta(lx, wy, lz, (byte) Math.max(0, Math.min(7, level)));
        c.modified = true;
        c.markDirty();
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

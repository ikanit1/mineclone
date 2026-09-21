package com.mineclone.world;

/**
 * Рукотворные постройки в мире: руины, хижина, обелиск.
 *
 * Шаблон — таблица символов по слоям, прямо в коде. Файлами это было бы
 * «красивее», но добавило бы чтение с диска в горячий путь генерации,
 * который крутится на фоновых потоках, и целую ветку обработки «файла нет».
 *
 * Размещение — чистая функция сида и координат чанка, поэтому в сейве ничего
 * не хранится и старые миры продолжают открываться. Тот же приём, которым
 * сделаны биомы и пещеры.
 *
 * Ограничение, про которое надо помнить: постройка целиком живёт внутри
 * одного чанка. Генерация пишет только в свой чанк, и строение на границе
 * обрезалось бы ровно посередине. Отсюда потолок 9x9 на след.
 */
public final class Structures {

    /** Символы шаблона: '.' — не трогать рельеф, '_' — расчистить до воздуха. */
    private static final char KEEP = '.';
    private static final char CLEAR = '_';

    /**
     * @param layers  слои снизу вверх; layers[y][z] — строка длиной w по оси X
     */
    private record Template(String[][] layers) {}

    private static final String[][] RUIN = {
            { "CCCCC",
              "CCCCC",
              "CCCCC",
              "CCCCC",
              "CCCCC" },
            { "CCCCC",
              "C___C",
              "C___C",
              "C___C",
              "CC_CC" },
            { "C_C_C",
              "C___C",
              "____C",
              "C___C",
              "C___C" },
            { "C___C",
              "_____",
              "_____",
              "_____",
              "C___C" },
    };

    /**
     * Углы хижины — камень, а не бревно. Бревно в этом мире означает ровно
     * одно: ствол дерева. Инвариант «ствол стоит на дёрне» проверяется тестом,
     * и деревянный столб на дощатом полу его ломает.
     */
    private static final String[][] HUT = {
            { "PPPPP",
              "PPPPP",
              "PPPPP",
              "PPPPP",
              "PPPPP" },
            { "CPPPC",
              "P___P",
              "P___P",
              "P___P",
              "CP_PC" },
            { "CPGPC",
              "P___P",
              "G___G",
              "P___P",
              "CP_PC" },
            { "PPPPP",
              "PPPPP",
              "PPPPP",
              "PPPPP",
              "PPPPP" },
    };

    private static final String[][] OBELISK = {
            { "SSS", "SSS", "SSS" },
            { "...", ".S.", "..." },
            { "...", ".S.", "..." },
            { "...", ".S.", "..." },
            { "...", ".S.", "..." },
            { "SSS", "SSS", "SSS" },
            { "...", ".T.", "..." },
    };

    /** Небольшая подземная камера: мох у пола, паутина и найденный дневник. */
    private static final String[][] DUNGEON = {
            { "MMMMMMM", "MCCCCCM", "MCCCCCM", "MCCCCCM", "MCCCCCM", "MCCCCCM", "MMMMMMM" },
            { "MMMMMMM", "M_____M", "M_Y___M", "M_____M", "M___J_M", "M_____M", "MMM_MMM" },
            { "MCC_CCM", "M_____M", "Y_____M", "M_____M", "M_____Y", "M_____M", "MCC_CCM" },
            { "MMMMMMM", "MMMMMMM", "MMCCCMM", "MMCCCMM", "MMCCCMM", "MMMMMMM", "MMMMMMM" },
    };

    private static final Template[] ALL = {
            new Template(RUIN),
            new Template(HUT),
            new Template(OBELISK),
            new Template(DUNGEON),
    };

    /** One candidate per 160x160 region, with a gap of at least seven chunks. */
    public static final int REGION_CHUNKS = 10;
    public static final int MIN_CHUNK_GAP = 7;
    public record Site(int kind, int x, int y, int z) {}

    private Structures() {}

    /**
     * Ставит постройку в чанк, если этому чанку выпало. Зовётся последним
     * проходом генерации. Место под наземную постройку резервируется до деревьев.
     *
     * @param heights высоты поверхности из первого прохода
     */
    public static void place(Chunk chunk, int[][] heights, long seed, int seaLevel) {
        place(chunk, plan(chunk, heights, seed, seaLevel), seed);
    }

    /** Pure region selection; floorDiv is essential on the negative half of the world. */
    public static int candidateKind(int cx, int cz, long seed) {
        int rx = Math.floorDiv(cx, REGION_CHUNKS), rz = Math.floorDiv(cz, REGION_CHUNKS);
        long h = hash(rx, rz, seed ^ 0x535452554354L);
        if ((h & 3) == 0) return -1;
        int x = rx * REGION_CHUNKS + 3 + (int) ((h >>> 9) % 4);
        int z = rz * REGION_CHUNKS + 3 + (int) ((h >>> 25) % 4);
        return cx == x && cz == z ? (int) ((h >>> 43) % ALL.length) : -1;
    }

    /** Tree reservation is coordinate-only, including crowns from neighbouring chunks. */
    public static boolean reservesTrees(int wx, int wz, long seed) {
        int cx = Math.floorDiv(wx, 16), cz = Math.floorDiv(wz, 16);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            int sx = cx + dx, sz = cz + dz;
            int kind = candidateKind(sx, sz, seed);
            if (kind >= 0 && kind != 3 && wx >= sx * 16 - 3 && wx <= sx * 16 + 18
                    && wz >= sz * 16 - 3 && wz <= sz * 16 + 18) return true;
        }
        return false;
    }

    public static Site plan(Chunk chunk, int[][] heights, long seed, int seaLevel) {
        int kind = candidateKind(chunk.cx, chunk.cz, seed);
        if (kind < 0) return null;
        Template t = ALL[kind];
        int w = t.layers()[0][0].length(), d = t.layers()[0].length;
        for (int attempt = 0; attempt < 8; attempt++) {
            long bits = hash(chunk.cx, chunk.cz, seed ^ (0x9E3779B97F4A7C15L * (attempt + 1)));
            int x = 2 + (int) (bits % (16 - w - 3));
            int z = 2 + (int) ((bits >>> 19) % (16 - d - 3));
            int y = flatBase(heights, x, z, w, d);
            if (kind == 3) {
                int min = 127;
                for (int dx = 0; dx < w; dx++) for (int dz = 0; dz < d; dz++)
                    min = Math.min(min, heights[x + dx][z + dz]);
                y = min - 10 - (int) ((bits >>> 35) % 14);
                if (y < 5) continue;
            } else if (y < seaLevel + 2 || y + t.layers().length + 1 >= Chunk.SIZE_Y) continue;
            boolean sound = true;
            for (int dx = 0; dx < w && sound; dx++) for (int dz = 0; dz < d && sound; dz++) {
                int ground = kind == 3 ? y - 1 : heights[x + dx][z + dz];
                // Reject cave mouths, water, ice and unsupported slopes.
                for (int sy = ground; sy >= ground - 2; sy--) {
                    BlockType b = chunk.get(x + dx, sy, z + dz);
                    if (!b.solid || b == BlockType.ICE || b == BlockType.THIN_ICE) sound = false;
                }
            }
            if (sound) return new Site(kind, x, y, z);
        }
        return null;
    }

    public static void place(Chunk chunk, Site site, long seed) {
        if (site == null) return;
        Template t = ALL[site.kind];
        int x0 = site.x, z0 = site.z, base = site.y;
        int w = t.layers()[0][0].length(), d = t.layers()[0].length;
        if (site.kind != 3) {
            for (int x = x0; x < x0 + w; x++) for (int z = z0; z < z0 + d; z++) {
                for (int y = base + 1; y < Chunk.SIZE_Y; y++) chunk.set(x, y, z, BlockType.AIR);
                for (int y = base - 1; y >= base - 3; y--) {
                    if (chunk.get(x, y, z).solid) break;
                    chunk.set(x, y, z, BlockType.COBBLE);
                }
            }
        }
        for (int y = 0; y < t.layers().length; y++)
            for (int z = 0; z < d; z++) {
                String row = t.layers()[y][z];
                for (int x = 0; x < w; x++) {
                    char ch = row.charAt(x);
                    if (ch == KEEP)
                        continue;
                    // Руина стоит не первый век: часть кладки выкрошилась.
                    // Пол не трогаем — иначе постройка проваливается.
                    if (site.kind == 0 && y > 0 && ch == 'C') {
                        boolean missing = (hash(chunk.cx * 16 + x0 + x, chunk.cz * 16 + z0 + z,
                                seed ^ (y * 31L)) & 7) == 0;
                        if (missing || !chunk.get(x0 + x, base + y - 1, z0 + z).solid)
                            ch = CLEAR;
                    }
                    chunk.set(x0 + x, base + y, z0 + z, decode(ch));
                }
            }

        // A floor torch is valid inside a room. Never replace the obelisk's stone pillar.
        if (site.kind != 2)
            chunk.set(x0 + w / 2, base + 1, z0 + d / 2, BlockType.TORCH);
    }

    /**
     * Уровень, на который садится пол. Постройка ставится только на ровное
     * место: иначе половина стены висит в воздухе, а вторая уходит в холм.
     *
     * @return высота пола или -1, если площадка слишком неровная
     */
    static int flatBase(int[][] heights, int x0, int z0, int w, int d) {
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int x = x0; x < x0 + w; x++)
            for (int z = z0; z < z0 + d; z++) {
                int y = heights[x][z];
                min = Math.min(min, y);
                max = Math.max(max, y);
            }
        // Перепад в два блока переживём: недостающее подсыпается грунтом.
        // Дальше уже не постройка, а стена, наполовину ушедшая в холм.
        return (max - min) <= 2 ? max : -1;
    }

    static BlockType decode(char ch) {
        return switch (ch) {
            case CLEAR -> BlockType.AIR;
            case 'C' -> BlockType.COBBLE;
            case 'S' -> BlockType.STONE;
            case 'P' -> BlockType.PLANKS;
            case 'W' -> BlockType.WOOD;
            case 'G' -> BlockType.GLASS;
            case 'T' -> BlockType.TORCH;
            case 'M' -> BlockType.MOSSY_COBBLE;
            case 'Y' -> BlockType.WEB;
            case 'J' -> BlockType.JOURNAL;
            default -> BlockType.AIR;
        };
    }

    /** Тот же перемешиватель, что у растительности: разносит соседние клетки. */
    static long hash(int a, int b, long seed) {
        long v = a * 341873128712L + b * 132897987541L + seed;
        v ^= v >>> 33;
        v *= 0xff51afd7ed558ccdL;
        v ^= v >>> 33;
        v *= 0xc4ceb9fe1a85ec53L;
        v ^= v >>> 33;
        return v & Long.MAX_VALUE;
    }
}

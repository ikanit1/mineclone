package com.mineclone.world;

/**
 * Рукотворные постройки в мире: руины, хижина, обелиск.
 *
 * Шаблон — таблица символов по слоям, прямо в коде. Файлами это было бы
 * «красивее», но добавило бы чтение с диска в горячий путь генерации,
 * который крутится на фоновых потоках, и целую ветку обработки «файла нет».
 * Текстуры в этом проекте тоже описаны кодом — держим один стиль.
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
     * @param decay   выедать ли часть блоков, чтобы постройка выглядела руиной
     * @param rarity  один шанс из rarity на чанк
     */
    private record Template(String name, String[][] layers, boolean decay, int rarity) {}

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
            new Template("ruin", RUIN, true, 70),
            new Template("hut", HUT, false, 110),
            new Template("obelisk", OBELISK, false, 90),
            new Template("dungeon", DUNGEON, true, 82),
    };

    /** Куда ставить факел внутри — считается от шаблона, а не зашито в него. */
    private static final int TORCH_LAYER = 2;
    /** Сколько раз пробуем найти ровное пятно в чанке. */
    private static final int PLACE_ATTEMPTS = 12;
    /** На сколько блоков выше постройки расчищается пятно от деревьев. */
    private static final int TREE_CLEARANCE = 6;

    private Structures() {}

    /**
     * Ставит постройку в чанк, если этому чанку выпало. Зовётся последним
     * проходом генерации: строение имеет право снести дерево, выросшее на его
     * месте, но не наоборот.
     *
     * @param heights высоты поверхности из первого прохода
     */
    public static void place(Chunk chunk, int[][] heights, long seed, int seaLevel) {
        long h = hash(chunk.cx, chunk.cz, seed);
        Template t = pick(h);
        if (t == null)
            return;

        int w = t.layers()[0][0].length();
        int d = t.layers()[0].length;
        int margin = 2;
        int span = Chunk.SIZE_X - w - margin * 2;
        if (span < 1)
            return;

        // Несколько попыток на чанк. С одной попыткой постройки почти не
        // появляются: пятно 5x5 ровным в пределах пары блоков бывает редко,
        // и единственный бросок почти всегда падает на склон.
        int x0 = -1, z0 = -1, base = -1;
        for (int attempt = 0; attempt < PLACE_ATTEMPTS; attempt++) {
            long bits = h >>> (attempt * 5);
            int tx = margin + (int) ((bits >>> 20) % span);
            int tz = margin + (int) ((bits >>> 34) % span);
            int b = flatBase(heights, tx, tz, w, d);
            if (b >= seaLevel + 2 && b + t.layers().length < Chunk.SIZE_Y - 2) {
                x0 = tx;
                z0 = tz;
                base = b;
                break;
            }
        }
        if (base < 0)
            return;

        // Расчищаем пятно до неба: иначе дерево, выросшее здесь третьим
        // проходом, останется торчать сквозь крышу, а его ствол окажется
        // стоящим на досках пола.
        int clearTop = base + t.layers().length + TREE_CLEARANCE;
        for (int x = x0; x < x0 + w; x++)
            for (int z = z0; z < z0 + d; z++) {
                for (int y = base + 1; y < Math.min(Chunk.SIZE_Y, clearTop); y++)
                    chunk.set(x, y, z, BlockType.AIR);
                // И подсыпаем грунт там, где склон ниже пола, иначе постройка
                // стоит на сваях из воздуха.
                for (int y = heights[x][z] + 1; y <= base; y++)
                    chunk.set(x, y, z, BlockType.DIRT);
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
                    if (t.decay() && y > 0 && (hash(x0 + x, z0 + z, seed ^ (y * 31L)) & 7) == 0)
                        continue;
                    chunk.set(x0 + x, base + y, z0 + z, decode(ch));
                }
            }

        // Факел в центре: постройку должно быть видно ночью, и внутрь неё не
        // должны заселяться мобы.
        if (t.layers().length > TORCH_LAYER)
            chunk.set(x0 + w / 2, base + TORCH_LAYER, z0 + d / 2, BlockType.TORCH);
    }

    /** Какой шаблон выпал этому чанку, или null — ничего. */
    static Template pick(long h) {
        for (int i = 0; i < ALL.length; i++) {
            // Свой разряд хэша на каждый шаблон: общий счётчик выстроил бы
            // постройки в решётку.
            long bits = h >>> (i * 13);
            if (bits % ALL[i].rarity() == 0)
                return ALL[i];
        }
        return null;
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

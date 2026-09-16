package com.mineclone.world;

import java.util.Random;

/**
 * Жилы руды в камне.
 *
 * Каждая попытка — случайная точка в своей высотной полосе и короткое
 * случайное блуждание от неё: так жила получается рваной кляксой, а не
 * кубом. Руда ставится только вместо камня, поэтому в уже прорезанной
 * пещере жил не будет — порядок проходов в {@link World} это гарантирует.
 *
 * Генератор детерминирован по (сид, координаты чанка): в сейве ничего не
 * хранится, и один и тот же сид всегда даёт одну и ту же карту руд.
 */
public final class OreGenerator {

    /**
     * @param attempts сколько жил пытаться поставить на чанк
     * @param minSize  минимальный размер жилы в блоках
     */
    private record Vein(BlockType block, int minY, int maxY, int attempts, int minSize, int maxSize) {}

    /**
     * Глубина, редкость и размер. Уголь встречается высоко и часто, алмаз —
     * у самого бедрока и почти никогда: именно этот разброс и заставляет
     * копать вниз.
     */
    private static final Vein[] VEINS = {
            new Vein(BlockType.COAL_ORE,    6, 96, 30, 5, 13),
            new Vein(BlockType.IRON_ORE,    4, 60, 16, 4,  9),
            new Vein(BlockType.GOLD_ORE,    2, 32,  3, 3,  7),
            new Vein(BlockType.DIAMOND_ORE, 2, 15,  1, 3,  6),
    };

    private OreGenerator() {}

    public static void place(Chunk chunk, long seed) {
        // Те же множители, что MC использует для по-чанковых потоков: они
        // достаточно разносят соседние чанки, чтобы жилы не выстраивались в сетку.
        Random rnd = new Random(seed ^ (chunk.cx * 341873128712L) ^ (chunk.cz * 132897987541L));
        for (Vein v : VEINS) {
            for (int attempt = 0; attempt < v.attempts(); attempt++) {
                int x = rnd.nextInt(Chunk.SIZE_X);
                int z = rnd.nextInt(Chunk.SIZE_Z);
                int y = v.minY() + rnd.nextInt(Math.max(1, v.maxY() - v.minY() + 1));
                int size = v.minSize() + rnd.nextInt(v.maxSize() - v.minSize() + 1);
                walk(chunk, rnd, v, x, y, z, size);
            }
        }
    }

    /**
     * Случайное блуждание от стартовой точки, заменяющее камень рудой.
     *
     * По вертикали блуждание зажато в полосу жилы: без зажима алмазная жила
     * уползает на десяток блоков вверх, и «копать глубже» перестаёт что-либо
     * значить.
     */
    private static void walk(Chunk chunk, Random rnd, Vein v,
                             int x, int y, int z, int size) {
        for (int i = 0; i < size; i++) {
            if (!chunk.inBounds(x, y, z))
                return;
            if (chunk.get(x, y, z) == BlockType.STONE)
                chunk.set(x, y, z, v.block());
            switch (rnd.nextInt(6)) {
                case 0 -> x++;
                case 1 -> x--;
                case 2 -> y = Math.min(v.maxY(), y + 1);
                case 3 -> y = Math.max(v.minY(), y - 1);
                case 4 -> z++;
                default -> z--;
            }
        }
    }
}

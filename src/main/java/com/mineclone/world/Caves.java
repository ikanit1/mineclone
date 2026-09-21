package com.mineclone.world;

/**
 * Вырезание пещер трёхмерным шумом.
 *
 * Тоннель — это место, где ОБА шумовых поля близки к нулю: пересечение двух
 * изоповерхностей даёт связные червоточины, а не пузыри, которые получаются
 * из одного поля с порогом. По вертикали частота выше, чем по горизонтали,
 * поэтому ходы получаются пологими, а не колодцами.
 *
 * Поля — чистая функция мировых координат и сида, поэтому пещера не может
 * оборваться на границе чанка и ничего не нужно хранить в сейве.
 *
 * Порядок в генерации критичен: рельеф → пещеры → руды → растительность.
 * Если резать после растительности, деревья повиснут в воздухе; если ставить
 * руду до пещер, половина жил уедет в пустоту.
 */
public final class Caves {

    /** Радиус в пространстве (шумA, шумB), внутри которого блок вырезается. */
    private static final double THRESHOLD = 0.115;
    private static final double FREQ_H = 0.0215;
    /**
     * По вертикали чуть чаще горизонтали — ходы стелются вдоль, а не бурят
     * вниз. Сильнее 1.5x делать нельзя: на 2.2x тоннели вырождаются в плоские
     * линзы, которые в разрезе читаются как трещины, а не как пещеры.
     */
    private static final double FREQ_V = 0.032;

    /** Ниже этого уровня не режем: под ногами должен остаться пол над бедроком. */
    public static final int MIN_Y = 2;
    /**
     * Насколько толстым должно остаться дно под водой. Пробитое дно океана
     * осушает его целиком через WaterSimulator — дороже любой красивой пещеры.
     */
    private static final int SEABED_GUARD = 3;

    private final PerlinNoise fieldA;
    private final PerlinNoise fieldB;

    public Caves(long seed) {
        this.fieldA = new PerlinNoise(seed ^ 0x5DEECE66DL);
        this.fieldB = new PerlinNoise(seed ^ 0x2545F4914F6CDD1DL);
    }

    /** Попадает ли мировая точка внутрь тоннеля. */
    public boolean isCave(int wx, int wy, int wz) {
        double a = fieldA.noise(wx * FREQ_H, wy * FREQ_V, wz * FREQ_H);
        double b = fieldB.noise(wx * FREQ_H, wy * FREQ_V, wz * FREQ_H);
        return a * a + b * b < THRESHOLD * THRESHOLD;
    }

    /**
     * Прорезает пещеры в уже сгенерированном рельефе чанка.
     *
     * @param heights высота поверхности по колонкам, из первого прохода
     */
    public void carve(Chunk chunk, int[][] heights, int seaLevel) {
        int baseX = chunk.cx * Chunk.SIZE_X;
        int baseZ = chunk.cz * Chunk.SIZE_Z;
        for (int x = 0; x < Chunk.SIZE_X; x++) {
            for (int z = 0; z < Chunk.SIZE_Z; z++) {
                int height = heights[x][z];
                boolean underwater = height <= seaLevel + 1;
                // Под водой оставляем толстое дно; на суше можно резать до самой
                // поверхности — так появляются входы в пещеры.
                int top = underwater ? height - SEABED_GUARD : height;
                for (int y = MIN_Y; y <= top; y++) {
                    if (!carvable(chunk.get(x, y, z)))
                        continue;
                    if (isCave(baseX + x, y, baseZ + z))
                        chunk.set(x, y, z, BlockType.AIR);
                }
            }
        }
    }

    /** Режем только сам рельеф: воду и бедрок трогать нельзя. */
    private static boolean carvable(BlockType b) {
        return switch (b) {
            case STONE, DIRT, GRASS, SAND, SNOWY_GRASS, PODZOL, PEAT, MUD,
                    DRY_GRASS, RED_SAND, TERRACOTTA, LIMESTONE, BASALT, ASH, GRAVEL -> true;
            default -> false;
        };
    }
}

package com.mineclone.world;

/**
 * Реки и озёра: размыв колонны рельефа до уровня воды.
 *
 * Рек как отдельного блока не существует — русло получается тем, что колонна
 * опускается ниже уровня моря, а вода в неё наливается тем же правилом
 * {@code y <= SEA_LEVEL}, которым наполняется океан. Поэтому реки ничего не
 * ломают: ни мешер, ни симулятор воды, ни сохранение о них не знают.
 *
 * Детерминировано по сиду и позиции, ничего не хранится.
 */
public final class Rivers {

    /** Масштаб русла: чем меньше, тем длиннее и плавнее излучины. */
    private static final double FREQ = 0.0026;
    /** Полуширина русла в единицах шума — внутри него размыв полный. */
    private static final double WIDTH = 0.030;
    /** Где русло переходит в берег; между WIDTH и BANK размыв спадает. */
    private static final double BANK = 0.105;
    /** На сколько дно реки ниже уровня моря. */
    public static final int DEPTH = 3;
    /**
     * На сколько над уровнем моря река ещё способна пробиться.
     *
     * Без этого предела река режет сквозь горы отвесным каньоном: русло
     * задано двумерным шумом и про высоту ничего не знает. Реки бывают в
     * долинах — значит, с высотой размыв обязан затухать.
     */
    private static final int MAX_ABOVE = 15;

    /** Масштаб озёрных пятен: крупнее рек и без вытянутости. */
    private static final double LAKE_FREQ = 0.0075;
    /** Порог шума, выше которого начинается озеро. */
    private static final double LAKE_THRESHOLD = 0.42;
    /** На сколько дно озера ниже уровня моря. */
    public static final int LAKE_DEPTH = 4;
    /** Выше этого над морем озёр не бывает — им неоткуда наполниться. */
    private static final int LAKE_MAX_ABOVE = 9;

    private final PerlinNoise river;
    private final PerlinNoise lake;

    public Rivers(long seed) {
        this.river = new PerlinNoise(seed ^ 0x1F123BB5L);
        this.lake = new PerlinNoise(seed ^ 0x7A3C59E1L);
    }

    /**
     * Сила размыва руслом в точке, 0..1.
     *
     * Русло — это нулевая линия шума, а не его пик: {@code abs(n)} рядом с
     * нулём даёт узкую извилистую ленту, тогда как порог по самому шуму дал
     * бы пятна.
     */
    public float riverStrength(int wx, int wz) {
        double n = river.fbm(wx * FREQ, wz * FREQ, 3, 2.0, 0.5);
        double a = Math.abs(n);
        if (a >= BANK)
            return 0f;
        if (a <= WIDTH)
            return 1f;
        return 1f - smoothstep((float) ((a - WIDTH) / (BANK - WIDTH)));
    }

    /** Сила размыва озером в точке, 0..1. */
    public float lakeStrength(int wx, int wz) {
        double n = lake.fbm(wx * LAKE_FREQ, wz * LAKE_FREQ, 3, 2.0, 0.5);
        if (n <= LAKE_THRESHOLD)
            return 0f;
        return smoothstep((float) Math.min(1.0, (n - LAKE_THRESHOLD) / 0.18));
    }

    /**
     * Новая высота колонны после размыва.
     *
     * @param height    высота из шумов рельефа
     * @param seaLevel  уровень воды
     * @return высота не выше исходной; вода нальётся сама, если стало ниже моря
     */
    public int carve(int wx, int wz, int height, int seaLevel) {
        int out = height;
        out = Math.min(out, lower(wx, wz, height, seaLevel - DEPTH,
                riverStrength(wx, wz), MAX_ABOVE, seaLevel));
        out = Math.min(out, lower(wx, wz, height, seaLevel - LAKE_DEPTH,
                lakeStrength(wx, wz), LAKE_MAX_ABOVE, seaLevel));
        return out;
    }

    /** Есть ли здесь вода из-за размыва — для тестов и растительности. */
    public boolean isWaterCarved(int wx, int wz, int height, int seaLevel) {
        return carve(wx, wz, height, seaLevel) < height
                && carve(wx, wz, height, seaLevel) < seaLevel;
    }

    private static int lower(int wx, int wz, int height, int target, float strength,
                             int maxAbove, int seaLevel) {
        if (strength <= 0f || height <= target)
            return height;
        // Затухание по высоте: в горах размыва почти нет.
        float alt = (height - seaLevel) / (float) maxAbove;
        if (alt >= 1f)
            return height;
        if (alt > 0f)
            strength *= 1f - smoothstep(alt);
        int lowered = Math.round(height + (target - height) * strength);
        return Math.max(target, Math.min(height, lowered));
    }

    private static float smoothstep(float t) {
        float k = t < 0f ? 0f : (t > 1f ? 1f : t);
        return k * k * (3f - 2f * k);
    }
}

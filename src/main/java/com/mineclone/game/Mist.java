package com.mineclone.game;

import com.mineclone.world.Biome;

/**
 * Сколько мглы в воздухе: низовой туман и дневная дымка.
 *
 * Низовой туман копится ночью и достигает пика вскоре после восхода — утренняя
 * мгла над лесом и рекой; к полудню его нет. Дымка держится весь день и почти
 * не видна сама по себе, но именно в ней под кронами встают столбы света.
 * Лес туманнее равнины, пустыня суше всех.
 *
 * Чистые функции — проверяются тестом.
 */
public final class Mist {
    private Mist() {}

    private static final double CYCLE = Math.PI * 2.0;
    /** Где после восхода пик утренней мглы и сколько она держится, радианы игрового времени. */
    private static final float MORNING_PEAK = 0.20f, MORNING_WIDTH = 0.60f;

    /** Множитель мглы по биому. */
    public static float biomeFactor(Biome b) {
        return switch (b) {
            case FOREST, TAIGA -> 1.6f;
            case SWAMP -> 2.3f;
            case OCEAN -> 1.3f;
            case TUNDRA, ALPINE -> 1.2f;
            case PLAINS, SAVANNA -> 1.0f;
            case DESERT, BADLANDS -> 0.15f;
            case VOLCANIC -> 0.65f;
        };
    }

    /** Утренний горб 0..1: ноль ночью и днём, единица чуть после восхода. */
    public static float morning(float gameTime) {
        double t = gameTime % CYCLE;
        if (t < 0)
            t += CYCLE;
        double d = t - MORNING_PEAK;
        if (d > Math.PI)
            d -= CYCLE;               // перед восходом тоже утро
        float k = 1f - (float) Math.abs(d) / MORNING_WIDTH;
        return k <= 0f ? 0f : k * k;
    }

    /** Плотность низового тумана у земли. */
    public static float groundDensity(float gameTime, float daylight, float precipitation, Biome b) {
        float night = Math.max(0f, Math.min(1f, (0.55f - daylight) / 0.45f));
        float base = 0.018f * night * night + 0.034f * morning(gameTime);
        base *= 0.75f + 0.55f * precipitation;
        return base * biomeFactor(b);
    }

    /** Равномерная дымка на любой высоте. */
    public static float haze(float precipitation, Biome b) {
        float h = b == Biome.SWAMP ? 0.0036f : b == Biome.FOREST || b == Biome.TAIGA ? 0.0024f : 0.0009f;
        if (b.isArid())
            h = 0.0003f;
        return h + 0.0020f * precipitation;
    }
}

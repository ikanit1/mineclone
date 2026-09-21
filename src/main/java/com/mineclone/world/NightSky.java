package com.mineclone.world;

/**
 * Ночное небо: фазы луны и северное сияние.
 *
 * Обе вещи — чистые функции игрового времени и сида. Фаза луны зависит от
 * номера суток, поэтому игровое время больше не нормализуется в один оборот
 * при {@code /time set}: иначе каждая команда времени возвращала бы полнолуние.
 *
 * Соглашение времени то же, что у светила: {@code gameTime = 0} — восход,
 * {@code pi/2} — полдень, ночь идёт на второй половине оборота.
 */
public final class NightSky {
    private NightSky() {}

    public static final double CYCLE = Math.PI * 2.0;
    /** Фаз в лунном месяце. Ноль — полнолуние, четыре — новолуние. */
    public static final int PHASES = 8;
    /** Доля ночей, в которые вообще бывает сияние. */
    public static final float AURORA_CHANCE = 0.42f;
    /** Ослабление сияния вне тундры: в тёплых широтах оно бледный отсвет у горизонта. */
    public static final float AURORA_WARM_BIOME = 0.30f;

    /** Номер игровых суток; меняется на восходе. */
    public static long dayIndex(float gameTime) {
        return (long) Math.floor(gameTime / CYCLE);
    }

    /**
     * Перевод часов внутри текущих суток: время суток меняется, номер суток —
     * нет. Именно так должна работать {@code /time set}: иначе команда
     * возвращала бы мир в нулевые сутки, а с ними — луну в полнолуние и погоду
     * в первый фронт.
     *
     * @param timeOfDay желаемое время суток, радианы 0..2pi
     */
    public static float withTimeOfDay(float gameTime, float timeOfDay) {
        double t = timeOfDay % CYCLE;
        if (t < 0)
            t += CYCLE;
        return (float) (dayIndex(gameTime) * CYCLE + t);
    }

    /** Фаза луны в эту ночь: 0 полнолуние … 4 новолуние … 7 почти полная. */
    public static int moonPhase(float gameTime) {
        return (int) Math.floorMod(dayIndex(gameTime), (long) PHASES);
    }

    /** Освещённая доля диска 0..1. */
    public static float moonIllumination(int phase) {
        return (float) ((1.0 + Math.cos(phase * CYCLE / PHASES)) * 0.5);
    }

    /**
     * Множитель ночного света от луны.
     *
     * Новолуние не гасит мир в ноль: звёзды и отражённый свет неба остаются,
     * а совсем чёрная ночь на поверхности непроходима без факела — это уже не
     * атмосфера, а наказание.
     */
    public static float moonlight(int phase) {
        return 0.32f + 0.68f * moonIllumination(phase);
    }

    /**
     * Насколько сейчас «глубокая ночь» 0..1: ноль в сумерках, единица в полночь.
     * Сияние и звёзды разгораются по этой кривой, а не включаются ступенькой.
     */
    public static float nightDepth(float gameTime) {
        double t = gameTime % CYCLE;
        if (t < 0)
            t += CYCLE;
        if (t < Math.PI)
            return 0f;
        float s = (float) Math.sin(t - Math.PI);
        return s * s * (3f - 2f * s);
    }

    /**
     * Активность сияния в эту ночь, 0..1, без учёта облаков и биома.
     *
     * Решается броском по номеру суток: сияние — событие, и если оно горит
     * каждую ночь, на него перестают поднимать голову.
     */
    public static float auroraActivity(long seed, float gameTime) {
        long day = dayIndex(gameTime);
        long h = (seed ^ 0xA0B0C0D0L) * 0x9E3779B97F4A7C15L + day * 0xD1B54A32D192ED03L;
        h ^= h >>> 31;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 29;
        float roll = (float) ((h >>> 11) & 0xFFFFFFL) / 0x1000000;
        if (roll >= AURORA_CHANCE)
            return 0f;
        // Сила ночи тоже своя: не каждое сияние в полнеба.
        float power = 0.45f + 0.55f * (roll / AURORA_CHANCE);
        return power * nightDepth(gameTime);
    }

    /** Итоговая яркость сияния с поправкой на биом и облака. */
    public static float auroraStrength(long seed, float gameTime, Biome biome, float cloudiness) {
        float biomeK = biome.isCold() ? 1f : AURORA_WARM_BIOME;
        float clear = Math.max(0f, 1f - cloudiness * 1.25f);
        return auroraActivity(seed, gameTime) * biomeK * clear;
    }
}

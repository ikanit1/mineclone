package com.mineclone.world;

/**
 * Погода: общий на весь мир график атмосферных фронтов.
 *
 * Время режется на фронты длиной {@link #FRONT_LENGTH}; каждый фронт по сиду
 * выбирает себе вид — ясно, облачно, морось, ливень или буря. Соседние фронты
 * не переключаются ступенькой, а перетекают друг в друга за
 * {@link #TRANSITION}: небо затягивает, ветер крепнет, и только потом идёт
 * дождь. Вид осадков фронт не решает — его решает биом под ногами: одна и та
 * же буря в лесу льёт ливнем с грозой, а в тундре становится метелью.
 *
 * Чистая функция от (сид, время): никакого состояния, поэтому погоду не нужно
 * сохранять — она восстанавливается из игрового времени, и проверяется
 * обычными тестами.
 */
public final class Weather {
    private Weather() {}

    /** Вид погоды фронта. Числа — установившиеся значения внутри фронта. */
    public enum Kind {
        //       осадки  облачность  ветер  буря
        CLEAR   (0.00f,  0.00f,      0.5f,  0f),
        CLOUDY  (0.00f,  0.60f,      1.1f,  0f),
        LIGHT   (0.40f,  0.78f,      1.3f,  0f),
        HEAVY   (0.80f,  0.92f,      2.3f,  0.3f),
        STORM   (1.00f,  1.00f,      4.2f,  1f);

        /** Сила осадков 0..1. */
        public final float precipitation;
        /** Насколько затянуто небо 0..1: гасит солнце, звёзды и сияние. */
        public final float cloudiness;
        /** Средняя скорость ветра, блоков в секунду. */
        public final float wind;
        /** 0..1 — гроза в тёплом биоме, метель в холодном. */
        public final float storm;

        Kind(float precipitation, float cloudiness, float wind, float storm) {
            this.precipitation = precipitation;
            this.cloudiness = cloudiness;
            this.wind = wind;
            this.storm = storm;
        }
    }

    /** Погода в момент времени — уже со смешиванием соседних фронтов. */
    public record State(Kind kind, float precipitation, float cloudiness, float wind, float storm) {}

    /** Длина одного фронта, секунды. Около половины игровых суток. */
    public static final float FRONT_LENGTH = 300f;
    /** За сколько секунд прежняя погода перетекает в новую. */
    public static final float TRANSITION = 60f;
    /** Предел видимости в самую сильную метель. */
    public static final float MIN_VISIBILITY = 0.22f;
    /** Выше скольких блоков над морем осадки идут снегом в любом биоме. */
    public static final int SNOW_ALTITUDE = 40;

    /**
     * Веса видов, в процентах. Ясных и облачных фронтов больше половины:
     * погода, которая всё время портится, перестаёт быть событием.
     */
    private static final int[] WEIGHTS = { 36, 22, 20, 14, 8 };

    /** Вид погоды фронта. Первый фронт мира всегда ясный — мир встречает солнцем. */
    public static Kind kindAt(long seed, long front) {
        if (front <= 0)
            return Kind.CLEAR;
        int roll = (int) Math.floorMod(mix(seed ^ 0x5EA7EE5L, front), 100L);
        Kind[] kinds = Kind.values();
        for (int i = 0; i < kinds.length; i++) {
            roll -= WEIGHTS[i];
            if (roll < 0)
                return kinds[i];
        }
        return Kind.CLEAR;
    }

    /** Погода в момент {@code seconds} игрового времени. */
    public static State sample(long seed, float seconds) {
        long front = (long) Math.floor(seconds / FRONT_LENGTH);
        float local = seconds - front * FRONT_LENGTH;
        Kind prev = kindAt(seed, front - 1);
        Kind cur = kindAt(seed, front);
        float k = smooth(local / TRANSITION);
        return new State(cur,
                lerp(prev.precipitation, cur.precipitation, k),
                lerp(prev.cloudiness, cur.cloudiness, k),
                lerp(prev.wind, cur.wind, k),
                lerp(prev.storm, cur.storm, k));
    }

    /** Сила осадков 0..1 — то, что раньше отдавал простой цикл. */
    public static float intensity(long seed, float seconds) {
        return sample(seed, seconds).precipitation();
    }

    /** Бывают ли в биоме осадки вообще. */
    public static boolean precipitates(Biome biome) {
        return biome != Biome.DESERT;
    }

    /**
     * Идут ли осадки здесь снегом. Холодный биом — всегда, горы — выше
     * {@link #SNOW_ALTITUDE}: иначе на вершине в лесу лил бы дождь.
     */
    public static boolean snowsAt(Biome biome, float y) {
        return biome == Biome.TUNDRA || y > World.SEA_LEVEL + SNOW_ALTITUDE;
    }

    /**
     * Видимость 0..1 — множитель дальности тумана.
     *
     * Снег съедает видимость сильнее дождя: крупные хлопья и позёмка закрывают
     * горизонт, а дождь его только затягивает. Буря в тундре — метель, в
     * которой не видно дальше пары десятков блоков; ниже {@link #MIN_VISIBILITY}
     * не опускаемся, иначе игрок перестаёт видеть собственные ноги.
     */
    public static float visibility(float precipitation, float storm, boolean snow) {
        float loss = snow
                ? 0.52f * precipitation + 0.28f * storm
                : 0.30f * precipitation + 0.28f * storm;
        return Math.max(MIN_VISIBILITY, 1f - loss);
    }

    /**
     * Ветер кадра, блоков в секунду: {x, z}.
     *
     * Направление медленно гуляет по времени, поверх — порывы двумя
     * несинхронными гармониками. Общий на всю сцену: иначе снежинки летят
     * вразнобой и метель рассыпается в шум.
     */
    public static float[] wind(long seed, float seconds) {
        State s = sample(seed, seconds);
        double base = Math.floorMod(seed, 628L) / 100.0;
        double angle = base + seconds * 0.0021 + Math.sin(seconds * 0.013) * 0.6;
        double gust = 1.0 + 0.30 * Math.sin(seconds * 0.47) + 0.18 * Math.sin(seconds * 1.31 + 0.7);
        float speed = (float) (s.wind() * gust);
        return new float[] { (float) Math.cos(angle) * speed, (float) Math.sin(angle) * speed };
    }

    private static long mix(long seed, long v) {
        long h = seed * 0x9E3779B97F4A7C15L + v * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return h;
    }

    private static float smooth(float t) {
        float k = t < 0f ? 0f : (t > 1f ? 1f : t);
        return k * k * (3f - 2f * k);
    }

    private static float lerp(float a, float b, float k) {
        return a + (b - a) * k;
    }
}

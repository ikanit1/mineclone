package com.mineclone.audio;

import com.mineclone.world.Chunk;

import java.util.Random;

/**
 * Что и когда звучит фоном: пещера, дождь, гром, вода над головой.
 *
 * Класс решает **что играть**, а не как: OpenAL, файлы и позиции остаются
 * снаружи. Поэтому всё расписание проверяется обычными тестами — а расписание
 * здесь и есть вся механика: редкий звук в пещере работает только если он
 * действительно редкий и действительно только в пещере.
 */
public final class AmbientSound {

    /** Что играть в этом тике. */
    public enum Cue {
        NONE,
        /** Далёкий звук в темноте под землёй. */
        CAVE,
        /** Шум дождя. */
        RAIN,
        /** Раскат грома. */
        THUNDER,
        /** Гул под водой. */
        UNDERWATER,
        /** Пузыри и прочая мелочь под водой. */
        UNDERWATER_EXTRA,
        /** Всплеск при погружении. */
        WATER_ENTER,
        /** Всплеск при выныривании. */
        WATER_EXIT,
        /** Порыв ветра под открытым небом — в бурю и метель. */
        WIND
    }

    /** Ветер слабее этого (блоков в секунду) не слышен. */
    public static final float WIND_THRESHOLD = 2.6f;
    /** Пауза между порывами ветра. */
    public static final float WIND_MIN = 5f, WIND_MAX = 11f;

    /** Пауза между звуками пещеры. Редко — иначе они перестают пугать. */
    public static final float CAVE_MIN = 45f, CAVE_MAX = 160f;
    /** Пауза между порциями шума дождя. */
    public static final float RAIN_MIN = 7f, RAIN_MAX = 13f;
    /** Пауза между гулами под водой — примерно длина самого сэмпла. */
    public static final float WATER_LOOP = 7.5f;
    /** Пауза между мелкими подводными звуками. */
    public static final float WATER_EXTRA_MIN = 5f, WATER_EXTRA_MAX = 15f;
    /** Сила осадков, ниже которой дождя не слышно. */
    public static final float RAIN_THRESHOLD = 0.25f;
    /** Сила осадков, выше которой начинает греметь. */
    public static final float THUNDER_THRESHOLD = 0.75f;
    /** Вероятность, что очередная порция дождя окажется громом. */
    public static final float THUNDER_CHANCE = 0.16f;
    /** Небесный свет у головы, при котором дождя уже не слышно: под толщей породы. */
    public static final int RAIN_SKY_SILENT = 3;
    /** С этого небесного света дождь слышен в полную силу: небо или крона над головой. */
    public static final int RAIN_SKY_FULL = 12;

    private final Random rnd;
    private float caveTimer;
    private float rainTimer;
    private float waterLoopTimer;
    private float waterExtraTimer;
    private float windTimer = WIND_MIN;
    private boolean wasUnderwater;
    private boolean started;

    public AmbientSound(Random rnd) {
        this.rnd = rnd;
        this.caveTimer = range(CAVE_MIN, CAVE_MAX);
        this.rainTimer = range(RAIN_MIN, RAIN_MAX);
    }

    /**
     * Шаг расписания.
     *
     * Порядок приоритетов не случаен: под водой не слышно ни дождя, ни
     * пещеры, а дождь громче далёкого шороха в темноте.
     *
     * @param dark        под землёй и в темноте: небо не достаёт, свет низкий
     * @param underwater  голова игрока в воде
     * @param rain        сила осадков 0..1
     * @return что запустить сейчас; {@link Cue#NONE} — ничего
     */
    public Cue tick(float dt, boolean dark, boolean underwater, float rain) {
        return tick(dt, dark, underwater, rain, rain > THUNDER_THRESHOLD ? 1f : 0f, 0f, false);
    }

    /**
     * Шаг расписания с погодой фронта, без замера неба: тёмное место считается
     * глубиной под породой, светлое — открытым небом. Для тестов расписания.
     */
    public Cue tick(float dt, boolean dark, boolean underwater, float rain, float storm,
                    float wind, boolean outdoors) {
        return tick(dt, dark, underwater, rain, storm, wind, outdoors, dark ? 0 : Chunk.MAX_LIGHT);
    }

    /**
     * Сколько дождя доходит до ушей: осадки фронта, приглушённые тем, насколько
     * над головой открыто небо.
     *
     * Осадки считаются по биому и одинаковы в пещере и на поверхности над ней —
     * без этой поправки ливень наверху звучал в глубине пещеры. Небесный свет
     * ровно та величина, что нужна: под открытым небом он 15, под кроной
     * 13–14, у входа в пещеру спадает на блок за шаг, в глубине — ноль. Поэтому
     * у входа дождь затихает постепенно, а не выключается на пороге.
     */
    public static float heardRain(float rain, int skyLight) {
        float open = (skyLight - RAIN_SKY_SILENT) / (float) (RAIN_SKY_FULL - RAIN_SKY_SILENT);
        return rain * Math.max(0f, Math.min(1f, open));
    }

    /**
     * Шаг расписания с погодой фронта.
     *
     * @param storm    гроза 0..1 — гремит только она, а не просто сильный дождь
     * @param wind     скорость ветра, блоков в секунду
     * @param outdoors голова под открытым небом: в доме порывов не слышно
     * @param skyLight небесный свет у головы 0..15 — сколько неба над ней
     */
    public Cue tick(float dt, boolean dark, boolean underwater, float rain, float storm,
                    float wind, boolean outdoors, int skyLight) {
        // Всплеск — событие перехода, а не состояния: он обязан сработать
        // ровно один раз и раньше всего остального.
        if (started && underwater != wasUnderwater) {
            wasUnderwater = underwater;
            resetWater();
            return underwater ? Cue.WATER_ENTER : Cue.WATER_EXIT;
        }
        wasUnderwater = underwater;
        started = true;

        if (underwater) {
            waterLoopTimer -= dt;
            waterExtraTimer -= dt;
            if (waterLoopTimer <= 0f) {
                waterLoopTimer = WATER_LOOP;
                return Cue.UNDERWATER;
            }
            if (waterExtraTimer <= 0f) {
                waterExtraTimer = range(WATER_EXTRA_MIN, WATER_EXTRA_MAX);
                return Cue.UNDERWATER_EXTRA;
            }
            return Cue.NONE;
        }

        // Порывы копятся независимо от дождя: в грозу слышно и то и другое.
        if (outdoors && wind > WIND_THRESHOLD) {
            windTimer -= dt;
        } else {
            windTimer = Math.max(windTimer, WIND_MIN * 0.5f);
        }

        // Слышимый дождь, а не выпадающий: под породой он ноль, и дальше по
        // приоритету звучит пещера.
        if (heardRain(rain, skyLight) > RAIN_THRESHOLD) {
            rainTimer -= dt;
            if (rainTimer <= 0f) {
                rainTimer = range(RAIN_MIN, RAIN_MAX);
                if (storm > 0.5f && rnd.nextFloat() < THUNDER_CHANCE)
                    return Cue.THUNDER;
                return Cue.RAIN;
            }
            if (windTimer <= 0f) {
                windTimer = range(WIND_MIN, WIND_MAX);
                return Cue.WIND;
            }
            return Cue.NONE;
        }

        if (windTimer <= 0f) {
            windTimer = range(WIND_MIN, WIND_MAX);
            return Cue.WIND;
        }

        if (dark) {
            caveTimer -= dt;
            if (caveTimer <= 0f) {
                caveTimer = range(CAVE_MIN, CAVE_MAX);
                return Cue.CAVE;
            }
        } else {
            // На поверхности таймер пещеры не копится: иначе первый же шаг
            // под землю встречает мгновенный вой, накопленный за день.
            caveTimer = Math.max(caveTimer, CAVE_MIN * 0.4f);
        }
        return Cue.NONE;
    }

    /** Сколько осталось до следующего звука пещеры — для отладки и тестов. */
    public float caveCountdown() {
        return caveTimer;
    }

    private void resetWater() {
        waterLoopTimer = WATER_LOOP * 0.5f;
        waterExtraTimer = range(WATER_EXTRA_MIN, WATER_EXTRA_MAX);
    }

    private float range(float lo, float hi) {
        return lo + rnd.nextFloat() * (hi - lo);
    }
}

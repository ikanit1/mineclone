package com.mineclone.game;

import com.mineclone.world.Biome;
import com.mineclone.world.World;

/**
 * Сколько мороза набрал игрок: 0 — тепло, 1 — края экрана затянуло инеем.
 *
 * Иней копится не от того, что биом холодный, а от того, что игрок долго в
 * этом холоде: короткая пробежка через тундру ничего не рисует, а ночь в
 * метели — рисует. Огонь и факел рядом отогревают быстро, и это правильная
 * подсказка: у костра теплее.
 *
 * Чистая модель без GL и без ссылок на мир — внешний код приносит числа.
 */
public final class Frost {

    /** Секунд на лютом морозе до полного инея. */
    public static final float FREEZE_TIME = 55f;
    /** Секунд у огня до полного оттаивания. */
    public static final float THAW_TIME = 6f;
    /** Секунд в тепле без огня до оттаивания. */
    public static final float WARMUP_TIME = 22f;
    /** Ниже этой стужи иней не нарастает вовсе. */
    public static final float COLD_THRESHOLD = 0.35f;
    /** Блочный свет, начиная с которого рядом есть огонь. */
    public static final int WARM_LIGHT = 10;

    private float level;

    /**
     * Насколько холодно в точке, 0..1.
     *
     * @param skyLight небесный свет в точке 0..15: под крышей и в пещере ветра
     *                 нет, и холодит заметно слабее
     * @param snowfall сила снегопада 0..1 (0, если осадки здесь не снегом)
     * @param storm    сила бури 0..1 — метель выстуживает сильнее всего
     */
    public static float coldness(Biome biome, float y, float daylight, float snowfall,
                                 float storm, int skyLight, boolean inWater) {
        float c = biome.isCold() ? 0.55f : 0f;
        // Горы холодные в любом биоме — на той же высоте, где дождь сменяется снегом.
        float alt = (y - (World.SEA_LEVEL + 34f)) / 30f;
        c += Math.max(0f, Math.min(1f, alt)) * 0.55f;
        if (c <= 0f)
            return 0f;
        c += 0.22f * (1f - daylight);
        c += 0.25f * snowfall + 0.20f * storm;
        if (inWater)
            c += 0.30f;
        if (skyLight < 8)
            c *= 0.45f;
        return Math.max(0f, Math.min(1f, c));
    }

    /**
     * Шаг модели.
     *
     * @param blockLight блочный свет в точке игрока 0..15
     * @return текущий уровень инея 0..1
     */
    public float update(float dt, float coldness, int blockLight) {
        if (blockLight >= WARM_LIGHT) {
            level -= dt / THAW_TIME;
        } else if (coldness > COLD_THRESHOLD) {
            float k = (coldness - COLD_THRESHOLD) / (1f - COLD_THRESHOLD);
            level += dt * k / FREEZE_TIME;
        } else {
            level -= dt / WARMUP_TIME;
        }
        level = Math.max(0f, Math.min(1f, level));
        return level;
    }

    public float level() {
        return level;
    }

    public void reset() {
        level = 0f;
    }
}

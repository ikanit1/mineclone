package com.mineclone.world;

/**
 * Когда можно лечь спать и до какого момента это переводит время.
 *
 * Чистые функции от времени и обстановки — ни мира, ни игрока, ни GL.
 * Ночь, монстры рядом и точка пробуждения это три правила, которые ломаются
 * от одной опечатки и которые невозможно проверить иначе как переиграв в
 * игре целые сутки.
 */
public final class SleepRules {

    /** Ниже этой освещённости считается, что уже ночь. */
    public static final float NIGHT_DAYLIGHT = 0.25f;
    /** В каком радиусе враждебный моб не даёт уснуть. */
    public static final float MONSTER_RANGE = 12f;

    /** Чем закончилась попытка лечь. */
    public enum Result {
        /** Ложимся. */
        OK,
        /** Ещё светло. */
        TOO_BRIGHT,
        /** Рядом монстры. */
        MONSTERS
    }

    private SleepRules() {}

    /**
     * Можно ли уснуть прямо сейчас.
     *
     * Порядок проверок важен для подсказки: сначала «ещё день» — это самая
     * частая причина и самая понятная игроку; «рядом монстры» имеет смысл
     * показывать только тому, кто уже дождался ночи.
     */
    public static Result check(float daylight, int hostilesNear) {
        if (daylight > NIGHT_DAYLIGHT)
            return Result.TOO_BRIGHT;
        if (hostilesNear > 0)
            return Result.MONSTERS;
        return Result.OK;
    }

    /**
     * Ближайший рассвет строго в будущем.
     *
     * Время не заворачивается назад, а прибавляется до следующего восхода:
     * номер суток растёт, а от него зависит фаза луны ({@link NightSky}).
     * Сон, отматывающий время назад, вернул бы вчерашнюю луну.
     */
    public static float nextDawn(float gameTime) {
        float cycle = (float) (Math.PI * 2.0);
        float t = gameTime % cycle;
        if (t < 0f)
            t += cycle;
        return gameTime + (cycle - t);
    }
}

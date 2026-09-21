package com.mineclone.game;

/**
 * Сколько воздух уже унёс: интеграл ветра, а не «ветер × время».
 *
 * <p>Снос частицы это путь, то есть сумма скоростей по кадрам. Считать его как
 * {@code ветер × общее время} можно только при постоянном ветре — а ветер
 * пульсирует порывами ({@link com.mineclone.world.Weather#wind}, период около
 * пяти секунд, размах почти в полтора раза). Умноженная на растущее общее
 * время, эта пульсация двигала разом весь снег, весь дождь и весь объёмный
 * туман: через десять минут игры порыв смещал картину на сотни блоков, и
 * осадки качались туда-сюда, вместо того чтобы просто идти быстрее и
 * медленнее. Баг: {@code knowledge/bugs/precipitation-swayed-with-gusts.md}.
 *
 * <p>Здесь же копится добавка к падению от бури — по той же причине: скорость
 * падения растёт вместе с ней, и умножать новую скорость на всё прошедшее
 * время значит телепортировать столб осадков.
 *
 * <p>Класс чистый, без GL и без мира: проверяется обычным тестом.
 */
public final class WeatherDrift {

    /** Больше этого шага не бывает: рывок кадра не должен рвать снос. */
    private static final float MAX_STEP = 0.1f;
    /** Во сколько раз метель несёт снег сильнее обычного ветра. */
    private static final float SNOW_STORM_CARRY = 0.8f;
    /** Насколько буря ускоряет падение снега и дождя, блоков в секунду. */
    private static final float SNOW_STORM_FALL = 2.2f, RAIN_STORM_FALL = 3.0f;

    /** Общий снос воздуха по осям X и Z, блоки. Им едут дождь и туман. */
    public float x, z;
    /** Снос снега: в метель хлопья несёт заметно дальше. */
    public float snowX, snowZ;
    /** Насколько буря успела добавить к падению снега и дождя, блоки. */
    public float snowFall, rainFall;

    /**
     * Шаг кадра.
     *
     * @param storm сила бури 0..1 в точке игрока
     */
    public void advance(float dt, float windX, float windZ, float storm) {
        float step = dt <= 0f ? 0f : Math.min(MAX_STEP, dt);
        if (step <= 0f)
            return;
        x += windX * step;
        z += windZ * step;
        float carry = 1f + storm * SNOW_STORM_CARRY;
        snowX += windX * carry * step;
        snowZ += windZ * carry * step;
        snowFall += storm * SNOW_STORM_FALL * step;
        rainFall += storm * RAIN_STORM_FALL * step;
    }

    /**
     * Забыть пройденное.
     *
     * <p>Зовётся при смене мира: числа копятся без предела, и у float на
     * больших значениях кадровый шаг перестаёт быть различимым. Видимого
     * скачка сброс не даёт — узор осадков просто сдвигается.
     */
    public void reset() {
        x = z = 0f;
        snowX = snowZ = 0f;
        snowFall = rainFall = 0f;
    }
}

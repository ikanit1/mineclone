package com.mineclone.item;

/**
 * Правила натяжения лука: во что превращается удержание кнопки.
 *
 * Чистая арифметика, как и {@link Combat}: «полное натяжение бьёт втрое
 * сильнее беглого» проверяется тестом, а не выстрелами по зомби.
 */
public final class Bow {

    /** Идентификатор лука в реестре: его спрашивают и рука, и выстрел. */
    public static final String ITEM = "bow";
    /** Чем стреляет. */
    public static final String AMMO = "arrow";

    /** За сколько секунд лук натягивается полностью. */
    public static final float DRAW_TIME = 1.0f;
    /**
     * Ниже этого натяжения выстрела нет вовсе.
     *
     * Иначе случайный щелчок тратит стрелу и роняет её под ноги — раздражает
     * больше, чем помогает.
     */
    public static final float MIN_DRAW = 0.15f;

    /** Скорость стрелы, блоков в секунду: от вялой до настильной. */
    public static final float MIN_SPEED = 12f, MAX_SPEED = 30f;
    /** Урон стрелы. */
    public static final float MIN_DAMAGE = 1.5f, MAX_DAMAGE = 6f;

    /** Границы стадий натяжения для модели в руке. */
    public static final float STAGE_1 = 0.30f, STAGE_2 = 0.65f;

    private Bow() {}

    /** Натяжение 0..1 по времени удержания. */
    public static float draw(float heldSeconds) {
        return clamp(heldSeconds / DRAW_TIME);
    }

    /** Стадия для спрайта: 0 — покой, 2 — на полную. */
    public static int stage(float draw) {
        float d = clamp(draw);
        if (d >= STAGE_2)
            return 2;
        return d >= STAGE_1 ? 1 : 0;
    }

    /** Полетит ли стрела вообще. */
    public static boolean canRelease(float draw) {
        return draw >= MIN_DRAW;
    }

    /**
     * Начальная скорость стрелы.
     *
     * Растёт квадратично: разница между «почти полным» и «полным» натяжением
     * должна окупать лишние полсекунды ожидания.
     */
    public static float speed(float draw) {
        float d = clamp(draw);
        return MIN_SPEED + (MAX_SPEED - MIN_SPEED) * d * d;
    }

    /** Урон стрелы при этом натяжении. */
    public static float damage(float draw) {
        float d = clamp(draw);
        return MIN_DAMAGE + (MAX_DAMAGE - MIN_DAMAGE) * d * d;
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}

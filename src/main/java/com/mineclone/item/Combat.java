package com.mineclone.item;

/**
 * Правила ближнего боя: сколько бьёт то, что в руке, и как часто.
 *
 * Отдельно от {@code Game}, потому что там они жили внутри обработчика мыши
 * и проверить их можно было только руками по живому зомби. Здесь это чистая
 * арифметика над {@link Item}, поэтому «меч выигрывает у топора по dps, а
 * топор по одному удару» — обычный тест, а не ощущение.
 *
 * <p>Пустая рука — это {@code null} предмета, а не особый случай в каждом
 * вызове: у неё свои урон и темп, и дальше она ничем не отличается от
 * оружия.
 */
public final class Combat {

    /** Урон голой рукой — одно сердце. */
    public static final float HAND_DAMAGE = 2f;
    /** Ударов рукой в секунду. Вместе с уроном даёт прежние 4 dps. */
    public static final float HAND_SPEED = 2f;
    /**
     * Во что превращается удар в самом начале отката.
     *
     * Не ноль: удар, не наносящий ничего, читается как пропущенный клик, а
     * не как поспешный. Пятая часть — уже заметная потеря.
     */
    public static final float MIN_CHARGED = 0.2f;
    /** Множитель урона при ударе в падении. */
    public static final float CRIT_MULTIPLIER = 1.5f;
    /** Во сколько раз сильнее отброс в спринте. */
    public static final float SPRINT_KNOCKBACK = 1.6f;

    private Combat() {}

    /** Ударов в секунду этим предметом. */
    public static float speed(Item item) {
        return item != null && item.attack != null ? item.attack.speed() : HAND_SPEED;
    }

    /** Секунд между полноценными ударами. */
    public static float cooldown(Item item) {
        return 1f / Math.max(0.05f, speed(item));
    }

    /** Урон предмета в полном откате, без крита. */
    public static float baseDamage(Item item) {
        return item != null && item.attack != null ? item.attack.damage() : HAND_DAMAGE;
    }

    /**
     * Готовность 0..1 по оставшемуся откату.
     *
     * @param left сколько ещё секунд до полного отката
     */
    public static float readiness(float left, float cooldown) {
        if (left <= 0f)
            return 1f;
        if (cooldown <= 0f)
            return 1f;
        return clamp(1f - left / cooldown);
    }

    /**
     * Урон удара.
     *
     * Готовность входит квадратично — как в MC: половина отката даёт заметно
     * меньше половины урона, и закликивание перестаёт быть выгодным. Без
     * этого быстрое оружие было бы просто лучше медленного, и раздельная
     * скорость ничего бы не значила.
     */
    public static float damage(Item item, float readiness, boolean crit) {
        float k = clamp(readiness);
        float scale = MIN_CHARGED + (1f - MIN_CHARGED) * k * k;
        float d = baseDamage(item) * scale;
        return crit ? d * CRIT_MULTIPLIER : d;
    }

    /** Отброс: спринт бьёт не больнее, но дальше. */
    public static float knockback(boolean sprinting) {
        return sprinting ? SPRINT_KNOCKBACK : 1f;
    }

    /** Урон в секунду при непрерывных ударах в полном откате. */
    public static float dps(Item item) {
        return baseDamage(item) * speed(item);
    }

    /** Есть ли у предмета своя боевая часть: у кирки есть, у доски нет. */
    public static boolean isWeapon(Item item) {
        return item != null && item.attack != null;
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}

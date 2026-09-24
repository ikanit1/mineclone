package com.mineclone.world;

import com.mineclone.item.Items;

/**
 * Что во что переплавляется и что горит.
 *
 * Таблица, а не метод на каждом предмете: переплавка — свойство пары
 * «печь + предмет», а не самого предмета, и держать её в одном месте
 * означает видеть весь баланс сразу.
 *
 * Топливо, наоборот, свойство самого предмета и живёт в данных: «сколько
 * секунд горит» — то же по смыслу, что «сколько сытости даёт».
 */
public final class Smelting {

    /** Длительность по умолчанию для совместимых вызывающих; рецепт задаёт свою. */
    public static final float COOK_TIME = 8f;

    private Smelting() {}

    /**
     * Результат переплавки одной единицы или {@code null}.
     *
     * Возвращается новая стопка с количеством из данных рецепта.
     */
    public static ItemStack result(ItemStack in) {
        if (in == null || in.count <= 0)
            return null;
        var recipe = Items.get().recipes().smelting(in.item);
        return recipe == null ? null : recipe.output();
    }

    /** Duration from the matched data recipe; the legacy constant remains an API default. */
    public static float cookTime(ItemStack in) {
        var recipe = in == null ? null : Items.get().recipes().smelting(in.item);
        return recipe == null ? COOK_TIME : recipe.time();
    }

    /**
     * Сколько секунд горит одна единица топлива; 0 — не топливо.
     *
     * Уголь — восемь переплавок, бревно полторы, доски одна. Инструменты и
     * еда не горят: деревянная кирка как топливо обесценила бы крафт.
     */
    public static float fuelSeconds(ItemStack fuel) {
        if (fuel == null || fuel.count <= 0)
            return 0f;
        return fuel.item.fuelSeconds;
    }

    /** Годится ли предмет в топку — для подсказок в интерфейсе. */
    public static boolean isFuel(ItemStack s) {
        return fuelSeconds(s) > 0f;
    }
}

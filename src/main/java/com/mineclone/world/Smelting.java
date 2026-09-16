package com.mineclone.world;

import com.mineclone.item.Item;
import com.mineclone.item.Items;

import java.util.HashMap;
import java.util.Map;

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

    /** Сколько секунд плавится одна единица. */
    public static final float COOK_TIME = 8f;

    /** Id того, что плавится, → id того, что выходит. */
    private static final String[][] TABLE = {
            { "beef", "cooked_beef" },
            { "porkchop", "cooked_porkchop" },
            { "chicken", "cooked_chicken" },
            { "mutton", "cooked_mutton" },
            { "sand", "glass" },
            { "cobblestone", "stone" },
    };

    private static Map<Item, Item> results;

    private Smelting() {}

    private static synchronized Map<Item, Item> results() {
        if (results == null) {
            results = new HashMap<>();
            for (String[] row : TABLE)
                results.put(Items.get().require(row[0]), Items.get().require(row[1]));
        }
        return results;
    }

    /**
     * Результат переплавки одной единицы или {@code null}.
     *
     * Возвращается новая стопка на один предмет: печь кладёт результат по
     * одному, а не перекладывает исходную стопку целиком.
     */
    public static ItemStack result(ItemStack in) {
        if (in == null || in.count <= 0)
            return null;
        Item out = results().get(in.item);
        return out == null ? null : new ItemStack(out, 1);
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

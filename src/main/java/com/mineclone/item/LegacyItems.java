package com.mineclone.item;

import com.mineclone.world.BlockType;

/**
 * Мост к сейвам, где предмет лежал порядковым номером перечисления.
 *
 * <p>Порядок этих таблиц — история, а не решение: он повторяет прежние
 * {@code ToolType} и {@code FoodType} и меняться не имеет права, иначе в
 * старом мире кирки превратятся в лопаты. Новые предметы сюда не
 * дописываются: в новом формате предмет пишется своим id.
 */
public final class LegacyItems {

    /** Прежний порядок {@code ToolType}. */
    public static final String[] TOOLS = {
            "wooden_pickaxe", "stone_pickaxe", "iron_pickaxe", "diamond_pickaxe",
            "wooden_axe", "stone_axe", "iron_axe", "diamond_axe",
            "wooden_shovel", "stone_shovel", "iron_shovel", "diamond_shovel",
    };

    /** Прежний порядок {@code FoodType}. */
    public static final String[] FOODS = {
            "beef", "porkchop", "chicken", "mutton",
            "cooked_beef", "cooked_porkchop", "cooked_chicken", "cooked_mutton",
    };

    private LegacyItems() {}

    /** Предмет блока по номеру {@code BlockType}; {@code null} — такого нет. */
    public static Item block(int ordinal) {
        BlockType[] all = BlockType.VALUES;
        if (ordinal < 0 || ordinal >= all.length)
            return null;
        return Items.get().forBlock(all[ordinal]);
    }

    public static Item tool(int ordinal) {
        return byTable(TOOLS, ordinal);
    }

    public static Item food(int ordinal) {
        return byTable(FOODS, ordinal);
    }

    /** Номер инструмента в прежней таблице; {@code -1} — его там не было. */
    public static int toolIndex(Item item) {
        return indexIn(TOOLS, item);
    }

    public static int foodIndex(Item item) {
        return indexIn(FOODS, item);
    }

    private static int indexIn(String[] table, Item item) {
        if (item == null)
            return -1;
        String path = item.id.path();
        for (int i = 0; i < table.length; i++)
            if (table[i].equals(path))
                return i;
        return -1;
    }

    private static Item byTable(String[] table, int ordinal) {
        if (ordinal < 0 || ordinal >= table.length)
            return null;
        return Items.get().get(table[ordinal]);
    }
}

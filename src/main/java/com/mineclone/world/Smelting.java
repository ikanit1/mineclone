package com.mineclone.world;

/**
 * Что во что переплавляется и что горит.
 *
 * Таблица, а не метод на каждом предмете: переплавка — свойство пары
 * «печь + предмет», а не самого предмета, и держать её в одном месте
 * означает видеть весь баланс сразу.
 */
public final class Smelting {

    /** Сколько секунд плавится одна единица. */
    public static final float COOK_TIME = 8f;

    private Smelting() {}

    /**
     * Результат переплавки одной единицы или {@code null}.
     *
     * Возвращается новая стопка на один предмет: печь кладёт результат по
     * одному, а не перекладывает исходную стопку целиком.
     */
    public static ItemStack result(ItemStack in) {
        if (in == null || in.count <= 0 || in.isTool())
            return null;
        if (in.isFood())
            return switch (in.food) {
                case RAW_BEEF -> new ItemStack(FoodType.COOKED_BEEF, 1);
                case RAW_PORK -> new ItemStack(FoodType.COOKED_PORK, 1);
                case RAW_CHICKEN -> new ItemStack(FoodType.COOKED_CHICKEN, 1);
                case RAW_MUTTON -> new ItemStack(FoodType.COOKED_MUTTON, 1);
                default -> null;    // жареное второй раз не жарится
            };
        return switch (in.type) {
            case SAND -> new ItemStack(BlockType.GLASS, 1);
            case COBBLE -> new ItemStack(BlockType.STONE, 1);
            default -> null;
        };
    }

    /**
     * Сколько секунд горит одна единица топлива; 0 — не топливо.
     *
     * Уголь — восемь переплавок, дерево полторы, доски одна. Инструменты не
     * горят: деревянная кирка как топливо обесценила бы крафт.
     */
    public static float fuelSeconds(ItemStack fuel) {
        if (fuel == null || fuel.count <= 0 || fuel.isTool() || fuel.isFood())
            return 0f;
        return switch (fuel.type) {
            case COAL_ORE -> COOK_TIME * 8f;
            case WOOD -> COOK_TIME * 1.5f;
            case PLANKS, STAIRS -> COOK_TIME;
            default -> 0f;
        };
    }

    /** Годится ли предмет в топку — для подсказок в интерфейсе. */
    public static boolean isFuel(ItemStack s) {
        return fuelSeconds(s) > 0f;
    }
}

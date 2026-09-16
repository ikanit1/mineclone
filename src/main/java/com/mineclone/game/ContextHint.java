package com.mineclone.game;

import com.mineclone.world.BlockType;
import com.mineclone.world.ItemStack;

/**
 * Всплывающая подсказка у прицела: что сделает клик по тому, на что смотришь.
 *
 * Показывается только для того, что без подсказки неочевидно: сундук и печь
 * выглядят как обычные блоки, дверь неясно — открыта она или закрыта, а еду в
 * руке новичок пытается «поставить». Для камня и земли подсказки нет: ломать
 * блоки игрок учится в первую же секунду, и лишняя надпись только шумит.
 *
 * Чистая функция — проверяется тестом.
 */
public final class ContextHint {
    private ContextHint() {}

    /** Подсказка: клавиша и действие. */
    public record Hint(String key, String action) {}

    public static final String RMB = "ПКМ";
    public static final String LMB = "ЛКМ";

    /**
     * @param target  блок под прицелом или null
     * @param held    что в руке, или null
     * @param canEat  желудок не полон — есть смысл есть
     * @param mobInReach моб под прицелом в досягаемости удара
     */
    public static Hint forTarget(BlockType target, ItemStack held, boolean canEat,
                                 boolean mobInReach) {
        if (target != null) {
            switch (target) {
                case CHEST -> { return new Hint(RMB, "открыть сундук"); }
                case FURNACE -> { return new Hint(RMB, "открыть печь"); }
                case DOOR_CLOSED -> { return new Hint(RMB, "открыть дверь"); }
                case DOOR_OPEN -> { return new Hint(RMB, "закрыть дверь"); }
                default -> { }
            }
        }
        if (held != null && held.isFood() && canEat)
            return new Hint(RMB, "съесть " + held.food.displayName.toLowerCase());
        if (mobInReach)
            return new Hint(LMB, "ударить");
        return null;
    }
}

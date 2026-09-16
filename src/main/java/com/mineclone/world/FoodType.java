package com.mineclone.world;

/**
 * Еда: третий вид предмета после блоков и инструментов.
 *
 * Отдельным перечислением, а не «блоком, который нельзя ставить»: блок обязан
 * иметь грани, звук шагов, поведение при постановке и id в чанке — всё это
 * мясу не нужно и только мешает.
 *
 * Значения добавляются строго в конец: порядковый номер лежит в сейве.
 */
public enum FoodType {
    //           сытость  тайл  название
    RAW_BEEF    (3,  69, "Сырая говядина"),
    RAW_PORK    (3,  70, "Сырая свинина"),
    RAW_CHICKEN (2,  71, "Сырая курятина"),
    RAW_MUTTON  (2,  72, "Сырая баранина"),
    // Жареное: вдвое сытнее сырого. Ради этой разницы печь и строится —
    // иначе топливо и время тратятся ни на что.
    COOKED_BEEF    (7, 82, "Жареная говядина"),
    COOKED_PORK    (7, 83, "Жареная свинина"),
    COOKED_CHICKEN (5, 84, "Жареная курятина"),
    COOKED_MUTTON  (5, 85, "Жареная баранина");

    /** Сколько единиц голода восстанавливает — из двадцати. */
    public final int nutrition;
    public final int tile;
    public final String displayName;

    FoodType(int nutrition, int tile, String displayName) {
        this.nutrition = nutrition;
        this.tile = tile;
        this.displayName = displayName;
    }

    public static final FoodType[] VALUES = values();

    /** Безопасное чтение из сейва: неизвестный id — не предмет, а пусто. */
    public static FoodType byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : null;
    }
}

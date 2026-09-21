package com.mineclone.ui.container;

/**
 * Назначение группы слотов.
 *
 * <p>Роль, а не класс слота: правила клика у всех слотов общие, отличается
 * только то, куда уходит стопка по Shift и что в слот вообще класть можно.
 * Разводить это наследованием означало бы двенадцать классов с одной строкой
 * различий в каждом.
 *
 * <p>Часть ролей заведена заранее — броня, пояс, украшения, сетка крафта
 * приходят планами B и C. Перечисление дешевле формата сейва: маршрут
 * Shift'а задаёт экран, а не эта таблица.
 */
public enum SlotRole {
    HOTBAR,
    MAIN,
    BAG,
    ARMOR,
    OFFHAND,
    ACCESSORY,
    CONTAINER,
    CRAFT_GRID,
    CRAFT_RESULT,
    FURNACE_INPUT,
    FURNACE_FUEL,
    FURNACE_OUTPUT,
    CREATIVE_SOURCE,
    EDITOR,
    TRASH;

    /** Из слота можно только забирать: результат крафта и выход печи. */
    public boolean takeOnly() {
        return this == CRAFT_RESULT || this == FURNACE_OUTPUT;
    }

    /** Бездонный источник: предметы берутся копиями и исчезают обратно. */
    public boolean isCreativeSource() {
        return this == CREATIVE_SOURCE;
    }
}

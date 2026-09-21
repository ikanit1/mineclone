package com.mineclone.ui.container;

import com.mineclone.core.KeyBindings;
import com.mineclone.world.GameMode;
import com.mineclone.world.Inventory;
import com.mineclone.world.ItemStack;

/**
 * Всё, что окну нужно от игры, и ничего сверх этого.
 *
 * <p>Экран не видит {@code Game}: он умеет положить предмет игроку, бросить
 * его перед собой, щёлкнуть, показать тост и спросить настройку. Благодаря
 * этому окно поднимается в тесте и в предпросмотре с заглушкой на десять
 * строк, а не с половиной игры.
 */
public interface WindowContext {

    Inventory inventory();

    int selectedSlot();

    GameMode mode();

    /** Бросить стопку перед игроком. */
    void throwStack(ItemStack s);

    /** Отдать игроку; что не влезло — бросить. */
    void give(ItemStack s);

    void click(float volume, float pitch);

    void toast(String text);

    /** Показывать ли в подсказке id, числа прочности и теги (F3+H). */
    boolean advancedTooltips();

    KeyBindings keys();
}

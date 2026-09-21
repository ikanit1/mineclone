package com.mineclone.ui.container;

import com.mineclone.world.Inventory;
import com.mineclone.world.ItemStack;

/**
 * Окно в инвентарь игрока: слоты {@code [from, from + count)}.
 *
 * <p>Хотбар и основная часть — это один и тот же массив, но две группы на
 * экране и два разных адреса для Shift'а. Копировать содержимое ради этого
 * нельзя: игрок ждёт, что предмет, положенный в окне, лежит в инвентаре сразу.
 */
public final class InventoryStorage implements SlotStorage {

    private final Inventory inventory;
    private final int from, count;

    public InventoryStorage(Inventory inventory, int from, int count) {
        this.inventory = inventory;
        this.from = from;
        this.count = count;
    }

    public Inventory inventory() {
        return inventory;
    }

    /** Номер слота в самом инвентаре — нужен обмену по цифре. */
    public int globalIndex(int index) {
        return from + index;
    }

    @Override
    public int size() {
        return count;
    }

    @Override
    public ItemStack get(int index) {
        return index >= 0 && index < count ? inventory.get(from + index) : null;
    }

    @Override
    public void set(int index, ItemStack stack) {
        if (index >= 0 && index < count)
            inventory.set(from + index, stack);
    }
}

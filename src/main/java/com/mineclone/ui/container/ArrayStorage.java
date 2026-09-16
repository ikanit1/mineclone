package com.mineclone.ui.container;

import com.mineclone.world.ItemStack;

import java.util.function.BiPredicate;
import java.util.function.IntConsumer;

/**
 * Слоты поверх обычного массива: сундук, печь, сетка крафта.
 *
 * <p>Фильтр и уведомление об изменении задаются снаружи: печь пускает в топку
 * только топливо, а сундук обязан пометить свой чанк изменённым — и то и
 * другое зависит от места, а не от массива.
 */
public final class ArrayStorage implements SlotStorage {

    private final ItemStack[] slots;
    private final BiPredicate<Integer, ItemStack> filter;
    private final IntConsumer onChanged;

    public ArrayStorage(ItemStack[] slots) {
        this(slots, null, null);
    }

    public ArrayStorage(ItemStack[] slots, BiPredicate<Integer, ItemStack> filter,
            IntConsumer onChanged) {
        this.slots = slots;
        this.filter = filter;
        this.onChanged = onChanged;
    }

    public ItemStack[] array() {
        return slots;
    }

    @Override
    public int size() {
        return slots.length;
    }

    @Override
    public ItemStack get(int index) {
        return index >= 0 && index < slots.length ? slots[index] : null;
    }

    @Override
    public void set(int index, ItemStack stack) {
        if (index >= 0 && index < slots.length)
            slots[index] = stack;
    }

    @Override
    public boolean canPlace(int index, ItemStack stack) {
        return filter == null || stack == null || filter.test(index, stack);
    }

    @Override
    public void changed(int index) {
        if (onChanged != null)
            onChanged.accept(index);
    }
}

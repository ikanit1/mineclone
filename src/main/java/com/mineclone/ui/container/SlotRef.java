package com.mineclone.ui.container;

import com.mineclone.world.ItemStack;

/** Адрес одного слота: группа и номер внутри неё. */
public record SlotRef(SlotGroup group, int index) {

    public ItemStack get() {
        return group.storage.get(index);
    }

    public void set(ItemStack s) {
        group.storage.set(index, s);
        group.storage.changed(index);
    }

    public SlotRole role() {
        return group.role;
    }

    /**
     * Роль «только забрать» здесь и решается: результат крафта и выход печи
     * не принимают ничего, и помнить об этом каждому экрану незачем.
     */
    public boolean canPlace(ItemStack s) {
        return !group.role.takeOnly() && group.storage.canPlace(index, s);
    }

    public boolean canTake() {
        return group.storage.canTake(index);
    }

    public int maxCount(ItemStack s) {
        return group.storage.maxCount(index, s);
    }

    @Override
    public String toString() {
        return group.id + "#" + index;
    }
}

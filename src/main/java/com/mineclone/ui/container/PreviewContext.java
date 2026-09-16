package com.mineclone.ui.container;

import com.mineclone.core.KeyBindings;
import com.mineclone.world.GameMode;
import com.mineclone.world.Inventory;
import com.mineclone.world.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Заглушка игры для снимков, замеров и тестов.
 *
 * <p>Ровно та причина, по которой {@link WindowContext} вообще существует:
 * окно поднимается без мира, без GL-контекста игры и без половины {@code Game},
 * потому что всё, что ему нужно, помещается в десяток строк.
 */
public final class PreviewContext implements WindowContext {

    public final Inventory inv;
    public final List<ItemStack> thrown = new ArrayList<>();
    public final List<String> toasts = new ArrayList<>();
    public GameMode mode = GameMode.SURVIVAL;
    public int selected;
    public boolean advanced;
    private final KeyBindings keys = new KeyBindings();

    public PreviewContext(Inventory inv) {
        this.inv = inv;
    }

    @Override
    public Inventory inventory() {
        return inv;
    }

    @Override
    public int selectedSlot() {
        return selected;
    }

    @Override
    public GameMode mode() {
        return mode;
    }

    @Override
    public void throwStack(ItemStack s) {
        thrown.add(s);
    }

    @Override
    public void give(ItemStack s) {
        if (s != null)
            inv.add(s);
    }

    @Override
    public void click(float volume, float pitch) {
    }

    @Override
    public void toast(String text) {
        toasts.add(text);
    }

    @Override
    public boolean advancedTooltips() {
        return advanced;
    }

    @Override
    public KeyBindings keys() {
        return keys;
    }
}

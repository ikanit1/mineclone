package com.mineclone.ui.container;

import com.mineclone.world.Inventory;
import com.mineclone.world.ItemStack;
import com.mineclone.world.Recipes;

/** Логика живой сетки крафта, общая для инвентаря 2×2 и верстака 3×3. */
public final class CraftingGrid {

    private final int width;
    private final ItemStack[] slots;
    private final ArrayStorage storage;

    public CraftingGrid(int width) {
        if (width != 2 && width != 3)
            throw new IllegalArgumentException("crafting grid must be 2x2 or 3x3");
        this.width = width;
        this.slots = new ItemStack[width * width];
        this.storage = new ArrayStorage(slots);
    }

    public int width() {
        return width;
    }

    public SlotStorage storage() {
        return storage;
    }

    public ItemStack[] slots() {
        return slots;
    }

    public Recipes.Recipe recipe() {
        return Recipes.match(slots, width);
    }

    public ItemStack result() {
        Recipes.Recipe recipe = recipe();
        return recipe == null ? null : Recipes.result(recipe);
    }

    /** Собрать один раз на курсор, только если вся стопка результата влезает. */
    public ItemStack craftToCursor(ContainerMenu menu) {
        ItemStack preview = result();
        if (preview == null)
            return null;
        ItemStack cursor = menu.cursor();
        if (cursor != null && (!cursor.stacksWith(preview)
                || cursor.count + preview.count > cursor.maxStack()))
            return null;
        ItemStack made = Recipes.consume(slots, width);
        if (made == null)
            return null;
        if (cursor == null)
            menu.setCursor(made);
        else
            cursor.count += made.count;
        return made;
    }

    /** Shift-клик: повторять рецепт, пока хватает ингредиентов и места. */
    public int craftAll(Inventory inventory) {
        int made = 0;
        for (int guard = 0; guard < 64; guard++) {
            ItemStack preview = result();
            if (preview == null || !inventory.canAdd(preview, preview.count))
                break;
            ItemStack out = Recipes.consume(slots, width);
            if (out == null)
                break;
            inventory.add(out);
            made += out.count;
        }
        return made;
    }

    /** Возвращает оставшиеся ингредиенты игроку при закрытии окна. */
    public void returnItems(WindowContext ctx) {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] != null)
                ctx.give(slots[i]);
            slots[i] = null;
        }
    }
}

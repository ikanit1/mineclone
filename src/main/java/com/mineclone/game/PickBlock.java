package com.mineclone.game;

import com.mineclone.item.Item;
import com.mineclone.world.Inventory;
import com.mineclone.world.ItemStack;

/**
 * Пипетка: средняя кнопка берёт в руку то, во что целишься.
 *
 * <p>Правила без GL и без мира: на вход инвентарь и предмет, на выход — какой
 * слот стал выбранным. Именно в этих правилах и живут все спорные случаи —
 * предмет в хранилище, полный хотбар, творческий режим.
 */
public final class PickBlock {

    private PickBlock() {}

    /**
     * @param creativeStack что выдать в творческом режиме; в выживании не нужен
     * @return номер слота хотбара, который стал выбранным
     */
    public static int pick(Inventory inv, int selected, Item item, boolean creative,
            ItemStack creativeStack) {
        if (item == null)
            return selected;

        // Уже в хотбаре — просто переключаемся: пипетка не должна
        // перекладывать то, что и так под рукой.
        for (int i = 0; i < Inventory.HOTBAR; i++) {
            ItemStack s = inv.get(i);
            if (s != null && s.item == item)
                return i;
        }

        if (!creative) {
            // В выживании предмет берётся из хранилища, а не создаётся:
            // пипетка — это удобство, а не источник материала.
            for (int j = Inventory.HOTBAR; j < inv.size(); j++) {
                ItemStack s = inv.get(j);
                if (s == null || s.item != item)
                    continue;
                int target = firstEmptyHotbar(inv);
                if (target < 0)
                    target = selected;
                ItemStack swap = inv.get(target);
                inv.set(target, s);
                inv.set(j, swap);
                return target;
            }
            return selected;
        }

        if (creativeStack == null)
            return selected;
        int target = inv.get(selected) == null ? selected : firstEmptyHotbar(inv);
        if (target < 0)
            target = selected;
        inv.set(target, creativeStack);
        return target;
    }

    private static int firstEmptyHotbar(Inventory inv) {
        for (int i = 0; i < Inventory.HOTBAR; i++)
            if (inv.get(i) == null)
                return i;
        return -1;
    }
}

package com.mineclone.ui.container;

import com.mineclone.world.ItemStack;

/**
 * Откуда окно берёт содержимое слотов.
 *
 * <p>Инвентарь игрока, массив сундука и три слота печи — это одно и то же с
 * точки зрения клика, и правила кликов не должны знать, во что они целятся.
 * Ограничения слота (что туда класть, сколько влезет, можно ли забрать) живут
 * здесь же: печь принимает в топку только топливо, и знать об этом должно
 * хранилище, а не каждый обработчик мыши.
 */
public interface SlotStorage {

    int size();

    ItemStack get(int index);

    void set(int index, ItemStack stack);

    /** Пускает ли слот эту стопку. */
    default boolean canPlace(int index, ItemStack stack) {
        return true;
    }

    /** Потолок слота для этой стопки; обычно — предел самой стопки. */
    default int maxCount(int index, ItemStack stack) {
        return stack == null ? 0 : stack.maxStack();
    }

    default boolean canTake(int index) {
        return true;
    }

    /**
     * Забирает до {@code amount} предметов из слота.
     *
     * <p>Отдельным методом, потому что у результата крафта «забрать половину»
     * не бывает: он выдаётся целиком, и переопределить это должен слот, а не
     * вызывающий.
     */
    default ItemStack take(int index, int amount) {
        ItemStack s = get(index);
        if (s == null || amount <= 0 || !canTake(index))
            return null;
        int taken = Math.min(amount, s.count);
        ItemStack out = s.copyWithCount(taken);
        s.count -= taken;
        if (s.count <= 0)
            set(index, null);
        changed(index);
        return out;
    }

    /** Слот изменился: сундуку пора пометить чанк грязным. */
    default void changed(int index) {
    }
}

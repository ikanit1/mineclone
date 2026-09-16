package com.mineclone.item;

import com.mineclone.world.ItemStack;

/**
 * Снимок печи: три слота и два таймера.
 *
 * <p>Неизменяемая копия {@code world.Furnace}, а не он сам: компонент стопки
 * обязан быть неизменяемым, а печь в мире тикает. Через этот снимок печь
 * переезжает в предмет пипеткой и обратно в мир при постановке.
 */
public record FurnaceState(ItemStack input, ItemStack fuel, ItemStack output,
        float burnLeft, float burnMax, float cook) {

    public FurnaceState {
        input = copy(input);
        fuel = copy(fuel);
        output = copy(output);
    }

    private static ItemStack copy(ItemStack s) {
        return s == null ? null : s.copy();
    }

    /** Свежие копии наружу: снимок отдаёт содержимое, а не даёт его править. */
    public ItemStack inputCopy() {
        return copy(input);
    }

    public ItemStack fuelCopy() {
        return copy(fuel);
    }

    public ItemStack outputCopy() {
        return copy(output);
    }

    public boolean isEmpty() {
        return input == null && fuel == null && output == null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof FurnaceState f))
            return false;
        return same(input, f.input) && same(fuel, f.fuel) && same(output, f.output)
                && burnLeft == f.burnLeft && burnMax == f.burnMax && cook == f.cook;
    }

    @Override
    public int hashCode() {
        int h = Float.hashCode(burnLeft) * 31 + Float.hashCode(burnMax);
        h = h * 31 + Float.hashCode(cook);
        h = h * 31 + hash(input);
        h = h * 31 + hash(fuel);
        return h * 31 + hash(output);
    }

    static boolean same(ItemStack a, ItemStack b) {
        return a == null ? b == null : a.contentEquals(b);
    }

    static int hash(ItemStack s) {
        return s == null ? 0 : s.contentHash();
    }
}

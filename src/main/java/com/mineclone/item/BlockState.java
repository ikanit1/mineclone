package com.mineclone.item;

import com.mineclone.world.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Данные блока, уехавшие в предмет: meta, содержимое сундука, состояние печи.
 *
 * <p>Нужен пипетке в креативе: Ctrl со средней кнопкой снимает блок вместе с
 * начинкой, и поставленный обратно сундук обязан открыться тем же, чем был.
 * Без этого «скопировать» означало бы «скопировать только оболочку».
 *
 * <p>Копии стопок глубокие: компонент неизменяем, а стопка — нет, и ссылка на
 * живой сундук превратила бы снимок в окно в него.
 */
public record BlockState(byte meta, List<ItemStack> chest, FurnaceState furnace) {

    public BlockState {
        chest = chest == null ? null : deepCopy(chest);
    }

    public static BlockState ofMeta(byte meta) {
        return new BlockState(meta, null, null);
    }

    /** Свежие копии наружу — в мир их кладёт постановка блока. */
    public List<ItemStack> chestCopy() {
        return chest == null ? null : deepCopy(chest);
    }

    public boolean hasChest() {
        return chest != null;
    }

    public boolean hasFurnace() {
        return furnace != null;
    }

    private static List<ItemStack> deepCopy(List<ItemStack> src) {
        List<ItemStack> out = new ArrayList<>(src.size());
        for (ItemStack s : src)
            out.add(s == null ? null : s.copy());
        return Collections.unmodifiableList(out);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof BlockState b) || meta != b.meta)
            return false;
        if (!java.util.Objects.equals(furnace, b.furnace))
            return false;
        if (chest == null || b.chest == null)
            return chest == b.chest;
        if (chest.size() != b.chest.size())
            return false;
        for (int i = 0; i < chest.size(); i++)
            if (!FurnaceState.same(chest.get(i), b.chest.get(i)))
                return false;
        return true;
    }

    @Override
    public int hashCode() {
        int h = meta;
        h = h * 31 + java.util.Objects.hashCode(furnace);
        if (chest != null)
            for (ItemStack s : chest)
                h = h * 31 + FurnaceState.hash(s);
        return h;
    }
}

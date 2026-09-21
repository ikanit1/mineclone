package com.mineclone.save;

import com.mineclone.item.Components;
import com.mineclone.item.Item;
import com.mineclone.item.LegacyItems;
import com.mineclone.item.StackIo;
import com.mineclone.world.ItemStack;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * Стопка на диске: новый формат и чтение старого.
 *
 * <p>Сам двоичный вид живёт в {@link StackIo} рядом со стопкой — им же
 * пишется сундук внутри компонента блока. Здесь к нему добавлено то, что
 * нужно только сохранениям: чтение размеченного слота версий 8 и ниже.
 */
public final class ItemStackCodec {

    /** Виды слота в формате v8: пусто, блок, инструмент, еда. */
    private static final int SLOT_EMPTY = 0;
    private static final int SLOT_BLOCK = 1;
    private static final int SLOT_TOOL = 2;
    private static final int SLOT_FOOD = 3;

    private ItemStackCodec() {}

    public static void write(DataOutput out, ItemStack s) throws IOException {
        StackIo.write(out, s);
    }

    /** Неизвестный id становится заглушкой; компоненты сохраняются как есть. */
    public static ItemStack read(DataInput in) throws IOException {
        return StackIo.read(in);
    }

    /**
     * Слот формата v8 и ниже: предмет лежал порядковым номером перечисления,
     * а износ — отдельным полем. Номер разбирает {@link LegacyItems}, износ
     * становится компонентом.
     */
    public static ItemStack readLegacy(DataInput in) throws IOException {
        int kind = in.readUnsignedByte();
        switch (kind) {
            case SLOT_TOOL: {
                Item t = LegacyItems.tool(in.readUnsignedByte());
                int damage = in.readShort();
                if (t == null)
                    return null;
                ItemStack s = new ItemStack(t, 1);
                if (damage > 0)
                    s.set(Components.DAMAGE, damage);
                return s;
            }
            case SLOT_FOOD: {
                Item f = LegacyItems.food(in.readUnsignedByte());
                int count = in.readShort();
                return f != null && count > 0 ? new ItemStack(f, count) : null;
            }
            case SLOT_BLOCK: {
                Item b = LegacyItems.block(in.readUnsignedByte());
                int count = in.readShort();
                return b != null && count > 0 ? new ItemStack(b, count) : null;
            }
            case SLOT_EMPTY:
            default:
                return null;
        }
    }
}

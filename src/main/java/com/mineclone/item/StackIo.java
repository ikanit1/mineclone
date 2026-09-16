package com.mineclone.item;

import com.mineclone.data.VarInt;
import com.mineclone.world.BlockType;
import com.mineclone.world.FoodType;
import com.mineclone.world.ItemStack;
import com.mineclone.world.ToolType;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * Двоичный вид одной стопки.
 *
 * <p>Лежит рядом со стопкой, а не в {@code save}: этим же кодом пишется и
 * компонент {@link BlockState} — сундук внутри предмета. {@code ItemStackCodec}
 * из пакета сохранений оборачивает его и добавляет чтение старых форматов.
 */
public final class StackIo {

    private static final int EMPTY = 0;
    private static final int BLOCK = 1;
    private static final int TOOL = 2;
    private static final int FOOD = 3;

    private StackIo() {}

    public static void write(DataOutput out, ItemStack s) throws IOException {
        if (s == null || s.count <= 0) {
            out.writeByte(EMPTY);
            return;
        }
        if (s.isTool()) {
            out.writeByte(TOOL);
            VarInt.write(out, s.tool.ordinal());
            VarInt.write(out, s.damage);
        } else if (s.isFood()) {
            out.writeByte(FOOD);
            VarInt.write(out, s.food.ordinal());
            VarInt.write(out, s.count);
        } else {
            out.writeByte(BLOCK);
            VarInt.write(out, s.type.ordinal());
            VarInt.write(out, s.count);
        }
    }

    public static ItemStack read(DataInput in) throws IOException {
        int kind = in.readUnsignedByte();
        switch (kind) {
            case EMPTY:
                return null;
            case TOOL: {
                ToolType t = ToolType.byId(VarInt.read(in));
                int damage = VarInt.read(in);
                if (t == null)
                    return null;
                ItemStack s = new ItemStack(t);
                s.damage = damage;
                return s;
            }
            case FOOD: {
                FoodType f = FoodType.byId(VarInt.read(in));
                int count = VarInt.read(in);
                return f == null ? null : new ItemStack(f, count);
            }
            case BLOCK: {
                int id = VarInt.read(in);
                int count = VarInt.read(in);
                BlockType b = id >= 0 && id < BlockType.VALUES.length ? BlockType.VALUES[id] : null;
                return b == null || b == BlockType.AIR ? null : new ItemStack(b, count);
            }
            default:
                throw new IOException("unknown stack kind " + kind);
        }
    }
}

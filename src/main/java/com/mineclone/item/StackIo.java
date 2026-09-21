package com.mineclone.item;

import com.mineclone.data.ResourceId;
import com.mineclone.data.VarInt;
import com.mineclone.world.ItemStack;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * Двоичный вид одной стопки: {@code UTF id, VarInt число, компоненты}.
 *
 * <p>Лежит рядом со стопкой, а не в {@code save}: этим же кодом пишется и
 * компонент {@link BlockState} — сундук внутри предмета. {@code ItemStackCodec}
 * из пакета сохранений оборачивает его и добавляет чтение старых форматов.
 *
 * <p>Предмет пишется строковым id, а не номером: номер зависит от порядка
 * файлов данных, а порядок меняет любой добавленный мод.
 */
public final class StackIo {

    private StackIo() {}

    public static void write(DataOutput out, ItemStack s) throws IOException {
        if (s == null || s.count <= 0) {
            out.writeUTF("");
            return;
        }
        out.writeUTF(s.item.id.toString());
        VarInt.write(out, s.count);
        s.components().write(out);
    }

    /**
     * Неизвестный id становится заглушкой, а не пустотой: снесённый
     * мод не имеет права молча очистить чужой сундук.
     */
    public static ItemStack read(DataInput in) throws IOException {
        String id = in.readUTF();
        if (id.isEmpty())
            return null;
        int count = VarInt.read(in);
        ItemComponents components = ItemComponents.read(in);
        ResourceId rid;
        try {
            rid = ResourceId.of(id);
        } catch (IllegalArgumentException bad) {
            rid = new ResourceId("unknown", "item");
        }
        ItemRegistry registry = Items.get();
        Item item = registry.get(rid);
        if (item == null)
            item = registry.missing(rid);
        ItemStack s = new ItemStack(item, count);
        s.setComponents(components);
        return s;
    }
}

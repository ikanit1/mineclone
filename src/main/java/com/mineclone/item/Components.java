package com.mineclone.item;

import com.mineclone.data.VarInt;
import com.mineclone.world.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Реестр известных компонентов стопки.
 *
 * <p>Компонент — это всё, что отличает две стопки одного предмета: износ,
 * своё имя, описание, начинка блока. Полями {@link Item} их не сделать —
 * предмет один на все стопки, а износ у каждой кирки свой.
 *
 * <p>Неизвестный компонент не регистрируется и не теряется: его байты
 * {@link ItemComponents} проносит через сохранение нетронутыми.
 */
public final class Components {

    /** Сколько строк описания влезает в подсказку. */
    public static final int MAX_LORE = 4;

    private static final Map<String, ComponentType<?>> BY_ID = new LinkedHashMap<>();

    private Components() {}

    /** Сколько блоков инструмент уже сломал. */
    public static final ComponentType<Integer> DAMAGE = register(new ComponentType<>("damage",
            new ComponentType.Codec<Integer>() {
                @Override
                public void write(java.io.DataOutput out, Integer v) throws java.io.IOException {
                    VarInt.write(out, v);
                }

                @Override
                public Integer read(java.io.DataInput in) throws java.io.IOException {
                    return VarInt.read(in);
                }
            }, null));

    /** Не изнашивается вовсе: творческий инструмент и отладка. */
    public static final ComponentType<Boolean> UNBREAKABLE = register(new ComponentType<>("unbreakable",
            new ComponentType.Codec<Boolean>() {
                @Override
                public void write(java.io.DataOutput out, Boolean v) throws java.io.IOException {
                    out.writeBoolean(v);
                }

                @Override
                public Boolean read(java.io.DataInput in) throws java.io.IOException {
                    return in.readBoolean();
                }
            }, null));

    /** Имя вместо имени предмета. */
    public static final ComponentType<String> CUSTOM_NAME = register(new ComponentType<>("custom_name",
            new ComponentType.Codec<String>() {
                @Override
                public void write(java.io.DataOutput out, String v) throws java.io.IOException {
                    out.writeUTF(v);
                }

                @Override
                public String read(java.io.DataInput in) throws java.io.IOException {
                    return in.readUTF();
                }
            }, null));

    /** Строки описания под именем в подсказке. */
    public static final ComponentType<List<String>> LORE = register(new ComponentType<>("lore",
            new ComponentType.Codec<List<String>>() {
                @Override
                public void write(java.io.DataOutput out, List<String> v) throws java.io.IOException {
                    VarInt.write(out, v.size());
                    for (String s : v)
                        out.writeUTF(s);
                }

                @Override
                public List<String> read(java.io.DataInput in) throws java.io.IOException {
                    int n = VarInt.read(in);
                    List<String> out = new ArrayList<>(Math.min(n, MAX_LORE));
                    for (int i = 0; i < n; i++)
                        out.add(in.readUTF());
                    return List.copyOf(out);
                }
            },
            // Лишние строки режутся, а не валят запись: слишком длинное
            // описание — беда косметическая, а исключение посреди клика нет.
            v -> List.copyOf(v.size() <= MAX_LORE ? v : v.subList(0, MAX_LORE))));

    /** Meta, сундук и печь, снятые пипеткой вместе с блоком. */
    public static final ComponentType<BlockState> BLOCK_STATE = register(new ComponentType<>("block_state",
            new ComponentType.Codec<BlockState>() {
                private static final int HAS_CHEST = 1;
                private static final int HAS_FURNACE = 2;

                @Override
                public void write(java.io.DataOutput out, BlockState v) throws java.io.IOException {
                    out.writeByte(v.meta());
                    int flags = (v.hasChest() ? HAS_CHEST : 0) | (v.hasFurnace() ? HAS_FURNACE : 0);
                    out.writeByte(flags);
                    if (v.hasChest()) {
                        VarInt.write(out, v.chest().size());
                        for (ItemStack s : v.chest())
                            StackIo.write(out, s);
                    }
                    if (v.hasFurnace()) {
                        FurnaceState f = v.furnace();
                        StackIo.write(out, f.input());
                        StackIo.write(out, f.fuel());
                        StackIo.write(out, f.output());
                        out.writeFloat(f.burnLeft());
                        out.writeFloat(f.burnMax());
                        out.writeFloat(f.cook());
                    }
                }

                @Override
                public BlockState read(java.io.DataInput in) throws java.io.IOException {
                    byte meta = in.readByte();
                    int flags = in.readUnsignedByte();
                    List<ItemStack> chest = null;
                    if ((flags & HAS_CHEST) != 0) {
                        int n = VarInt.read(in);
                        chest = new ArrayList<>(n);
                        for (int i = 0; i < n; i++)
                            chest.add(StackIo.read(in));
                    }
                    FurnaceState furnace = null;
                    if ((flags & HAS_FURNACE) != 0) {
                        ItemStack input = StackIo.read(in);
                        ItemStack fuel = StackIo.read(in);
                        ItemStack output = StackIo.read(in);
                        furnace = new FurnaceState(input, fuel, output,
                                in.readFloat(), in.readFloat(), in.readFloat());
                    }
                    return new BlockState(meta, chest, furnace);
                }
            }, null));

    private static <T> ComponentType<T> register(ComponentType<T> type) {
        if (BY_ID.put(type.id, type) != null)
            throw new IllegalStateException("duplicate component " + type.id);
        return type;
    }

    /** {@code null} — компонент чужой; его байты проносятся как есть. */
    public static ComponentType<?> byId(String id) {
        return BY_ID.get(id);
    }

    public static List<ComponentType<?>> all() {
        return List.copyOf(BY_ID.values());
    }
}

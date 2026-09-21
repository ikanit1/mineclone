package com.mineclone.item;

import com.mineclone.data.VarInt;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.TreeMap;

/**
 * Набор компонентов одной стопки — неизменяемый.
 *
 * <p>Неизменяемость здесь не украшение: по равенству компонентов решается,
 * сливаются ли две стопки, и общий на две стопки изменяемый набор сливал бы
 * их сегодня и разводил завтра. Любое изменение возвращает новый набор.
 *
 * <p>Порядок не значит ничего: {@code damage} и {@code custom_name},
 * положенные в разном порядке, — один и тот же набор. Хранится отсортированным
 * по id, поэтому и байты одинаковых наборов совпадают.
 *
 * <p>Чужой компонент (мод, будущая версия) хранится байтами и переживает
 * сохранение нетронутым: снесённый мод не имеет права стирать то, чего игра
 * не понимает.
 */
public final class ItemComponents {

    public static final ItemComponents EMPTY = new ItemComponents(new TreeMap<>());

    /** Байты чужого компонента: обёртка ради равенства по содержимому. */
    private record Raw(byte[] bytes) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Raw r && Arrays.equals(bytes, r.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }
    }

    private final TreeMap<String, Object> values;

    private ItemComponents(TreeMap<String, Object> values) {
        this.values = values;
    }

    // ---------------------------------------------------------------- доступ

    @SuppressWarnings("unchecked")
    public <T> T get(ComponentType<T> type) {
        Object v = values.get(type.id);
        return v instanceof Raw ? null : (T) v;
    }

    public <T> T getOr(ComponentType<T> type, T fallback) {
        T v = get(type);
        return v == null ? fallback : v;
    }

    public boolean has(ComponentType<?> type) {
        return values.containsKey(type.id);
    }

    /** Байты чужого компонента или {@code null}. */
    public byte[] raw(String id) {
        Object v = values.get(id);
        return v instanceof Raw r ? r.bytes().clone() : null;
    }

    public Set<String> ids() {
        return Collections.unmodifiableSet(values.keySet());
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public int size() {
        return values.size();
    }

    // -------------------------------------------------------------- правки

    /** {@code null} убирает компонент: отдельный вызов ради этого излишен. */
    public <T> ItemComponents with(ComponentType<T> type, T value) {
        if (value == null)
            return without(type);
        T normalized = type.normalize(value);
        Object old = values.get(type.id);
        if (normalized.equals(old))
            return this;
        TreeMap<String, Object> copy = new TreeMap<>(values);
        copy.put(type.id, normalized);
        return new ItemComponents(copy);
    }

    public ItemComponents without(ComponentType<?> type) {
        return without(type.id);
    }

    public ItemComponents without(String id) {
        if (!values.containsKey(id))
            return this;
        if (values.size() == 1)
            return EMPTY;
        TreeMap<String, Object> copy = new TreeMap<>(values);
        copy.remove(id);
        return new ItemComponents(copy);
    }

    /** Чужой компонент: байты кладутся как есть, без попытки их понять. */
    public ItemComponents withRaw(String id, byte[] bytes) {
        if (bytes == null)
            return without(id);
        TreeMap<String, Object> copy = new TreeMap<>(values);
        copy.put(id, new Raw(bytes.clone()));
        return new ItemComponents(copy);
    }

    // ---------------------------------------------------------------- байты

    /**
     * Формат: {@code VarInt n}, затем {@code n} раз
     * {@code UTF id, VarInt длина, байты}.
     *
     * <p>Длина нужна именно для того, чтобы чужой компонент можно было
     * пропустить, не умея его читать.
     */
    public void write(DataOutput out) throws IOException {
        VarInt.write(out, values.size());
        for (var e : values.entrySet()) {
            out.writeUTF(e.getKey());
            byte[] bytes = e.getValue() instanceof Raw r ? r.bytes() : encode(e.getKey(), e.getValue());
            VarInt.write(out, bytes.length);
            out.write(bytes);
        }
    }

    public static ItemComponents read(DataInput in) throws IOException {
        int n = VarInt.read(in);
        if (n == 0)
            return EMPTY;
        TreeMap<String, Object> values = new TreeMap<>();
        for (int i = 0; i < n; i++) {
            String id = in.readUTF();
            int length = VarInt.read(in);
            byte[] bytes = new byte[length];
            in.readFully(bytes);
            ComponentType<?> type = Components.byId(id);
            if (type == null) {
                values.put(id, new Raw(bytes));
                continue;
            }
            try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(bytes))) {
                values.put(id, type.codec.read(data));
            }
        }
        return new ItemComponents(values);
    }

    @SuppressWarnings("unchecked")
    private static byte[] encode(String id, Object value) throws IOException {
        ComponentType<Object> type = (ComponentType<Object>) Components.byId(id);
        if (type == null)
            throw new IOException("no codec for component " + id);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buf)) {
            type.codec.write(out, value);
        }
        return buf.toByteArray();
    }

    // ------------------------------------------------------------ равенство

    @Override
    public boolean equals(Object o) {
        return o instanceof ItemComponents c && values.equals(c.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return values.isEmpty() ? "{}" : values.toString();
    }
}

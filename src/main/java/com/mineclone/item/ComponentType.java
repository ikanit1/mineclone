package com.mineclone.item;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.function.UnaryOperator;

/**
 * Вид компонента стопки: строковый id и способ записать значение в байты.
 *
 * <p>Id строковый, а не порядковый номер: компоненты приходят и из модов, и
 * из будущих версий игры, а чужой компонент обязан пережить сохранение —
 * значит его надо уметь опознать по имени, не зная о нём ничего другого.
 *
 * @param <T> тип значения; он неизменяем, поэтому компонент можно раздавать
 *            наружу без копий
 */
public final class ComponentType<T> {

    public final String id;
    public final Codec<T> codec;
    private final UnaryOperator<T> normalize;

    public interface Codec<T> {
        void write(DataOutput out, T value) throws IOException;

        T read(DataInput in) throws IOException;
    }

    ComponentType(String id, Codec<T> codec, UnaryOperator<T> normalize) {
        this.id = id;
        this.codec = codec;
        this.normalize = normalize;
    }

    /**
     * Приводит значение к хранимому виду: копирует изменяемое, обрезает
     * слишком длинное. Значение внутри {@link ItemComponents} обязано быть
     * неизменяемым — иначе две стопки, равные вчера, разойдутся сегодня.
     */
    T normalize(T value) {
        return normalize == null ? value : normalize.apply(value);
    }

    @Override
    public String toString() {
        return id;
    }
}

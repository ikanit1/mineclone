package com.mineclone.ui.container;

/**
 * Прямоугольник слотов на экране: хранилище, роль и ширина сетки.
 *
 * <p>Id строковый, а не индекс: по нему автопилот находит слот в окне, и
 * добавленная группа не должна сдвигать чужие номера.
 */
public final class SlotGroup {

    public final String id;
    public final SlotRole role;
    public final SlotStorage storage;
    /** Сколько слотов в ряду; высота получается делением. */
    public final int columns;

    public SlotGroup(String id, SlotRole role, SlotStorage storage, int columns) {
        this.id = id;
        this.role = role;
        this.storage = storage;
        this.columns = Math.max(1, columns);
    }

    public int size() {
        return storage.size();
    }

    public int rows() {
        return (storage.size() + columns - 1) / columns;
    }

    @Override
    public String toString() {
        return id + "[" + role + "]";
    }
}

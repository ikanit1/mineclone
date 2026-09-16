package com.mineclone.world;

/**
 * Предмет, лежащий в мире, — в том виде, в каком он едет в сейв чанка.
 *
 * Только данные: физика и магнит живут в сущности
 * {@code com.mineclone.world.entity.ItemEntity}, а сюда она складывается при
 * выгрузке чанка и отсюда же поднимается при загрузке. Предметы — это
 * имущество игрока: выпавшее из разбитого сундука не имеет права исчезнуть
 * оттого, что игрок вышел из игры.
 */
public final class DroppedItem {
    public final ItemStack stack;
    public final float x, y, z;
    /** Сколько предмет уже пролежал, секунды — от этого считается исчезновение. */
    public final float age;

    public DroppedItem(ItemStack stack, float x, float y, float z, float age) {
        this.stack = stack;
        this.x = x;
        this.y = y;
        this.z = z;
        this.age = age;
    }
}

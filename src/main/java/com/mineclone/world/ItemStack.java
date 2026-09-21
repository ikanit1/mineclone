package com.mineclone.world;

import com.mineclone.item.AttackSpec;
import com.mineclone.item.ComponentType;
import com.mineclone.item.Components;
import com.mineclone.item.FoodSpec;
import com.mineclone.item.Item;
import com.mineclone.item.ItemComponents;
import com.mineclone.item.Items;
import com.mineclone.item.ToolSpec;

/**
 * Стопка одинаковых предметов.
 *
 * <p>Предмет — ссылка на запись реестра, а не одно из трёх полей «блок,
 * инструмент, еда»: блок, инструмент и еда перестали быть тремя разными
 * видами стопки и стали необязательными частями одного предмета. Проверка
 * «это инструмент» — вопрос к предмету ({@code tool() != null}), а не к
 * форме стопки.
 *
 * <p>Всё, что отличает две стопки одного предмета — износ, своё имя, начинка
 * сундука, — живёт в {@link ItemComponents}: они и решают, сливаются ли
 * стопки. Изнашиваемый предмет не стопкуется вовсе ({@code maxStack == 1}),
 * поэтому две кирки с разным износом не могут потерять одну из прочностей.
 */
public final class ItemStack {

    /** Запись реестра: одна на все стопки этого предмета. */
    public final Item item;
    public int count;

    private ItemComponents components = ItemComponents.EMPTY;

    public ItemStack(Item item, int count) {
        if (item == null)
            throw new IllegalArgumentException("ItemStack item must not be null");
        this.item = item;
        this.count = Math.max(1, Math.min(item.maxStack, count));
    }

    /** Стопка блока. Технический блок предметом не бывает — это ошибка кода. */
    public ItemStack(BlockType block, int count) {
        this(requireItem(block), count);
    }

    private static Item requireItem(BlockType block) {
        if (block == null)
            throw new IllegalArgumentException("ItemStack block must not be null");
        Item item = Items.get().forBlock(block);
        if (item == null)
            throw new IllegalArgumentException("no item for block " + block);
        return item;
    }

    public static ItemStack of(String id) {
        return of(id, 1);
    }

    public static ItemStack of(String id, int count) {
        return new ItemStack(Items.get().require(id), count);
    }

    // ----------------------------------------------------------- что это

    public BlockType block() {
        return item.block;
    }

    public ToolSpec tool() {
        return item.tool;
    }

    public FoodSpec food() {
        return item.food;
    }

    public AttackSpec attack() {
        return item.attack;
    }

    public boolean hasDurability() {
        return item.durability > 0;
    }

    public int maxStack() {
        return item.maxStack;
    }

    // -------------------------------------------------------- компоненты

    public ItemComponents components() {
        return components;
    }

    public void setComponents(ItemComponents c) {
        components = c == null ? ItemComponents.EMPTY : c;
    }

    public <T> T get(ComponentType<T> type) {
        return components.get(type);
    }

    /** Меняет стопку на месте и возвращает её же — чтобы писать цепочкой. */
    public <T> ItemStack set(ComponentType<T> type, T value) {
        components = components.with(type, value);
        return this;
    }

    public int damage() {
        Integer d = components.get(Components.DAMAGE);
        return d == null ? 0 : d;
    }

    public void setDamage(int d) {
        components = components.with(Components.DAMAGE, d <= 0 ? null : d);
    }

    /** Остаток прочности 0..1. У предмета без износа всегда 1. */
    public float condition() {
        if (item.durability <= 0)
            return 1f;
        return Math.max(0f, 1f - damage() / (float) item.durability);
    }

    /**
     * Сносит одно очко прочности.
     *
     * @return true, если предмет после этого сломался
     */
    public boolean wear() {
        if (item.durability <= 0 || Boolean.TRUE.equals(components.get(Components.UNBREAKABLE)))
            return false;
        int d = damage() + 1;
        setDamage(d);
        return d >= item.durability;
    }

    // ------------------------------------------------------------- показ

    public String displayName() {
        String custom = components.get(Components.CUSTOM_NAME);
        return custom != null ? custom : item.name;
    }

    /**
     * Тайл плоской иконки. У блока-куба своего тайла нет — иконка собирается
     * из граней, — но спросить всё равно могут, и ответ должен быть разумным.
     */
    public int iconTile() {
        if (item.iconTile >= 0)
            return item.iconTile;
        BlockType b = item.block;
        if (b == null)
            return 0;
        return b == BlockType.GRASS || b == BlockType.SNOWY_GRASS ? b.topTile : b.sideTile;
    }

    // ------------------------------------------------------------ слияние

    /**
     * Можно ли долить {@code other} в эту стопку: тот же предмет, те же
     * компоненты и предмет вообще стопкуется.
     */
    public boolean stacksWith(ItemStack other) {
        return other != null && item == other.item && item.maxStack > 1
                && components.equals(other.components);
    }

    public boolean isFull() {
        return count >= item.maxStack;
    }

    /**
     * Доливает до {@code amount} предметов, не переполняя стопку.
     *
     * @return остаток, который не влез
     */
    public int addUpTo(int amount) {
        if (amount <= 0)
            return 0;
        int added = Math.min(item.maxStack - count, amount);
        if (added <= 0)
            return amount;
        count += added;
        return amount - added;
    }

    public ItemStack copy() {
        return copyWithCount(count);
    }

    public ItemStack copyWithCount(int n) {
        ItemStack s = new ItemStack(item, n);
        s.components = components;
        return s;
    }

    /**
     * Совпадает ли содержимое двух стопок целиком: предмет, число, компоненты.
     *
     * <p>Не {@code equals}: стопка изменяема и живёт в массивах слотов, где
     * сравнение по тождеству и есть то, что нужно. Содержимое сравнивается
     * отдельным вопросом — его задают снимок блока и проверка сейва.
     */
    public boolean contentEquals(ItemStack o) {
        if (this == o)
            return true;
        return o != null && item == o.item && count == o.count
                && components.equals(o.components);
    }

    /** Хеш того же содержимого, что сравнивает {@link #contentEquals}. */
    public int contentHash() {
        return (item.id.hashCode() * 31 + count) * 31 + components.hashCode();
    }

    @Override
    public String toString() {
        return count + "x" + item.id + (components.isEmpty() ? "" : components.toString());
    }
}

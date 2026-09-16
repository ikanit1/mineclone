package com.mineclone.world;

/**
 * Стопка одинаковых предметов.
 *
 * Предмет — это блок, инструмент или еда; ровно одно из полей {@code type},
 * {@code tool} и {@code food} не null. Блоки и еда стопкуются до
 * {@link #MAX_STACK}, инструменты — никогда: у каждого свой износ, и слить
 * два в одну стопку значит потерять одну из прочностей.
 */
public final class ItemStack {
    public static final int MAX_STACK = 64;

    /** Блок, если это блочная стопка; null у инструмента. */
    public BlockType type;
    /** Инструмент, если это он; null иначе. */
    public final ToolType tool;
    /** Еда, если это она; null иначе. */
    public final FoodType food;
    public int count;
    /** Сколько блоков инструмент уже сломал. Для блоков всегда 0. */
    public int damage;

    public ItemStack(BlockType type, int count) {
        if (type == null) throw new IllegalArgumentException("ItemStack type must not be null");
        this.type = type;
        this.tool = null;
        this.food = null;
        this.count = Math.max(1, Math.min(MAX_STACK, count));
    }

    public ItemStack(ToolType tool) {
        if (tool == null) throw new IllegalArgumentException("ItemStack tool must not be null");
        this.type = null;
        this.tool = tool;
        this.food = null;
        this.count = 1;
    }

    public ItemStack(FoodType food, int count) {
        if (food == null) throw new IllegalArgumentException("ItemStack food must not be null");
        this.type = null;
        this.tool = null;
        this.food = food;
        this.count = Math.max(1, Math.min(MAX_STACK, count));
    }

    public boolean isTool() {
        return tool != null;
    }

    public boolean isFood() {
        return food != null;
    }

    /** Остаток прочности 0..1. Для блоков всегда 1. */
    public float condition() {
        if (tool == null) return 1f;
        return Math.max(0f, 1f - damage / (float) tool.durability);
    }

    /**
     * Сносит одно очко прочности.
     *
     * @return true, если инструмент после этого сломался
     */
    public boolean wear() {
        if (tool == null) return false;
        return ++damage >= tool.durability;
    }

    /** Тайл атласа для иконки. Трава показывается верхней гранью, как в хотбаре. */
    public int iconTile() {
        if (tool != null) return tool.tile;
        if (food != null) return food.tile;
        return type == BlockType.GRASS ? type.topTile : type.sideTile;
    }

    public String displayName() {
        if (tool != null) return tool.displayName;
        if (food != null) return food.displayName;
        return type.name();
    }

    /**
     * Можно ли долить {@code other} в эту стопку. Инструменты не стопкуются
     * никогда — у каждого свой износ.
     */
    public boolean stacksWith(ItemStack other) {
        if (other == null) return false;
        if (tool != null || other.tool != null) return false;
        if (food != null || other.food != null) return food == other.food;
        return type == other.type;
    }

    public boolean isFull() {
        return tool != null || count >= MAX_STACK;
    }

    /** Adds up to {@code amount} items, capped at MAX_STACK.
     *  @return the leftover that did not fit. */
    public int addUpTo(int amount) {
        if (amount <= 0) return 0;
        if (tool != null) return amount;   // инструмент не доливается
        int space = MAX_STACK - count;
        int added = Math.min(space, amount);
        count += added;
        return amount - added;
    }

    /**
     * Совпадает ли содержимое двух стопок целиком: предмет, число и износ.
     *
     * <p>Не {@code equals}: стопка изменяема и живёт в массивах слотов, где
     * сравнение по тождеству и есть то, что нужно. Содержимое сравнивается
     * отдельным вопросом — его задаёт снимок блока и проверка сейва.
     */
    public boolean contentEquals(ItemStack o) {
        if (o == null) return false;
        if (this == o) return true;
        return type == o.type && tool == o.tool && food == o.food
                && count == o.count && damage == o.damage;
    }

    /** Хеш того же содержимого, что сравнивает {@link #contentEquals}. */
    public int contentHash() {
        int h = type == null ? 0 : type.ordinal() + 1;
        h = h * 31 + (tool == null ? 0 : tool.ordinal() + 1);
        h = h * 31 + (food == null ? 0 : food.ordinal() + 1);
        h = h * 31 + count;
        return h * 31 + damage;
    }

    public ItemStack copy() {
        if (tool != null) {
            ItemStack s = new ItemStack(tool);
            s.damage = damage;
            return s;
        }
        if (food != null)
            return new ItemStack(food, count);
        return new ItemStack(type, count);
    }
}

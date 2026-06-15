package com.mineclone.world;

/** A stack of identical items. Items are blocks for now (tools come later).
 *  {@code type} is always a real block (never AIR). {@code count} is 1..MAX_STACK. */
public final class ItemStack {
    public static final int MAX_STACK = 64;

    public BlockType type;
    public int count;

    public ItemStack(BlockType type, int count) {
        if (type == null) throw new IllegalArgumentException("ItemStack type must not be null");
        this.type = type;
        this.count = Math.max(1, Math.min(MAX_STACK, count));
    }

    public boolean isFull() {
        return count >= MAX_STACK;
    }

    /** Adds up to {@code amount} items, capped at MAX_STACK.
     *  @return the leftover that did not fit. */
    public int addUpTo(int amount) {
        if (amount <= 0) return 0;
        int space = MAX_STACK - count;
        int added = Math.min(space, amount);
        count += added;
        return amount - added;
    }

    public ItemStack copy() {
        return new ItemStack(type, count);
    }
}

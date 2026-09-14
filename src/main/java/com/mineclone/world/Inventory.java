package com.mineclone.world;

/** Player inventory: 36 slots (0..8 hotbar, 9..35 main). Empty slot = null.
 *  Pure logic, no rendering — unit-testable. */
public final class Inventory {
    public static final int SIZE = 36;
    public static final int HOTBAR = 9;

    private final ItemStack[] slots = new ItemStack[SIZE];

    public ItemStack get(int i) {
        if (i < 0 || i >= SIZE) return null;
        return slots[i];
    }
    public void set(int i, ItemStack s) {
        if (i < 0 || i >= SIZE) return;
        slots[i] = s;
    }
    public int size() { return SIZE; }

    public boolean canAdd(BlockType type, int amount) {
        for (ItemStack s : slots) {
            if (s == null) amount -= ItemStack.MAX_STACK;
            else if (s.type == type) amount -= ItemStack.MAX_STACK - s.count;
            if (amount <= 0) return true;
        }
        return amount <= 0;
    }

    /** Adds items, merging into matching stacks first, then into empty slots.
     *  @return leftover that did not fit. */
    public int add(BlockType type, int amount) {
        if (type == null || type == BlockType.AIR || amount <= 0) return 0;
        // pass 1: top up existing stacks of this type
        for (int i = 0; i < SIZE && amount > 0; i++) {
            ItemStack s = slots[i];
            if (s != null && s.type == type && !s.isFull())
                amount = s.addUpTo(amount);
        }
        // pass 2: fill empty slots
        for (int i = 0; i < SIZE && amount > 0; i++) {
            if (slots[i] == null) {
                int put = Math.min(ItemStack.MAX_STACK, amount);
                slots[i] = new ItemStack(type, put);
                amount -= put;
            }
        }
        return amount;
    }

    /** Decrements the stack in {@code slot} by one; clears the slot at zero. */
    public void removeOne(int slot) {
        if (slot < 0 || slot >= SIZE) return;
        ItemStack s = slots[slot];
        if (s == null) return;
        if (--s.count <= 0) slots[slot] = null;
    }

    /** Left-click interaction. Returns the new cursor stack (may be null). */
    public ItemStack leftClick(int slot, ItemStack cursor) {
        if (slot < 0 || slot >= SIZE) return cursor;
        ItemStack s = slots[slot];
        if (cursor == null) {        // pick up the whole slot
            slots[slot] = null;
            return s;
        }
        if (s == null) {             // drop the whole cursor
            slots[slot] = cursor;
            return null;
        }
        if (s.type == cursor.type) { // merge, remainder stays on cursor
            int leftover = s.addUpTo(cursor.count);
            if (leftover == 0) return null;
            cursor.count = leftover;
            return cursor;
        }
        // different types: swap
        slots[slot] = cursor;
        return s;
    }

    /** Right-click interaction. Returns the new cursor stack (may be null). */
    public ItemStack rightClick(int slot, ItemStack cursor) {
        if (slot < 0 || slot >= SIZE) return cursor;
        ItemStack s = slots[slot];
        if (cursor == null) {        // take half (ceil) onto cursor
            if (s == null) return null;
            int half = (s.count + 1) / 2;
            ItemStack taken = new ItemStack(s.type, half);
            s.count -= half;
            if (s.count <= 0) slots[slot] = null;
            return taken;
        }
        if (s == null) {             // deposit one into empty slot
            slots[slot] = new ItemStack(cursor.type, 1);
            if (--cursor.count <= 0) return null;
            return cursor;
        }
        if (s.type == cursor.type && !s.isFull()) { // deposit one onto same type
            s.count++;
            if (--cursor.count <= 0) return null;
            return cursor;
        }
        if (s.type != cursor.type) { // different types: swap
            slots[slot] = cursor;
            return s;
        }
        return cursor;               // same type but full: no-op
    }

    /** True when the selected slot has something placeable. */
    public boolean hasItem(int slot) {
        if (slot < 0 || slot >= SIZE) return false;
        return slots[slot] != null;
    }
}

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
            else if (!s.isTool() && !s.isFood() && s.type == type)
                amount -= ItemStack.MAX_STACK - s.count;
            if (amount <= 0) return true;
        }
        return amount <= 0;
    }

    /**
     * Добавляет еду, сливая в подходящие стопки, потом в пустые слоты.
     * @return остаток, который не влез
     */
    public int addFood(FoodType food, int amount) {
        if (food == null || amount <= 0) return 0;
        for (int i = 0; i < SIZE && amount > 0; i++) {
            ItemStack s = slots[i];
            if (s != null && s.isFood() && s.food == food && !s.isFull())
                amount = s.addUpTo(amount);
        }
        for (int i = 0; i < SIZE && amount > 0; i++) {
            if (slots[i] == null) {
                int put = Math.min(ItemStack.MAX_STACK, amount);
                slots[i] = new ItemStack(food, put);
                amount -= put;
            }
        }
        return amount;
    }

    /** Кладёт предмет в первый свободный слот. @return true, если влез. */
    public boolean addItem(ItemStack item) {
        if (item == null) return true;
        for (int i = 0; i < SIZE; i++)
            if (slots[i] == null) {
                slots[i] = item;
                return true;
            }
        return false;
    }

    /** Adds items, merging into matching stacks first, then into empty slots.
     *  @return leftover that did not fit. */
    public int add(BlockType type, int amount) {
        if (type == null || type == BlockType.AIR || amount <= 0) return 0;
        // pass 1: top up existing stacks of this type
        for (int i = 0; i < SIZE && amount > 0; i++) {
            ItemStack s = slots[i];
            if (s != null && !s.isTool() && !s.isFood() && s.type == type && !s.isFull())
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
        return leftClick(slots, slot, cursor);
    }

    /**
     * То же самое, но над произвольным массивом слотов.
     *
     * Правила слияния стопок нетривиальны (инструмент не делится, еда не
     * смешивается с блоками, остаток остаётся на курсоре), и второй копии у
     * них быть не должно: сундук обязан вести себя ровно как инвентарь, иначе
     * игрок обнаруживает разницу в самый неподходящий момент.
     */
    public static ItemStack leftClick(ItemStack[] slots, int slot, ItemStack cursor) {
        if (slots == null || slot < 0 || slot >= slots.length) return cursor;
        ItemStack s = slots[slot];
        if (cursor == null) {        // pick up the whole slot
            slots[slot] = null;
            return s;
        }
        if (s == null) {             // drop the whole cursor
            slots[slot] = cursor;
            return null;
        }
        if (s.stacksWith(cursor)) { // merge, remainder stays on cursor
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
        return rightClick(slots, slot, cursor);
    }

    /** То же самое над произвольным массивом слотов — см. {@link #leftClick}. */
    public static ItemStack rightClick(ItemStack[] slots, int slot, ItemStack cursor) {
        if (slots == null || slot < 0 || slot >= slots.length) return cursor;
        ItemStack s = slots[slot];
        if (cursor == null) {        // take half (ceil) onto cursor
            if (s == null) return null;
            if (s.isTool()) {        // инструмент не делится — забираем целиком
                slots[slot] = null;
                return s;
            }
            int half = (s.count + 1) / 2;
            ItemStack taken = s.isFood() ? new ItemStack(s.food, half)
                                         : new ItemStack(s.type, half);
            s.count -= half;
            if (s.count <= 0) slots[slot] = null;
            return taken;
        }
        if (s == null) {             // deposit one into empty slot
            if (cursor.isTool()) {   // инструмент кладётся целиком
                slots[slot] = cursor;
                return null;
            }
            slots[slot] = cursor.isFood() ? new ItemStack(cursor.food, 1)
                                          : new ItemStack(cursor.type, 1);
            if (--cursor.count <= 0) return null;
            return cursor;
        }
        if (s.stacksWith(cursor) && !s.isFull()) { // deposit one onto same type
            s.count++;
            if (--cursor.count <= 0) return null;
            return cursor;
        }
        if (!s.stacksWith(cursor)) { // different items: swap
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

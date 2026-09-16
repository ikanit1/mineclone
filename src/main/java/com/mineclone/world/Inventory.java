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

    /** Влезет ли ещё {@code amount} таких же предметов. */
    public boolean canAdd(ItemStack sample, int amount) {
        if (sample == null || amount <= 0) return true;
        int max = sample.maxStack();
        for (ItemStack s : slots) {
            if (s == null) amount -= max;
            else if (s.stacksWith(sample)) amount -= max - s.count;
            if (amount <= 0) return true;
        }
        return amount <= 0;
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

    /**
     * Добавляет стопку, сливая в подходящие, потом в пустые слоты.
     *
     * <p>Кладёт копии, а не саму {@code s}: вызывающий отдаёт содержимое, а не
     * ссылку, и оставшаяся у него на руках стопка не должна оказаться той же
     * самой, что лежит теперь в инвентаре.
     *
     * @return остаток, который не влез
     */
    public int add(ItemStack s) {
        if (s == null || s.count <= 0) return 0;
        int amount = s.count;
        int max = s.maxStack();
        for (int i = 0; i < SIZE && amount > 0; i++) {
            ItemStack in = slots[i];
            if (in != null && in.stacksWith(s) && !in.isFull())
                amount = in.addUpTo(amount);
        }
        for (int i = 0; i < SIZE && amount > 0; i++) {
            if (slots[i] == null) {
                int put = Math.min(max, amount);
                slots[i] = s.copyWithCount(put);
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

    /**
     * Хеш содержимого целиком. Нужен там, где надо заметить, что инвентарь
     * изменился, не сравнивая его со снимком: предметы, числа и компоненты.
     */
    public int contentHash() {
        int h = 1;
        for (ItemStack s : slots)
            h = h * 31 + (s == null ? 0 : s.contentHash());
        return h;
    }

    /** Left-click interaction. Returns the new cursor stack (may be null). */
    public ItemStack leftClick(int slot, ItemStack cursor) {
        return leftClick(slots, slot, cursor);
    }

    /**
     * То же самое, но над произвольным массивом слотов.
     *
     * Правила слияния стопок нетривиальны (нестопкуемое не делится, разные
     * компоненты не смешиваются, остаток остаётся на курсоре), и второй копии
     * у них быть не должно: сундук обязан вести себя ровно как инвентарь,
     * иначе игрок обнаруживает разницу в самый неподходящий момент.
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
        // different items: swap
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
            if (s.maxStack() <= 1) { // нестопкуемое не делится — забираем целиком
                slots[slot] = null;
                return s;
            }
            int half = (s.count + 1) / 2;
            ItemStack taken = s.copyWithCount(half);
            s.count -= half;
            if (s.count <= 0) slots[slot] = null;
            return taken;
        }
        if (s == null) {             // deposit one into empty slot
            if (cursor.maxStack() <= 1) {   // нестопкуемое кладётся целиком
                slots[slot] = cursor;
                return null;
            }
            slots[slot] = cursor.copyWithCount(1);
            if (--cursor.count <= 0) return null;
            return cursor;
        }
        if (s.stacksWith(cursor) && !s.isFull()) { // deposit one onto the same item
            s.count++;
            if (--cursor.count <= 0) return null;
            return cursor;
        }
        if (!s.stacksWith(cursor)) { // different items: swap
            slots[slot] = cursor;
            return s;
        }
        return cursor;               // same item but full: no-op
    }

    /** True when the selected slot has something placeable. */
    public boolean hasItem(int slot) {
        if (slot < 0 || slot >= SIZE) return false;
        return slots[slot] != null;
    }
}

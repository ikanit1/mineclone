package com.mineclone.world;

/**
 * Состояние одной печи: три слота и два таймера.
 *
 * Ни одного GL-вызова и ни одной ссылки на мир — вся логика переплавки живёт
 * здесь и проверяется обычными тестами. Печь не знает, где она стоит; за это
 * отвечает чанк, который её хранит.
 */
public final class Furnace {

    /** Что плавим. */
    public ItemStack input;
    /** Чем топим. */
    public ItemStack fuel;
    /** Что получилось. */
    public ItemStack output;

    /** Сколько секунд осталось гореть текущей единице топлива. */
    public float burnLeft;
    /** Сколько она горела всего — на неё рисуется шкала пламени. */
    public float burnMax;
    /** Сколько секунд плавится текущая единица. */
    public float cook;

    /** Горит ли печь прямо сейчас. */
    public boolean isLit() {
        return burnLeft > 0f;
    }

    /** Доля прогресса переплавки 0..1 — на неё рисуется стрелка. */
    public float cookFraction() {
        return Math.max(0f, Math.min(1f, cook / Smelting.COOK_TIME));
    }

    /** Доля оставшегося топлива 0..1 — на неё рисуется пламя. */
    public float burnFraction() {
        return burnMax <= 0f ? 0f : Math.max(0f, Math.min(1f, burnLeft / burnMax));
    }

    /** Пусто ли внутри — можно ли ломать печь без потерь. */
    public boolean isEmpty() {
        return input == null && fuel == null && output == null;
    }

    /**
     * Шаг времени.
     *
     * @return true, если изменилось содержимое слотов — только тогда чанк
     *         надо помечать изменённым. Помечать его каждый тик горения
     *         означало бы переписывать файл чанка четыре раза в секунду всё
     *         время, пока печь работает.
     */
    public boolean tick(float dt) {
        boolean slotsChanged = false;
        ItemStack want = Smelting.result(input);
        boolean canOutput = want != null && fits(want);

        // Новая единица топлива тратится только если есть что плавить и куда
        // класть: иначе печь сжигала бы уголь впустую.
        if (burnLeft <= 0f && canOutput) {
            float seconds = Smelting.fuelSeconds(fuel);
            if (seconds > 0f) {
                burnLeft = seconds;
                burnMax = seconds;
                fuel = shrink(fuel);
                slotsChanged = true;
            }
        }

        if (burnLeft > 0f) {
            burnLeft = Math.max(0f, burnLeft - dt);
            if (canOutput) {
                cook += dt;
                if (cook >= Smelting.COOK_TIME) {
                    cook -= Smelting.COOK_TIME;
                    if (output == null)
                        output = want;
                    else
                        output.count++;
                    input = shrink(input);
                    slotsChanged = true;
                }
            } else {
                cook = 0f;
            }
        } else {
            // Остывает вдвое быстрее, чем грелась: вынул уголь — прогресс
            // тает на глазах, и это видно по стрелке.
            cook = Math.max(0f, cook - dt * 2f);
        }
        return slotsChanged;
    }

    /** Влезет ли результат в выходной слот. */
    private boolean fits(ItemStack want) {
        if (output == null)
            return true;
        return output.stacksWith(want) && output.count < output.maxStack();
    }

    private static ItemStack shrink(ItemStack s) {
        if (s == null)
            return null;
        if (--s.count <= 0)
            return null;
        return s;
    }
}

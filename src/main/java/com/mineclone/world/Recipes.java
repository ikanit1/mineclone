package com.mineclone.world;

import com.mineclone.item.Item;
import com.mineclone.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * Крафт: бесформенные рецепты в сетке 2x2.
 *
 * Без формы — сознательно. Сетка 3x3 и формованные рецепты требуют
 * отдельного блока верстака, своего экрана и понятия «пустая клетка внутри
 * формы»; всё это стоит дорого, а первая вертикаль прогресса (дерево →
 * камень → железо → алмаз) прекрасно кодируется количеством материала:
 * кирка берёт три единицы, топор две, лопата одну.
 *
 * Рукоять — доски, а не палки. Палка была бы ещё одной позицией в реестре
 * ради одной клетки рецепта.
 */
public final class Recipes {

    /**
     * Один рецепт: что нужно и что получится.
     *
     * @param need        основной материал
     * @param needCount   сколько его нужно
     * @param handle      второй материал (рукоять) или null
     * @param handleCount сколько нужно рукояти
     * @param result      что выходит
     * @param resultCount сколько выходит
     */
    public record Recipe(Item need, int needCount,
                         Item handle, int handleCount,
                         Item result, int resultCount) {

        public String resultName() {
            return result.name;
        }
    }

    private static Recipe tool(String material, int count, String out) {
        return new Recipe(item(material), count, item("planks"), 1, item(out), 1);
    }

    private static Recipe blocks(String from, int count, String out, int outCount) {
        return new Recipe(item(from), count, null, 0, item(out), outCount);
    }

    private static Item item(String id) {
        return Items.get().require(id);
    }

    private static Recipe[] all;

    /**
     * Порядок важен: совпадения ищутся сверху вниз, и более требовательный
     * рецепт обязан стоять раньше менее требовательного из того же материала.
     * Иначе три булыжника всегда уходили бы в лопату.
     *
     * <p>Таблица собирается лениво: реестр предметов читается с диска, а
     * статическая инициализация не то место, где такому чтению падать.
     */
    private static synchronized Recipe[] table() {
        if (all == null)
            all = new Recipe[] {
                    // Кирки
                    tool("planks", 3, "wooden_pickaxe"),
                    tool("cobblestone", 3, "stone_pickaxe"),
                    tool("iron_ore", 3, "iron_pickaxe"),
                    tool("diamond_ore", 3, "diamond_pickaxe"),
                    // Топоры
                    tool("planks", 2, "wooden_axe"),
                    tool("cobblestone", 2, "stone_axe"),
                    tool("iron_ore", 2, "iron_axe"),
                    tool("diamond_ore", 2, "diamond_axe"),
                    // Лопаты
                    tool("planks", 1, "wooden_shovel"),
                    tool("cobblestone", 1, "stone_shovel"),
                    tool("iron_ore", 1, "iron_shovel"),
                    tool("diamond_ore", 1, "diamond_shovel"),
                    // Материалы
                    blocks("log", 1, "planks", 4),
                    // Восемь досок — заметная цена, но сундук и должен стоить
                    // похода за деревом: бесплатное хранилище обесценивает инвентарь.
                    blocks("planks", 8, "chest", 1),
                    // Спальник: доски на каркас и листва на подстилку. Шерсти в игре
                    // нет, а гонять игрока за несуществующим материалом ради одной
                    // позиции — худший вид «глубины».
                    new Recipe(item("planks"), 3, item("leaves"), 3, item("bedroll"), 1),
                    // Печь дороже ступеней, поэтому стоит выше: иначе восемь
                    // булыжников читались бы как «ступени, но много».
                    blocks("cobblestone", 8, "furnace", 1),
                    blocks("cobblestone", 4, "stairs", 4),
                    new Recipe(item("coal_ore"), 1, item("planks"), 1, item("torch"), 4),
            };
        return all;
    }

    private Recipes() {}

    /** Все рецепты — для справочника и тестов. */
    public static Recipe[] all() {
        return table().clone();
    }

    /** Сколько всего этого предмета лежит в инвентаре. */
    public static int count(Inventory inv, Item item) {
        if (item == null)
            return 0;
        int n = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (s != null && s.item == item)
                n += s.count;
        }
        return n;
    }

    /**
     * Хватает ли материала на рецепт.
     *
     * Отдельный случай — когда рукоять того же вида, что и основной материал
     * (деревянная кирка: три доски плюс доска на рукоять). Тогда считать надо
     * сумму, иначе одни и те же три доски засчитаются дважды и кирка выйдет
     * дешевле объявленного.
     */
    public static boolean canCraft(Inventory inv, Recipe r) {
        if (r.handle() == r.need())
            return count(inv, r.need()) >= r.needCount() + r.handleCount();
        return count(inv, r.need()) >= r.needCount()
                && (r.handle() == null || count(inv, r.handle()) >= r.handleCount());
    }

    /**
     * Собирает рецепт: списывает материал и кладёт результат.
     *
     * Материал списывается только после того, как место под результат
     * найдено — иначе неудачная сборка съедает ресурсы впустую.
     *
     * @return true, если собрали
     */
    public static boolean craft(Inventory inv, Recipe r) {
        if (!canCraft(inv, r))
            return false;
        ItemStack out = result(r);
        if (out.maxStack() <= 1) {
            if (!hasFreeSlot(inv))
                return false;
        } else if (!inv.canAdd(out, out.count)) {
            return false;
        }
        if (r.handle() == r.need()) {
            take(inv, r.need(), r.needCount() + r.handleCount());
        } else {
            take(inv, r.need(), r.needCount());
            take(inv, r.handle(), r.handleCount());
        }
        if (out.maxStack() <= 1)
            inv.addItem(out);
        else
            inv.add(out);
        return true;
    }

    private static boolean hasFreeSlot(Inventory inv) {
        for (int i = 0; i < inv.size(); i++)
            if (inv.get(i) == null)
                return true;
        return false;
    }

    private static void take(Inventory inv, Item item, int amount) {
        if (item == null || amount <= 0)
            return;
        for (int i = 0; i < inv.size() && amount > 0; i++) {
            ItemStack s = inv.get(i);
            if (s == null || s.item != item)
                continue;
            int used = Math.min(amount, s.count);
            s.count -= used;
            amount -= used;
            if (s.count <= 0)
                inv.set(i, null);
        }
    }

    /** Готовая стопка-результат рецепта. */
    public static ItemStack result(Recipe r) {
        return new ItemStack(r.result(), r.resultCount());
    }

    /** Рецепты, которые прямо сейчас можно собрать из содержимого инвентаря. */
    public static List<Recipe> available(Inventory inv) {
        List<Recipe> out = new ArrayList<>();
        for (Recipe r : table())
            if (canCraft(inv, r))
                out.add(r);
        return out;
    }
}

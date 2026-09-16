package com.mineclone.world;

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
 * Рукоять — доски, а не палки. Палка была бы четвёртым видом предмета
 * (не блок и не инструмент) и потянула бы за собой ещё одну ветку в модели
 * предметов ради одной позиции.
 */
public final class Recipes {

    /**
     * Один рецепт: что нужно и что получится.
     *
     * @param needBlock   основной материал
     * @param needCount   сколько его нужно
     * @param handle      второй материал (рукоять) или null
     * @param handleCount сколько нужно рукояти
     */
    public record Recipe(BlockType needBlock, int needCount,
                         BlockType handle, int handleCount,
                         ToolType tool, BlockType block, int blockCount) {

        public String resultName() {
            return tool != null ? tool.displayName : block.name();
        }
    }

    private static Recipe tool(BlockType mat, int count, ToolType out) {
        return new Recipe(mat, count, BlockType.PLANKS, 1, out, null, 0);
    }

    private static Recipe blocks(BlockType from, int count, BlockType out, int outCount) {
        return new Recipe(from, count, null, 0, null, out, outCount);
    }

    /**
     * Порядок важен: совпадения ищутся сверху вниз, и более требовательный
     * рецепт обязан стоять раньше менее требовательного из того же материала.
     * Иначе три булыжника всегда уходили бы в лопату.
     */
    private static final Recipe[] ALL = {
            // Кирки
            tool(BlockType.PLANKS, 3, ToolType.WOOD_PICKAXE),
            tool(BlockType.COBBLE, 3, ToolType.STONE_PICKAXE),
            tool(BlockType.IRON_ORE, 3, ToolType.IRON_PICKAXE),
            tool(BlockType.DIAMOND_ORE, 3, ToolType.DIAMOND_PICKAXE),
            // Топоры
            tool(BlockType.PLANKS, 2, ToolType.WOOD_AXE),
            tool(BlockType.COBBLE, 2, ToolType.STONE_AXE),
            tool(BlockType.IRON_ORE, 2, ToolType.IRON_AXE),
            tool(BlockType.DIAMOND_ORE, 2, ToolType.DIAMOND_AXE),
            // Лопаты
            tool(BlockType.PLANKS, 1, ToolType.WOOD_SHOVEL),
            tool(BlockType.COBBLE, 1, ToolType.STONE_SHOVEL),
            tool(BlockType.IRON_ORE, 1, ToolType.IRON_SHOVEL),
            tool(BlockType.DIAMOND_ORE, 1, ToolType.DIAMOND_SHOVEL),
            // Материалы
            blocks(BlockType.WOOD, 1, BlockType.PLANKS, 4),
            // Восемь досок — заметная цена, но сундук и должен стоить
            // похода за деревом: бесплатное хранилище обесценивает инвентарь.
            blocks(BlockType.PLANKS, 8, BlockType.CHEST, 1),
            // Спальник: доски на каркас и листва на подстилку. Шерсти в игре
            // нет, а гонять игрока за несуществующим материалом ради одной
            // позиции — худший вид «глубины».
            new Recipe(BlockType.PLANKS, 3, BlockType.LEAVES, 3, null, BlockType.BEDROLL, 1),
            // Печь дороже ступеней, поэтому стоит выше: иначе восемь
            // булыжников читались бы как «ступени, но много».
            blocks(BlockType.COBBLE, 8, BlockType.FURNACE, 1),
            blocks(BlockType.COBBLE, 4, BlockType.STAIRS, 4),
            new Recipe(BlockType.COAL_ORE, 1, BlockType.PLANKS, 1, null, BlockType.TORCH, 4),
    };

    private Recipes() {}

    /** Все рецепты — для справочника и тестов. */
    public static Recipe[] all() {
        return ALL.clone();
    }

    /**
     * Сколько всего материала этого вида лежит в инвентаре.
     * Инструменты не считаются: они не материал.
     */
    public static int count(Inventory inv, BlockType type) {
        if (type == null)
            return 0;
        int n = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (s != null && !s.isTool() && !s.isFood() && s.type == type)
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
        if (r.handle() == r.needBlock())
            return count(inv, r.needBlock()) >= r.needCount() + r.handleCount();
        return count(inv, r.needBlock()) >= r.needCount()
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
        if (out.isTool()) {
            if (!hasFreeSlot(inv))
                return false;
        } else if (!inv.canAdd(out.type, out.count)) {
            return false;
        }
        if (r.handle() == r.needBlock()) {
            take(inv, r.needBlock(), r.needCount() + r.handleCount());
        } else {
            take(inv, r.needBlock(), r.needCount());
            take(inv, r.handle(), r.handleCount());
        }
        if (out.isTool())
            inv.addItem(out);
        else
            inv.add(out.type, out.count);
        return true;
    }

    private static boolean hasFreeSlot(Inventory inv) {
        for (int i = 0; i < inv.size(); i++)
            if (inv.get(i) == null)
                return true;
        return false;
    }

    private static void take(Inventory inv, BlockType type, int amount) {
        if (type == null || amount <= 0)
            return;
        for (int i = 0; i < inv.size() && amount > 0; i++) {
            ItemStack s = inv.get(i);
            if (s == null || s.isTool() || s.isFood() || s.type != type)
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
        return r.tool() != null ? new ItemStack(r.tool())
                                : new ItemStack(r.block(), r.blockCount());
    }

    /** Рецепты, которые прямо сейчас можно собрать из содержимого инвентаря. */
    public static List<Recipe> available(Inventory inv) {
        List<Recipe> out = new ArrayList<>();
        for (Recipe r : ALL)
            if (canCraft(inv, r))
                out.add(r);
        return out;
    }
}

package com.mineclone.ui.container;

import com.mineclone.item.Item;
import com.mineclone.item.Items;
import com.mineclone.ui.MenuTheme;
import com.mineclone.world.Inventory;
import com.mineclone.world.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Творческое окно: все предметы реестра и хотбар под ними.
 *
 * <p>Источник бездонный: предмет берётся копией, а положенная обратно стопка
 * исчезает. Вкладки по категориям приходят планом D — здесь пока одна сетка с
 * прокруткой, зато настоящая: предметов уже больше, чем помещается на экран.
 */
public final class CreativeScreen extends ContainerScreen {

    /** Ширина сетки источника и сколько рядов видно за раз. */
    public static final int COLUMNS = 10, VISIBLE_ROWS = 5;

    private final List<Item> items;
    private int scrollRow;
    private float gridX, gridY;

    public CreativeScreen(WindowContext ctx) {
        this(ctx, visibleItems());
    }

    private CreativeScreen(WindowContext ctx, List<Item> items) {
        super(ctx, build(ctx, items));
        this.items = items;
    }

    private static List<Item> visibleItems() {
        List<Item> out = new ArrayList<>();
        for (Item i : Items.get().all())
            if (!i.hidden)
                out.add(i);
        return out;
    }

    private static ContainerMenu build(WindowContext ctx, List<Item> items) {
        Inventory inv = ctx.inventory();
        ContainerMenu m = new ContainerMenu(List.of(
                new SlotGroup("source", SlotRole.CREATIVE_SOURCE, new Source(items), COLUMNS),
                new SlotGroup("hotbar", SlotRole.HOTBAR,
                        new InventoryStorage(inv, 0, Inventory.HOTBAR), 9)));
        m.route(SlotRole.CREATIVE_SOURCE, SlotRole.HOTBAR, SlotRole.MAIN);
        return m;
    }

    /**
     * Витрина: отдаёт стопку по одному, но ничего не хранит.
     *
     * <p>Стопки создаются на лету, потому что реестр — это список предметов, а
     * не список стопок, и держать тут пятьдесят шесть живых объектов ради
     * показа незачем.
     */
    private record Source(List<Item> items) implements SlotStorage {
        @Override
        public int size() {
            return items.size();
        }

        @Override
        public ItemStack get(int index) {
            return index >= 0 && index < items.size() ? new ItemStack(items.get(index), 1) : null;
        }

        @Override
        public void set(int index, ItemStack s) {
            // Бездонный источник ничего не принимает и ничего не теряет.
        }

        @Override
        public boolean canPlace(int index, ItemStack s) {
            return false;
        }
    }

    @Override
    protected String title() {
        return "Творческий инвентарь";
    }

    @Override
    protected float[] layout(MenuTheme theme) {
        float panelW = gridWidth(COLUMNS) + 2 * PAD;
        float panelH = 64f + gridHeight(VISIBLE_ROWS) + 34f + SLOT + PAD;
        float panelX = theme.width() / 2f - panelW / 2f;
        float panelY = theme.height() / 2f - panelH / 2f;
        gridX = panelX + PAD;
        gridY = panelY + 64f;

        int rows = (items.size() + COLUMNS - 1) / COLUMNS;
        int maxScroll = Math.max(0, rows - VISIBLE_ROWS);
        scrollRow = Math.max(0, Math.min(maxScroll,
                scrollRow - Math.round(theme.activeInput().scroll)));

        // Прокрутка сдвигает сетку целыми рядами: дробный сдвиг резал бы
        // слоты пополам, а слот — это цель для мыши.
        place(menu.group("source"), gridX, gridY - scrollRow * (SLOT + GAP));
        place(menu.group("hotbar"),
                panelX + panelW / 2f - gridWidth(9) / 2f,
                gridY + gridHeight(VISIBLE_ROWS) + 34f);
        return new float[] { panelX, panelY, panelW, panelH };
    }

    @Override
    protected void drawExtras(MenuTheme theme) {
        int rows = (items.size() + COLUMNS - 1) / COLUMNS;
        int maxScroll = Math.max(0, rows - VISIBLE_ROWS);
        if (maxScroll <= 0)
            return;
        float trackX = gridX + gridWidth(COLUMNS) + 6f;
        float trackH = gridHeight(VISIBLE_ROWS);
        theme.quad(trackX, gridY, 4f, trackH, 1f, 1f, 1f, 0.10f);
        float knobH = Math.max(24f, trackH * VISIBLE_ROWS / rows);
        float knobY = gridY + (trackH - knobH) * scrollRow / maxScroll;
        theme.quad(trackX, knobY, 4f, knobH, MenuTheme.ACCENT, 0.75f);
    }

    /**
     * Ряды за пределами окна не рисуются и не ловят мышь: сетка источника
     * выше панели, и без отсечения слоты вылезали бы поверх хотбара.
     */
    @Override
    public float[] slotRect(SlotRef s) {
        float[] r = super.slotRect(s);
        if (r == null || !s.role().isCreativeSource())
            return r;
        if (r[1] + r[2] <= gridY - 1f || r[1] >= gridY + gridHeight(VISIBLE_ROWS) + 1f)
            return null;
        return r;
    }
}

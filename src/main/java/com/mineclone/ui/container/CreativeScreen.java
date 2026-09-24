package com.mineclone.ui.container;

import com.mineclone.item.Item;
import com.mineclone.item.Items;
import com.mineclone.ui.MenuTheme;
import com.mineclone.world.Inventory;
import com.mineclone.world.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Творческое окно: каталог предметов по категориям и хотбар под ним.
 *
 * <p>Источник бездонный: предмет берётся копией, а положенная обратно стопка
 * исчезает. Фильтр меняет живой список источника, поэтому правила кликов и
 * перетаскивания остаются общими с остальными контейнерами.
 */
public final class CreativeScreen extends ContainerScreen {

    /** Ширина сетки источника и сколько рядов видно за раз. */
    public static final int COLUMNS = 10, VISIBLE_ROWS = 5;

    private static final String[] TAB_LABELS = {
            "Все", "Блоки", "Декор", "Мех.", "Инстр.", "Еда", "Ещё", "Рюкзак"
    };
    private static final String[] TAB_CATEGORIES = {
            null, "building", "decor", "mechanics", "tools", "food", "materials,misc", "inventory"
    };

    private final List<Item> allItems;
    private final List<Item> items;
    private final com.mineclone.ui.TextField search = new com.mineclone.ui.TextField("", 80, null);
    private boolean typing;
    private float searchY, trashX;
    private int category;
    private int scrollRow;
    private float gridX, gridY, tabsX, tabsY;

    public CreativeScreen(WindowContext ctx) {
        this(ctx, visibleItems(), new ArrayList<>());
    }

    private CreativeScreen(WindowContext ctx, List<Item> allItems, List<Item> items) {
        super(ctx, build(ctx, items));
        this.allItems = List.copyOf(allItems);
        this.items = items;
        applyCategory(0);
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
                new SlotGroup("main", SlotRole.MAIN,
                        new InventoryStorage(inv, Inventory.HOTBAR, Inventory.SIZE - Inventory.HOTBAR), 9),
                new SlotGroup("hotbar", SlotRole.HOTBAR,
                        new InventoryStorage(inv, 0, Inventory.HOTBAR), 9)));
        m.route(SlotRole.CREATIVE_SOURCE, SlotRole.HOTBAR, SlotRole.MAIN);
        m.route(SlotRole.MAIN, SlotRole.HOTBAR);
        m.route(SlotRole.HOTBAR, SlotRole.MAIN);
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
        float panelH = 178f + gridHeight(VISIBLE_ROWS) + 34f + SLOT + PAD;
        float panelX = theme.width() / 2f - panelW / 2f;
        float panelY = theme.height() / 2f - panelH / 2f;
        gridX = panelX + PAD;
        tabsX = gridX;
        tabsY = panelY + 55f;
        searchY = panelY + 129f;
        trashX = gridX + gridWidth(COLUMNS) - 130f;
        gridY = panelY + 178f;
        var in = theme.activeInput();
        typing = category != 7 && (in.mousePressed
                ? in.mouseX >= gridX && in.mouseX < trashX - 10f
                    && in.mouseY >= searchY && in.mouseY <= searchY + 32f
                : theme.focused("creative-search"));

        int rows = (items.size() + COLUMNS - 1) / COLUMNS;
        int maxScroll = Math.max(0, rows - VISIBLE_ROWS);
        scrollRow = Math.max(0, Math.min(maxScroll,
                scrollRow - Math.round(theme.activeInput().scroll)));

        // Прокрутка сдвигает сетку целыми рядами: дробный сдвиг резал бы
        // слоты пополам, а слот — это цель для мыши.
        if (category == 7)
            place(menu.group("main"), gridX + (gridWidth(COLUMNS) - gridWidth(9)) / 2f, gridY);
        else
            place(menu.group("source"), gridX, gridY - scrollRow * (SLOT + GAP));
        place(menu.group("hotbar"),
                panelX + panelW / 2f - gridWidth(9) / 2f,
                gridY + gridHeight(VISIBLE_ROWS) + 34f);
        return new float[] { panelX, panelY, panelW, panelH };
    }

    @Override
    protected void drawExtras(MenuTheme theme) {
        int top = theme.segmentedSmall("creative-category-top", tabsX, tabsY,
                gridWidth(COLUMNS), 30f, java.util.Arrays.copyOfRange(TAB_LABELS, 0, 4),
                category < 4 ? category : -1);
        int bottom = theme.segmentedSmall("creative-category-bottom", tabsX, tabsY + 35f,
                gridWidth(COLUMNS), 30f, java.util.Arrays.copyOfRange(TAB_LABELS, 4, 8),
                category >= 4 ? category - 4 : -1);
        int next = category;
        if (top >= 0 && top != (category < 4 ? category : -1)) next = top;
        if (bottom >= 0 && bottom != (category >= 4 ? category - 4 : -1)) next = bottom + 4;
        if (next != category) {
            theme.unfocus();
            typing = false;
            applyCategory(next);
        }
        if (category != 7) {
            String previous = search.text();
            theme.textField("creative-search", gridX, searchY, trashX - gridX - 10f, 32f,
                    search, "Поиск: название / ID");
            if (!previous.equals(search.text())) applyCategory(category);
        } else {
            theme.smallText("Все 36 слотов инвентаря", gridX, searchY + 22f, MenuTheme.TEXT_FAINT, 1f);
        }
        if (theme.button("creative-trash", trashX, searchY, 130f, 32f, "Сброс")) {
            if (theme.activeInput().shift()) {
                for (int i = 0; i < Inventory.SIZE; i++) ctx.inventory().set(i, null);
            }
            menu.setCursor(null);
            ctx.click(0.4f, 0.8f);
        }
        theme.smallText("СКМ: стопка · 1–9: хотбар · Shift+Сброс: всё",
                gridX, gridY + gridHeight(VISIBLE_ROWS) + 23f, MenuTheme.TEXT_FAINT, 1f);

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

    @Override
    protected boolean inputWidgetAt(float x, float y) {
        return x >= tabsX && x <= tabsX + gridWidth(COLUMNS)
                && y >= tabsY && y <= searchY + 32f;
    }

    public void applyCategory(int next) {
        category = Math.max(0, Math.min(TAB_CATEGORIES.length - 1, next));
        String wanted = TAB_CATEGORIES[category];
        items.clear();
        for (Item item : allItems)
            if ((wanted == null || categoryMatches(wanted, item.category)) && matchesSearch(item, search.text()))
                items.add(item);
        scrollRow = 0;
    }

    @Override
    protected boolean capturesKeyboard() { return typing; }

    public void setSearch(String text) { search.setText(text); applyCategory(category); }

    public static boolean matchesSearch(Item item, String query) {
        String text = (item.name + " " + item.id).toLowerCase(java.util.Locale.ROOT).replace('ё', 'е');
        for (String token : query.strip().toLowerCase(java.util.Locale.ROOT).replace('ё', 'е').split("\\s+"))
            if (!text.contains(token)) return false;
        return true;
    }

    private static boolean categoryMatches(String wanted, String category) {
        for (String candidate : wanted.split(","))
            if (candidate.equals(category))
                return true;
        return false;
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

package com.mineclone.ui.container;

import com.mineclone.render.ItemIcons;
import com.mineclone.ui.MenuTheme;
import com.mineclone.world.Inventory;
import com.mineclone.world.ItemStack;
import com.mineclone.world.Recipes;

import java.util.List;

/**
 * Окно инвентаря выживания: три ряда хранилища, хотбар, корзина и полка
 * рецептов.
 *
 * <p>Полка вместо сетки крафта — сознательно, до плана B: рецепты бесформенные,
 * и раскладывать материалы по клеткам не во что.
 */
public final class InventoryScreen extends ContainerScreen {

    /** Сколько рецептов помещается на полку. */
    public static final int CRAFT_MAX = 18;
    private static final float CRAFT_ROW_H = 121f;
    private static final float TRASH_SIZE = 44f;

    private final Inventory inv;
    private float trashX, trashY;
    private final List<Recipes.Recipe> craftable = new java.util.ArrayList<>();
    private float craftX, craftY, mainLabelY, hotbarLabelY;

    public InventoryScreen(WindowContext ctx) {
        super(ctx, build(ctx));
        this.inv = ctx.inventory();
    }

    private static ContainerMenu build(WindowContext ctx) {
        Inventory inv = ctx.inventory();
        ContainerMenu m = new ContainerMenu(List.of(
                new SlotGroup("main", SlotRole.MAIN,
                        new InventoryStorage(inv, Inventory.HOTBAR,
                                Inventory.SIZE - Inventory.HOTBAR), 9),
                new SlotGroup("hotbar", SlotRole.HOTBAR,
                        new InventoryStorage(inv, 0, Inventory.HOTBAR), 9)));
        m.route(SlotRole.MAIN, SlotRole.HOTBAR);
        m.route(SlotRole.HOTBAR, SlotRole.MAIN);
        return m;
    }

    @Override
    protected String title() {
        return "Инвентарь";
    }

    @Override
    protected float[] layout(MenuTheme theme) {
        float panelW = gridWidth(9) + 2 * PAD;
        float mainY = 72f;
        float hotbarY = mainY + gridHeight(3) + 28f;
        float craftTop = hotbarY + SLOT + 34f;
        float panelH = craftTop + CRAFT_ROW_H + PAD;
        float panelX = theme.width() / 2f - panelW / 2f;
        float panelY = theme.height() / 2f - panelH / 2f;
        float gridX = panelX + PAD;

        place(menu.group("main"), gridX, panelY + mainY);
        place(menu.group("hotbar"), gridX, panelY + hotbarY);
        mainLabelY = panelY + mainY - 8f;
        hotbarLabelY = panelY + hotbarY - 8f;
        trashX = panelX + panelW - 70f;
        trashY = panelY + 18f;
        craftX = gridX;
        craftY = panelY + craftTop + 14f;
        return new float[] { panelX, panelY, panelW, panelH };
    }

    @Override
    protected void drawExtras(MenuTheme theme) {
        theme.smallText("Хранилище", craftX, mainLabelY, MenuTheme.TEXT_FAINT, 1f);
        theme.smallText("Хотбар", craftX, hotbarLabelY, MenuTheme.TEXT_FAINT, 1f);

        // Рамка выбранного слота хотбара: окно не должно отнимать у игрока
        // понимание того, чем он ударит, закрыв его.
        SlotGroup hotbar = menu.group("hotbar");
        float[] r = slotRect(new SlotRef(hotbar, ctx.selectedSlot()));
        if (r != null) {
            theme.quad(r[0] - 3f, r[1] - 3f, r[2] + 6f, 3f, 1f, 1f, 1f, 0.95f);
            theme.quad(r[0] - 3f, r[1] + r[2], r[2] + 6f, 3f, 1f, 1f, 1f, 0.95f);
            theme.quad(r[0] - 3f, r[1], 3f, r[2], 1f, 1f, 1f, 0.95f);
            theme.quad(r[0] + r[2], r[1], 3f, r[2], 1f, 1f, 1f, 0.95f);
        }

        drawTrash(theme);
        drawCraftShelf(theme);
    }

    private void drawTrash(MenuTheme theme) {
        boolean hov = theme.hovered(trashX, trashY, TRASH_SIZE, TRASH_SIZE);
        theme.quad(trashX, trashY, TRASH_SIZE, TRASH_SIZE,
                0.34f, 0.12f, 0.14f, hov ? 1f : 0.92f);
        theme.quad(trashX, trashY, TRASH_SIZE, 1f, 1f, 1f, 1f, 0.18f);
        theme.quad(trashX + 10f, trashY + 12f, 24f, 4f, 0.95f, 0.95f, 0.95f, 0.85f);
        theme.quad(trashX + 13f, trashY + 18f, 18f, 16f, 0.80f, 0.80f, 0.80f, 0.85f);
        if (hov && (theme.activeInput().mousePressed || theme.activeInput().rightPressed)
                && menu.cursor() != null) {
            menu.setCursor(null);
            ctx.click(0.45f, 0.8f);
        }
    }

    /**
     * Полка собираемого прямо сейчас.
     *
     * <p>Список пересобирается тем же вызовом, который его и рисует, поэтому
     * индекс под курсором валиден ровно в этом кадре.
     */
    private void drawCraftShelf(MenuTheme theme) {
        craftable.clear();
        craftable.addAll(Recipes.available(inv));
        theme.smallText(craftable.isEmpty() ? "Крафт — пока не из чего" : "Крафт",
                craftX, craftY - 10f, MenuTheme.TEXT_FAINT, 1f);
        for (int i = 0; i < craftable.size() && i < CRAFT_MAX; i++) {
            float x = craftX + (i % 9) * (SLOT + GAP);
            float y = craftY + (i / 9) * (SLOT + GAP);
            boolean hov = theme.hovered(x, y, SLOT, SLOT);
            drawSlotBack(theme, x, y, SLOT, hov);
            ItemStack out = Recipes.result(craftable.get(i));
            theme.itemIcon(out, x + 6f, y + 6f, SLOT - 12f, 1f,
                    hov ? ItemIcons.ICON_YAW + theme.time() * ItemIcons.ICON_SPIN
                        : ItemIcons.ICON_YAW);
            if (out.count > 1)
                theme.smallShadow(Integer.toString(out.count),
                        x + SLOT - 4f - theme.smallWidth(Integer.toString(out.count)),
                        y + SLOT - 6f, MenuTheme.TEXT, 1f);
            if (hov && theme.activeInput().mousePressed && Recipes.craft(inv, craftable.get(i))) {
                ctx.toast("Собрано: " + craftable.get(i).resultName());
                ctx.click(0.5f, 0.9f);
            }
        }
    }
}

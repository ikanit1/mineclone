package com.mineclone.ui.container;

import com.mineclone.render.ItemIcons;
import com.mineclone.ui.MenuTheme;
import com.mineclone.ui.UiInput;
import com.mineclone.world.Inventory;
import com.mineclone.world.ItemStack;

import java.util.List;

/**
 * Инвентарь выживания с настоящей карманной сеткой крафта 2×2.
 *
 * <p>Ингредиенты лежат в слотах и возвращаются при закрытии. Результат
 * появляется только при точном совпадении формы; Shift-клик собирает максимум.
 */
public final class InventoryScreen extends ContainerScreen {

    private static final float TRASH_SIZE = 44f;

    private final Inventory inv;
    private final CraftingGrid crafting;
    private float trashX, trashY, resultX, resultY, craftX, craftY;
    private float mainLabelY, hotbarLabelY;

    public InventoryScreen(WindowContext ctx) {
        this(ctx, new CraftingGrid(2));
    }

    private InventoryScreen(WindowContext ctx, CraftingGrid crafting) {
        super(ctx, build(ctx, crafting));
        this.inv = ctx.inventory();
        this.crafting = crafting;
    }

    private static ContainerMenu build(WindowContext ctx, CraftingGrid crafting) {
        Inventory inv = ctx.inventory();
        ContainerMenu menu = new ContainerMenu(List.of(
                new SlotGroup("craft", SlotRole.CRAFT_GRID, crafting.storage(), 2),
                new SlotGroup("main", SlotRole.MAIN,
                        new InventoryStorage(inv, Inventory.HOTBAR,
                                Inventory.SIZE - Inventory.HOTBAR), 9),
                new SlotGroup("hotbar", SlotRole.HOTBAR,
                        new InventoryStorage(inv, 0, Inventory.HOTBAR), 9)));
        menu.route(SlotRole.CRAFT_GRID, SlotRole.MAIN, SlotRole.HOTBAR);
        menu.route(SlotRole.MAIN, SlotRole.HOTBAR);
        menu.route(SlotRole.HOTBAR, SlotRole.MAIN);
        return menu;
    }

    @Override
    protected String title() {
        return "Инвентарь";
    }

    @Override
    protected float[] layout(MenuTheme theme) {
        float panelW = gridWidth(9) + 2 * PAD;
        float panelX = theme.width() / 2f - panelW / 2f;
        float craftTop = 64f;
        float mainTop = craftTop + gridHeight(2) + 45f;
        float hotbarTop = mainTop + gridHeight(3) + 28f;
        float panelH = hotbarTop + SLOT + PAD;
        float panelY = theme.height() / 2f - panelH / 2f;
        float gridX = panelX + PAD;

        craftX = panelX + 104f;
        craftY = panelY + craftTop;
        resultX = craftX + gridWidth(2) + 76f;
        resultY = craftY + (gridHeight(2) - SLOT) / 2f;
        place(menu.group("craft"), craftX, craftY);
        place(menu.group("main"), gridX, panelY + mainTop);
        place(menu.group("hotbar"), gridX, panelY + hotbarTop);

        mainLabelY = panelY + mainTop - 8f;
        hotbarLabelY = panelY + hotbarTop - 8f;
        trashX = panelX + panelW - 70f;
        trashY = panelY + 18f;
        return new float[] { panelX, panelY, panelW, panelH };
    }

    @Override
    protected void drawExtras(MenuTheme theme) {
        theme.smallText("Создание", craftX, craftY - 10f, MenuTheme.TEXT_FAINT, 1f);
        theme.smallText("Инвентарь", craftX - 82f, mainLabelY, MenuTheme.TEXT_FAINT, 1f);
        theme.smallText("Хотбар", craftX - 82f, hotbarLabelY, MenuTheme.TEXT_FAINT, 1f);
        drawSelectedHotbar(theme);
        drawTrash(theme);
        drawCraftResult(theme);
    }

    private void drawSelectedHotbar(MenuTheme theme) {
        SlotGroup hotbar = menu.group("hotbar");
        float[] r = slotRect(new SlotRef(hotbar, ctx.selectedSlot()));
        if (r == null)
            return;
        theme.quad(r[0] - 3f, r[1] - 3f, r[2] + 6f, 3f, 1f, 1f, 1f, 0.95f);
        theme.quad(r[0] - 3f, r[1] + r[2], r[2] + 6f, 3f, 1f, 1f, 1f, 0.95f);
        theme.quad(r[0] - 3f, r[1], 3f, r[2], 1f, 1f, 1f, 0.95f);
        theme.quad(r[0] + r[2], r[1], 3f, r[2], 1f, 1f, 1f, 0.95f);
    }

    private void drawTrash(MenuTheme theme) {
        boolean hovered = theme.hovered(trashX, trashY, TRASH_SIZE, TRASH_SIZE);
        theme.quad(trashX, trashY, TRASH_SIZE, TRASH_SIZE,
                0.34f, 0.12f, 0.14f, hovered ? 1f : 0.92f);
        theme.quad(trashX, trashY, TRASH_SIZE, 1f, 1f, 1f, 1f, 0.18f);
        theme.quad(trashX + 10f, trashY + 12f, 24f, 4f, 0.95f, 0.95f, 0.95f, 0.85f);
        theme.quad(trashX + 13f, trashY + 18f, 18f, 16f, 0.80f, 0.80f, 0.80f, 0.85f);
        UiInput in = theme.activeInput();
        if (hovered && (in.mousePressed || in.rightPressed) && menu.cursor() != null) {
            menu.setCursor(null);
            ctx.click(0.45f, 0.8f);
        }
    }

    private void drawCraftResult(MenuTheme theme) {
        float arrowX = craftX + gridWidth(2) + 18f;
        float arrowY = resultY + SLOT / 2f - 4f;
        theme.quad(arrowX, arrowY, 38f, 8f, 0.48f, 0.52f, 0.58f, 0.75f);
        theme.quad(arrowX + 30f, arrowY - 5f, 8f, 18f, 0.48f, 0.52f, 0.58f, 0.75f);

        boolean hovered = theme.hovered(resultX, resultY, SLOT, SLOT);
        drawSlotBack(theme, resultX, resultY, SLOT, hovered);
        ItemStack out = crafting.result();
        if (out != null) {
            theme.itemIcon(out, resultX + 6f, resultY + 6f, SLOT - 12f, 1f,
                    hovered ? ItemIcons.ICON_YAW + theme.time() * ItemIcons.ICON_SPIN
                            : ItemIcons.ICON_YAW);
            if (out.count > 1)
                theme.smallShadow(Integer.toString(out.count),
                        resultX + SLOT - 4f - theme.smallWidth(Integer.toString(out.count)),
                        resultY + SLOT - 6f, MenuTheme.TEXT, 1f);
            if (hovered)
                theme.smallText(out.displayName(), resultX - 2f,
                        resultY + SLOT + 16f, MenuTheme.TEXT_DIM, 1f);
        }

        UiInput in = theme.activeInput();
        if (!hovered || (!in.mousePressed && !in.rightPressed) || out == null)
            return;
        int made;
        if (in.shift())
            made = crafting.craftAll(inv);
        else
            made = crafting.craftToCursor(menu) == null ? 0 : out.count;
        if (made > 0) {
            ctx.toast("Создано: " + out.displayName() + (made > out.count ? " ×" + made : ""));
            ctx.click(0.5f, 0.95f);
        }
    }

    @Override
    protected boolean inputWidgetAt(float x, float y) {
        return inside(x, y, resultX, resultY, SLOT, SLOT)
                || inside(x, y, trashX, trashY, TRASH_SIZE, TRASH_SIZE);
    }

    private static boolean inside(float x, float y, float bx, float by, float w, float h) {
        return x >= bx && x <= bx + w && y >= by && y <= by + h;
    }

    @Override
    public void closed() {
        crafting.returnItems(ctx);
        super.closed();
    }
}

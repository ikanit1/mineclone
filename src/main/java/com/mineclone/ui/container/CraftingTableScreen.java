package com.mineclone.ui.container;

import com.mineclone.render.ItemIcons;
import com.mineclone.ui.MenuTheme;
import com.mineclone.ui.UiInput;
import com.mineclone.world.Inventory;
import com.mineclone.world.ItemStack;

import java.util.List;
import java.util.function.BooleanSupplier;

/** Полноразмерный верстак с Minecraft-подобной сеткой рецептов 3x3. */
public final class CraftingTableScreen extends ContainerScreen {

    private final Inventory inventory;
    private final CraftingGrid crafting;
    private final BooleanSupplier alive;
    private float craftX, craftY, resultX, resultY;
    private float mainLabelY, hotbarLabelY;

    public CraftingTableScreen(WindowContext ctx, BooleanSupplier alive) {
        this(ctx, alive, new CraftingGrid(3));
    }

    private CraftingTableScreen(WindowContext ctx, BooleanSupplier alive, CraftingGrid crafting) {
        super(ctx, build(ctx, crafting));
        this.inventory = ctx.inventory();
        this.alive = alive;
        this.crafting = crafting;
    }

    private static ContainerMenu build(WindowContext ctx, CraftingGrid crafting) {
        Inventory inventory = ctx.inventory();
        ContainerMenu menu = new ContainerMenu(List.of(
                new SlotGroup("craft", SlotRole.CRAFT_GRID, crafting.storage(), 3),
                new SlotGroup("main", SlotRole.MAIN,
                        new InventoryStorage(inventory, Inventory.HOTBAR,
                                Inventory.SIZE - Inventory.HOTBAR), 9),
                new SlotGroup("hotbar", SlotRole.HOTBAR,
                        new InventoryStorage(inventory, 0, Inventory.HOTBAR), 9)));
        menu.route(SlotRole.CRAFT_GRID, SlotRole.MAIN, SlotRole.HOTBAR);
        menu.route(SlotRole.MAIN, SlotRole.HOTBAR);
        menu.route(SlotRole.HOTBAR, SlotRole.MAIN);
        return menu;
    }

    @Override
    protected String title() {
        return "Верстак";
    }

    @Override
    protected float[] layout(MenuTheme theme) {
        float panelW = gridWidth(9) + 2 * PAD;
        float panelX = theme.width() / 2f - panelW / 2f;
        float craftTop = 64f;
        float mainTop = craftTop + gridHeight(3) + 45f;
        float hotbarTop = mainTop + gridHeight(3) + 28f;
        float panelH = hotbarTop + SLOT + PAD;
        float panelY = theme.height() / 2f - panelH / 2f;
        float gridX = panelX + PAD;

        craftX = panelX + 72f;
        craftY = panelY + craftTop;
        resultX = panelX + panelW - PAD - SLOT - 72f;
        resultY = craftY + (gridHeight(3) - SLOT) / 2f;
        place(menu.group("craft"), craftX, craftY);
        place(menu.group("main"), gridX, panelY + mainTop);
        place(menu.group("hotbar"), gridX, panelY + hotbarTop);

        mainLabelY = panelY + mainTop - 8f;
        hotbarLabelY = panelY + hotbarTop - 8f;
        return new float[] { panelX, panelY, panelW, panelH };
    }

    @Override
    protected void drawExtras(MenuTheme theme) {
        theme.smallText("Создание 3×3", craftX, craftY - 10f, MenuTheme.TEXT_FAINT, 1f);
        theme.smallText("Инвентарь", craftX - 50f, mainLabelY, MenuTheme.TEXT_FAINT, 1f);
        theme.smallText("Хотбар", craftX - 50f, hotbarLabelY, MenuTheme.TEXT_FAINT, 1f);
        drawCraftResult(theme);
    }

    private void drawCraftResult(MenuTheme theme) {
        float arrowX = craftX + gridWidth(3) + 18f;
        float arrowY = resultY + SLOT / 2f - 4f;
        theme.quad(arrowX, arrowY, 38f, 8f, 0.48f, 0.52f, 0.58f, 0.75f);
        theme.quad(arrowX + 30f, arrowY - 5f, 8f, 18f,
                0.48f, 0.52f, 0.58f, 0.75f);

        boolean hovered = theme.hovered(resultX, resultY, SLOT, SLOT);
        drawSlotBack(theme, resultX, resultY, SLOT, hovered);
        ItemStack output = crafting.result();
        if (output != null) {
            theme.itemIcon(output, resultX + 6f, resultY + 6f, SLOT - 12f, 1f,
                    hovered ? ItemIcons.ICON_YAW + theme.time() * ItemIcons.ICON_SPIN
                            : ItemIcons.ICON_YAW);
            if (output.count > 1) {
                String count = Integer.toString(output.count);
                theme.smallShadow(count, resultX + SLOT - 4f - theme.smallWidth(count),
                        resultY + SLOT - 6f, MenuTheme.TEXT, 1f);
            }
            if (hovered)
                theme.smallText(output.displayName(), resultX - 2f,
                        resultY + SLOT + 16f, MenuTheme.TEXT_DIM, 1f);
        }

        UiInput input = theme.activeInput();
        if (!hovered || (!input.mousePressed && !input.rightPressed) || output == null)
            return;
        int made = input.shift()
                ? crafting.craftAll(inventory)
                : (crafting.craftToCursor(menu) == null ? 0 : output.count);
        if (made > 0) {
            ctx.toast("Создано: " + output.displayName()
                    + (made > output.count ? " ×" + made : ""));
            ctx.click(0.5f, 0.95f);
        }
    }

    @Override
    protected boolean inputWidgetAt(float x, float y) {
        return x >= resultX && x <= resultX + SLOT
                && y >= resultY && y <= resultY + SLOT;
    }

    @Override
    public boolean valid() {
        return alive == null || alive.getAsBoolean();
    }

    @Override
    public void closed() {
        crafting.returnItems(ctx);
        super.closed();
    }
}

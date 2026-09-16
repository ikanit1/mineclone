package com.mineclone.ui.container;

import com.mineclone.ui.MenuTheme;
import com.mineclone.world.Inventory;
import com.mineclone.world.ItemStack;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/**
 * Окно сундука: его двадцать семь слотов сверху, инвентарь игрока снизу.
 *
 * <p>Сундук может исчезнуть, пока окно открыто: его сломал огонь или выгрузился
 * чанк. Окно спрашивает об этом каждый кадр и закрывается само — иначе игрок
 * перекладывает вещи в то, чего уже нет.
 */
public final class ChestScreen extends ContainerScreen {

    private final BooleanSupplier alive;
    private float labelX, labelY;

    public ChestScreen(WindowContext ctx, ItemStack[] chest, IntConsumer onChanged,
            BooleanSupplier alive) {
        super(ctx, build(ctx, chest, onChanged));
        this.alive = alive;
    }

    private static ContainerMenu build(WindowContext ctx, ItemStack[] chest, IntConsumer changed) {
        Inventory inv = ctx.inventory();
        ContainerMenu m = new ContainerMenu(List.of(
                new SlotGroup("chest", SlotRole.CONTAINER,
                        new ArrayStorage(chest, null, changed), 9),
                new SlotGroup("main", SlotRole.MAIN,
                        new InventoryStorage(inv, Inventory.HOTBAR,
                                Inventory.SIZE - Inventory.HOTBAR), 9),
                new SlotGroup("hotbar", SlotRole.HOTBAR,
                        new InventoryStorage(inv, 0, Inventory.HOTBAR), 9)));
        m.route(SlotRole.MAIN, SlotRole.CONTAINER);
        m.route(SlotRole.HOTBAR, SlotRole.CONTAINER);
        m.route(SlotRole.CONTAINER, SlotRole.MAIN, SlotRole.HOTBAR);
        return m;
    }

    @Override
    public boolean valid() {
        return alive == null || alive.getAsBoolean();
    }

    @Override
    protected String title() {
        return "Сундук";
    }

    @Override
    protected float[] layout(MenuTheme theme) {
        float panelW = gridWidth(9) + 2 * PAD;
        float panelH = 64f + gridHeight(3) + 46f + gridHeight(3) + 16f + SLOT + PAD;
        float panelX = theme.width() / 2f - panelW / 2f;
        float panelY = theme.height() / 2f - panelH / 2f;
        float gridX = panelX + PAD;

        float chestY = panelY + 64f;
        float mainY = chestY + gridHeight(3) + 46f;
        float hotbarY = mainY + gridHeight(3) + 16f;
        place(menu.group("chest"), gridX, chestY);
        place(menu.group("main"), gridX, mainY);
        place(menu.group("hotbar"), gridX, hotbarY);
        labelX = gridX;
        labelY = mainY - 10f;
        return new float[] { panelX, panelY, panelW, panelH };
    }

    @Override
    protected void drawExtras(MenuTheme theme) {
        theme.smallText("Инвентарь", labelX, labelY, MenuTheme.TEXT_FAINT, 1f);
    }
}

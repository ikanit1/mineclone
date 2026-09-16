package com.mineclone.ui.container;

import com.mineclone.ui.MenuTheme;
import com.mineclone.world.Furnace;
import com.mineclone.world.Inventory;
import com.mineclone.world.ItemStack;
import com.mineclone.world.Smelting;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Окно печи: что плавим, чем топим, что вышло.
 *
 * <p>Что куда класть, решает само хранилище: в топку идёт только топливо, в
 * загрузку — только то, что вообще плавится. Проверять это на каждом клике
 * означало бы несколько мест, которые обязаны договориться между собой.
 */
public final class FurnaceScreen extends ContainerScreen {

    private final Furnace furnace;
    private final BooleanSupplier alive;
    private float inputX, inputY, fuelY, outputX, outputY;

    public FurnaceScreen(WindowContext ctx, Furnace furnace, BooleanSupplier alive) {
        super(ctx, build(ctx, furnace));
        this.furnace = furnace;
        this.alive = alive;
    }

    private static ContainerMenu build(WindowContext ctx, Furnace furnace) {
        Inventory inv = ctx.inventory();
        ContainerMenu m = new ContainerMenu(List.of(
                new SlotGroup("input", SlotRole.FURNACE_INPUT, new Cell(furnace, 0), 1),
                new SlotGroup("fuel", SlotRole.FURNACE_FUEL, new Cell(furnace, 1), 1),
                new SlotGroup("output", SlotRole.FURNACE_OUTPUT, new Cell(furnace, 2), 1),
                new SlotGroup("main", SlotRole.MAIN,
                        new InventoryStorage(inv, Inventory.HOTBAR,
                                Inventory.SIZE - Inventory.HOTBAR), 9),
                new SlotGroup("hotbar", SlotRole.HOTBAR,
                        new InventoryStorage(inv, 0, Inventory.HOTBAR), 9)));
        m.route(SlotRole.MAIN, SlotRole.FURNACE_FUEL, SlotRole.FURNACE_INPUT);
        m.route(SlotRole.HOTBAR, SlotRole.FURNACE_FUEL, SlotRole.FURNACE_INPUT);
        m.route(SlotRole.FURNACE_INPUT, SlotRole.MAIN, SlotRole.HOTBAR);
        m.route(SlotRole.FURNACE_FUEL, SlotRole.MAIN, SlotRole.HOTBAR);
        m.route(SlotRole.FURNACE_OUTPUT, SlotRole.MAIN, SlotRole.HOTBAR);
        return m;
    }

    /**
     * Один слот самой печи.
     *
     * <p>Через печь, а не через копию массива: окно, правящее копию, топило бы
     * печь тем, чего в ней уже нет.
     */
    private record Cell(Furnace furnace, int slot) implements SlotStorage {
        @Override
        public int size() {
            return 1;
        }

        @Override
        public ItemStack get(int index) {
            return switch (slot) {
                case 0 -> furnace.input;
                case 1 -> furnace.fuel;
                default -> furnace.output;
            };
        }

        @Override
        public void set(int index, ItemStack s) {
            switch (slot) {
                case 0 -> furnace.input = s;
                case 1 -> furnace.fuel = s;
                default -> furnace.output = s;
            }
        }

        @Override
        public boolean canPlace(int index, ItemStack s) {
            if (s == null)
                return true;
            return switch (slot) {
                case 0 -> Smelting.result(s) != null;
                case 1 -> Smelting.isFuel(s);
                default -> false;
            };
        }
    }

    @Override
    public boolean valid() {
        return alive == null || alive.getAsBoolean();
    }

    @Override
    protected String title() {
        return "Печь";
    }

    @Override
    protected float[] layout(MenuTheme theme) {
        float panelW = gridWidth(9) + 2 * PAD;
        float panelH = 240f + gridHeight(3) + 16f + SLOT + PAD;
        float panelX = theme.width() / 2f - panelW / 2f;
        float panelY = theme.height() / 2f - panelH / 2f;
        float gridX = panelX + PAD;
        float cx = panelX + panelW / 2f;

        inputX = cx - 110f;
        inputY = panelY + 70f;
        fuelY = inputY + SLOT + 56f;
        outputX = cx + 70f;
        outputY = inputY + (SLOT + 56f) / 2f;

        place(menu.group("input"), inputX, inputY);
        place(menu.group("fuel"), inputX, fuelY);
        place(menu.group("output"), outputX, outputY);

        float mainY = panelY + 240f;
        float hotbarY = mainY + gridHeight(3) + 16f;
        place(menu.group("main"), gridX, mainY);
        place(menu.group("hotbar"), gridX, hotbarY);
        return new float[] { panelX, panelY, panelW, panelH };
    }

    @Override
    protected void drawExtras(MenuTheme theme) {
        // Пламя под загрузкой и стрелка к выходу: по ним видно, что печь
        // работает, не глядя ни на какие числа.
        float flameX = inputX + SLOT / 2f - 8f;
        float flameY = inputY + SLOT + 18f;
        theme.quad(flameX, flameY, 16f, 20f, 0.10f, 0.10f, 0.12f, 0.85f);
        float burn = furnace.burnFraction();
        if (burn > 0f)
            theme.quad(flameX, flameY + 20f * (1f - burn), 16f, 20f * burn,
                    1f, 0.56f + 0.3f * burn, 0.12f, 0.95f);

        float arrowX = inputX + SLOT + 24f;
        float arrowY = outputY + SLOT / 2f - 5f;
        float arrowW = outputX - arrowX - 18f;
        theme.quad(arrowX, arrowY, arrowW, 10f, 0.10f, 0.10f, 0.12f, 0.85f);
        theme.quad(arrowX, arrowY, arrowW * furnace.cookFraction(), 10f,
                MenuTheme.ACCENT, 0.95f);

        float[] r = slotRect(new SlotRef(menu.group("main"), 0));
        if (r != null)
            theme.smallText("Инвентарь", r[0], r[1] - 10f, MenuTheme.TEXT_FAINT, 1f);
    }
}

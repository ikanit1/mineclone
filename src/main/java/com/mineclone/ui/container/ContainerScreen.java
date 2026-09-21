package com.mineclone.ui.container;

import com.mineclone.core.KeyBindings;
import com.mineclone.render.ItemIcons;
import com.mineclone.ui.MenuAction;
import com.mineclone.ui.MenuTheme;
import com.mineclone.ui.Screen;
import com.mineclone.ui.UiInput;
import com.mineclone.world.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE;

/**
 * Каркас окна с сеткой слотов: раскладка, разбор мыши и отрисовка.
 *
 * <p>Инвентарь, сундук, печь и креатив отличаются набором групп, подписями и
 * парой своих виджетов — а не тем, что делает клик. Четыре копии одних и тех
 * же правил расходились ровно так, как расходятся копии: по мелочи и в разные
 * стороны, и чинить приходилось каждую отдельно.
 *
 * <p>Логика кликов живёт в {@link ContainerMenu} и ничего не знает ни про
 * мышь, ни про GL; здесь — только перевод «курсор над таким-то слотом» в её
 * вызовы.
 */
public abstract class ContainerScreen implements Screen {

    /** Сторона слота и зазор между слотами — как в прежних окнах. */
    public static final float SLOT = 42f, GAP = 5f;
    /** Панель шире сетки на столько с каждой стороны. */
    protected static final float PAD = 22f;

    protected final WindowContext ctx;
    protected final ContainerMenu menu;

    /** Экранные прямоугольники слотов этого кадра. */
    private final Map<SlotGroup, float[]> origins = new HashMap<>();
    private final List<SlotGroup> laidOut = new ArrayList<>();

    private final Spring open = new Spring(18f, 0.6f, 0.96f);
    private final SpringVec2 cursorPos = new SpringVec2(26f, 0.85f);
    private final SpringVec2 tooltipPos = new SpringVec2(22f, 0.9f);
    private final ItemFlights flights = new ItemFlights();
    private boolean cursorPlaced, tooltipPlaced;

    private SlotRef hovered;
    private boolean closing;

    protected ContainerScreen(WindowContext ctx, ContainerMenu menu) {
        this.ctx = ctx;
        this.menu = menu;
    }

    public ContainerMenu menu() {
        return menu;
    }

    /** Жив ли ещё блок, ради которого окно открыто. */
    public boolean valid() {
        return true;
    }

    /** Заголовок панели. */
    protected abstract String title();

    /** Разложить группы и нарисовать свои виджеты; вернуть габариты панели. */
    protected abstract float[] layout(MenuTheme theme);

    /** Своё содержимое поверх слотов — пламя печи, полка рецептов. */
    protected void drawExtras(MenuTheme theme) {
    }

    /**
     * Область собственного виджета экрана, которая не является слотом.
     *
     * <p>Нужна вкладкам и кнопкам внутри контейнера: клик по ним не должен
     * одновременно выбрасывать стопку с курсора как клик «мимо окна».
     */
    protected boolean inputWidgetAt(float x, float y) {
        return false;
    }

    // ------------------------------------------------------------ раскладка

    /** Ширина сетки группы в пикселях. */
    protected static float gridWidth(int columns) {
        return columns * SLOT + (columns - 1) * GAP;
    }

    protected static float gridHeight(int rows) {
        return rows * SLOT + (rows - 1) * GAP;
    }

    /** Поставить группу левым верхним углом в (x, y). */
    protected void place(SlotGroup g, float x, float y) {
        origins.put(g, new float[] { x, y });
        if (!laidOut.contains(g))
            laidOut.add(g);
    }

    /** Экранный прямоугольник слота: {x, y, size}. */
    public float[] slotRect(SlotRef s) {
        float[] o = origins.get(s.group());
        if (o == null)
            return null;
        int col = s.index() % s.group().columns;
        int row = s.index() / s.group().columns;
        return new float[] { o[0] + col * (SLOT + GAP), o[1] + row * (SLOT + GAP), SLOT };
    }

    /** Центр слота — автопилоту и перелётам. */
    public float[] slotCenter(String groupId, int index) {
        SlotGroup g = menu.group(groupId);
        if (g == null || index < 0 || index >= g.size())
            return null;
        float[] r = slotRect(new SlotRef(g, index));
        return r == null ? null : new float[] { r[0] + r[2] / 2f, r[1] + r[2] / 2f };
    }

    private SlotRef slotAt(MenuTheme theme, float mx, float my) {
        for (SlotGroup g : laidOut)
            for (int i = 0; i < g.size(); i++) {
                float[] r = slotRect(new SlotRef(g, i));
                if (r == null)
                    continue;
                if (mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[2])
                    return new SlotRef(g, i);
            }
        return null;
    }

    // --------------------------------------------------------------- кадр

    @Override
    public MenuAction draw(MenuTheme theme) {
        UiInput in = theme.activeInput();
        float dt = theme.dt();
        origins.clear();
        laidOut.clear();
        flights.update(dt);
        menu.clearMoves();

        // Окно подрастает при открытии: мгновенно возникшая панель читается
        // как подмена кадра, а не как «открылось».
        open.update(1f, dt);
        now += dt;

        theme.dim(0.55f);
        float[] panel = layout(theme);
        hovered = slotAt(theme, in.mouseX, in.mouseY);
        handleInput(in);

        theme.panel(panel[0], panel[1], panel[2], panel[3]);
        theme.header(title(), panel[0] + PAD, panel[1] + 12f, panel[2] - 2 * PAD);
        drawSlots(theme);
        drawExtras(theme);
        drawFlights(theme);
        drawCursor(theme, in);
        drawTooltip(theme, in);

        takeDropped();
        if (closing || !valid())
            return MenuAction.back();
        if (in.escape() || in.pressed(ctx.keys().key(KeyBindings.Action.INVENTORY)))
            return MenuAction.back();
        return MenuAction.NONE;
    }

    /** Масштаб появления — экраны-наследники рисуют им свои виджеты. */
    protected float openScale() {
        return Math.min(1f, open.value);
    }

    private void takeDropped() {
        if (menu.dropped().isEmpty())
            return;
        for (ItemStack s : menu.dropped())
            ctx.throwStack(s);
        menu.dropped().clear();
    }

    // ---------------------------------------------------------------- ввод

    private void handleInput(UiInput in) {
        if (menu.dragging()) {
            menu.dragOver(hovered);
            if (!in.mouseDown && !in.rightDown) {
                menu.endDrag();
                ctx.click(0.4f, 1.1f);
            }
            return;
        }

        boolean creative = ctx.mode() == com.mineclone.world.GameMode.CREATIVE;
        if (in.middlePressed && hovered != null && creative) {
            menu.cloneFull(hovered);
            ctx.click(0.4f, 1.2f);
            return;
        }
        if (hovered != null && creative && in.held(GLFW_KEY_DELETE)
                && (in.mousePressed || in.rightPressed)) {
            menu.delete(hovered);
            ctx.click(0.4f, 0.8f);
            return;
        }

        if (hovered != null) {
            int drop = ctx.keys().key(KeyBindings.Action.DROP);
            if (in.pressed(drop)) {
                menu.drop(hovered, in.ctrl());
                ctx.click(0.35f, 1.0f);
                return;
            }
            for (int i = 0; i < 9; i++)
                if (in.pressed(ctx.keys().key(KeyBindings.slot(i)))) {
                    menu.numberKey(hovered, i);
                    ctx.click(0.4f, 1.1f);
                    return;
                }
        }

        if (in.mousePressed || in.rightPressed) {
            if (hovered == null) {
                if (inputWidgetAt(in.mouseX, in.mouseY))
                    return;
                // Клик мимо панелей со стопкой на курсоре — выбросить её.
                if (menu.cursor() != null)
                    menu.dropCursor(in.ctrl() || in.mousePressed);
                return;
            }
            if (in.shift()) {
                menu.shiftClick(hovered);
                ctx.click(0.45f, 1.0f);
                return;
            }
            if (menu.cursor() != null) {
                // Нажатие с непустым курсором — возможное начало протяжки;
                // одна клетка на отпускании превратится в обычный клик.
                menu.beginDrag(in.rightPressed);
                menu.dragOver(hovered);
                return;
            }
            if (in.mousePressed && wasDouble()) {
                menu.leftClick(hovered);
                menu.doubleClick(hovered);
            } else if (in.mousePressed) {
                menu.leftClick(hovered);
            } else {
                menu.rightClick(hovered);
            }
            ctx.click(0.4f, 1.1f);
            noteClick();
        }
    }

    // Двойной клик: то же окно, тот же слот, укладываемся в окно темы.
    private SlotRef lastClickSlot;
    private float lastClickTime;
    private float now;

    private boolean wasDouble() {
        return lastClickSlot != null && lastClickSlot.equals(hovered)
                && now - lastClickTime <= MenuTheme.DOUBLE_CLICK;
    }

    private void noteClick() {
        lastClickSlot = hovered;
        lastClickTime = now;
    }

    // ------------------------------------------------------------ рисование

    private void drawSlots(MenuTheme theme) {
        Map<SlotRef, Integer> preview = menu.dragPreview();
        for (SlotGroup g : laidOut)
            for (int i = 0; i < g.size(); i++) {
                SlotRef ref = new SlotRef(g, i);
                float[] r = slotRect(ref);
                if (r == null)
                    continue;
                boolean hov = ref.equals(hovered);
                drawSlotBack(theme, r[0], r[1], r[2], hov);
                ItemStack s = ref.get();
                Integer promised = preview.get(ref);
                if (flights.hides(ref))
                    continue;
                if (s != null)
                    theme.itemIcon(s, r[0] + 6f, r[1] + 6f, r[2] - 12f, 1f,
                            hov ? ItemIcons.ICON_YAW + theme.time() * ItemIcons.ICON_SPIN
                                : ItemIcons.ICON_YAW);
                else if (promised != null && menu.cursor() != null)
                    theme.itemIcon(menu.cursor(), r[0] + 6f, r[1] + 6f, r[2] - 12f, 0.45f,
                            ItemIcons.ICON_YAW);
                int shown = promised != null ? promised : (s == null ? 0 : s.count);
                if (shown > 1)
                    theme.smallShadow(Integer.toString(shown),
                            r[0] + r[2] - 4f - theme.smallWidth(Integer.toString(shown)),
                            r[1] + r[2] - 6f,
                            promised != null ? MenuTheme.ACCENT : MenuTheme.TEXT, 1f);
            }
    }

    protected void drawSlotBack(MenuTheme theme, float x, float y, float size, boolean hovered) {
        theme.quad(x, y, size, size, 0.16f, 0.18f, 0.23f, hovered ? 0.72f : 0.42f);
        theme.quad(x, y, size, 1f, 1f, 1f, 1f, hovered ? 0.22f : 0.10f);
        theme.quad(x, y + size - 1f, size, 1f, 0f, 0f, 0f, 0.30f);
    }

    private void drawFlights(MenuTheme theme) {
        flights.forEach((icon, x, y, scale) ->
                theme.itemIcon(icon, x - SLOT * scale * 0.4f, y - SLOT * scale * 0.4f,
                        (SLOT - 12f) * scale, 1f, ItemIcons.ICON_YAW));
    }

    private void drawCursor(MenuTheme theme, UiInput in) {
        ItemStack held = menu.cursor();
        if (held == null) {
            cursorPlaced = false;
            return;
        }
        float tx = in.mouseX - 18f, ty = in.mouseY - 18f;
        if (!cursorPlaced) {
            cursorPos.snap(tx, ty);
            cursorPlaced = true;
        } else {
            cursorPos.update(tx, ty, theme.dt());
        }
        theme.itemIcon(held, cursorPos.valueX(), cursorPos.valueY(), 36f, 1f, ItemIcons.ICON_YAW);
        if (held.count > 1)
            theme.smallShadow(Integer.toString(held.count),
                    cursorPos.valueX() + 34f - theme.smallWidth(Integer.toString(held.count)),
                    cursorPos.valueY() + 32f, MenuTheme.TEXT, 1f);
    }

    private void drawTooltip(MenuTheme theme, UiInput in) {
        if (hovered == null || menu.cursor() != null || menu.dragging()) {
            tooltipPlaced = false;
            return;
        }
        ItemStack s = hovered.get();
        if (s == null) {
            tooltipPlaced = false;
            return;
        }
        List<Tooltip.Line> lines = Tooltip.lines(s, ctx.advancedTooltips());
        float lineH = theme.small().getPixelHeight() + 4f;
        float w = 0f;
        for (int i = 0; i < lines.size(); i++)
            w = Math.max(w, i == 0 ? theme.textWidth(lines.get(i).text())
                    : theme.smallWidth(lines.get(i).text()));
        float tw = w + 20f;
        float th = theme.font().getPixelHeight() + 8f + (lines.size() - 1) * lineH + 12f;

        float[] r = slotRect(hovered);
        TooltipLayout.Placement p = TooltipLayout.place(r[0], r[1], r[2], r[2], tw, th,
                theme.width(), theme.height(), 10f, 6f);
        if (!tooltipPlaced) {
            tooltipPos.snap(p.x(), p.y());
            tooltipPlaced = true;
        } else {
            tooltipPos.update(p.x(), p.y(), theme.dt());
        }
        float x = tooltipPos.valueX(), y = tooltipPos.valueY();
        theme.panel(x, y, tw, th);
        float baseline = y + theme.font().getPixelHeight() + 8f;
        for (int i = 0; i < lines.size(); i++) {
            Tooltip.Line l = lines.get(i);
            if (i == 0)
                theme.text(l.text(), x + 10f, baseline, l.rgb(), 1f);
            else
                theme.smallText(l.text(), x + 10f, baseline, l.rgb(), 1f);
            baseline += i == 0 ? lineH + 2f : lineH;
        }
    }

    // ------------------------------------------------------------ закрытие

    protected void requestClose() {
        closing = true;
    }

    @Override
    public MenuAction escape() {
        return MenuAction.back();
    }

    @Override
    public void closed() {
        for (ItemStack s : menu.closeAll())
            ctx.give(s);
        for (ItemStack s : menu.dropped())
            ctx.throwStack(s);
        menu.dropped().clear();
        flights.clear();
    }

    /** Часы окна — двойной клик и вращение кубиков. */
    public void tick(float seconds) {
        now = seconds;
    }
}

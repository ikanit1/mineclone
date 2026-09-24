package com.mineclone.ui.container;

import com.mineclone.world.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Правила окна: что делает клик, Shift, цифра, протяжка и закрытие.
 *
 * <p>Ни одного GL-вызова и ни одной ссылки на мир: окно — это набор групп
 * слотов и таблица маршрутов, а всё остальное решает арифметика. Поэтому
 * правила проверяются обычными тестами, а не кликами по живому экрану, где
 * ошибку видно только на глаз и только иногда.
 *
 * <p>Правила слияния общие с инвентарём и сундуком до последней детали:
 * игрок обнаруживает расхождение в самый неподходящий момент.
 */
public final class ContainerMenu {

    /** Перелёт стопки: откуда, куда и что именно летит. */
    public record Move(SlotRef from, SlotRef to, ItemStack icon) {}

    private final List<SlotGroup> groups;
    private final Map<SlotRole, List<SlotRole>> routes = new EnumMap<>(SlotRole.class);

    private ItemStack cursor;
    private final List<Move> moves = new ArrayList<>();
    private final List<ItemStack> dropped = new ArrayList<>();

    /** Двойной клик копит из слотов, из которых уже брал, — их не трогаем дважды. */
    private boolean dragging;
    private boolean dragRight;
    private final List<SlotRef> dragSlots = new ArrayList<>();
    private ItemStack dragStart;
    private java.util.function.Consumer<MenuCommand> remote;

    public void setRemote(java.util.function.Consumer<MenuCommand> remote) { this.remote=remote; }
    private boolean dispatch(MenuCommand.Kind kind,SlotRef slot,int argument) {
        if(remote==null)return false;
        remote.accept(MenuCommand.of(kind,slot,argument));
        return true;
    }

    public void cancelDrag() { dragging=false;dragSlots.clear();dragStart=null; }

    /** Items temporarily owned by this menu, excluding the shared container and craft output. */
    public List<ItemStack> pendingItems() {
        List<ItemStack> result=new ArrayList<>();
        if(cursor!=null)result.add(cursor.copy());
        for(SlotGroup g:groups)if(g.role==SlotRole.CRAFT_GRID)
            for(int i=0;i<g.size();i++)if(g.storage.get(i)!=null)result.add(g.storage.get(i).copy());
        for(ItemStack s:dropped)result.add(s.copy());
        return result;
    }

    public void pickAll(SlotRef s) {
        if(dispatch(MenuCommand.Kind.PICK_ALL,s,0))return;
        leftClick(s);doubleClick(s);
    }

    public ContainerMenu(List<SlotGroup> groups) {
        this.groups = List.copyOf(groups);
    }

    public List<SlotGroup> groups() {
        return groups;
    }

    public SlotGroup group(String id) {
        for (SlotGroup g : groups)
            if (g.id.equals(id))
                return g;
        return null;
    }

    public ItemStack cursor() {
        return cursor;
    }

    public void setCursor(ItemStack s) {
        cursor = s == null || s.count <= 0 ? null : s;
    }

    /** Куда Shift переносит из этой роли — по порядку. */
    public void route(SlotRole from, SlotRole... to) {
        routes.put(from, List.of(to));
    }

    /** Перемещения за последнюю операцию — по ним рисуются перелёты иконок. */
    public List<Move> moves() {
        return moves;
    }

    public void clearMoves() {
        moves.clear();
    }

    /** Что должно улететь в мир; вызывающий забирает и очищает. */
    public List<ItemStack> dropped() {
        return dropped;
    }

    // ------------------------------------------------------------ клики

    public void leftClick(SlotRef s) {
        if(dispatch(MenuCommand.Kind.LEFT,s,0))return;
        if (s == null)
            return;
        if (s.role().isCreativeSource()) {
            creativeClick(s);
            return;
        }
        if (s.role() == SlotRole.TRASH) {
            cursor = null;
            return;
        }
        ItemStack in = s.get();
        if (cursor == null) {
            if (in == null || !s.canTake())
                return;
            cursor = s.group().storage.take(s.index(), in.count);
            return;
        }
        // Слот «только забрать»: положить нельзя, но подхватить содержимое
        // подходящим курсором можно — иначе печь пришлось бы разгружать
        // пустой рукой.
        if (!s.canPlace(cursor)) {
            if (in != null && in.stacksWith(cursor))
                pullInto(s);
            return;
        }
        if (in != null && in.stacksWith(cursor)) {
            int room = Math.min(s.maxCount(cursor), in.maxStack()) - in.count;
            if (room > 0) {
                int add = Math.min(room, cursor.count);
                in.count += add;
                cursor.count -= add;
                s.group().storage.changed(s.index());
                if (cursor.count <= 0)
                    cursor = null;
            }
            return;
        }
        if (in == null) {
            int fit = Math.min(s.maxCount(cursor), cursor.count);
            if (fit <= 0)
                return;
            if (fit >= cursor.count) {
                s.set(cursor);
                cursor = null;
            } else {
                s.set(cursor.copyWithCount(fit));
                cursor.count -= fit;
            }
            return;
        }
        if (!s.canTake())
            return;
        ItemStack held = cursor;
        cursor = in;
        s.set(held);
    }

    public void rightClick(SlotRef s) {
        if(dispatch(MenuCommand.Kind.RIGHT,s,0))return;
        if (s == null)
            return;
        if (s.role().isCreativeSource()) {
            creativeClick(s);
            return;
        }
        if (s.role() == SlotRole.TRASH) {
            cursor = null;
            return;
        }
        ItemStack in = s.get();
        if (cursor == null) {
            if (in == null || !s.canTake())
                return;
            // Нестопкуемое не делится — забираем целиком.
            int half = in.maxStack() <= 1 ? in.count : (in.count + 1) / 2;
            cursor = s.group().storage.take(s.index(), half);
            return;
        }
        if (!s.canPlace(cursor)) {
            if (in != null && in.stacksWith(cursor))
                pullInto(s);
            return;
        }
        if (in != null && in.stacksWith(cursor)) {
            if (in.count < Math.min(s.maxCount(cursor), in.maxStack())) {
                in.count++;
                cursor.count--;
                s.group().storage.changed(s.index());
                if (cursor.count <= 0)
                    cursor = null;
            }
            return;
        }
        if (in == null) {
            if (cursor.maxStack() <= 1) {
                s.set(cursor);
                cursor = null;
                return;
            }
            s.set(cursor.copyWithCount(1));
            cursor.count--;
            if (cursor.count <= 0)
                cursor = null;
            return;
        }
        if (!s.canTake())
            return;
        ItemStack held = cursor;
        cursor = in;
        s.set(held);
    }

    /** Забирает из слота столько, сколько влезет в курсор. */
    private void pullInto(SlotRef s) {
        int room = cursor.maxStack() - cursor.count;
        if (room <= 0)
            return;
        ItemStack taken = s.group().storage.take(s.index(), room);
        if (taken != null)
            cursor.count += taken.count;
    }

    // ---------------------------------------------------------- Shift

    /**
     * Перенос по Shift: сначала неполные подходящие стопки целевых групп,
     * потом пустые слоты. Куда переносить, решает таблица маршрутов окна.
     */
    public void shiftClick(SlotRef s) {
        if(dispatch(MenuCommand.Kind.SHIFT,s,0))return;
        if (s == null)
            return;
        if (s.role().isCreativeSource()) {
            giveCreativeStack(s);
            return;
        }
        ItemStack in = s.get();
        if (in == null || !s.canTake())
            return;
        List<SlotRole> to = routes.get(s.role());
        if (to == null || to.isEmpty())
            return;
        ItemStack moving = in.copy();
        int before = moving.count;
        int left = deposit(moving, to, s);
        int moved = before - left;
        if (moved <= 0)
            return;
        in.count -= moved;
        if (in.count <= 0)
            s.set(null);
        else
            s.group().storage.changed(s.index());
    }

    /** @return сколько не влезло */
    private int deposit(ItemStack moving, List<SlotRole> to, SlotRef exclude) {
        for (SlotRole role : to)
            for (SlotGroup g : groups) {
                if (g.role != role)
                    continue;
                for (int i = 0; i < g.size() && moving.count > 0; i++) {
                    SlotRef t = new SlotRef(g, i);
                    if (t.equals(exclude))
                        continue;
                    ItemStack in = t.get();
                    if (in == null || !in.stacksWith(moving) || !t.canPlace(moving))
                        continue;
                    int room = Math.min(t.maxCount(moving), in.maxStack()) - in.count;
                    if (room <= 0)
                        continue;
                    int add = Math.min(room, moving.count);
                    in.count += add;
                    moving.count -= add;
                    g.storage.changed(i);
                    moves.add(new Move(exclude, t, moving.copyWithCount(add)));
                }
            }
        for (SlotRole role : to)
            for (SlotGroup g : groups) {
                if (g.role != role)
                    continue;
                for (int i = 0; i < g.size() && moving.count > 0; i++) {
                    SlotRef t = new SlotRef(g, i);
                    if (t.equals(exclude) || t.get() != null || !t.canPlace(moving))
                        continue;
                    int fit = Math.min(t.maxCount(moving), moving.count);
                    if (fit <= 0)
                        continue;
                    t.set(moving.copyWithCount(fit));
                    moving.count -= fit;
                    moves.add(new Move(exclude, t, moving.copyWithCount(fit)));
                }
            }
        return moving.count;
    }

    // ------------------------------------------------------- двойной клик

    /**
     * Двойной клик собирает на курсор всё того же вида: сначала неполные
     * стопки, потом полные.
     *
     * <p>Неполные вперёд — иначе окно оставляет после себя россыпь огрызков
     * по одному предмету, а собирал игрок как раз ради порядка.
     */
    public void doubleClick(SlotRef s) {
        if(dispatch(MenuCommand.Kind.DOUBLE,s,0))return;
        if (cursor == null || cursor.maxStack() <= 1)
            return;
        for (int pass = 0; pass < 2; pass++)
            for (SlotGroup g : groups) {
                if (g.role.takeOnly() || g.role.isCreativeSource() || g.role == SlotRole.TRASH)
                    continue;
                for (int i = 0; i < g.size() && cursor.count < cursor.maxStack(); i++) {
                    ItemStack in = g.storage.get(i);
                    if (in == null || !in.stacksWith(cursor))
                        continue;
                    boolean full = in.count >= in.maxStack();
                    if (full != (pass == 1))
                        continue;
                    int room = cursor.maxStack() - cursor.count;
                    ItemStack taken = g.storage.take(i, Math.min(room, in.count));
                    if (taken != null)
                        cursor.count += taken.count;
                }
            }
    }

    // ------------------------------------------------------------ цифры

    /** Обмен слота с ячейкой хотбара — с проверкой, что обе стороны примут. */
    public void numberKey(SlotRef s, int hotbarIndex) {
        if(dispatch(MenuCommand.Kind.NUMBER,s,hotbarIndex))return;
        if (s == null)
            return;
        SlotGroup hotbar = null;
        for (SlotGroup g : groups)
            if (g.role == SlotRole.HOTBAR)
                hotbar = g;
        if (hotbar == null || hotbarIndex < 0 || hotbarIndex >= hotbar.size())
            return;
        SlotRef h = new SlotRef(hotbar, hotbarIndex);
        if (s.role().isCreativeSource()) {
            ItemStack sample = s.get();
            if (sample != null && h.canPlace(sample)) {
                ItemStack copy = sample.copyWithCount(Math.min(sample.maxStack(), h.maxCount(sample)));
                h.set(copy);
                moves.add(new Move(s, h, copy.copy()));
            }
            return;
        }
        if (h.equals(s))
            return;
        ItemStack here = s.get(), there = h.get();
        if (here != null && !s.canTake())
            return;
        if (there != null && !h.canTake())
            return;
        if (there != null && !s.canPlace(there))
            return;
        if (here != null && !h.canPlace(here))
            return;
        s.set(there);
        h.set(here);
        if (here != null)
            moves.add(new Move(s, h, here.copy()));
    }

    // ------------------------------------------------------------- бросок

    public void drop(SlotRef s, boolean wholeStack) {
        if(dispatch(MenuCommand.Kind.DROP,s,wholeStack?1:0))return;
        if (s == null || s.role().isCreativeSource())
            return;
        ItemStack in = s.get();
        if (in == null || !s.canTake())
            return;
        ItemStack out = s.group().storage.take(s.index(), wholeStack ? in.count : 1);
        if (out != null)
            dropped.add(out);
    }

    public void dropCursor(boolean wholeStack) {
        if(dispatch(MenuCommand.Kind.DROP_CURSOR,null,wholeStack?1:0))return;
        if (cursor == null)
            return;
        if (wholeStack || cursor.count <= 1) {
            dropped.add(cursor);
            cursor = null;
            return;
        }
        dropped.add(cursor.copyWithCount(1));
        cursor.count--;
    }

    // ------------------------------------------------------------ протяжка

    public void beginDrag(boolean right) {
        if (cursor == null)
            return;
        dragging = true;
        dragRight = right;
        dragSlots.clear();
        dragStart = cursor.copy();
    }

    public boolean dragging() {
        return dragging;
    }

    /**
     * Слот под курсором во время протяжки.
     *
     * <p>Слоты добавляются, пока их меньше числа предметов на курсоре: делить
     * восемь предметов на девять слотов нечего, и подсветка не должна обещать
     * того, чего не будет.
     */
    public void dragOver(SlotRef s) {
        if (!dragging || s == null || dragStart == null)
            return;
        if (dragSlots.size() >= dragStart.count)
            return;
        if (dragSlots.contains(s))
            return;
        if (s.role().takeOnly() || s.role().isCreativeSource() || s.role() == SlotRole.TRASH)
            return;
        ItemStack in = s.get();
        if (in != null && (!in.stacksWith(dragStart) || in.count >= in.maxStack()))
            return;
        if (!s.canPlace(dragStart))
            return;
        dragSlots.add(s);
    }

    /** Что достанется каждому слоту, если отпустить сейчас. */
    public Map<SlotRef, Integer> dragPreview() {
        Map<SlotRef, Integer> out = new LinkedHashMap<>();
        if (!dragging || cursor == null || dragSlots.size() < 2)
            return out;
        int[] add = split();
        for (int i = 0; i < dragSlots.size(); i++)
            if (add[i] > 0) {
                ItemStack in = dragSlots.get(i).get();
                out.put(dragSlots.get(i), (in == null ? 0 : in.count) + add[i]);
            }
        return out;
    }

    private int[] split() {
        int n = dragSlots.size();
        int[] existing = new int[n];
        int[] limits = new int[n];
        for (int i = 0; i < n; i++) {
            SlotRef s = dragSlots.get(i);
            ItemStack in = s.get();
            existing[i] = in == null ? 0 : in.count;
            limits[i] = Math.min(s.maxCount(cursor), cursor.maxStack());
        }
        return DragSplit.distribute(cursor.count, dragRight, existing, limits);
    }

    /**
     * Отпустили протяжку.
     *
     * <p>Одна клетка — это обычный клик: игрок, который просто нажал и отпустил
     * на слоте, не имел в виду «раздать поровну на один слот».
     */
    public void endDrag() {
        if(remote!=null && dragging) {
            var targets=dragSlots.stream().map(s -> new MenuCommand.Target(s.group().id,s.index())).toList();
            var command=new MenuCommand(MenuCommand.Kind.DRAG,"",-1,dragRight?1:0,targets);
            cancelDrag();remote.accept(command);return;
        }
        if (!dragging)
            return;
        dragging = false;
        if (cursor == null || dragSlots.isEmpty()) {
            dragSlots.clear();
            dragStart = null;
            return;
        }
        if (dragSlots.size() == 1) {
            SlotRef only = dragSlots.get(0);
            dragSlots.clear();
            dragStart = null;
            if (dragRight)
                rightClick(only);
            else
                leftClick(only);
            return;
        }
        int[] add = split();
        for (int i = 0; i < dragSlots.size(); i++) {
            if (add[i] <= 0)
                continue;
            SlotRef s = dragSlots.get(i);
            ItemStack in = s.get();
            if (in == null)
                s.set(cursor.copyWithCount(add[i]));
            else {
                in.count += add[i];
                s.group().storage.changed(s.index());
            }
            cursor.count -= add[i];
        }
        if (cursor.count <= 0)
            cursor = null;
        dragSlots.clear();
        dragStart = null;
    }

    // ------------------------------------------------------------- креатив

    /**
     * Клик по бездонному источнику: копия на курсор, повторный клик тем же
     * предметом — плюс один.
     */
    private void creativeClick(SlotRef s) {
        ItemStack sample = s.get();
        if (sample == null)
            return;
        if (cursor != null && cursor.stacksWith(sample)) {
            if (cursor.count < cursor.maxStack())
                cursor.count++;
            return;
        }
        // Стопка, положенная на источник, исчезает: источник бездонный, и
        // «вернуть» в него нечего.
        cursor = sample.copyWithCount(1);
    }

    /** Средняя кнопка в креативе: полная стопка на курсор. */
    public void cloneFull(SlotRef s) {
        if(dispatch(MenuCommand.Kind.CLONE,s,0))return;
        if (s == null)
            return;
        ItemStack sample = s.get();
        if (sample == null)
            return;
        cursor = sample.copyWithCount(sample.maxStack());
    }

    /** Delete с кликом в креативе: слот очищается. */
    public void delete(SlotRef s) {
        if(dispatch(MenuCommand.Kind.DELETE,s,0))return;
        if (s == null || s.role().isCreativeSource())
            return;
        if (!s.canTake())
            return;
        s.set(null);
    }

    /** Shift по источнику: полная стопка едет в хотбар. */
    private void giveCreativeStack(SlotRef s) {
        ItemStack sample = s.get();
        if (sample == null)
            return;
        List<SlotRole> to = routes.get(SlotRole.CREATIVE_SOURCE);
        if (to == null || to.isEmpty())
            to = List.of(SlotRole.HOTBAR, SlotRole.MAIN);
        ItemStack moving = sample.copyWithCount(sample.maxStack());
        deposit(moving, to, null);
    }

    // ------------------------------------------------------------ закрытие

    /**
     * Окно закрывается: всё, что держал курсор, возвращается игроку.
     *
     * <p>Курсор — это предметы игрока, просто ни в одном слоте. Уронить их при
     * закрытии значило бы наказать за Esc.
     */
    public List<ItemStack> closeAll() {
        List<ItemStack> out = new ArrayList<>();
        if (cursor != null) {
            out.add(cursor);
            cursor = null;
        }
        dragging = false;
        dragSlots.clear();
        dragStart = null;
        return out;
    }
}

package com.mineclone.ui;

import com.mineclone.save.SaveManager;
import com.mineclone.world.BlockType;
import com.mineclone.world.GameMode;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Список миров: превью, режим, сутки, размер, последний вход.
 *
 * <p>Управление миром — переименовать, копировать, удалить — живёт здесь же и
 * идёт прямо в {@link SaveManager}: игре незачем знать о файлах. Наружу
 * уходит только «играть в этот мир».
 *
 * <p>Удаление — через подтверждение: оно необратимо, а кнопка стоит рядом с
 * «Копией». Двойной щелчок по строке — сразу в игру, стрелки двигают выбор.
 */
public final class WorldSelectScreen implements Screen {

    private enum Dialog { NONE, DELETE, RENAME }

    static final float ROW_H = 92f, ROW_GAP = 8f;
    static final float THUMB_W = 128f, THUMB_H = 72f;
    /** Предел длины имени — длиннее не влезает ни в строку, ни в заголовок паузы. */
    static final int NAME_MAX = 32;

    private final SaveManager save;
    private final SettingsModel settings;
    private final WorldIconCache icons;
    private final ScrollState scroll = new ScrollState();
    private List<SaveManager.WorldInfo> worlds = new ArrayList<>();
    private String selectedId;
    private Dialog dialog = Dialog.NONE;
    private final TextField renameField = new TextField("", NAME_MAX, TextField.ANY);
    private boolean ensureSelectedVisible;
    /**
     * Размеры миров считаются в фоне и дописываются в строки, когда готовы:
     * обход тысячи файлов чанков стоил кадр при каждом открытии списка.
     */
    private final Map<String, Long> sizes = new ConcurrentHashMap<>();

    public WorldSelectScreen(SaveManager save, SettingsModel settings) {
        this.save = save;
        this.settings = settings;
        this.icons = new WorldIconCache(save);
        refresh(null);
    }

    private void refresh(String select) {
        worlds = save.listWorlds(false);
        List<String> ids = new ArrayList<>();
        for (SaveManager.WorldInfo w : worlds)
            ids.add(w.id);
        Thread sizer = new Thread(() -> {
            for (String id : ids)
                sizes.put(id, save.worldSize(id));
        }, "world-sizes");
        sizer.setDaemon(true);
        sizer.start();
        selectedId = null;
        for (SaveManager.WorldInfo w : worlds)
            if (w.id.equals(select))
                selectedId = select;
        if (selectedId == null && !worlds.isEmpty() && worlds.get(0).playable())
            selectedId = worlds.get(0).id;
        ensureSelectedVisible = true;
    }

    /** Выбрать мир по id, как щелчком по строке. */
    public void select(String id) {
        refresh(id);
    }

    /** Открыть подтверждение удаления выбранного мира. */
    public void requestDelete() {
        if (selected() != null)
            dialog = Dialog.DELETE;
    }

    /** Открыть переименование выбранного мира. */
    public void requestRename(MenuTheme t) {
        SaveManager.WorldInfo sel = selected();
        if (sel == null || !sel.playable())
            return;
        renameField.setText(sel.displayName);
        dialog = Dialog.RENAME;
        t.focus("worlds.renameField");
    }

    private SaveManager.WorldInfo selected() {
        for (SaveManager.WorldInfo w : worlds)
            if (w.id.equals(selectedId))
                return w;
        return null;
    }

    @Override
    public void resumed() {
        icons.clear();
        refresh(selectedId);
    }

    @Override
    public void closed() {
        icons.clear();
    }

    @Override
    public MenuAction draw(MenuTheme t) {
        int sw = t.width(), sh = t.height();
        t.dim(0.30f);
        float pw = Math.min(920f, sw - 48f), ph = Math.min(680f, sh - 40f);
        float px = (sw - pw) / 2f, py = (sh - ph) / 2f;
        t.panel(px, py, pw, ph);
        float inner = px + 24f, iw = pw - 48f;
        float y = t.header("Одиночная игра", inner, py + 14f, iw);
        if (!worlds.isEmpty())
            t.smallRight(MenuText.count(worlds.size(), "мир", "мира", "миров"), inner + iw, py + 38f,
                    MenuTheme.TEXT_DIM, 1f);

        boolean modal = dialog != Dialog.NONE;
        t.setInputEnabled(!modal);
        MenuAction result = MenuAction.NONE;

        float footH = 124f;
        float areaY = y + 12f, areaH = py + ph - footH - areaY;
        if (worlds.isEmpty()) {
            result = drawEmpty(t, inner, areaY, iw, areaH);
        } else {
            result = drawList(t, inner, areaY, iw, areaH);
            MenuAction foot = drawFooter(t, inner, py + ph - footH + 12f, iw);
            if (foot != MenuAction.NONE)
                result = foot;
            if (!modal)
                result = keyboard(t, result, areaH);
        }
        t.setInputEnabled(true);

        if (dialog == Dialog.DELETE)
            result = drawDelete(t);
        else if (dialog == Dialog.RENAME)
            result = drawRename(t);
        return result;
    }

    private MenuAction drawEmpty(MenuTheme t, float x, float y, float w, float h) {
        float cx = x + w / 2f, cy = y + h / 2f - 40f;
        t.tile(BlockType.GRASS.sideTile, cx - 24f, cy - 70f, 48f, 48f, 1f, 1f);
        t.textCentered("Миров пока нет", cx, cy + 10f, MenuTheme.TEXT, 1f);
        t.smallCentered("Создайте первый — это займёт пару секунд.", cx, cy + 36f, MenuTheme.TEXT_DIM, 1f);
        float bw = Math.min(320f, w);
        MenuAction a = MenuAction.NONE;
        if (t.button("worlds.createEmpty", cx - bw / 2f, cy + 60f, bw, 48f, "Создать мир",
                MenuTheme.Style.PRIMARY, true))
            a = MenuAction.push(new WorldCreateScreen(save, settings));
        float backW = Math.min(200f, w);
        if (t.button("worlds.backEmpty", cx - backW / 2f, cy + 118f, backW, 42f, "Назад"))
            a = MenuAction.back();
        return a;
    }

    private MenuAction drawList(MenuTheme t, float x, float y, float w, float h) {
        float contentH = worlds.size() * (ROW_H + ROW_GAP) - ROW_GAP;
        if (ensureSelectedVisible) {
            int i = indexOf(selectedId);
            if (i >= 0) {
                scroll.clamp(contentH, h);
                float top = i * (ROW_H + ROW_GAP);
                scroll.ensureVisible(top, top + ROW_H, h);
            }
            ensureSelectedVisible = false;
        }
        MenuAction result = MenuAction.NONE;
        boolean before = t.inputEnabled();
        UiInput in = t.input();
        t.beginClip(x, y, w, h);
        t.setInputEnabled(before && in.mouseY >= y && in.mouseY <= y + h);
        float rowW = contentH > h ? w - 16f : w;
        long now = System.currentTimeMillis();
        ZoneId zone = ZoneId.systemDefault();
        for (int i = 0; i < worlds.size(); i++) {
            float ry = y + i * (ROW_H + ROW_GAP) - scroll.offset();
            if (ry + ROW_H < y || ry > y + h)
                continue;
            SaveManager.WorldInfo wi = worlds.get(i);
            boolean sel = wi.id.equals(selectedId);
            if (t.row("worlds.row." + wi.id, x, ry, rowW, ROW_H, sel)) {
                if (wi.playable()) {
                    if (t.wasDoubleClick() && wi.id.equals(selectedId))
                        result = MenuAction.play(wi.id);
                    selectedId = wi.id;
                } else {
                    selectedId = wi.id;
                }
            }
            drawRowContent(t, wi, x, ry, rowW, now, zone);
        }
        t.setInputEnabled(before);
        t.endClip();
        t.scrollArea("worlds.scroll", x, y, w, h, scroll, contentH);
        return result;
    }

    private void drawRowContent(MenuTheme t, SaveManager.WorldInfo wi, float x, float y, float w,
                                long now, ZoneId zone) {
        float tx = x + 12f, ty = y + (ROW_H - THUMB_H) / 2f;
        int tex = !wi.playable() ? 0 : icons.texture(wi);
        t.quad(tx - 1f, ty - 1f, THUMB_W + 2f, THUMB_H + 2f, 0f, 0f, 0f, 0.55f);
        if (tex != 0) {
            t.texQuad(tx, ty, THUMB_W, THUMB_H, tex, 0f, 0f, 1f, 1f, 1f, 1f, 1f, 1f);
        } else {
            // Нет снимка — клочок травы: мир ещё ни разу не сохранялся. У
            // повреждённого — тот же клочок, но тёмный и в красном.
            float cw = THUMB_W / 4f, ch = THUMB_H / 2f;
            float shade = !wi.playable() ? 0.30f : 0.55f;
            for (int c = 0; c < 4; c++) {
                t.tile(BlockType.GRASS.topTile, tx + c * cw, ty, cw, ch, shade, 1f);
                t.tile(BlockType.GRASS.sideTile, tx + c * cw, ty + ch, cw, ch, shade, 1f);
            }
            if (!wi.playable())
                t.quad(tx, ty, THUMB_W, THUMB_H, 0.55f, 0.05f, 0.05f, 0.35f);
        }
        t.quad(tx, ty, THUMB_W, 1f, 1f, 1f, 1f, 0.18f);

        float lx = tx + THUMB_W + 18f, lw = x + w - lx - 14f;
        if (!wi.playable()) {
            t.text(wi.tooNew ? "Мир новее этой версии" : "Повреждённый сейв", lx, y + 36f, MenuTheme.DANGER, 1f);
            t.smallText(MenuTheme.ellipsize(t.small(), "Папка " + wi.id + (wi.tooNew ? ": требуется новая версия игры" : ": level.dat не читается"), lw),
                    lx, y + 60f, MenuTheme.TEXT_DIM, 1f);
            t.smallText(wi.tooNew ? "Откройте мир в совместимой версии игры." : "Восстановите level.dat из резервной копии.", lx, y + 78f, MenuTheme.TEXT_FAINT, 1f);
            return;
        }
        t.text(MenuTheme.ellipsize(t.font(), wi.displayName, lw), lx, y + 34f, MenuTheme.TEXT, 1f);
        String mode = wi.mode == GameMode.CREATIVE ? "Творчество" : "Выживание";
        Long size = sizes.get(wi.id);
        String info = mode + "  ·  " + MenuText.gameDay(wi.timeOfDay)
                + (size != null ? "  ·  " + MenuText.fileSize(size) : "");
        t.smallText(MenuTheme.ellipsize(t.small(), info, lw), lx, y + 58f, MenuTheme.TEXT_DIM, 1f);
        String when = "Последний вход: " + MenuText.lastPlayed(wi.lastPlayed, now, zone) + "  ·  сид " + wi.seed;
        t.smallText(MenuTheme.ellipsize(t.small(), when, lw), lx, y + 77f, MenuTheme.TEXT_FAINT, 1f);
    }

    private MenuAction drawFooter(MenuTheme t, float x, float y, float w) {
        SaveManager.WorldInfo sel = selected();
        boolean playable = sel != null && sel.playable();
        MenuAction result = MenuAction.NONE;
        float gap = 10f;
        float half = (w - gap) / 2f;
        if (t.button("worlds.play", x, y, half, 48f, "Играть", MenuTheme.Style.PRIMARY, playable))
            result = MenuAction.play(sel.id);
        if (t.button("worlds.create", x + half + gap, y, half, 48f, "Создать мир"))
            result = MenuAction.push(new WorldCreateScreen(save, settings));

        // «Переименовать» длиннее соседей — ему полторы доли ширины.
        float unit = (w - gap * 4) / 5.5f, rw = unit * 1.5f, qy = y + 58f;
        if (t.button("worlds.rename", x, qy, rw, 42f, "Переименовать", MenuTheme.Style.NORMAL, playable)) {
            renameField.setText(sel.displayName);
            dialog = Dialog.RENAME;
            t.focus("worlds.renameField");
        }
        if (t.button("worlds.copy", x + rw + gap, qy, unit, 42f, "Копия", MenuTheme.Style.NORMAL, playable)) {
            String copy = save.duplicateWorld(sel.id);
            if (copy != null)
                refresh(copy);
        }
        if (t.button("worlds.backups", x + rw + unit + gap * 2, qy, unit, 42f, "Бэкапы…", MenuTheme.Style.NORMAL,
                sel != null))
            result = MenuAction.push(new WorldBackupsScreen(save, sel.id));
        if (t.button("worlds.delete", x + rw + unit * 2 + gap * 3, qy, unit, 42f, "Удалить", MenuTheme.Style.NORMAL,
                sel != null))
            dialog = Dialog.DELETE;
        if (t.button("worlds.back", x + rw + unit * 3 + gap * 4, qy, unit, 42f, "Назад"))
            result = MenuAction.back();
        return result;
    }

    /** Стрелки двигают выбор, Enter — играть, Delete — спросить об удалении. */
    private MenuAction keyboard(MenuTheme t, MenuAction result, float viewH) {
        UiInput in = t.input();
        int i = indexOf(selectedId);
        if (in.pressed(GLFW_KEY_DOWN) && i < worlds.size() - 1) {
            selectedId = worlds.get(i + 1).id;
            ensureSelectedVisible = true;
        } else if (in.pressed(GLFW_KEY_UP) && i > 0) {
            selectedId = worlds.get(i - 1).id;
            ensureSelectedVisible = true;
        }
        SaveManager.WorldInfo sel = selected();
        if (in.enter() && sel != null && sel.playable())
            return MenuAction.play(sel.id);
        if (in.pressed(GLFW_KEY_DELETE) && sel != null)
            dialog = Dialog.DELETE;
        return result;
    }

    private int indexOf(String id) {
        for (int i = 0; i < worlds.size(); i++)
            if (worlds.get(i).id.equals(id))
                return i;
        return -1;
    }

    private MenuAction drawDelete(MenuTheme t) {
        SaveManager.WorldInfo sel = selected();
        if (sel == null) {
            dialog = Dialog.NONE;
            return MenuAction.NONE;
        }
        int sw = t.width(), sh = t.height();
        t.flush();
        t.dim(0.45f);
        float pw = Math.min(500f, sw - 48f), ph = 214f;
        float px = (sw - pw) / 2f, py = (sh - ph) / 2f;
        t.panel(px, py, pw, ph);
        t.textCentered("Удалить мир?", sw / 2f, py + 46f, MenuTheme.HEADER, 1f);
        t.smallCentered(MenuTheme.ellipsize(t.small(), "«" + sel.displayName + "» будет удалён безвозвратно.", pw - 40f),
                sw / 2f, py + 76f, MenuTheme.TEXT_DIM, 1f);
        t.smallCentered("Вместе с постройками, сундуками и превью.", sw / 2f, py + 96f, MenuTheme.TEXT_FAINT, 1f);
        float bw = (pw - 48f - 12f) / 2f, by = py + ph - 68f;
        if (t.button("worlds.confirmDelete", px + 24f, by, bw, 46f, "Удалить", MenuTheme.Style.DANGER, true)) {
            icons.forget(sel.id);
            save.deleteWorld(sel.id);
            dialog = Dialog.NONE;
            refresh(null);
        }
        if (t.button("worlds.cancelDelete", px + 24f + bw + 12f, by, bw, 46f, "Отмена"))
            dialog = Dialog.NONE;
        return MenuAction.NONE;
    }

    private MenuAction drawRename(MenuTheme t) {
        SaveManager.WorldInfo sel = selected();
        if (sel == null) {
            dialog = Dialog.NONE;
            return MenuAction.NONE;
        }
        int sw = t.width(), sh = t.height();
        t.flush();
        t.dim(0.45f);
        float pw = Math.min(540f, sw - 48f), ph = 212f;
        float px = (sw - pw) / 2f, py = (sh - ph) / 2f;
        t.panel(px, py, pw, ph);
        t.textCentered("Переименовать мир", sw / 2f, py + 44f, MenuTheme.HEADER, 1f);
        boolean submit = t.textField("worlds.renameField", px + 24f, py + 70f, pw - 48f, 42f,
                renameField, "Название мира");
        String name = renameField.text().trim();
        float bw = (pw - 48f - 12f) / 2f, by = py + ph - 68f;
        boolean ok = t.button("worlds.renameOk", px + 24f, by, bw, 46f, "Готово", MenuTheme.Style.PRIMARY,
                !name.isEmpty());
        if ((ok || submit) && !name.isEmpty()) {
            save.renameWorld(sel.id, name);
            dialog = Dialog.NONE;
            refresh(sel.id);
        }
        if (t.button("worlds.renameCancel", px + 24f + bw + 12f, by, bw, 46f, "Отмена"))
            dialog = Dialog.NONE;
        return MenuAction.NONE;
    }

    /** Esc закрывает сначала диалог, потом экран. */
    @Override
    public MenuAction escape() {
        if (dialog != Dialog.NONE) {
            dialog = Dialog.NONE;
            return MenuAction.NONE;
        }
        return MenuAction.back();
    }
}

package com.mineclone.ui;

import com.mineclone.core.KeyBindings;
import com.mineclone.core.KeyBindings.Action;

import java.util.EnumSet;

/**
 * Назначение клавиш: список действий по разделам, щелчок по клавише ловит
 * следующее нажатие.
 *
 * <p>Конфликт не запрещается, а подсвечивается у обоих действий: игрок часто
 * меняет две клавиши местами, и запрет на промежуточном шаге сделал бы обмен
 * невозможным.
 */
public final class KeybindScreen implements Screen {

    private static final float ROW_H = 38f, ROW_GAP = 6f, GROUP_H = 34f;
    /** Сколько секунд держится подсказка «клавиша занята игрой». */
    static final float WARN_TIME = 2.4f;

    private final SettingsModel m;
    private final ScrollState scroll = new ScrollState();
    private Action capturing;
    private float warn;

    public KeybindScreen(SettingsModel model) {
        this.m = model;
    }

    /** Начать ждать клавишу для действия — как щелчок по его кнопке. */
    public void startCapture(Action a) {
        capturing = a;
    }

    @Override
    public MenuAction draw(MenuTheme t) {
        int sw = t.width(), sh = t.height();
        warn = Math.max(0f, warn - t.dt());
        UiInput in = t.input();

        if (capturing != null && t.inputEnabled()) {
            int key = in.firstPressed();
            if (key >= 0 && key != org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                if (m.keys.set(capturing, key)) {
                    m.changed();
                    capturing = null;
                } else {
                    warn = WARN_TIME;
                }
            }
        }

        t.dim(0.30f);
        float pw = Math.min(700f, sw - 48f), ph = Math.min(660f, sh - 40f);
        float px = (sw - pw) / 2f, py = (sh - ph) / 2f;
        t.panel(px, py, pw, ph);
        float inner = px + 24f, iw = pw - 48f;
        float y = t.header("Назначение клавиш", inner, py + 14f, iw);

        float footH = 104f;
        float areaY = y + 10f, areaH = py + ph - footH - areaY;
        float contentH = contentHeight();
        EnumSet<Action> conflicts = m.keys.conflicts();

        t.beginClip(inner, areaY, iw, areaH);
        // Строки, срезанные краем списка, не должны ловить курсор за краем.
        boolean listInput = t.inputEnabled() && in.mouseY >= areaY && in.mouseY <= areaY + areaH;
        t.setInputEnabled(listInput);
        float cy = areaY - scroll.offset();
        float rowW = contentH > areaH ? iw - 16f : iw;
        KeyBindings.Group group = null;
        for (Action a : Action.values()) {
            if (a.group != group) {
                group = a.group;
                t.smallText(group.title.toUpperCase(), inner + 4f, cy + GROUP_H - 10f, MenuTheme.ACCENT, 1f);
                cy += GROUP_H;
            }
            if (cy + ROW_H >= areaY && cy <= areaY + areaH)
                drawRow(t, a, inner, cy, rowW, conflicts.contains(a));
            cy += ROW_H + ROW_GAP;
        }
        t.setInputEnabled(true);
        t.endClip();
        t.scrollArea("keys.scroll", inner, areaY, iw, areaH, scroll, contentH);

        // Подсказка под списком говорит о том, что важно прямо сейчас.
        float hintY = py + ph - footH + 26f;
        if (warn > 0f)
            t.smallCentered("Эту клавишу занимает сама игра — выберите другую.", sw / 2f, hintY,
                    MenuTheme.DANGER, Math.min(1f, warn / 0.3f));
        else if (capturing != null)
            t.smallCentered("Нажмите клавишу для «" + capturing.title + "». Esc — отмена.", sw / 2f, hintY,
                    MenuTheme.ACCENT, 1f);
        else if (!conflicts.isEmpty())
            t.smallCentered("Красным — одна клавиша на несколько действий.", sw / 2f, hintY,
                    MenuTheme.DANGER, 1f);
        else
            t.smallCentered("Щёлкните по клавише, чтобы назначить другую.", sw / 2f, hintY,
                    MenuTheme.TEXT_DIM, 1f);

        float bw = (Math.min(560f, iw) - 12f) / 2f;
        float bx = px + (pw - (bw * 2 + 12f)) / 2f, by = py + ph - 58f;
        MenuAction result = MenuAction.NONE;
        if (t.button("keys.reset", bx, by, bw, 44f, "Сбросить всё")) {
            m.keys.reset();
            capturing = null;
            m.changed();
        }
        if (t.button("keys.done", bx + bw + 12f, by, bw, 44f, "Готово", MenuTheme.Style.PRIMARY, true))
            result = MenuAction.back();
        return result;
    }

    private void drawRow(MenuTheme t, Action a, float x, float y, float w, boolean conflict) {
        float keyW = Math.min(230f, w * 0.42f);
        t.quad(x, y, w, ROW_H, 0.10f, 0.12f, 0.16f, 0.34f);
        t.text(MenuTheme.ellipsize(t.font(), a.title, w - keyW - 28f), x + 14f, t.baseline(t.font(), y, ROW_H),
                conflict ? MenuTheme.DANGER : MenuTheme.TEXT, 1f);

        boolean capture = a == capturing;
        String label;
        if (capture) {
            // Ожидание нажатия пульсирует: пустая кнопка выглядела бы зависшей.
            boolean on = (int) (t.time() * 2.5f) % 2 == 0;
            label = on ? "> ? <" : ">   <";
        } else {
            label = KeyBindings.keyName(m.keys.key(a));
        }
        float kx = x + w - keyW;
        if (t.button("keys." + a.name(), kx, y, keyW, ROW_H, label,
                capture ? MenuTheme.Style.PRIMARY : MenuTheme.Style.NORMAL, true))
            capturing = capture ? null : a;
        if (conflict && !capture)
            t.outline(kx, y, keyW, ROW_H, 1.5f, MenuTheme.DANGER, 0.9f);
    }

    private float contentHeight() {
        int groups = KeyBindings.Group.values().length;
        return groups * GROUP_H + Action.values().length * (ROW_H + ROW_GAP);
    }

    /** Esc во время захвата отменяет захват, а не закрывает экран. */
    @Override
    public MenuAction escape() {
        if (capturing != null) {
            capturing = null;
            return MenuAction.NONE;
        }
        return MenuAction.back();
    }
}

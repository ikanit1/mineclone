package com.mineclone.ui;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Состояние поля ввода: текст, каретка, повтор Backspace, вставка.
 *
 * <p>Без GL — рисует поле {@link MenuTheme#textField}, а редактирование
 * проверяется обычными тестами. Ввод в игре знает только нажатия, поэтому
 * повтор зажатой клавиши считается здесь, по времени кадра.
 */
public final class TextField {

    /** Что можно ввести. Управляющие символы отсекаются до фильтра всегда. */
    public interface Filter {
        boolean accept(char c);
    }

    public static final Filter ANY = c -> true;

    /** Через сколько секунд зажатый Backspace начинает повторять. */
    static final float REPEAT_DELAY = 0.45f;
    /** Шаг повтора, секунды. */
    static final float REPEAT_RATE = 0.035f;

    private final StringBuilder text = new StringBuilder();
    private final int maxLength;
    private final Filter filter;
    private int caret;
    private float repeatWait;
    private int repeatKey = -1;

    public TextField(String initial, int maxLength, Filter filter) {
        this.maxLength = Math.max(1, maxLength);
        this.filter = filter != null ? filter : ANY;
        setText(initial);
    }

    public String text() {
        return text.toString();
    }

    public int caret() {
        return caret;
    }

    public int maxLength() {
        return maxLength;
    }

    /**
     * Заменить содержимое целиком.
     *
     * <p>Курсор сбрасывается <b>до</b> вставки, а не только после неё: буфер
     * уже очищен, а {@link #insert} вставляет по текущей позиции, и курсор,
     * оставшийся от прежнего текста, указывал бы за конец пустой строки.
     * Стоило это исключения посреди кадра меню.
     */
    public void setText(String s) {
        text.setLength(0);
        caret = 0;
        insert(s != null ? s : "");
        caret = text.length();
    }

    /**
     * Разобрать ввод кадра.
     *
     * @return true — нажат Enter
     */
    public boolean edit(UiInput in, float dt) {
        if (in.ctrl() && in.pressed(GLFW_KEY_V)) {
            String line = in.paste;
            int nl = line.indexOf('\n');
            if (nl >= 0)
                line = line.substring(0, nl);
            insert(line);
        } else if (!in.typed.isEmpty()) {
            insert(in.typed);
        }

        editKey(in, GLFW_KEY_BACKSPACE, dt, this::backspace);
        editKey(in, GLFW_KEY_DELETE, dt, this::delete);
        editKey(in, GLFW_KEY_LEFT, dt, () -> caret = Math.max(0, caret - 1));
        editKey(in, GLFW_KEY_RIGHT, dt, () -> caret = Math.min(text.length(), caret + 1));
        if (in.pressed(GLFW_KEY_HOME))
            caret = 0;
        if (in.pressed(GLFW_KEY_END))
            caret = text.length();
        return in.enter();
    }

    /** Нажатие делает шаг сразу, удержание — после задержки и с шагом. */
    private void editKey(UiInput in, int key, float dt, Runnable step) {
        if (in.pressed(key)) {
            step.run();
            repeatKey = key;
            repeatWait = REPEAT_DELAY;
            return;
        }
        if (repeatKey != key)
            return;
        if (!in.held(key)) {
            repeatKey = -1;
            return;
        }
        repeatWait -= dt;
        while (repeatWait <= 0f) {
            step.run();
            repeatWait += REPEAT_RATE;
        }
    }

    private void insert(String s) {
        for (int i = 0; i < s.length() && text.length() < maxLength; i++) {
            char c = s.charAt(i);
            if (c < 32 || c == 127 || !filter.accept(c))
                continue;
            text.insert(caret, c);
            caret++;
        }
    }

    private void backspace() {
        if (caret > 0) {
            text.deleteCharAt(caret - 1);
            caret--;
        }
    }

    private void delete() {
        if (caret < text.length())
            text.deleteCharAt(caret);
    }
}

package com.mineclone.ui;

import java.util.BitSet;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Ввод одного кадра для меню.
 *
 * <p>Без GLFW и без окна: {@code Game} собирает снимок из {@code Input}, а
 * тесты и предпросмотр — руками. Поэтому любой экран можно провести по
 * наведению, клику, набору текста и захвату клавиши, не запуская игру.
 *
 * <p>Координаты мыши — виртуальные, уже поделённые на масштаб интерфейса.
 */
public final class UiInput {
    public final float mouseX, mouseY;
    public final boolean mouseDown, mousePressed, mouseReleased;
    /** Правая кнопка: в окнах она кладёт по одному и делит стопку пополам. */
    public final boolean rightDown, rightPressed, rightReleased;
    /** Средняя кнопка: пипетка в игре и полная стопка в креативе. */
    public final boolean middlePressed;
    /** Колесо в щелчках; плюс — от себя, то есть вверх по списку. */
    public final float scroll;
    /** Символы, набранные в этом кадре. */
    public final String typed;
    /** Буфер обмена, если в этом кадре нажали Ctrl+V; иначе пусто. */
    public final String paste;
    private final BitSet pressed;
    private final BitSet held;
    private final int first;

    public static final UiInput NONE = builder().at(-1f, -1f).build();

    private UiInput(Builder b) {
        mouseX = b.x;
        mouseY = b.y;
        mouseDown = b.down;
        mousePressed = b.pressed;
        mouseReleased = b.released;
        rightDown = b.rightDown;
        rightPressed = b.rightPressed;
        rightReleased = b.rightReleased;
        middlePressed = b.middlePressed;
        scroll = b.scroll;
        typed = b.typed;
        paste = b.paste;
        pressed = (BitSet) b.keys.clone();
        held = (BitSet) b.held.clone();
        held.or(pressed);   // только что нажатая клавиша и зажата тоже
        first = b.first;
    }

    /** Клавиша нажата в этом кадре. */
    public boolean pressed(int key) {
        return key >= 0 && pressed.get(key);
    }

    /** Клавиша зажата (в том числе нажата в этом кадре). */
    public boolean held(int key) {
        return key >= 0 && held.get(key);
    }

    /** Первая клавиша, нажатая в этом кадре, или −1 — для захвата на экране клавиш. */
    public int firstPressed() {
        return first;
    }

    public boolean ctrl() {
        return held(GLFW_KEY_LEFT_CONTROL) || held(GLFW_KEY_RIGHT_CONTROL);
    }

    public boolean shift() {
        return held(GLFW_KEY_LEFT_SHIFT) || held(GLFW_KEY_RIGHT_SHIFT);
    }

    public boolean alt() {
        return held(GLFW_KEY_LEFT_ALT) || held(GLFW_KEY_RIGHT_ALT);
    }

    public boolean enter() {
        return pressed(GLFW_KEY_ENTER) || pressed(GLFW_KEY_KP_ENTER);
    }

    public boolean escape() {
        return pressed(GLFW_KEY_ESCAPE);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private float x = -1f, y = -1f;
        private boolean down, pressed, released;
        private boolean rightDown, rightPressed, rightReleased, middlePressed;
        private float scroll;
        private String typed = "", paste = "";
        private final BitSet keys = new BitSet();
        private final BitSet held = new BitSet();
        private int first = -1;

        public Builder at(float x, float y) {
            this.x = x;
            this.y = y;
            return this;
        }

        public Builder mouseDown(boolean v) {
            down = v;
            return this;
        }

        public Builder mousePressed(boolean v) {
            pressed = v;
            return this;
        }

        public Builder mouseReleased(boolean v) {
            released = v;
            return this;
        }

        /** Полный клик за один кадр — удобно предпросмотру и тестам. */
        public Builder click() {
            pressed = true;
            released = true;
            return this;
        }

        public Builder rightDown(boolean v) {
            rightDown = v;
            return this;
        }

        public Builder rightPressed(boolean v) {
            rightPressed = v;
            return this;
        }

        public Builder rightReleased(boolean v) {
            rightReleased = v;
            return this;
        }

        /** Полный правый клик за один кадр — удобно предпросмотру и тестам. */
        public Builder rightClick() {
            rightPressed = true;
            rightReleased = true;
            return this;
        }

        public Builder middleClick() {
            middlePressed = true;
            return this;
        }

        public Builder scroll(float v) {
            scroll = v;
            return this;
        }

        public Builder typed(String s) {
            typed = s != null ? s : "";
            return this;
        }

        public Builder paste(String s) {
            paste = s != null ? s : "";
            return this;
        }

        /** Клавиши, нажатые в этом кадре; первая становится {@link #firstPressed()}. */
        public Builder key(int... codes) {
            for (int c : codes) {
                if (c < 0)
                    continue;
                if (first < 0)
                    first = c;
                keys.set(c);
            }
            return this;
        }

        /** Клавиши, зажатые с прошлых кадров. */
        public Builder held(int... codes) {
            for (int c : codes)
                if (c >= 0)
                    held.set(c);
            return this;
        }

        public UiInput build() {
            return new UiInput(this);
        }
    }
}

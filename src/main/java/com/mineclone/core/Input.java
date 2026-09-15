package com.mineclone.core;

import static org.lwjgl.glfw.GLFW.*;

public class Input {
    private final long window;
    private double lastX, lastY, dx, dy;
    private boolean first = true;
    private boolean[] keysPrev = new boolean[GLFW_KEY_LAST + 1];
    private boolean[] keysCur  = new boolean[GLFW_KEY_LAST + 1];
    private boolean[] mousePrev = new boolean[8];
    private boolean[] mouseCur  = new boolean[8];

    private double scrollAccum;
    private double scrollThisFrame;
    private boolean cursorGrabbed = true;
    private final StringBuilder charBuffer = new StringBuilder();
    /** Раскладка, через которую игра спрашивает действия, а не клавиши. */
    private KeyBindings bindings = new KeyBindings();
    /** Можно ли прятать и запирать курсор; автопилот идёт на чужом экране. */
    private boolean grabAllowed = true;

    public Input(long window) {
        this.window = window;
        grabCursor(true);
        if (glfwRawMouseMotionSupported()) {
            glfwSetInputMode(window, GLFW_RAW_MOUSE_MOTION, GLFW_TRUE);
        }
        glfwSetScrollCallback(window, (win, xoff, yoff) -> scrollAccum += yoff);
        glfwSetCharCallback(window, (win, codepoint) -> charBuffer.append((char) codepoint));
    }

    /** Returns and clears all characters typed this frame. */
    public String pollChars() {
        String s = charBuffer.toString();
        charBuffer.setLength(0);
        return s;
    }

    public void update() {
        double[] x = new double[1], y = new double[1];
        glfwGetCursorPos(window, x, y);
        if (first) { lastX = x[0]; lastY = y[0]; first = false; }
        dx = x[0] - lastX;
        dy = y[0] - lastY;
        lastX = x[0]; lastY = y[0];

        scrollThisFrame = scrollAccum;
        scrollAccum = 0;

        System.arraycopy(keysCur, 0, keysPrev, 0, keysCur.length);
        for (int k = 32; k < keysCur.length; k++) {
            keysCur[k] = glfwGetKey(window, k) == GLFW_PRESS;
        }
        System.arraycopy(mouseCur, 0, mousePrev, 0, mouseCur.length);
        for (int b = 0; b < mouseCur.length; b++) {
            mouseCur[b] = glfwGetMouseButton(window, b) == GLFW_PRESS;
        }
    }

    public boolean keyDown(int key) { return keysCur[key]; }
    public boolean keyPressed(int key) { return keysCur[key] && !keysPrev[key]; }
    public boolean keyReleased(int key) { return !keysCur[key] && keysPrev[key]; }

    public void setBindings(KeyBindings bindings) { this.bindings = bindings; }
    public KeyBindings bindings() { return bindings; }
    public boolean down(KeyBindings.Action a) { return keyDown(bindings.key(a)); }
    public boolean pressed(KeyBindings.Action a) { return keyPressed(bindings.key(a)); }
    public boolean released(KeyBindings.Action a) { return keyReleased(bindings.key(a)); }

    /** Первая клавиша, нажатая в этом кадре, или −1. Нужна захвату на экране клавиш. */
    public int firstKeyPressed() {
        for (int k = GLFW_KEY_SPACE; k < keysCur.length; k++)
            if (keyPressed(k))
                return k;
        return -1;
    }

    /** Текст из буфера обмена или пустая строка — для вставки сида. */
    public String clipboard() {
        String s = glfwGetClipboardString(window);
        return s != null ? s : "";
    }
    public boolean mouseDown(int b) { return mouseCur[b]; }
    public boolean mousePressed(int b) { return mouseCur[b] && !mousePrev[b]; }
    public boolean mouseReleased(int b) { return !mouseCur[b] && mousePrev[b]; }
    public double getDx() { return dx; }
    public double getDy() { return dy; }
    public double getScroll() { return scrollThisFrame; }

    public void setGrabAllowed(boolean allowed) {
        grabAllowed = allowed;
        if (!allowed)
            glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
    }

    public void grabCursor(boolean grab) {
        cursorGrabbed = grab;
        glfwSetInputMode(window, GLFW_CURSOR, grab && grabAllowed ? GLFW_CURSOR_DISABLED : GLFW_CURSOR_NORMAL);
        if (grab) first = true; // avoid a big dx/dy jump on re-grab
    }

    public boolean isCursorGrabbed() { return cursorGrabbed; }

    /** Cursor position in window pixels (top-left origin). Useful when cursor is released for menus. */
    public double getCursorX() {
        double[] x = new double[1], y = new double[1];
        glfwGetCursorPos(window, x, y);
        return x[0];
    }

    public double getCursorY() {
        double[] x = new double[1], y = new double[1];
        glfwGetCursorPos(window, x, y);
        return y[0];
    }
}

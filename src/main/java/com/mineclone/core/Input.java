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

    private double scrollAccum;   // written by GLFW callback thread-of-poll
    private double scrollThisFrame;
    private boolean cursorGrabbed = true;

    public Input(long window) {
        this.window = window;
        grabCursor(true);
        if (glfwRawMouseMotionSupported()) {
            glfwSetInputMode(window, GLFW_RAW_MOUSE_MOTION, GLFW_TRUE);
        }
        glfwSetScrollCallback(window, (win, xoff, yoff) -> scrollAccum += yoff);
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
    public boolean mouseDown(int b) { return mouseCur[b]; }
    public boolean mousePressed(int b) { return mouseCur[b] && !mousePrev[b]; }
    public double getDx() { return dx; }
    public double getDy() { return dy; }
    public double getScroll() { return scrollThisFrame; }

    public void grabCursor(boolean grab) {
        cursorGrabbed = grab;
        glfwSetInputMode(window, GLFW_CURSOR, grab ? GLFW_CURSOR_DISABLED : GLFW_CURSOR_NORMAL);
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

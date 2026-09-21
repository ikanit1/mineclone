package com.mineclone.core;

import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;

import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_MULTISAMPLE;
import static org.lwjgl.system.MemoryStack.stackPush;

public class Window {
    private final String title;
    private int width, height;
    private long handle;
    private boolean resized;
    private boolean fullscreen;
    /** 0 — окно, 1 — без рамки, 2 — полноэкранный. */
    private int windowMode;
    /** Куда возвращает F11: безрамочный или полноэкранный, смотря откуда ушли. */
    private int lastFullMode = 2;
    /** Запрошенное разрешение полноэкранного режима; 0 — родное. */
    private int fullW, fullH;
    private boolean vsync = true;
    private int windowedX, windowedY, windowedW, windowedH;

    private final boolean visible;

    public Window(String title, int width, int height) {
        this(title, width, height, true);
    }

    /**
     * @param visible false — окно не показывается и не забирает фокус; рисовать
     *                в него можно. Нужно автопилоту, который идёт на экране игрока.
     */
    public Window(String title, int width, int height, boolean visible) {
        this.title = title;
        this.width = width;
        this.height = height;
        this.visible = visible;
    }

    public void init() {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new IllegalStateException("Unable to init GLFW");

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        boolean compatibility = Boolean.getBoolean("mineclone.gl33");
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, compatibility ? 3 : 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, compatibility ? 3 : 4);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GL_TRUE);
        glfwWindowHint(GLFW_SAMPLES, 4);

        handle = glfwCreateWindow(width, height, title, 0L, 0L);
        if (handle == 0L && !compatibility) {
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
            handle = glfwCreateWindow(width, height, title, 0L, 0L);
        }
        if (handle == 0L) throw new RuntimeException("Failed to create GLFW window");

        glfwSetFramebufferSizeCallback(handle, (w, nw, nh) -> {
            width = nw; height = nh; resized = true;
        });

        try (var stack = stackPush()) {
            IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1);
            glfwGetWindowSize(handle, w, h);
            GLFWVidMode vid = glfwGetVideoMode(glfwGetPrimaryMonitor());
            if (vid != null) {
                glfwSetWindowPos(handle, (vid.width() - w.get(0)) / 2, (vid.height() - h.get(0)) / 2);
            }
        }

        glfwMakeContextCurrent(handle);
        glfwSwapInterval(1);
        if (visible)
            glfwShowWindow(handle);

        GL.createCapabilities();
        if (GL.getCapabilities().GL_ARB_parallel_shader_compile)
            org.lwjgl.opengl.ARBParallelShaderCompile.glMaxShaderCompilerThreadsARB(Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);
        glEnable(GL_MULTISAMPLE);
        glClearColor(0.55f, 0.75f, 0.95f, 1.0f);
    }

    public boolean shouldClose() { return glfwWindowShouldClose(handle); }
    public void update() { glfwSwapBuffers(handle); glfwPollEvents(); }
    public long getHandle() { return handle; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public float getAspect() { return (float) width / Math.max(1, height); }
    public boolean isResized() { return resized; }
    public void setResized(boolean r) { resized = r; }
    public boolean isFullscreen() { return fullscreen; }

    /** 0 — окно, 1 — без рамки во весь экран, 2 — полноэкранный режим. */
    public int getWindowMode() { return windowMode; }

    public void setVSync(boolean enable) {
        vsync = enable;
        glfwSwapInterval(enable ? 1 : 0);
    }

    /**
     * Разрешения, которые умеет основной монитор, без повторов и по возрастанию.
     *
     * <p>Частота кадров здесь намеренно теряется: список нужен экрану
     * настроек, а два одинаковых «1920 × 1080» с разной частотой игрок читает
     * как ошибку меню, а не как выбор.
     */
    public static int[][] videoModes() {
        GLFWVidMode.Buffer modes = glfwGetVideoModes(glfwGetPrimaryMonitor());
        if (modes == null) return new int[0][];
        java.util.LinkedHashSet<Long> seen = new java.util.LinkedHashSet<>();
        for (int i = 0; i < modes.limit(); i++) {
            GLFWVidMode m = modes.get(i);
            if (m.width() < 640 || m.height() < 480) continue;
            seen.add(((long) m.width() << 32) | m.height());
        }
        java.util.ArrayList<Long> sorted = new java.util.ArrayList<>(seen);
        sorted.sort(java.util.Comparator.naturalOrder());
        int[][] out = new int[sorted.size()][2];
        for (int i = 0; i < out.length; i++) {
            long v = sorted.get(i);
            out[i][0] = (int) (v >>> 32);
            out[i][1] = (int) v;
        }
        return out;
    }

    /** Родное разрешение основного монитора, или null, если его не спросить. */
    public static int[] nativeMode() {
        GLFWVidMode vid = glfwGetVideoMode(glfwGetPrimaryMonitor());
        return vid == null ? null : new int[] { vid.width(), vid.height(), vid.refreshRate() };
    }

    public void setFullscreen(boolean wantFullscreen) {
        setWindowMode(wantFullscreen ? 2 : 0, 0, 0);
    }

    /** F11: из окна — в последний полноэкранный режим, обратно — в окно. */
    public void toggleFullscreen() {
        setWindowMode(windowMode == 0 ? (lastFullMode == 0 ? 2 : lastFullMode) : 0, fullW, fullH);
    }

    /**
     * Переключает режим окна.
     *
     * <p>Безрамочный — это обычное окно без рамки, растянутое на монитор, а не
     * эксклюзивный режим: alt-tab из него мгновенный, и второй монитор не
     * гаснет. Полноэкранный отдаёт монитор драйверу и умеет менять его
     * разрешение — там же работает и выбор разрешения в настройках.
     *
     * @param width  запрошенное разрешение полноэкранного режима; 0 — родное
     */
    public void setWindowMode(int mode, int width, int height) {
        long monitor = glfwGetPrimaryMonitor();
        GLFWVidMode vid = glfwGetVideoMode(monitor);
        if (vid == null) return;
        mode = Math.max(0, Math.min(2, mode));
        if (mode == windowMode && (mode != 2 || (width == fullW && height == fullH)))
            return;
        if (windowMode == 0 && mode != 0)
            rememberWindowed();
        fullW = width;
        fullH = height;
        if (mode != 0)
            lastFullMode = mode;
        switch (mode) {
            case 1 -> {
                glfwSetWindowMonitor(handle, 0L, 0, 0, vid.width(), vid.height(), 0);
                glfwSetWindowAttrib(handle, GLFW_DECORATED, GLFW_FALSE);
                try (var stack = stackPush()) {
                    IntBuffer mx = stack.mallocInt(1), my = stack.mallocInt(1);
                    glfwGetMonitorPos(monitor, mx, my);
                    glfwSetWindowPos(handle, mx.get(0), my.get(0));
                }
            }
            case 2 -> {
                glfwSetWindowAttrib(handle, GLFW_DECORATED, GLFW_TRUE);
                int w = width > 0 ? width : vid.width();
                int h = height > 0 ? height : vid.height();
                glfwSetWindowMonitor(handle, monitor, 0, 0, w, h, vid.refreshRate());
            }
            default -> {
                glfwSetWindowMonitor(handle, 0L, windowedX, windowedY, windowedW, windowedH, 0);
                glfwSetWindowAttrib(handle, GLFW_DECORATED, GLFW_TRUE);
            }
        }
        windowMode = mode;
        fullscreen = mode != 0;
        // Смена монитора сбрасывает интервал обмена: без этого вертикальная
        // синхронизация тихо выключалась при каждом входе в полный экран.
        glfwSwapInterval(vsync ? 1 : 0);
    }

    private void rememberWindowed() {
        try (var stack = stackPush()) {
            IntBuffer wx = stack.mallocInt(1), wy = stack.mallocInt(1);
            glfwGetWindowPos(handle, wx, wy);
            windowedX = wx.get(0);
            windowedY = wy.get(0);
        }
        windowedW = width;
        windowedH = height;
    }

    public void destroy() {
        if (handle != 0L) {
            glfwDestroyWindow(handle);
            handle = 0L;
        }
        glfwTerminate();
        var cb = glfwSetErrorCallback(null);
        if (cb != null) cb.free();
    }
}

package com.mineclone.ui;

import java.util.Locale;

/**
 * Настройки: вкладки «Графика», «Управление», «Звук».
 *
 * <p>Вкладки вместо прежнего хаба с тремя подэкранами: переключение между
 * разделами — один щелчок, а не «Готово» и снова вход. Содержимое вкладки
 * прокручивается — на маленьком окне графика целиком не помещается.
 *
 * <p>Изменение применяется сразу ({@link SettingsModel#changed()}), запись в
 * файл — при закрытии экрана ({@link SettingsModel#commit()}).
 */
public final class SettingsScreen implements Screen {

    static final String[] TABS = { "Графика", "Управление", "Звук" };
    static final String[] SHADERS = { "Быстро", "Красиво", "Ультра" };
    static final String[] SHADER_HINTS = {
            "Без теней и пост-эффектов — для слабых видеокарт.",
            "Тени, свечение и лучи света.",
            "Мягкие тени высокого разрешения.",
    };
    static final String[] GUI_SCALES = { "Авто", "1×", "2×", "3×" };

    private final SettingsModel m;
    private int tab;
    private final ScrollState scroll = new ScrollState();

    public SettingsScreen(SettingsModel model) {
        this(model, 0);
    }

    /** Сразу на вкладке: 0 — графика, 1 — управление, 2 — звук. */
    public SettingsScreen(SettingsModel model, int tab) {
        this.m = model;
        this.tab = Math.max(0, Math.min(TABS.length - 1, tab));
    }

    // ---- перевод ползунков: 0..1 ↔ значения игры ----

    public static int radiusAt(float t) {
        return SettingsModel.RADIUS_MIN + Math.round(clamp01(t) * (SettingsModel.RADIUS_MAX - SettingsModel.RADIUS_MIN));
    }

    public static float radiusT(int r) {
        return (r - SettingsModel.RADIUS_MIN) / (float) (SettingsModel.RADIUS_MAX - SettingsModel.RADIUS_MIN);
    }

    public static int fovAt(float t) {
        return SettingsModel.FOV_MIN + Math.round(clamp01(t) * (SettingsModel.FOV_MAX - SettingsModel.FOV_MIN));
    }

    public static float fovT(int fov) {
        return (fov - SettingsModel.FOV_MIN) / (float) (SettingsModel.FOV_MAX - SettingsModel.FOV_MIN);
    }

    /** Правый край ползунка — без ограничения (0). */
    public static int fpsAt(float t) {
        int v = SettingsModel.FPS_MIN + Math.round(clamp01(t) * (SettingsModel.FPS_UNLIMITED - SettingsModel.FPS_MIN));
        return v >= SettingsModel.FPS_UNLIMITED - 5 ? 0 : v;
    }

    public static float fpsT(int fps) {
        if (fps <= 0)
            return 1f;
        return (fps - SettingsModel.FPS_MIN) / (float) (SettingsModel.FPS_UNLIMITED - SettingsModel.FPS_MIN);
    }

    public static String fpsLabel(int fps) {
        return fps <= 0 ? "Без ограничения" : fps + " к/с";
    }

    /** Чувствительность 0.5..2.0 шагом 0.05 — иначе ползунок не попадает в круглые значения. */
    public static float sensitivityAt(float t) {
        float v = 0.5f + clamp01(t) * 1.5f;
        return Math.round(v * 20f) / 20f;
    }

    public static float sensitivityT(float s) {
        return (s - 0.5f) / 1.5f;
    }

    // ---- экран ----

    @Override
    public MenuAction draw(MenuTheme t) {
        int sw = t.width(), sh = t.height();
        t.dim(0.30f);
        float pw = Math.min(700f, sw - 48f), ph = Math.min(620f, sh - 40f);
        float px = (sw - pw) / 2f, py = (sh - ph) / 2f;
        t.panel(px, py, pw, ph);
        float inner = px + 24f, iw = pw - 48f;
        float y = t.header("Настройки", inner, py + 14f, iw);

        // Вкладки
        float tabY = y + 12f, tabW = (iw - 8f) / TABS.length;
        for (int i = 0; i < TABS.length; i++) {
            float tx = inner + i * (tabW + 4f);
            if (t.button("settings.tab" + i, tx, tabY, tabW, 40f, TABS[i],
                    i == tab ? MenuTheme.Style.PRIMARY : MenuTheme.Style.QUIET, true) && i != tab) {
                tab = i;
                scroll.scrollBy(-1e6f, 1f, 1f);
                scroll.snap();
            }
        }

        float footH = 64f;
        float areaY = tabY + 56f, areaH = py + ph - footH - areaY;
        float contentH = contentHeight();
        t.beginClip(inner, areaY, iw, areaH);
        UiInput in = t.input();
        t.setInputEnabled(t.inputEnabled() && in.mouseY >= areaY && in.mouseY <= areaY + areaH);
        float cy = areaY - scroll.offset();
        float rowW = contentH > areaH ? iw - 16f : iw;
        MenuAction result = MenuAction.NONE;
        switch (tab) {
            case 0 -> drawGraphics(t, inner, cy, rowW);
            case 1 -> result = drawControls(t, inner, cy, rowW);
            default -> drawAudio(t, inner, cy, rowW);
        }
        t.setInputEnabled(true);
        t.endClip();
        t.scrollArea("settings.scroll", inner, areaY, iw, areaH, scroll, contentH);

        float bw = Math.min(260f, iw);
        if (t.button("settings.done", px + (pw - bw) / 2f, py + ph - footH + 10f, bw, 44f, "Готово",
                MenuTheme.Style.PRIMARY, true))
            result = MenuAction.back();
        return result;
    }

    private static final float ROW = MenuTheme.ROW_H + 10f;

    private float contentHeight() {
        return switch (tab) {
            case 0 -> ROW * 4 + (ROW + 30f) * 2 + ROW * 3;
            case 1 -> ROW * 3;
            default -> ROW * 3;
        };
    }

    private void drawGraphics(MenuTheme t, float x, float y, float w) {
        float h = MenuTheme.ROW_H;
        float nt = t.slider("set.radius", x, y, w, h, "Дальность прорисовки",
                MenuText.count(m.renderRadius, "чанк", "чанка", "чанков"), radiusT(m.renderRadius));
        set(radiusAt(nt) != m.renderRadius, () -> m.renderRadius = radiusAt(nt));
        y += ROW;
        float ft = t.slider("set.fov", x, y, w, h, "Поле зрения", m.fov + "°", fovT(m.fov));
        set(fovAt(ft) != m.fov, () -> m.fov = fovAt(ft));
        y += ROW;
        float bt = t.slider("set.bright", x, y, w, h, "Яркость", Math.round(m.brightness * 100f) + " %",
                m.brightness / 2f);
        float nb = Math.round(bt * 40f) / 20f;   // шаг 5 %
        set(Math.abs(nb - m.brightness) > 1e-4f, () -> m.brightness = nb);
        y += ROW;
        float pt = t.slider("set.fps", x, y, w, h, "Ограничение кадров", fpsLabel(m.maxFps), fpsT(m.maxFps));
        set(fpsAt(pt) != m.maxFps, () -> m.maxFps = fpsAt(pt));
        y += ROW;

        float half = w * 0.34f;
        t.text("Шейдеры", x + 14f, t.baseline(t.font(), y, h), MenuTheme.TEXT, 1f);
        int q = t.segmented("set.shaders", x + half, y, w - half, h, SHADERS, m.shaderQuality);
        set(q != m.shaderQuality, () -> m.shaderQuality = q);
        t.smallText(SHADER_HINTS[Math.max(0, Math.min(2, m.shaderQuality))], x + half + 4f, y + h + 18f,
                MenuTheme.TEXT_DIM, 1f);
        y += ROW + 30f;
        t.text("Интерфейс", x + 14f, t.baseline(t.font(), y, h), MenuTheme.TEXT, 1f);
        int g = t.segmented("set.gui", x + half, y, w - half, h, GUI_SCALES, m.guiScale);
        set(g != m.guiScale, () -> m.guiScale = g);
        t.smallText("Масштаб; «Авто» подбирает его по высоте окна.", x + half + 4f, y + h + 18f,
                MenuTheme.TEXT_DIM, 1f);
        y += ROW + 30f;

        boolean v = t.toggle("set.vsync", x, y, w, h, "Вертикальная синхронизация", m.vsync);
        set(v != m.vsync, () -> m.vsync = v);
        y += ROW;
        boolean f = t.toggle("set.full", x, y, w, h, "Полный экран", m.fullscreen);
        set(f != m.fullscreen, () -> m.fullscreen = f);
        y += ROW;
        boolean b = t.toggle("set.bob", x, y, w, h, "Покачивание камеры при ходьбе", m.viewBobbing);
        set(b != m.viewBobbing, () -> m.viewBobbing = b);
    }

    private MenuAction drawControls(MenuTheme t, float x, float y, float w) {
        float h = MenuTheme.ROW_H;
        float st = t.slider("set.sens", x, y, w, h, "Чувствительность мыши",
                String.format(Locale.ROOT, "%.2f×", m.sensitivity).replace('.', ','), sensitivityT(m.sensitivity));
        float ns = sensitivityAt(st);
        set(Math.abs(ns - m.sensitivity) > 1e-4f, () -> m.sensitivity = ns);
        y += ROW;
        boolean inv = t.toggle("set.invert", x, y, w, h, "Инверсия мыши по вертикали", m.invertY);
        set(inv != m.invertY, () -> m.invertY = inv);
        y += ROW;
        int conflicts = m.keys.conflicts().size();
        String label = conflicts > 0 ? "Назначение клавиш — есть конфликты" : "Назначение клавиш…";
        if (t.button("set.keys", x, y, w, h, label))
            return MenuAction.push(new KeybindScreen(m));
        return MenuAction.NONE;
    }

    private void drawAudio(MenuTheme t, float x, float y, float w) {
        float h = MenuTheme.ROW_H;
        float a = t.slider("set.master", x, y, w, h, "Общая громкость", percent(m.masterVolume), m.masterVolume);
        float na = Math.round(a * 100f) / 100f;
        set(Math.abs(na - m.masterVolume) > 1e-4f, () -> m.masterVolume = na);
        y += ROW;
        float mu = t.slider("set.music", x, y, w, h, "Музыка", percent(m.musicVolume), m.musicVolume);
        float nm = Math.round(mu * 100f) / 100f;
        set(Math.abs(nm - m.musicVolume) > 1e-4f, () -> m.musicVolume = nm);
        y += ROW;
        float e = t.slider("set.effects", x, y, w, h, "Звуки", percent(m.effectsVolume), m.effectsVolume);
        float ne = Math.round(e * 100f) / 100f;
        set(Math.abs(ne - m.effectsVolume) > 1e-4f, () -> m.effectsVolume = ne);
    }

    private void set(boolean differs, Runnable apply) {
        if (!differs)
            return;
        apply.run();
        m.changed();
    }

    private static String percent(float v) {
        return Math.round(v * 100f) + " %";
    }

    @Override
    public void closed() {
        m.commit();
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}

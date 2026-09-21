package com.mineclone.ui;

import java.util.Locale;

/**
 * Настройки: «Графика», «Экран», «Игра», «Управление», «Звук».
 *
 * <p>Раньше вся графика была одним переключателем «Шейдеры» на три значения:
 * либо всё, либо ничего. Слабая видеокарта тянет мир с тенями, но не тянет
 * объёмный туман; у кого-то не хватает кадров именно на осадках. Поэтому
 * пресет остался (он просто выставляет остальные ручки и уходит), а рядом
 * встали сами ручки — и подпись пресета честно меняется на «Свои», как только
 * тронули любую из них.
 *
 * <p>Вкладки вместо прежнего хаба с подэкранами: переключение между разделами —
 * один щелчок. Содержимое вкладки прокручивается — на маленьком окне графика
 * целиком не помещается.
 *
 * <p>Изменение применяется сразу ({@link SettingsModel#changed()}), запись в
 * файл — при закрытии экрана ({@link SettingsModel#commit()}).
 */
public final class SettingsScreen implements Screen {

    static final String[] TABS = { "Графика", "Экран", "Игра", "Управление", "Звук" };
    static final String[] PRESETS = { "Быстро", "Красиво", "Ультра", "Свои" };
    static final String[] PRESET_HINTS = {
            "Без теней и пост-эффектов — для слабых видеокарт.",
            "Тени, свечение и лучи света.",
            "Мягкие тени, отражения на воде, полная детализация.",
            "Ручки ниже выставлены вручную.",
    };
    static final String[] SHADOWS = { "Выкл", "Низкие", "Средние", "Высокие" };
    static final String[] PARTICLES = { "Выкл", "Мало", "Средне", "Максимум" };
    static final String[] WEATHER = { "Выкл", "Реже", "Полные" };
    static final String[] GUI_SCALES = { "Авто", "1×", "2×", "3×" };
    static final String[] WINDOW_MODES = { "Окно", "Без рамки", "Полный экран" };
    static final String[] AA = { "Выкл", "2×", "4×", "8×" };
    static final int[] AA_VALUES = { 0, 2, 4, 8 };
    static final String[] FPS_DISPLAY = { "Выкл", "Кадры", "Кадры и рывки" };

    private final SettingsModel m;
    private int tab;
    private final ScrollState scroll = new ScrollState();

    public SettingsScreen(SettingsModel model) {
        this(model, 0);
    }

    /** Сразу на вкладке: 0 — графика, 1 — экран, 2 — игра, 3 — управление, 4 — звук. */
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

    /** Масштаб рендера шагом 5 % — иначе ползунок не попадает в круглые числа. */
    public static int scaleAt(float t) {
        int span = SettingsModel.SCALE_MAX - SettingsModel.SCALE_MIN;
        int v = SettingsModel.SCALE_MIN + Math.round(clamp01(t) * span);
        return Math.round(v / 5f) * 5;
    }

    public static float scaleT(int scale) {
        return (scale - SettingsModel.SCALE_MIN)
                / (float) (SettingsModel.SCALE_MAX - SettingsModel.SCALE_MIN);
    }

    /** Дальность сущностей шагом 5 %. */
    public static int entityAt(float t) {
        int span = SettingsModel.ENTITY_MAX - SettingsModel.ENTITY_MIN;
        int v = SettingsModel.ENTITY_MIN + Math.round(clamp01(t) * span);
        return Math.round(v / 5f) * 5;
    }

    public static float entityT(int percent) {
        return (percent - SettingsModel.ENTITY_MIN)
                / (float) (SettingsModel.ENTITY_MAX - SettingsModel.ENTITY_MIN);
    }

    /** Чувствительность 0.5..2.0 шагом 0.05 — иначе ползунок не попадает в круглые значения. */
    public static float sensitivityAt(float t) {
        float v = 0.5f + clamp01(t) * 1.5f;
        return Math.round(v * 20f) / 20f;
    }

    public static float sensitivityT(float s) {
        return (s - 0.5f) / 1.5f;
    }

    /** Индекс в списке сглаживания по числу выборок; неизвестное — «Выкл». */
    public static int aaIndex(int samples) {
        for (int i = 0; i < AA_VALUES.length; i++)
            if (AA_VALUES[i] == samples)
                return i;
        return 0;
    }

    // ---- экран ----

    @Override
    public MenuAction draw(MenuTheme t) {
        int sw = t.width(), sh = t.height();
        t.dim(0.30f);
        float pw = Math.min(860f, sw - 48f), ph = Math.min(650f, sh - 40f);
        float px = (sw - pw) / 2f, py = (sh - ph) / 2f;
        t.panel(px, py, pw, ph);
        float inner = px + 24f, iw = pw - 48f;
        float y = t.header("Настройки", inner, py + 14f, iw);

        // Вкладки. Ширина не поровну, а по длине надписи: «Управление» вдвое
        // длиннее «Игры», и на равных долях оно обрезалось многоточием, пока
        // рядом пустовала половина соседней кнопки.
        float tabY = y + 12f;
        float gap = 4f, free = iw - gap * (TABS.length - 1), sum = 0f;
        for (String name : TABS)
            sum += t.textWidth(name) + 28f;
        float tx = inner;
        for (int i = 0; i < TABS.length; i++) {
            float tabW = free * (t.textWidth(TABS[i]) + 28f) / sum;
            if (t.button("settings.tab" + i, tx, tabY, tabW, 40f, TABS[i],
                    i == tab ? MenuTheme.Style.PRIMARY : MenuTheme.Style.QUIET, true) && i != tab) {
                tab = i;
                scroll.scrollBy(-1e6f, 1f, 1f);
                scroll.snap();
            }
            tx += tabW + gap;
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
            case 1 -> drawScreen(t, inner, cy, rowW);
            case 2 -> drawGameplay(t, inner, cy, rowW);
            case 3 -> result = drawControls(t, inner, cy, rowW);
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
    /** Заголовок раздела: подпись и воздух над ней. */
    private static final float SECTION = 40f;
    /** Строка пояснения под элементом. */
    private static final float HINT = 30f;

    private float contentHeight() {
        return switch (tab) {
            //   пресет+подсказка, «мир» (2), «свет и тени» (4),
            //   «детали» (3), «оптимизация» (2 + подсказка)
            case 0 -> ROW + HINT + SECTION + ROW * 2 + SECTION + ROW * 4
                    + SECTION + ROW * 3 + SECTION + ROW * 2 + HINT;
            case 1 -> SECTION + ROW * 3 + SECTION + ROW * 2 + HINT + SECTION + ROW * 4;
            case 2 -> SECTION + ROW * 4 + SECTION + ROW * 2 + HINT;
            case 3 -> ROW * 3;
            default -> ROW * 3;
        };
    }

    // ---- вкладка «Графика» ----

    private void drawGraphics(MenuTheme t, float x, float y, float w) {
        float h = MenuTheme.ROW_H;
        float half = w * 0.40f;

        int preset = t.segmentedSmall("set.preset", x + half, y, w - half, h, PRESETS, m.shaderQuality);
        t.text("Качество", x + 14f, t.baseline(t.font(), y, h), MenuTheme.TEXT, 1f);
        if (preset != m.shaderQuality && preset != SettingsModel.PRESET_CUSTOM) {
            m.applyPreset(preset);
            m.changed();
        }
        t.smallText(PRESET_HINTS[Math.max(0, Math.min(3, m.shaderQuality))], x + 14f, y + h + 18f,
                MenuTheme.TEXT_DIM, 1f);
        y += ROW + HINT;

        y = section(t, "Мир", x, y, w);
        float nt = t.slider("set.radius", x, y, w, h, "Дальность прорисовки",
                MenuText.count(m.renderRadius, "чанк", "чанка", "чанков"), radiusT(m.renderRadius));
        set(radiusAt(nt) != m.renderRadius, () -> m.renderRadius = radiusAt(nt));
        y += ROW;
        float et = t.slider("set.entity", x, y, w, h, "Дальность существ",
                m.entityDistance + " %", entityT(m.entityDistance));
        setQuality(entityAt(et) != m.entityDistance, () -> m.entityDistance = entityAt(et));
        y += ROW;

        y = section(t, "Свет и тени", x, y, w);
        t.text("Тени", x + 14f, t.baseline(t.font(), y, h), MenuTheme.TEXT, 1f);
        int sh = t.segmentedSmall("set.shadows", x + half, y, w - half, h, SHADOWS, m.shadows);
        setQuality(sh != m.shadows, () -> m.shadows = sh);
        y += ROW;
        boolean bl = t.toggle("set.bloom", x, y, w, h, "Свечение (bloom)", m.bloom);
        setQuality(bl != m.bloom, () -> m.bloom = bl);
        y += ROW;
        boolean gr = t.toggle("set.rays", x, y, w, h, "Лучи света", m.godRays);
        setQuality(gr != m.godRays, () -> m.godRays = gr);
        y += ROW;
        boolean vf = t.toggle("set.fog", x, y, w, h, "Объёмный туман", m.volumetricFog);
        setQuality(vf != m.volumetricFog, () -> m.volumetricFog = vf);
        y += ROW;

        y = section(t, "Детали", x, y, w);
        t.text("Частицы", x + 14f, t.baseline(t.font(), y, h), MenuTheme.TEXT, 1f);
        int pa = t.segmentedSmall("set.particles", x + half, y, w - half, h, PARTICLES, m.particles);
        setQuality(pa != m.particles, () -> m.particles = pa);
        y += ROW;
        t.text("Осадки", x + 14f, t.baseline(t.font(), y, h), MenuTheme.TEXT, 1f);
        int we = t.segmentedSmall("set.weather", x + half, y, w - half, h, WEATHER, m.weather);
        setQuality(we != m.weather, () -> m.weather = we);
        y += ROW;
        boolean wr = t.toggle("set.ssr", x, y, w, h, "Отражения на воде", m.waterReflections);
        setQuality(wr != m.waterReflections, () -> m.waterReflections = wr);
        y += ROW;

        y = section(t, "Оптимизация", x, y, w);
        boolean lod = t.toggle("set.lod", x, y, w, h, "Упрощать дальние чанки", m.chunkLod);
        setQuality(lod != m.chunkLod, () -> m.chunkLod = lod);
        y += ROW;
        boolean oc = t.toggle("set.occlusion", x, y, w, h, "Не рисовать закрытое", m.occlusion);
        set(oc != m.occlusion, () -> m.occlusion = oc);
        t.smallText("Чанк за горой не рисуется. Выключите, если картинка мигает.",
                x + 14f, y + h + 18f, MenuTheme.TEXT_DIM, 1f);
    }

    // ---- вкладка «Экран» ----

    private void drawScreen(MenuTheme t, float x, float y, float w) {
        float h = MenuTheme.ROW_H;
        float half = w * 0.40f;

        y = section(t, "Окно", x, y, w);
        t.text("Режим", x + 14f, t.baseline(t.font(), y, h), MenuTheme.TEXT, 1f);
        int wm = t.segmentedSmall("set.winmode", x + half, y, w - half, h, WINDOW_MODES, m.windowMode);
        set(wm != m.windowMode, () -> {
            m.windowMode = wm;
            m.fullscreen = wm != 0;
        });
        y += ROW;
        // Разрешение имеет смысл только в полноэкранном: в окне его задаёт
        // сам размер окна, без рамки — монитор.
        boolean pickable = m.windowMode == 2 && m.videoModes.length > 0;
        stepper(t, "set.res", x, y, w, h, "Разрешение", resolutionLabel(), pickable, step -> {
            int n = m.videoModes.length;
            int cur = m.resolutionIndex < 0 ? n : m.resolutionIndex;   // n — «родное»
            int next = Math.floorMod(cur + step, n + 1);
            m.resolutionIndex = next == n ? -1 : next;
            m.changed();
        });
        y += ROW;
        boolean vs = t.toggle("set.vsync", x, y, w, h, "Вертикальная синхронизация", m.vsync);
        set(vs != m.vsync, () -> m.vsync = vs);
        y += ROW;

        y = section(t, "Кадр", x, y, w);
        float pt = t.slider("set.fps", x, y, w, h, "Ограничение кадров", fpsLabel(m.maxFps), fpsT(m.maxFps));
        set(fpsAt(pt) != m.maxFps, () -> m.maxFps = fpsAt(pt));
        y += ROW;
        float st = t.slider("set.scale", x, y, w, h, "Масштаб рендера",
                m.renderScale + " %", scaleT(m.renderScale));
        set(scaleAt(st) != m.renderScale, () -> m.renderScale = scaleAt(st));
        t.smallText("Сцена рисуется мельче и растягивается. Интерфейс остаётся чётким.",
                x + 14f, y + h + 18f, MenuTheme.TEXT_DIM, 1f);
        y += ROW + HINT;

        y = section(t, "Изображение", x, y, w);
        t.text("Сглаживание", x + 14f, t.baseline(t.font(), y, h), MenuTheme.TEXT, 1f);
        int aa = t.segmentedSmall("set.aa", x + half, y, w - half, h, AA, aaIndex(m.antialiasing));
        setQuality(AA_VALUES[aa] != m.antialiasing, () -> m.antialiasing = AA_VALUES[aa]);
        y += ROW;
        float ft = t.slider("set.fov", x, y, w, h, "Поле зрения", m.fov + "°", fovT(m.fov));
        set(fovAt(ft) != m.fov, () -> m.fov = fovAt(ft));
        y += ROW;
        float bt = t.slider("set.bright", x, y, w, h, "Яркость", Math.round(m.brightness * 100f) + " %",
                m.brightness / 2f);
        float nb = Math.round(bt * 40f) / 20f;   // шаг 5 %
        set(Math.abs(nb - m.brightness) > 1e-4f, () -> m.brightness = nb);
        y += ROW;
        t.text("Интерфейс", x + 14f, t.baseline(t.font(), y, h), MenuTheme.TEXT, 1f);
        int g = t.segmentedSmall("set.gui", x + half, y, w - half, h, GUI_SCALES, m.guiScale);
        set(g != m.guiScale, () -> m.guiScale = g);
    }

    private String resolutionLabel() {
        if (m.windowMode != 2)
            return "по окну";
        if (m.resolutionIndex < 0 || m.resolutionIndex >= m.videoModes.length)
            return "родное";
        int[] mode = m.videoModes[m.resolutionIndex];
        return mode[0] + " × " + mode[1];
    }

    // ---- вкладка «Игра» ----

    private void drawGameplay(MenuTheme t, float x, float y, float w) {
        float h = MenuTheme.ROW_H;
        float half = w * 0.40f;

        y = section(t, "Камера и экран", x, y, w);
        boolean bob = t.toggle("set.bob", x, y, w, h, "Покачивание камеры при ходьбе", m.viewBobbing);
        set(bob != m.viewBobbing, () -> m.viewBobbing = bob);
        y += ROW;
        boolean shake = t.toggle("set.shake", x, y, w, h, "Тряска камеры от урона", m.cameraShake);
        set(shake != m.cameraShake, () -> m.cameraShake = shake);
        y += ROW;
        boolean fx = t.toggle("set.fx", x, y, w, h, "Эффекты состояний на экране", m.screenEffects);
        set(fx != m.screenEffects, () -> m.screenEffects = fx);
        y += ROW;
        t.text("Счётчик кадров", x + 14f, t.baseline(t.font(), y, h), MenuTheme.TEXT, 1f);
        int fd = t.segmentedSmall("set.fpsdisp", x + half, y, w - half, h, FPS_DISPLAY, m.fpsDisplay);
        set(fd != m.fpsDisplay, () -> m.fpsDisplay = fd);
        y += ROW;

        y = section(t, "Подсказки", x, y, w);
        boolean hints = t.toggle("set.hints", x, y, w, h, "Подсказки у прицела", m.contextHints);
        set(hints != m.contextHints, () -> m.contextHints = hints);
        y += ROW;
        boolean adv = t.toggle("set.adv", x, y, w, h, "Подробные подсказки предметов", m.advancedTooltips);
        set(adv != m.advancedTooltips, () -> m.advancedTooltips = adv);
        t.smallText("Id предмета, числа прочности и теги. То же делает F3+H.",
                x + 14f, y + h + 18f, MenuTheme.TEXT_DIM, 1f);
    }

    // ---- вкладка «Управление» ----

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

    // ---- вкладка «Звук» ----

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

    // ---- мелочи раскладки ----

    /** Заголовок раздела; возвращает y следующей строки. */
    private float section(MenuTheme t, String title, float x, float y, float w) {
        t.smallText(title.toUpperCase(Locale.ROOT), x + 14f, y + 22f, MenuTheme.ACCENT, 0.85f);
        t.quad(x + 14f, y + 30f, w - 28f, 1f, 1f, 1f, 1f, 0.08f);
        return y + SECTION;
    }

    /**
     * Строка «подпись — ‹ значение ›».
     *
     * <p>Для списка, который нельзя разложить в ряд кнопок: разрешений у
     * монитора бывает под тридцать, и сегментами они не помещаются никуда.
     */
    private void stepper(MenuTheme t, String id, float x, float y, float w, float h,
            String label, String value, boolean enabled, java.util.function.IntConsumer step) {
        t.quad(x, y, w, h, 0.12f, 0.14f, 0.18f, enabled ? 0.55f : 0.30f);
        t.quad(x, y, w, 1.5f, 1f, 1f, 1f, 0.10f);
        t.quad(x, y + h - 1.5f, w, 1.5f, 0f, 0f, 0f, 0.40f);
        float bw = 34f;
        float rightX = x + w - bw - 10f, leftX = rightX - bw - 4f;
        float[] valueColor = enabled ? MenuTheme.ACCENT : MenuTheme.TEXT_DIM;
        t.text(label, x + 14f, t.baseline(t.font(), y, h), enabled ? MenuTheme.TEXT : MenuTheme.TEXT_DIM, 1f);
        t.textRight(value, leftX - 12f, t.baseline(t.font(), y, h), valueColor, 1f);
        boolean was = t.inputEnabled();
        t.setInputEnabled(was && enabled);
        // Стрелки обычные, а не типографские: в пиксельном шрифте игры
        // «‹» и «›» нет, и кнопка выходила пустой.
        if (t.button(id + ".prev", leftX, y + 4f, bw, h - 8f, "<", MenuTheme.Style.QUIET, true))
            step.accept(-1);
        if (t.button(id + ".next", rightX, y + 4f, bw, h - 8f, ">", MenuTheme.Style.QUIET, true))
            step.accept(1);
        t.setInputEnabled(was);
    }

    private void set(boolean differs, Runnable apply) {
        if (!differs)
            return;
        apply.run();
        m.changed();
    }

    /** То же, но правка руками сбивает пресет на «Свои». */
    private void setQuality(boolean differs, Runnable apply) {
        if (!differs)
            return;
        apply.run();
        m.custom();
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

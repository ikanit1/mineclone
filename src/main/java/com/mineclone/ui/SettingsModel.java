package com.mineclone.ui;

import com.mineclone.core.KeyBindings;
import com.mineclone.save.Options;

/**
 * Настройки, которые правят экраны меню.
 *
 * <p>Экран меняет поля и зовёт {@link #changed()} — игра применяет изменение
 * сразу: громкость слышна на ползунке, полный экран включается по щелчку. При
 * закрытии экрана настроек зовётся {@link #commit()} — игра пишет options.dat.
 * Писать файл на каждый сдвиг ползунка незачем.
 *
 * <p>Модель плоская нарочно: экран трогает поля напрямую, а перекладывание в
 * {@link Options} и обратно живёт в одном месте — {@link #toVideo()} и
 * соседях. Пока настройка была одна («Шейдеры»), она и жила одним числом;
 * теперь их два десятка, и пресет — это просто тот, кто выставляет остальные.
 */
public final class SettingsModel {

    public interface Listener {
        void changed(SettingsModel m);

        void committed(SettingsModel m);
    }

    public static final int RADIUS_MIN = 2, RADIUS_MAX = 16;
    public static final int FOV_MIN = 50, FOV_MAX = 120;
    public static final int FPS_MIN = 30, FPS_UNLIMITED = 260;
    /** Ниже половины сцена превращается в кашу, выше окна — уже суперсэмплинг. */
    public static final int SCALE_MIN = 50, SCALE_MAX = 100;
    /** Дальность сущностей в процентах от дальности прорисовки. */
    public static final int ENTITY_MIN = 25, ENTITY_MAX = 100;

    /** Номер пресета «Свои настройки» — его выставляет любая ручная правка. */
    public static final int PRESET_CUSTOM = 3;

    public int renderRadius = 6;
    public int fov = 75;
    /** 0..2 — 0 %..200 %. */
    public float brightness = 1f;
    /** 0 — без ограничения. */
    public int maxFps = 0;
    /** 0 — авто, 1..3 — множитель. */
    public int guiScale = 0;
    /** 0 — быстро, 1 — красиво, 2 — ультра, 3 — свои настройки. */
    public int shaderQuality = 1;
    public boolean vsync = true;
    public boolean fullscreen = false;
    public boolean viewBobbing = true;
    public float sensitivity = 1f;
    public boolean invertY = false;
    public float masterVolume = 1f;
    public float musicVolume = 1f;
    public float effectsVolume = 1f;

    // ---- экран ----
    /** 0 — окно, 1 — без рамки, 2 — полный экран. */
    public int windowMode = 0;
    /** Индекс видеорежима для полноэкранного; −1 — родной режим монитора. */
    public int resolutionIndex = -1;
    /** Процент от размера окна, в котором рисуется сцена. */
    public int renderScale = 100;
    /** Сглаживание сцены: 0, 2, 4 или 8. */
    public int antialiasing = 4;

    // ---- графика ----
    /** 0 — выкл, 1 — низкие, 2 — средние, 3 — высокие. */
    public int shadows = 1;
    public boolean bloom = true;
    public boolean godRays = true;
    public boolean volumetricFog = true;
    public boolean waterReflections = false;
    /** 0 — выкл, 1 — мало, 2 — средне, 3 — максимум. */
    public int particles = 3;
    /** 0 — выкл, 1 — редкие, 2 — полные. */
    public int weather = 2;
    /** Дальность мобов и предметов, % от дальности прорисовки. */
    public int entityDistance = 100;
    public boolean occlusion = true;
    public boolean chunkLod = true;

    // ---- игра ----
    public boolean cameraShake = true;
    public boolean screenEffects = true;
    public boolean contextHints = true;
    /** 0 — ничего, 1 — к/с, 2 — к/с и худший кадр. */
    public int fpsDisplay = 0;
    public boolean advancedTooltips = false;

    /**
     * Разрешения монитора для вкладки «Экран». Кладёт игра — экран о GLFW
     * ничего не знает и поднимается в снимках и тестах без окна вовсе.
     */
    public int[][] videoModes = new int[0][];

    /** Та же раскладка, что читает Input: назначенная клавиша работает сразу. */
    public final KeyBindings keys;

    private Listener listener;

    public SettingsModel(KeyBindings keys) {
        this.keys = keys != null ? keys : new KeyBindings();
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void changed() {
        if (listener != null)
            listener.changed(this);
    }

    public void commit() {
        if (listener != null)
            listener.committed(this);
    }

    // ---- пресеты ----

    /**
     * Ставит группу настроек качества по пресету.
     *
     * <p>Пресет не «режим», а заготовка: он один раз записывает ручки и
     * уходит. Дальше игрок крутит их по одной, и экран показывает «Свои».
     * Так ползунок «Шейдеры» перестал быть единственной ручкой, но не
     * перестал работать для тех, кому разбираться не хочется.
     */
    public void applyPreset(int preset) {
        shaderQuality = preset;
        switch (preset) {
            case 0 -> {                     // Быстро
                shadows = 0;
                bloom = false;
                godRays = false;
                volumetricFog = false;
                waterReflections = false;
                particles = 1;
                weather = 1;
                entityDistance = 60;
                chunkLod = true;
                antialiasing = 0;
            }
            case 1 -> {                     // Красиво
                shadows = 1;
                bloom = true;
                godRays = true;
                volumetricFog = true;
                waterReflections = false;
                particles = 3;
                weather = 2;
                entityDistance = 100;
                chunkLod = true;
                antialiasing = 4;
            }
            case 2 -> {                     // Ультра
                shadows = 3;
                bloom = true;
                godRays = true;
                volumetricFog = true;
                waterReflections = true;
                particles = 3;
                weather = 2;
                entityDistance = 100;
                chunkLod = false;
                antialiasing = 4;
            }
            default -> shaderQuality = PRESET_CUSTOM;
        }
    }

    /** Ручная правка качества: пресет становится «Свои». */
    public void custom() {
        shaderQuality = PRESET_CUSTOM;
    }

    // ---- перекладка в Options и обратно ----

    public Options.Video toVideo() {
        return new Options.Video(windowMode, resolutionIndex, renderScale, antialiasing);
    }

    public Options.Graphics toGraphics() {
        return new Options.Graphics(shadows, bloom, godRays, volumetricFog, waterReflections,
                particles, weather, entityDistance, occlusion, chunkLod);
    }

    public Options.Gameplay toGameplay() {
        return new Options.Gameplay(cameraShake, screenEffects, contextHints, fpsDisplay);
    }

    public void load(Options.Video v) {
        windowMode = v.windowMode();
        resolutionIndex = v.resolutionIndex();
        renderScale = v.renderScale();
        antialiasing = v.antialiasing();
    }

    public void load(Options.Graphics g) {
        shadows = g.shadows();
        bloom = g.bloom();
        godRays = g.godRays();
        volumetricFog = g.volumetricFog();
        waterReflections = g.waterReflections();
        particles = g.particles();
        weather = g.weather();
        entityDistance = g.entityDistance();
        occlusion = g.occlusion();
        chunkLod = g.chunkLod();
    }

    public void load(Options.Gameplay g) {
        cameraShake = g.cameraShake();
        screenEffects = g.screenEffects();
        contextHints = g.contextHints();
        fpsDisplay = g.fpsDisplay();
    }
}

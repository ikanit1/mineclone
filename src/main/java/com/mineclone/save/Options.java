package com.mineclone.save;

import com.mineclone.core.KeyBindings;

/** Global settings; sibling of saves/ (not per-world). Mirrors options.dat. */
public final class Options {
    public final int renderRadius;
    public final int fovDegrees;
    public final float brightness;
    public final float masterVolume;
    public final int maxFps;             // 0 = unlimited
    public final boolean vsync;
    public final boolean fullscreen;
    public final boolean viewBobbing;
    public final float mouseSensitivity; // multiplier; 1.0 = default (0.0025 rad/px)
    public final boolean invertMouseY;
    public final float musicVolume;
    public final float effectsVolume;
    /** 0=Auto, 1=Small (1×), 2=Normal (2×), 3=Large (3×). */
    public final int guiScale;
    /** 0=Fast (без теней и пост-эффектов), 1=Fancy, 2=Ultra, 3=свои настройки. */
    public final int shaderQuality;
    /** Раскладка клавиш; копия — изменения на экране не должны течь в сохранённый объект. */
    public final KeyBindings keys;

    // ---- привычки инвентаря (v6) ----
    // Настройки окон, а не графики: они живут ровно столько же, сколько и
    // раскладка клавиш, и восстанавливаться должны так же — при запуске.
    /** Показывать ли в подсказке id, числа прочности и теги. */
    public final boolean advancedTooltips;
    /** Была ли книга рецептов открыта на выходе. */
    public final boolean recipeBookOpen;
    /** Фильтр «только собираемое» в книге рецептов. */
    public final boolean recipeBookCraftable;
    /** Выбранная вкладка книги рецептов; {@code "all"} — все. */
    public final String recipeBookCategory;
    /** Способ сортировки инвентаря: 0 — по категории. */
    public final int sortMode;

    // ---- v7: экран, графика, игра ----
    // Три записи вместо двух десятков полей подряд: конструктор Options и так
    // принимает четырнадцать аргументов, и ещё полтора десятка в тот же ряд
    // превратили бы любой вызов в головоломку «какой булев где».

    /**
     * Окно и его разрешение.
     *
     * @param windowMode      0 — оконный, 1 — без рамки на весь экран, 2 — полноэкранный
     * @param resolutionIndex видеорежим для полноэкранного; −1 — родной режим монитора
     * @param renderScale     во сколько процентов от окна рисуется сцена (50..100)
     * @param antialiasing    сглаживание сцены: 0, 2, 4 или 8 выборок
     */
    public record Video(int windowMode, int resolutionIndex, int renderScale, int antialiasing) {
        public static Video defaults() {
            return new Video(0, -1, 100, 4);
        }
    }

    /**
     * Качество картинки по частям.
     *
     * @param shadows          0 — выкл, 1 — низкие (1024/PCF3), 2 — средние (2048/PCF3), 3 — высокие (2048/PCF5)
     * @param particles        0 — выкл, 1 — мало, 2 — средне, 3 — максимум
     * @param weather          0 — выкл, 1 — редкие осадки, 2 — полные
     * @param entityDistance   дальность мобов и предметов в процентах от дальности прорисовки
     * @param chunkLod         упрощать ли дальние чанки
     */
    public record Graphics(int shadows, boolean bloom, boolean godRays, boolean volumetricFog,
            boolean waterReflections, int particles, int weather, int entityDistance,
            boolean occlusion, boolean chunkLod) {
        public static Graphics defaults() {
            return new Graphics(1, true, true, true, false, 3, 2, 100, true, true);
        }
    }

    /**
     * Что игра показывает и чем трясёт.
     *
     * @param fpsDisplay 0 — ничего, 1 — кадры в секунду, 2 — кадры и худший кадр окна
     */
    public record Gameplay(boolean cameraShake, boolean screenEffects, boolean contextHints,
            int fpsDisplay) {
        public static Gameplay defaults() {
            return new Gameplay(true, true, true, 0);
        }
    }

    public final Video video;
    public final Graphics graphics;
    public final Gameplay gameplay;
    /**
     * Игра по сети: имя игрока, ключ Photon, последняя комната.
     *
     * <p>Не запись в ряд с {@link Video} и {@link Graphics}, а отдельный тип
     * из пакета {@code net}: его читает и сетевой экран, и сама сессия, и
     * держать его копию в настройках значило бы синхронизировать два места.
     */
    public final com.mineclone.net.NetSettings net;

    public Options(int renderRadius, int fovDegrees, float brightness, float masterVolume,
            int maxFps, boolean vsync, boolean fullscreen, boolean viewBobbing,
            float mouseSensitivity, boolean invertMouseY, float musicVolume, float effectsVolume,
            int guiScale, int shaderQuality) {
        this(renderRadius, fovDegrees, brightness, masterVolume, maxFps, vsync, fullscreen,
                viewBobbing, mouseSensitivity, invertMouseY, musicVolume, effectsVolume,
                guiScale, shaderQuality, new KeyBindings());
    }

    public Options(int renderRadius, int fovDegrees, float brightness, float masterVolume,
            int maxFps, boolean vsync, boolean fullscreen, boolean viewBobbing,
            float mouseSensitivity, boolean invertMouseY, float musicVolume, float effectsVolume,
            int guiScale, int shaderQuality, KeyBindings keys) {
        this(renderRadius, fovDegrees, brightness, masterVolume, maxFps, vsync, fullscreen,
                viewBobbing, mouseSensitivity, invertMouseY, musicVolume, effectsVolume,
                guiScale, shaderQuality, keys, false, false, false, "all", 0);
    }

    public Options(int renderRadius, int fovDegrees, float brightness, float masterVolume,
            int maxFps, boolean vsync, boolean fullscreen, boolean viewBobbing,
            float mouseSensitivity, boolean invertMouseY, float musicVolume, float effectsVolume,
            int guiScale, int shaderQuality, KeyBindings keys,
            boolean advancedTooltips, boolean recipeBookOpen, boolean recipeBookCraftable,
            String recipeBookCategory, int sortMode) {
        this(renderRadius, fovDegrees, brightness, masterVolume, maxFps, vsync, fullscreen,
                viewBobbing, mouseSensitivity, invertMouseY, musicVolume, effectsVolume,
                guiScale, shaderQuality, keys, advancedTooltips, recipeBookOpen,
                recipeBookCraftable, recipeBookCategory, sortMode,
                Video.defaults(), Graphics.defaults(), Gameplay.defaults());
    }

    public Options(int renderRadius, int fovDegrees, float brightness, float masterVolume,
            int maxFps, boolean vsync, boolean fullscreen, boolean viewBobbing,
            float mouseSensitivity, boolean invertMouseY, float musicVolume, float effectsVolume,
            int guiScale, int shaderQuality, KeyBindings keys,
            boolean advancedTooltips, boolean recipeBookOpen, boolean recipeBookCraftable,
            String recipeBookCategory, int sortMode,
            Video video, Graphics graphics, Gameplay gameplay) {
        this(renderRadius, fovDegrees, brightness, masterVolume, maxFps, vsync, fullscreen,
                viewBobbing, mouseSensitivity, invertMouseY, musicVolume, effectsVolume,
                guiScale, shaderQuality, keys, advancedTooltips, recipeBookOpen,
                recipeBookCraftable, recipeBookCategory, sortMode, video, graphics, gameplay,
                com.mineclone.net.NetSettings.defaults());
    }

    public Options(int renderRadius, int fovDegrees, float brightness, float masterVolume,
            int maxFps, boolean vsync, boolean fullscreen, boolean viewBobbing,
            float mouseSensitivity, boolean invertMouseY, float musicVolume, float effectsVolume,
            int guiScale, int shaderQuality, KeyBindings keys,
            boolean advancedTooltips, boolean recipeBookOpen, boolean recipeBookCraftable,
            String recipeBookCategory, int sortMode,
            Video video, Graphics graphics, Gameplay gameplay,
            com.mineclone.net.NetSettings net) {
        this.advancedTooltips = advancedTooltips;
        this.recipeBookOpen = recipeBookOpen;
        this.recipeBookCraftable = recipeBookCraftable;
        this.recipeBookCategory = recipeBookCategory == null || recipeBookCategory.isEmpty()
                ? "all" : recipeBookCategory;
        this.sortMode = sortMode;
        this.renderRadius = renderRadius;
        this.fovDegrees = fovDegrees;
        this.brightness = brightness;
        this.masterVolume = masterVolume;
        this.maxFps = maxFps;
        this.vsync = vsync;
        this.fullscreen = fullscreen;
        this.viewBobbing = viewBobbing;
        this.mouseSensitivity = mouseSensitivity;
        this.invertMouseY = invertMouseY;
        this.musicVolume = musicVolume;
        this.effectsVolume = effectsVolume;
        this.guiScale = guiScale;
        this.shaderQuality = shaderQuality;
        this.keys = keys != null ? keys.copy() : new KeyBindings();
        this.video = video != null ? video : Video.defaults();
        this.graphics = graphics != null ? graphics : Graphics.defaults();
        this.gameplay = gameplay != null ? gameplay : Gameplay.defaults();
        this.net = net != null ? net : com.mineclone.net.NetSettings.defaults();
    }

    /** Hardcoded defaults — applied when options.dat is missing/unreadable. */
    public static Options defaults() {
        return new Options(6, 75, 1.0f, 1.0f, 0, true, false, true, 1.0f, false, 1.0f, 1.0f, 0, 1);
    }
}

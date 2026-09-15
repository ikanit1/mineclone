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
    /** 0=Fast (без теней и пост-эффектов), 1=Fancy, 2=Ultra. */
    public final int shaderQuality;
    /** Раскладка клавиш; копия — изменения на экране не должны течь в сохранённый объект. */
    public final KeyBindings keys;

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
    }

    /** Hardcoded defaults — applied when options.dat is missing/unreadable. */
    public static Options defaults() {
        return new Options(6, 75, 1.0f, 1.0f, 0, true, false, true, 1.0f, false, 1.0f, 1.0f, 0, 1);
    }
}

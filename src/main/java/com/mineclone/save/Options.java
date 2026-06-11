package com.mineclone.save;

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

    public Options(int renderRadius, int fovDegrees, float brightness, float masterVolume,
            int maxFps, boolean vsync, boolean fullscreen, boolean viewBobbing,
            float mouseSensitivity, boolean invertMouseY, float musicVolume, float effectsVolume,
            int guiScale) {
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
    }

    /** Hardcoded defaults — applied when options.dat is missing/unreadable. */
    public static Options defaults() {
        return new Options(6, 75, 1.0f, 1.0f, 0, true, false, true, 1.0f, false, 1.0f, 1.0f, 0);
    }
}

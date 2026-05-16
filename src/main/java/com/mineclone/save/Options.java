package com.mineclone.save;

/** Global settings; sibling of saves/ (not per-world). Mirrors options.dat. */
public final class Options {
    public final int renderRadius;
    public final int fovDegrees;
    public final float brightness;
    public final float volume;

    public Options(int renderRadius, int fovDegrees, float brightness, float volume) {
        this.renderRadius = renderRadius;
        this.fovDegrees = fovDegrees;
        this.brightness = brightness;
        this.volume = volume;
    }

    /** Hardcoded defaults — applied when options.dat is missing/unreadable. */
    public static Options defaults() {
        return new Options(6, 75, 1.0f, 1.0f);
    }
}

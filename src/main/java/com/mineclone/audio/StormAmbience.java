package com.mineclone.audio;

import java.util.List;

/**
 * Continuous gusting wind, with shelter/underwater fades and one persistent OpenAL source.
 *
 * <p>Each wind recording is one gust that rises out of silence and dies back
 * into it; one of them looped fell into a two-to-three-second lull every turn.
 * The bed is all of them stitched into one seamless stereo loop ({@link SeamlessLoop}).
 */
public final class StormAmbience {
    public static final String LOOP_KEY = "storm-wind";
    /** Wind is a slow rumble: a short seam would be heard as a change of timbre, so it blends over half a second. */
    public static final float CROSSFADE_SECONDS = 0.5f;
    private final SoundEngine sound;
    private final List<String> samples;
    private float gain, pitch = 0.85f;

    public StormAmbience(SoundEngine sound, List<String> samples) {
        this.sound = sound;
        this.samples = List.copyOf(samples);
    }

    public static float targetGain(float storm, float dust, float wind, int skyLight,
                                    boolean outdoors, boolean underwater) {
        if (underwater || skyLight <= 2) return 0;
        float exposure = Math.min(1, (skyLight - 2) / 13f) * (outdoors ? 1 : 0.20f);
        float strength = Math.max(storm, dust);
        float gust = Math.max(0, Math.min(1, wind / 6f));
        return Math.min(0.65f, strength * (0.30f + gust * 0.25f + dust * 0.10f)) * exposure;
    }

    public void update(float dt, float storm, float dust, float wind, int skyLight,
                       boolean outdoors, boolean underwater) {
        float target = targetGain(storm, dust, wind, skyLight, outdoors, underwater);
        float blend = 1 - (float) Math.exp(-Math.max(0, dt) / (target > gain ? 0.6f : 0.35f));
        gain += (target - gain) * blend;
        float targetPitch = 0.82f + Math.min(1, wind / 6f) * 0.12f + dust * 0.08f;
        pitch += (targetPitch - pitch) * blend;
        if (target == 0 && gain < 0.001f) {
            gain = 0;
            sound.stopLoop(LOOP_KEY);
        } else if (gain > 0) {
            sound.updateLoopAllOf(LOOP_KEY, samples, CROSSFADE_SECONDS, gain, pitch);
        }
    }

    public void reset() { gain = 0; pitch = 0.85f; sound.stopLoop(LOOP_KEY); }
    public float gain() { return gain; }
}

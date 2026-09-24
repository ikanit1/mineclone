package com.mineclone.audio;

import java.util.List;

/** Continuous rain bed, independent of the timers for thunder and other ambient cues. */
public final class RainAmbience {
    public static final String LOOP_KEY = "rain-ambient";
    private final SoundEngine sound;
    private final List<String> samples;
    private float gain;

    public RainAmbience(SoundEngine sound, List<String> samples) {
        this.sound = sound;
        this.samples = List.copyOf(samples);
    }

    public void update(float dt, float rain, int skyLight, boolean underwater) {
        float heard = underwater ? 0 : AmbientSound.heardRain(rain, skyLight);
        float target = heard <= 0.01f ? 0 : 0.55f * (float)Math.pow(Math.min(1, heard), 0.75);
        float blend = 1 - (float)Math.exp(-Math.max(0, dt) / (target > gain ? 0.35f : 0.25f));
        gain += (target - gain) * blend;
        if (target == 0 && gain < 0.001f) {
            gain = 0;
            sound.stopLoop(LOOP_KEY);
        } else if (gain > 0) {
            // Retry every frame while decoding, then keep the same source and buffer alive.
            sound.updateLoopOneOf(LOOP_KEY, samples, gain, 1);
        }
    }

    public void reset() {
        gain = 0;
        sound.stopLoop(LOOP_KEY);
    }

    public float gain() { return gain; }
}

package com.mineclone.save;

/** Everything stored in level.dat. Immutable. */
public final class LevelData {
    public final long seed;
    public final double px, py, pz;
    public final float yaw, pitch;
    public final float timeOfDay;
    public final int selectedSlot;

    public LevelData(long seed, double px, double py, double pz,
                     float yaw, float pitch, float timeOfDay, int selectedSlot) {
        this.seed = seed;
        this.px = px; this.py = py; this.pz = pz;
        this.yaw = yaw; this.pitch = pitch;
        this.timeOfDay = timeOfDay;
        this.selectedSlot = selectedSlot;
    }
}

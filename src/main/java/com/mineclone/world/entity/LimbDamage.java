package com.mineclone.world.entity;

/** Per-limb wounds kept independently from total health. */
public final class LimbDamage {
    public enum Limb { HEAD, TORSO, LEFT_ARM, RIGHT_ARM, LEFT_LEG, RIGHT_LEG }
    private final float[] damage = new float[Limb.values().length];

    public void hit(Limb limb, float amount) {
        if (limb != null && amount > 0f)
            damage[limb.ordinal()] = Math.min(1f, damage[limb.ordinal()] + amount);
    }

    public float damage(Limb limb) { return damage[limb.ordinal()]; }
    public float speedMultiplier() {
        float legs = Math.max(damage(Limb.LEFT_LEG), damage(Limb.RIGHT_LEG));
        return Math.max(0.42f, 1f - legs * 0.58f);
    }
    public float attackMultiplier() {
        float arms = Math.max(damage(Limb.LEFT_ARM), damage(Limb.RIGHT_ARM));
        return Math.max(0.48f, 1f - arms * 0.52f);
    }
    public float headDamageMultiplier() { return 1f + damage(Limb.HEAD) * 0.75f; }

    public static Limb fromHitHeight(float normalizedY, float lateralX) {
        if (normalizedY > 0.78f) return Limb.HEAD;
        if (normalizedY < 0.38f) return lateralX < 0f ? Limb.LEFT_LEG : Limb.RIGHT_LEG;
        if (Math.abs(lateralX) > 0.28f) return lateralX < 0f ? Limb.LEFT_ARM : Limb.RIGHT_ARM;
        return Limb.TORSO;
    }
}

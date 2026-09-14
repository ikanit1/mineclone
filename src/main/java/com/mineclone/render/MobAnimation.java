package com.mineclone.render;

import com.mineclone.world.entity.Mob;

/** Shared, deterministic poses for the renderer and offline animation checks. */
public final class MobAnimation {
    private MobAnimation() {}

    public static float leg(Mob m) {
        float phase = m.inWater ? m.animationTime * 7f : m.walkedDistance * 6f;
        return (float) Math.sin(phase) * 0.7f * (m.inWater ? 0.65f : m.walkAmount);
    }

    public static float headYaw(Mob m) {
        return m.type.hostile ? 0f : (float) Math.sin(m.animationTime * 0.8f) * 0.20f;
    }

    public static float headPitch(Mob m) {
        return (float) Math.sin(m.animationTime * 1.7f) * 0.055f
                + (float) Math.sin(m.walkedDistance * 6f) * m.walkAmount * 0.08f;
    }

    public static float breathe(Mob m) {
        return (float) Math.sin(m.animationTime * 2.4f) * 0.008f;
    }

    public static float wing(Mob m) {
        return !m.onGround || m.inWater
                ? 0.8f + (float) Math.sin(m.animationTime * 24f) * 0.65f
                : 0.08f + (float) Math.sin(m.animationTime * 3f) * 0.06f;
    }

    public static float arm(Mob m) {
        float strike = m.attackSwing / Mob.ATTACK_SWING_TIME;
        return 1.5708f + (float) Math.sin(m.animationTime * 2f) * 0.045f
                + leg(m) * 0.12f - (float) Math.sin(strike * Math.PI) * 1.2f;
    }
}

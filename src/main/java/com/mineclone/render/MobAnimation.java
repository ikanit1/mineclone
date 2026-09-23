package com.mineclone.render;

import com.mineclone.world.entity.Mob;
import com.mineclone.world.entity.MobType;

/** Species-specific, distance-driven gait. Angles are local joint rotations in radians. */
public final class MobAnimation {
    private MobAnimation() {}
    private static float sin(float x) { return (float) Math.sin(x); }
    private static float clamp(float x, float a, float b) { return Math.max(a, Math.min(b, x)); }

    private static float phase(Mob m) {
        float stride = switch (m.type) {
            case COW -> 5.2f;
            case SHEEP -> 6.4f;
            case PIG -> 8f;
            case WOLF -> 7f;
            case ZOMBIE -> 4.8f;
            case CHICKEN -> 13f;
            case BIRD -> 19f;
            case RABBIT -> 10f;
            // Скелет семенит чаще зомби — он легче; паук перебирает восемью
            // ногами, поэтому шаг у него частый и мелкий.
            case SKELETON -> 5.4f;
            case SPIDER -> 11f;
            case CREEPER -> 5.0f;
        };
        return m.inWater ? m.animationTime * 7f : m.walkedDistance * stride;
    }

    public static float leg(Mob m) { return leg(m, true, false); }

    public static float leg(Mob m, boolean front, boolean right) {
        if (m.dead) return (right ? -0.25f : 0.35f) * clamp(m.topple, 0, 1);
        float air = clamp(m.airborneAmount, 0, 1);
        float amount = clamp(m.walkAmount, 0, 1);
        if (m.type == MobType.RABBIT && !m.inWater) {
            // Both rear feet push together; do not turn a hopping rabbit into a trotting dog.
            float extension = clamp(m.velocity.y / 5.4f, -1, 1);
            return (front ? -0.35f - extension * 0.25f : 0.3f + extension * 0.65f) * air;
        }
        float sign = (front != right) ? 1 : -1;
        float amplitude = switch (m.type) {
            case COW -> 0.43f;
            case PIG -> 0.48f;
            case SHEEP -> 0.50f;
            case WOLF -> 0.65f;
            case ZOMBIE -> 0.48f;
            // У паука ноги торчат вбок: большой размах читался бы как
            // судорога, а не как бег.
            case SPIDER -> 0.30f;
            case SKELETON -> 0.52f;
            case CREEPER -> 0.42f;
            default -> 0.60f;
        };
        float ground = sin(phase(m) + (!front && m.type == MobType.WOLF ? 0.22f : 0))
                * amplitude * (m.inWater ? 0.75f : amount) * sign;
        float tucked = m.type.flying ? -1.05f : (m.type == MobType.CHICKEN ? -0.35f : sign * 0.16f);
        return m.inWater ? ground : ground * (1 - air) + tucked * air;
    }

    /** Contact correction shortens the limb from its socket instead of detaching it. */
    public static float legLength(Mob m, boolean front, boolean right, float length) {
        if (m.dead || m.inWater) return 1;
        float offset = right ? m.legOffsetB : m.legOffsetA;
        float swing = m.type == MobType.RABBIT ? 0 : Math.max(0,
                (float) Math.cos(phase(m)) * (front != right ? 1 : -1));
        float lift = swing * 0.24f * m.walkAmount * (1 - m.airborneAmount);
        return clamp(1 - offset * (1 - m.airborneAmount) / length - lift, 0.65f, 1.15f);
    }

    public static float strike(Mob m) {
        if (m.dead || m.attackSwing <= 0) return 0;
        float t = 1 - clamp(m.attackSwing / Mob.ATTACK_SWING_TIME, 0, 1);
        // Slow anticipation, sharp strike, then a smooth recovery.
        return t < 0.32f ? -0.3f * sin(t / 0.32f * (float) Math.PI)
                : sin((t - 0.32f) / 0.68f * (float) Math.PI);
    }

    public static float headYaw(Mob m) {
        if (m.dead) return 0;
        return m.type.hostile ? 0 : clamp(m.lookYaw + sin(m.animationTime * 0.8f) * 0.07f, -0.72f, 0.72f);
    }

    public static float headPitch(Mob m) {
        if (m.dead) return -0.16f * clamp(m.topple, 0, 1);
        float pitch = -m.grazeAmount * 0.65f + sin(m.animationTime * 1.7f) * 0.025f;
        float step = sin(phase(m) * 2) * m.walkAmount * (1 - m.airborneAmount);
        pitch += step * (m.type == MobType.CHICKEN || m.type == MobType.BIRD ? 0.13f : 0.035f);
        if (m.type == MobType.WOLF) pitch -= strike(m) * 0.32f;
        if (m.type.flying) pitch -= bodyPitch(m) * 0.6f;
        return pitch;
    }

    public static float breathe(Mob m) {
        return m.dead ? 0 : sin(m.animationTime * 2.4f) * 0.006f;
    }

    public static float bodyPitch(Mob m) {
        if (m.dead) return 0;
        if (m.inWater) return (m.type == MobType.ZOMBIE ? -0.95f : 0.08f)
                + sin(m.animationTime * 3f) * 0.025f;
        if (m.type == MobType.RABBIT) return clamp(m.velocity.y * 0.045f, -0.20f, 0.24f) * m.airborneAmount;
        if (m.type.flying) return clamp(m.velocity.y * 0.07f, -0.25f, 0.30f) * m.airborneAmount;
        if (m.type == MobType.WOLF) return -strike(m) * 0.10f
                + sin(phase(m) * 2) * m.walkAmount * 0.025f;
        return sin(phase(m) * 2) * m.walkAmount * (1 - m.airborneAmount) * 0.02f;
    }

    public static float bodyRoll(Mob m) {
        if (m.dead || m.inWater) return 0;
        return sin(phase(m)) * m.walkAmount * (1 - m.airborneAmount)
                * (m.type == MobType.CHICKEN ? 0.045f : 0.022f);
    }

    public static float wing(Mob m) {
        if (m.dead) return 0.18f;
        float air = clamp(m.airborneAmount, 0, 1);
        float folded = 0.07f + sin(m.animationTime * 2.6f) * 0.025f;
        float flying = m.type.flying ? 1.48f + sin(m.animationTime * 22f) * 0.95f
                : 0.95f + sin(m.animationTime * 25f) * 0.70f;
        return folded * (1 - air) + flying * air;
    }

    public static float tail(Mob m) {
        if (m.dead) return 0;
        if (m.type.flying) return sin(m.animationTime * 4f) * 0.045f;
        return sin(m.animationTime * (m.isAngry() ? 11f : 5f))
                * (0.07f + 0.22f * m.walkAmount + (m.isAngry() ? 0.10f : 0));
    }

    public static float arm(Mob m) { return arm(m, false); }
    public static float arm(Mob m, boolean right) {
        if (m.dead) return 0.25f;
        // Positive X swings a hanging arm forward into -Z.
        return 1.38f + sin(m.animationTime * 2f + (right ? 0.5f : 0)) * 0.035f
                + leg(m) * (right ? -0.10f : 0.10f) - strike(m) * 0.65f;
    }
}

package com.mineclone.world.damage;

import com.mineclone.world.entity.MobType;
import java.util.Objects;

/**
 * One hit: its kind, who dealt it and where it came from.
 *
 * <p>The attacker is identified by number rather than by reference, so a
 * source can outlive the mob or player that produced it and still travel in a
 * packet or a death message. A mob attacker also names its type; a participant
 * attacker leaves {@code attackerType} empty. An origin of NaN means the hit
 * has no direction (starvation, the void) and must not knock anyone back.
 */
public record DamageSource(DamageType type, int attackerId, MobType attackerType,
                           float originX, float originY, float originZ, float knockback) {
    /** No one dealt this damage. Participant and entity numbers are never negative. */
    public static final int NO_ATTACKER = -1;

    public DamageSource {
        Objects.requireNonNull(type, "type");
        if (attackerId < NO_ATTACKER)
            throw new IllegalArgumentException("invalid attacker " + attackerId);
        if (!Float.isFinite(knockback) || knockback < 0f)
            throw new IllegalArgumentException("knockback must be finite and non-negative");
        boolean anyOrigin = !Float.isNaN(originX) || !Float.isNaN(originY) || !Float.isNaN(originZ);
        boolean fullOrigin = Float.isFinite(originX) && Float.isFinite(originY) && Float.isFinite(originZ);
        if (anyOrigin && !fullOrigin)
            throw new IllegalArgumentException("origin must be all finite or all NaN");
    }

    /** Anonymous, directionless damage of one kind. */
    public static DamageSource of(DamageType type) {
        return new DamageSource(type, NO_ATTACKER, null, Float.NaN, Float.NaN, Float.NaN, 0f);
    }
}

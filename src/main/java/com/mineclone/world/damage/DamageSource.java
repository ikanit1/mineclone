package com.mineclone.world.damage;

import com.mineclone.world.entity.LimbDamage;
import com.mineclone.world.entity.MobType;
import java.util.Objects;

/**
 * One hit: its kind, who dealt it, where it came from and where it landed.
 *
 * <p>A player attacker is identified by participant number rather than by
 * reference, so a source can outlive the player and still travel in a packet
 * or a death message; its number may be {@link #NO_ATTACKER} when the player
 * is gone (an arrow still in flight), and {@link #PLAYER} says who dealt it
 * all the same. A mob attacker names its type (mobs have no stable numbers).
 * Neither — the world: fall, fire, starvation. An origin of NaN means the hit
 * has no direction and must not knock anyone back; {@code knockback} scales
 * the target's usual knockback (1 = an ordinary hit). {@code limb} is where a
 * weapon struck a mob, or null.
 */
public record DamageSource(DamageType type, int attackerId, MobType attackerType,
                           float originX, float originY, float originZ, float knockback,
                           LimbDamage.Limb limb, int flags) {
    /** No one dealt this damage. Participant numbers are never negative. */
    public static final int NO_ATTACKER = -1;
    /** Dealt by a player: angers a neutral mob, and a kill counts as theirs. */
    public static final int PLAYER = 1;
    private static final int KNOWN_FLAGS = PLAYER;

    public DamageSource {
        Objects.requireNonNull(type, "type");
        if (attackerId < NO_ATTACKER)
            throw new IllegalArgumentException("invalid attacker " + attackerId);
        if ((flags & ~KNOWN_FLAGS) != 0)
            throw new IllegalArgumentException("unknown damage flags " + flags);
        if ((flags & PLAYER) != 0 && attackerType != null)
            throw new IllegalArgumentException("a player is not a " + attackerType);
        if (attackerId != NO_ATTACKER && (flags & PLAYER) == 0)
            throw new IllegalArgumentException("only players have attacker numbers");
        if (!Float.isFinite(knockback) || knockback < 0f)
            throw new IllegalArgumentException("knockback must be finite and non-negative");
        boolean anyOrigin = !Float.isNaN(originX) || !Float.isNaN(originY) || !Float.isNaN(originZ);
        boolean fullOrigin = Float.isFinite(originX) && Float.isFinite(originY) && Float.isFinite(originZ);
        if (anyOrigin && !fullOrigin)
            throw new IllegalArgumentException("origin must be all finite or all NaN");
    }

    /** Anonymous, directionless damage of one kind. */
    public static DamageSource of(DamageType type) {
        return new DamageSource(type, NO_ATTACKER, null, Float.NaN, Float.NaN, Float.NaN, 0f, null, 0);
    }

    /** A player's hit from (x, y, z); {@code participant} may be {@link #NO_ATTACKER} when unknown. */
    public static DamageSource byPlayer(DamageType type, int participant, float x, float y, float z,
                                        float knockback) {
        return new DamageSource(type, participant, null, x, y, z, knockback, null, PLAYER);
    }

    /** A mob's hit from (x, y, z); {@code mob} may be null when the shooter is gone. */
    public static DamageSource byMob(DamageType type, MobType mob, float x, float y, float z, float knockback) {
        return new DamageSource(type, NO_ATTACKER, mob, x, y, z, knockback, null, 0);
    }

    /** The same hit, landing on {@code limb}. */
    public DamageSource onLimb(LimbDamage.Limb limb) {
        return new DamageSource(type, attackerId, attackerType, originX, originY, originZ, knockback, limb, flags);
    }

    /** Dealt by a player (see {@link #PLAYER}). */
    public boolean byPlayer() { return (flags & PLAYER) != 0; }

    /** The participant who dealt it, or {@link #NO_ATTACKER}. */
    public int participant() { return attackerId; }

    /** Whether the hit came from somewhere, and so may knock its target away. */
    public boolean hasOrigin() { return !Float.isNaN(originX); }
}

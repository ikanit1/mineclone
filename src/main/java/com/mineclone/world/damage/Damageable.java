package com.mineclone.world.damage;

/** Anything that can be hurt through the shared damage vocabulary. */
public interface Damageable {
    /**
     * Apply a hit.
     *
     * @return true if the hit landed; false when it was refused (creative mode,
     *         the invulnerability window, a dead target, a non-positive amount)
     */
    boolean damage(DamageSource source, float amount);
}

package com.mineclone.world.damage;

/**
 * What kind of harm a hit is, independent of who dealt it.
 *
 * <p>The flags follow the game's current split between the two player damage
 * entry points: attacks respect the post-hit invulnerability window, while
 * environmental damage is already rate-limited by its caller (fall once per
 * landing, fire and starvation per second) and must not be swallowed by a
 * window opened by an unrelated zombie punch. Armor applies to what a body
 * blocks; drowning, starvation, poison and the void go straight through.
 */
public enum DamageType {
    MELEE(false, false, false),
    PROJECTILE(false, false, false),
    EXPLOSION(false, false, false),
    FALL(true, true, false),
    FIRE(false, true, true),
    LAVA(false, true, true),
    DROWN(true, true, false),
    STARVE(true, true, false),
    POISON(true, true, false),
    CACTUS(false, true, false),
    SUFFOCATE(true, true, false),
    VOID(true, true, false),
    LIGHTNING(false, false, false),
    MAGIC(true, false, false),
    /** Harm whose cause the caller did not name: what the old {@code Player.takeDamage} meant. */
    GENERIC(false, true, false);

    private final boolean bypassesArmor;
    private final boolean bypassesInvulnerability;
    private final boolean fire;

    DamageType(boolean bypassesArmor, boolean bypassesInvulnerability, boolean fire) {
        this.bypassesArmor = bypassesArmor;
        this.bypassesInvulnerability = bypassesInvulnerability;
        this.fire = fire;
    }

    /** Armor points and toughness do not reduce this damage. */
    public boolean bypassesArmor() { return bypassesArmor; }

    /** Lands even inside the invulnerability window opened by a previous attack. */
    public boolean bypassesInvulnerability() { return bypassesInvulnerability; }

    /** Burning damage: what fire resistance will cancel. */
    public boolean isFire() { return fire; }
}

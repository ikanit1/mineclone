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
    MELEE(0, false, false, false),
    PROJECTILE(1, false, false, false),
    EXPLOSION(2, false, false, false),
    FALL(3, true, true, false),
    FIRE(4, false, true, true),
    LAVA(5, false, true, true),
    DROWN(6, true, true, false),
    STARVE(7, true, true, false),
    POISON(8, true, true, false),
    CACTUS(9, false, true, false),
    SUFFOCATE(10, true, true, false),
    VOID(11, true, true, false),
    LIGHTNING(12, false, false, false),
    MAGIC(13, true, false, false),
    /** Harm whose cause the caller did not name: what the old {@code Player.takeDamage} meant. */
    GENERIC(14, false, true, false);

    private final int id;
    private final boolean bypassesArmor;
    private final boolean bypassesInvulnerability;
    private final boolean fire;

    DamageType(int id, boolean bypassesArmor, boolean bypassesInvulnerability, boolean fire) {
        this.id = id;
        this.bypassesArmor = bypassesArmor;
        this.bypassesInvulnerability = bypassesInvulnerability;
        this.fire = fire;
    }

    /** Stable number for packets (protocol v8) and saves; never the ordinal. */
    public int id() { return id; }

    /** The kind with this number, or null when this build does not know it. */
    public static DamageType byId(int id) {
        for (DamageType type : values()) if (type.id == id) return type;
        return null;
    }

    /** Armor points and toughness do not reduce this damage. */
    public boolean bypassesArmor() { return bypassesArmor; }

    /** Lands even inside the invulnerability window opened by a previous attack. */
    public boolean bypassesInvulnerability() { return bypassesInvulnerability; }

    /** Burning damage: what fire resistance will cancel. */
    public boolean isFire() { return fire; }
}

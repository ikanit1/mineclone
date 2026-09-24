package com.mineclone.world.damage;

/**
 * What armor leaves of a hit. There is no armor yet (CMB-01/02): every hit
 * lands whole, and this is the one place that will change when there is.
 * Whatever {@link DamageType#bypassesArmor() bypasses armor} will stay whole.
 */
public final class ArmorMath {
    private ArmorMath() {}

    public static float afterArmor(float amount, DamageType type, ArmorView armor) {
        return amount;
    }
}

package com.mineclone.world.damage;

/** Shared defense formula for local and remote participants. */
public final class ArmorMath {
    private ArmorMath() {}
    public static float reduce(float amount,float defense,float toughness){
        float effective=Math.min(20f,Math.max(defense/5f,defense-amount/(2f+toughness/4f)));
        return amount*(1f-effective/25f);
    }
    public static float afterArmor(float amount,DamageType type,ArmorView armor){return type.bypassesArmor()?amount:reduce(amount,armor.points(),armor.toughness());}
}

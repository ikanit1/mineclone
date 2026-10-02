package com.mineclone.item;

/** Immutable armor data. Slot indices match the append-only player equipment section. */
public record ArmorSpec(Slot slot, float defense, float toughness, float knockbackResistance) {
    public enum Slot { HEAD, CHEST, LEGS, FEET }
    public ArmorSpec {
        if(slot==null || !Float.isFinite(defense)||defense<0 || !Float.isFinite(toughness)||toughness<0
                || !Float.isFinite(knockbackResistance)||knockbackResistance<0||knockbackResistance>1)
            throw new IllegalArgumentException("invalid armor values");
    }
}

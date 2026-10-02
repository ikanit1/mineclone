package com.mineclone.world.effect;

import com.mineclone.world.damage.DamageSource;

public record EffectInstance(StatusEffect effect,int amplifier,float remaining,DamageSource source,boolean hidden){
    public EffectInstance{if(effect==null||amplifier<0||amplifier>255||!Float.isFinite(remaining)||remaining<0)throw new IllegalArgumentException("invalid effect");}
}

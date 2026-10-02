package com.mineclone.item;

import com.mineclone.world.effect.StatusEffect;
import java.util.List;

/** Nutrition and saturation are separate; optional effects are rolled on completed eating only. */
public record FoodSpec(int nutrition,float saturation,float eatSeconds,List<Effect> effects){
    public record Effect(StatusEffect effect,float seconds,int amplifier,float chance){public Effect{if(effect==null||!Float.isFinite(seconds)||seconds<=0||seconds>86400||amplifier<0||amplifier>255||!Float.isFinite(chance)||chance<0||chance>1)throw new IllegalArgumentException("invalid food effect");}}
    public FoodSpec(int nutrition){this(nutrition,nutrition*.6f,1.6f,List.of());}
    public FoodSpec{effects=List.copyOf(effects);if(nutrition<0||!Float.isFinite(saturation)||saturation<0||!Float.isFinite(eatSeconds)||eatSeconds<=0)throw new IllegalArgumentException("invalid food");}
}

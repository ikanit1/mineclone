package com.mineclone.world.effect;

import com.mineclone.save.PlayerRecord;
import com.mineclone.world.damage.*;
import java.util.*;

/** Simulation effects, with no renderer or platform dependencies. */
public final class StatusEffects {
    public interface Target extends Damageable {float health();void heal(float amount);default void exhaust(float amount){} }
    private final EnumMap<StatusEffect,EffectInstance> effects=new EnumMap<>(StatusEffect.class);
    private final EnumMap<StatusEffect,Float> pulses=new EnumMap<>(StatusEffect.class);
    public static StatusEffect kind(String id){String name=id.startsWith("mineclone:")?id.substring(10):id;if(name.contains(":"))return null;try{return StatusEffect.valueOf(name.toUpperCase(Locale.ROOT));}catch(IllegalArgumentException future){return null;}}
    private final EnumMap<StatusEffect,String> names=new EnumMap<>(StatusEffect.class);
    private final List<PlayerRecord.Effect> unknown=new ArrayList<>();
    public void apply(StatusEffect effect,int amplifier,float seconds,DamageSource source,boolean hidden){
        var old=effects.get(effect);if(old!=null){amplifier=Math.max(amplifier,old.amplifier());seconds=Math.max(seconds,old.remaining());}
        if(seconds>0)effects.put(effect,new EffectInstance(effect,amplifier,seconds,source,hidden));
    }
    public void apply(StatusEffect effect,float seconds){apply(effect,0,seconds,null,false);}
    public boolean active(StatusEffect effect){return effects.containsKey(effect);}
    public float remaining(StatusEffect effect){var e=effects.get(effect);return e==null?0:e.remaining();}
    public float movementMultiplier(){float m=1;for(var e:effects.values())switch(e.effect()){
        case SLOWNESS,CHILLED->m*=Math.max(.1f,1-.15f*(e.amplifier()+1));case SPEED->m*=1+.2f*(e.amplifier()+1);default->{}}
        return Math.max(.1f,Math.min(4,m));}
    public float weaken(float amount){var e=effects.get(StatusEffect.WEAKNESS);return e==null?amount:Math.max(0,amount-2*(e.amplifier()+1));}
    public List<EffectInstance> all(){return List.copyOf(effects.values());}
    public void clear(){effects.clear();pulses.clear();unknown.clear();names.clear();}
    public void tick(float dt,Target target){
        if(!(dt>0)||!Float.isFinite(dt))return;
        for(var type:new ArrayList<>(effects.keySet())){
            var e=effects.get(type);float elapsed=Math.min(dt,e.remaining()),pulse=pulses.getOrDefault(type,0f)+elapsed;
            float interval=type==StatusEffect.POISON?1.25f/(e.amplifier()+1):1f;
            if(type==StatusEffect.HUNGER)target.exhaust(.5f*(e.amplifier()+1)*elapsed);
            while(pulse>=interval){pulse-=interval;
                if(type==StatusEffect.POISON&&target.health()>1)target.damage(DamageSource.of(DamageType.POISON),Math.min(1,target.health()-1));
                if(type==StatusEffect.REGENERATION||type==StatusEffect.WELL_FED)target.heal(type==StatusEffect.WELL_FED?.25f:e.amplifier()+1);
            }
            pulses.put(type,pulse);float left=e.remaining()-elapsed;
            if(left<=0){effects.remove(type);pulses.remove(type);}else effects.put(type,new EffectInstance(type,e.amplifier(),left,e.source(),e.hidden()));
        }
    }
    public List<PlayerRecord.Effect> snapshot(){var a=new ArrayList<>(unknown);for(var e:effects.values())a.add(new PlayerRecord.Effect(names.getOrDefault(e.effect(),"mineclone:"+e.effect().name().toLowerCase(Locale.ROOT)),(long)Math.ceil(e.remaining()*20),e.amplifier()));return List.copyOf(a);}
    public void restore(List<PlayerRecord.Effect> saved){clear();for(var effect:saved){var type=kind(effect.id());if(type==null){unknown.add(effect);continue;}names.put(type,effect.id());apply(type,effect.amplifier(),effect.remainingTicks()/20f,null,false);}}
}

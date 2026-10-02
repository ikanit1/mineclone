package com.mineclone.world.entity;

import com.mineclone.sim.*;
import com.mineclone.world.*;
import java.util.*;
import java.util.function.LongSupplier;
import org.joml.Vector3f;

/** Host-owned feeding, food following, pairing and birth; children are appended after mob iteration. */
public final class Breeding {
    private record Pair(long first,long second){}
    public java.util.function.IntConsumer bred=actor->{};
    private final Map<Pair,Float> closeTime=new HashMap<>();
    public static boolean food(MobType type,ItemStack stack){if(stack==null||stack.count<=0)return false;String id=stack.item.id.path();return switch(type){
        case COW,SHEEP->id.equals("wheat");case PIG->id.equals("carrot")||id.equals("potato");case CHICKEN->id.equals("wheat_seeds");default->false;};}
    public static boolean feed(Mob mob,ItemStack held,GameMode mode){if(mob.dead||!food(mob.type,held)||!mob.baby()&&(mob.loveCooldown>0||mob.love>0))return false;
        if(mode==GameMode.SURVIVAL)held.count--;mob.persistent=true;
        if(mob.baby())mob.age*=.9f;else mob.love=30;return true;}
    public void tick(World world,List<Mob> mobs,Participants participants,float dt,LongSupplier ids){
        var paired=Collections.newSetFromMap(new IdentityHashMap<Mob,Boolean>());var births=new ArrayList<Mob>();var active=new HashSet<Pair>();
        for(var m:mobs){m.foodTarget=null;if(m.dead||m.type.hostile)continue;
            if(m.baby()){Mob adult=null;float distance=100;for(var a:mobs)if(a!=m&&!a.dead&&!a.baby()&&a.type==m.type){float d=m.position.distanceSquared(a.position);if(d<distance){adult=a;distance=d;}}if(adult!=null&&distance>4)m.foodTarget=new Vector3f(adult.position);}
            for(int i=0;i<participants.size();i++){var p=participants.get(i);if(p.alive()&&food(m.type,p.heldItem())&&m.position.distanceSquared(p.position())<100){m.foodTarget=new Vector3f(p.position());break;}}
        }
        for(var m:mobs){
            if(m.dead||m.baby()||m.love<=0||m.loveCooldown>0||paired.contains(m))continue;
            Mob partner=null;float best=64;for(var a:mobs)if(a!=m&&!a.dead&&!a.baby()&&a.type==m.type&&a.love>0&&a.loveCooldown<=0&&!paired.contains(a)){float d=m.position.distanceSquared(a.position);if(d<best){best=d;partner=a;}}
            if(partner==null)continue;paired.add(m);paired.add(partner);m.foodTarget=new Vector3f(partner.position);partner.foodTarget=new Vector3f(m.position);
            Pair pair=new Pair(Math.min(m.id,partner.id),Math.max(m.id,partner.id));active.add(pair);
            float seconds=best<4?closeTime.getOrDefault(pair,0f)+dt:0;closeTime.put(pair,seconds);
            if(seconds>=3){var child=new Mob(m.type,(m.position.x+partner.position.x)/2,m.position.y,(m.position.z+partner.position.z)/2,new Random(m.id^partner.id));child.id=ids.getAsLong();bred.accept(m.breedingOwner);if(partner.breedingOwner!=m.breedingOwner)bred.accept(partner.breedingOwner);child.age=-1200;child.persistent=true;births.add(child);m.love=partner.love=0;m.loveCooldown=partner.loveCooldown=300;closeTime.remove(pair);}
        }closeTime.keySet().retainAll(active);mobs.addAll(births);
    }
}

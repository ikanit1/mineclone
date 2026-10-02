package com.mineclone.world.entity;

import com.mineclone.world.*;
import com.mineclone.world.damage.*;
import com.mineclone.world.shape.Shapes;
import org.joml.Vector3fc;

/** Shared environmental contact pulses for players and mobs, with independent damage clocks. */
public final class EntityHazards {
    private float burn,fire,cactus,suffocate,voidTime;
    public boolean burning(){return burn>0;}
    public void clear(){burn=fire=cactus=suffocate=voidTime=0;}
    public void tick(Damageable target,Vector3fc p,float width,float height,float eye,boolean water,boolean resistant,World w,float dt){
        if(w==null||!(dt>0)||!Float.isFinite(dt))return;boolean flame=false,lava=false,spines=false;
        for(int x=(int)Math.floor(p.x()-width/2-.01f);x<=(int)Math.floor(p.x()+width/2+.01f);x++)for(int z=(int)Math.floor(p.z()-width/2-.01f);z<=(int)Math.floor(p.z()+width/2+.01f);z++)for(int y=(int)Math.floor(p.y());y<=(int)Math.floor(p.y()+height-.01f);y++){var b=w.getBlock(x,y,z);flame|=b==BlockType.FIRE;lava|=b==BlockType.LAVA;spines|=b==BlockType.CACTUS;}
        burn=water?0:flame||lava?4:Math.max(0,burn-dt);fire=pulse(target,!resistant&&(lava||burn>0),fire,dt,1,lava?DamageType.LAVA:DamageType.FIRE,lava?4:1);
        cactus=pulse(target,spines,cactus,dt,.5f,DamageType.CACTUS,1);
        int x=(int)Math.floor(p.x()),y=(int)Math.floor(p.y()+eye),z=(int)Math.floor(p.z());var head=w.getBlock(x,y,z);boolean solid=!head.transparent&&Shapes.of(head).fullCube(w.getBlockMeta(x,y,z));
        suffocate=pulse(target,solid,suffocate,dt,.5f,DamageType.SUFFOCATE,1);voidTime=pulse(target,p.y()< -16,voidTime,dt,1,DamageType.VOID,4);
    }
    private static float pulse(Damageable target,boolean active,float timer,float dt,float interval,DamageType type,float amount){if(!active)return 0;timer+=dt;while(timer>=interval){timer-=interval;target.damage(DamageSource.of(type),amount);}return timer;}
}

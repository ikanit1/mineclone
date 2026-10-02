package com.mineclone.world;

/** Names are persisted; order is append-only for runtime selection. */
public enum Difficulty {
    PEACEFUL(0,20,0),EASY(.5f,10,.5f),NORMAL(1,1,1),HARD(1.5f,0,1.5f);
    public final float mobDamage,starvationFloor,eliteChance;
    Difficulty(float damage,float floor,float elite){mobDamage=damage;starvationFloor=floor;eliteChance=elite;}
}

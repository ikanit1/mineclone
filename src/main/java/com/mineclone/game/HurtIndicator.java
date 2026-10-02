package com.mineclone.game;

import com.mineclone.world.damage.DamageSource;

/** Direction of the latest positioned hit, in camera-relative radians, lasting 1.2 seconds. */
public final class HurtIndicator {
    private float remaining,worldAngle;
    public void hit(DamageSource source,float x,float z){if(source==null||!source.hasOrigin())return;worldAngle=(float)Math.atan2(source.originX()-x,-(source.originZ()-z));remaining=1.2f;}
    public void tick(float dt){remaining=Math.max(0,remaining-dt);}
    public float angle(float yaw){float a=worldAngle-yaw;return (float)Math.atan2(Math.sin(a),Math.cos(a));}
    public float alpha(){return Math.min(1,remaining/.4f);}
}

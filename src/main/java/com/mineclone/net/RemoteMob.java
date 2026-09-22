package com.mineclone.net;

import com.mineclone.render.BodyRotation;
import com.mineclone.world.entity.Mob;
import com.mineclone.world.entity.MobTactics;

/** Render interpolation only: clients never run the authoritative mob AI or physics. */
public final class RemoteMob {
    private final Mob mob;
    private MobSnapshot target;
    private float age, remaining;
    public RemoteMob(Mob mob) { this.mob = mob; }

    public void accept(MobSnapshot next) {
        if (!next.valid() || next.type() != mob.type.ordinal()) return;
        boolean snap = target == null || mob.position.distanceSquared(next.x(), next.y(), next.z()) > 64;
        target = next;
        age = 0;
        remaining = 1f / NetProto.TICK_RATE;
        mob.health = next.health();
        mob.dead = (next.flags() & 1) != 0;
        mob.burning = (next.flags() & 2) != 0;
        mob.inWater = (next.flags() & 4) != 0;
        mob.onGround = (next.flags() & 8) != 0;
        mob.enraged = (next.flags() & 16) != 0;
        mob.visualAngry = (next.flags() & 32) != 0;
        mob.elite = MobTactics.Elite.values()[next.elite()];
        mob.state = Mob.State.values()[next.state()];
        mob.deathAxisX = next.axisX();
        mob.deathAxisZ = next.axisZ();
        if (snap) {
            blend(1);
            mob.animationTime = next.time();
            remaining = 0;
        }
    }

    public void update(float dt) {
        if (target == null || !Float.isFinite(dt) || dt <= 0) return;
        age += dt;
        float k = remaining > 0 ? Math.min(1, dt / remaining) : 1;
        blend(k);
        remaining = Math.max(0, remaining - dt);
        if (!mob.dead) {
            // Bounded clock correction cannot run time backwards or freeze a wing mid-flap.
            float error = target.time() + age - mob.animationTime;
            mob.animationTime += dt + Math.max(-dt * 0.5f, Math.min(dt * 0.5f, error * dt * 3));
        }
        mob.attackSwing = Math.max(0, target.attack() - age);
        mob.hurtFlash = Math.max(0, target.hurt() - age);
        mob.deathTimer = Math.max(0, target.deathTime() - age);
    }

    private static float mix(float a, float b, float k) { return k >= 1 ? b : a + (b - a) * k; }
    private void blend(float k) {
        MobSnapshot t = target;
        mob.position.set(mix(mob.position.x,t.x(),k),mix(mob.position.y,t.y(),k),mix(mob.position.z,t.z(),k));
        mob.yaw += BodyRotation.wrap(t.yaw() - mob.yaw) * k;
        mob.yaw = BodyRotation.wrap(mob.yaw);
        mob.walkedDistance = mix(mob.walkedDistance,t.distance(),k);
        mob.walkAmount = mix(mob.walkAmount,t.walk(),k);
        mob.lookYaw = mix(mob.lookYaw,t.look(),k);
        mob.grazeAmount = mix(mob.grazeAmount,t.graze(),k);
        mob.legOffsetA = mix(mob.legOffsetA,t.footA(),k);
        mob.legOffsetB = mix(mob.legOffsetB,t.footB(),k);
        mob.topple = mix(mob.topple,t.topple(),k);
        mob.velocity.y = mix(mob.velocity.y,t.vy(),k);
        mob.airborneAmount = mix(mob.airborneAmount,t.air(),k);
        mob.attackSwing = t.attack();
        mob.hurtFlash = t.hurt();
        mob.deathTimer = t.deathTime();
    }
}

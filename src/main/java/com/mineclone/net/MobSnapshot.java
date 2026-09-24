package com.mineclone.net;

import com.mineclone.world.entity.Mob;
import com.mineclone.world.entity.MobType;
import com.mineclone.world.entity.MobTactics;

/** Complete visual state. The same codec is used even when skipping an untrusted sender. */
public record MobSnapshot(int id, int type, int flags, int elite, int state,
        float x, float y, float z, float yaw, float health, float distance, float time,
        float walk, float look, float graze, float attack, float footA, float footB,
        float topple, float axisX, float axisZ, float deathTime, float hurt, float vy, float air) {
    public static final int MIN_BYTES = 85;
    public static MobSnapshot capture(int id, Mob m) {
        int flags = (m.dead ? 1 : 0) | (m.burning ? 2 : 0) | (m.inWater ? 4 : 0)
                | (m.onGround ? 8 : 0) | (m.enraged ? 16 : 0) | (m.isAngry() ? 32 : 0);
        return new MobSnapshot(id, m.type.ordinal(), flags, m.elite.ordinal(), m.state.ordinal(),
                m.position.x, m.position.y, m.position.z, com.mineclone.render.BodyRotation.wrap(m.yaw), m.health, m.walkedDistance,
                m.animationTime, m.walkAmount, m.lookYaw, m.grazeAmount, m.attackSwing,
                m.legOffsetA, m.legOffsetB, m.topple, m.deathAxisX, m.deathAxisZ,
                m.deathTimer, m.hurtFlash, m.velocity.y, m.airborneAmount);
    }
    public void write(PacketBuf b) {
        b.varInt(id).u8(type).u8(flags).u8(elite).u8(state)
                .f32(x).f32(y).f32(z).f32(yaw).f32(health).f32(distance).f32(time)
                .f32(walk).f32(look).f32(graze).f32(attack).f32(footA).f32(footB)
                .f32(topple).f32(axisX).f32(axisZ).f32(deathTime).f32(hurt).f32(vy).f32(air);
    }
    public static MobSnapshot read(PacketBuf b) {
        return new MobSnapshot(b.readVarInt(), b.readU8(), b.readU8(), b.readU8(), b.readU8(),
                b.readF32(), b.readF32(), b.readF32(), b.readF32(), b.readF32(), b.readF32(), b.readF32(),
                b.readF32(), b.readF32(), b.readF32(), b.readF32(), b.readF32(), b.readF32(),
                b.readF32(), b.readF32(), b.readF32(), b.readF32(), b.readF32(), b.readF32(), b.readF32());
    }
    private static final int TYPES = MobType.values().length, ELITES = MobTactics.Elite.values().length,
            STATES = Mob.State.values().length;

    /** Checked field by field: a snapshot per mob per network frame must not allocate (TD-35). */
    public boolean valid() {
        if (id <= 0 || type < 0 || type >= TYPES || elite >= ELITES || state >= STATES || (flags & ~63) != 0) return false;
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z) || !Float.isFinite(yaw)
                || !Float.isFinite(health) || !Float.isFinite(distance) || !Float.isFinite(time)
                || !Float.isFinite(walk) || !Float.isFinite(look) || !Float.isFinite(graze) || !Float.isFinite(attack)
                || !Float.isFinite(footA) || !Float.isFinite(footB) || !Float.isFinite(topple)
                || !Float.isFinite(axisX) || !Float.isFinite(axisZ) || !Float.isFinite(deathTime)
                || !Float.isFinite(hurt) || !Float.isFinite(vy) || !Float.isFinite(air)) return false;
        float axis = axisX * axisX + axisZ * axisZ;
        return Math.abs(x) <= 1e7f && Math.abs(y) <= 1e7f && Math.abs(z) <= 1e7f
                && Math.abs(yaw) <= 3.15f && Math.abs(distance) <= 1e20f && Math.abs(time) <= 1e20f
                && Math.abs(vy) <= 1e4f && Math.abs(health) <= 1e6f && deathTime >= 0 && hurt >= 0
                && walk >= 0 && walk <= 1.01f && graze >= 0 && graze <= 1.01f && air >= 0 && air <= 1.01f
                && Math.abs(look) <= 1 && attack >= 0 && attack <= Mob.ATTACK_SWING_TIME
                && Math.abs(footA) <= 0.33f && Math.abs(footB) <= 0.33f
                && topple >= 0 && topple <= 1.91f && axis > 0.99f && axis < 1.01f;
    }
}

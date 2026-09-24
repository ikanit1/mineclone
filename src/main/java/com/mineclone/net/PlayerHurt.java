package com.mineclone.net;

import com.mineclone.world.damage.DamageSource;
import com.mineclone.world.damage.DamageType;
import com.mineclone.world.entity.MobType;

/**
 * {@code S_PLAYER_HURT}, protocol v8: a hit the host dealt a guest. v7 carried
 * the amount alone; now the guest learns the kind of harm, who dealt it and
 * from where, so its own damage path applies the same rules as the host's —
 * the window, creative, the cause of death — and it is knocked away from the
 * blow.
 *
 * <p>Layout: {@code f32 amount, u8 kind (DamageType.id), u8 flags (1 a player's
 * hit, 2 has an origin), [f32 x, y, z], f32 knockback, u8 mob type + 1 (0 none),
 * varInt participant + 1 (0 none)}. Fields are kept raw until {@link #valid()}
 * has accepted them: a source built from a stranger's numbers would throw.
 */
public record PlayerHurt(float amount, int kind, int flags, float x, float y, float z, float knockback,
                         int mob, int participant) {
    public static final int F_PLAYER = 1, F_ORIGIN = 2;
    /** No hit in the game comes near this; a bigger one is a broken packet. */
    public static final float MAX_AMOUNT = 1000f;
    public static final float MAX_KNOCKBACK = 16f;

    public static PlayerHurt of(DamageSource source, float amount) {
        int flags = (source.byPlayer() ? F_PLAYER : 0) | (source.hasOrigin() ? F_ORIGIN : 0);
        return new PlayerHurt(amount, source.type().id(), flags,
                source.hasOrigin() ? source.originX() : 0f, source.hasOrigin() ? source.originY() : 0f,
                source.hasOrigin() ? source.originZ() : 0f, source.knockback(),
                source.attackerType() == null ? 0 : source.attackerType().ordinal() + 1,
                source.participant() + 1);
    }

    public void write(PacketBuf b) {
        b.f32(amount).u8(kind).u8(flags);
        if ((flags & F_ORIGIN) != 0)
            b.f32(x).f32(y).f32(z);
        b.f32(knockback).u8(mob).varInt(participant);
    }

    public static PlayerHurt read(PacketBuf b) {
        float amount = b.readF32();
        int kind = b.readU8(), flags = b.readU8();
        float x = 0f, y = 0f, z = 0f;
        if ((flags & F_ORIGIN) != 0) {
            x = b.readF32();
            y = b.readF32();
            z = b.readF32();
        }
        return new PlayerHurt(amount, kind, flags, x, y, z, b.readF32(), b.readU8(), b.readVarInt());
    }

    public boolean valid() {
        if (!(amount > 0f) || amount > MAX_AMOUNT || DamageType.byId(kind) == null || (flags & ~(F_PLAYER | F_ORIGIN)) != 0)
            return false;
        if (!(knockback >= 0f) || knockback > MAX_KNOCKBACK || mob < 0 || mob > MobType.values().length || participant < 0)
            return false;
        boolean player = (flags & F_PLAYER) != 0;
        if (player ? mob != 0 : participant != 0)
            return false;
        return (flags & F_ORIGIN) == 0 || Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z)
                && Math.abs(x) <= 1e7f && Math.abs(y) <= 1e7f && Math.abs(z) <= 1e7f;
    }

    /** The hit as the guest's damage path takes it; only after {@link #valid()}. */
    public DamageSource source() {
        boolean origin = (flags & F_ORIGIN) != 0;
        return new DamageSource(DamageType.byId(kind), participant - 1, mob == 0 ? null : MobType.values()[mob - 1],
                origin ? x : Float.NaN, origin ? y : Float.NaN, origin ? z : Float.NaN, knockback, null,
                (flags & F_PLAYER) != 0 ? DamageSource.PLAYER : 0);
    }
}

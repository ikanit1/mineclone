package com.mineclone.net;

import com.mineclone.world.GameMode;
import com.mineclone.world.gen.WorldGenSettings;

/**
 * {@code S_WELCOME}, protocol v8: everything a guest's world is born from. v7
 * sent the seed, the name, a float time, the mode and the spawn; v8 adds the
 * host's generator (GEN-02) — a guest used to assume the 1.0 generator — and
 * the precise clock: the game time as a double, which a float loses within
 * days, and the simulation's tick count.
 *
 * <p>Layout: {@code i64 seed, str name, i64 gameTime bits, i64 worldTicks, u8
 * mode, f32 spawn x, y, z, bytes generator (WorldGenSettings.encode, 16)}.
 */
public record Welcome(long seed, String name, double gameTime, long worldTicks, int mode,
                      float spawnX, float spawnY, float spawnZ, byte[] generator) {
    /** Longer than any name a world list shows. */
    public static final int NAME_LIMIT = 256;

    public static Welcome of(long seed, String name, double gameTime, long worldTicks, int mode,
                             float x, float y, float z, WorldGenSettings generator) {
        return new Welcome(seed, name, gameTime, worldTicks, mode, x, y, z, generator.encode());
    }

    public void write(PacketBuf b) {
        b.i64(seed).str(name).i64(Double.doubleToLongBits(gameTime)).i64(worldTicks).u8(mode)
                .f32(spawnX).f32(spawnY).f32(spawnZ).bytes(generator);
    }

    public static Welcome read(PacketBuf b) {
        return new Welcome(b.readI64(), b.readStr(), Double.longBitsToDouble(b.readI64()), b.readI64(), b.readU8(),
                b.readF32(), b.readF32(), b.readF32(), b.readBytes());
    }

    /** Whether the numbers make a world; a generator this build does not know is {@link #settings()}' null. */
    public boolean valid() {
        return name != null && name.length() <= NAME_LIMIT && Double.isFinite(gameTime) && worldTicks >= 0
                && mode >= 0 && mode < GameMode.values().length
                && Float.isFinite(spawnX) && Float.isFinite(spawnY) && Float.isFinite(spawnZ)
                && Math.abs(spawnX) <= 1e7f && Math.abs(spawnY) <= 1e7f && Math.abs(spawnZ) <= 1e7f;
    }

    /** The host's generator, or null when this build cannot generate it (a newer host within v8). */
    public WorldGenSettings settings() {
        try {
            return WorldGenSettings.decode(generator);
        } catch (RuntimeException unknown) {
            return null;
        }
    }
}

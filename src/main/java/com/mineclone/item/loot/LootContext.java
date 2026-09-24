package com.mineclone.item.loot;

import com.mineclone.data.ResourceId;
import com.mineclone.world.GameMode;
import com.mineclone.world.ItemStack;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

/**
 * Inputs to one loot roll.
 *
 * <p>{@code tool} is the mining tool for block drops and null for mobs and
 * chests. A null session RNG explicitly requests deterministic chest loot: the
 * result is then a pure function of the world seed, the position and the table.
 */
public record LootContext(long worldSeed, int x, int y, int z, ItemStack tool,
                          boolean killedByParticipant, GameMode mode, RandomGenerator sessionRandom) {
    public LootContext { Objects.requireNonNull(mode, "mode"); }

    public static LootContext chest(long seed, int x, int y, int z) {
        return new LootContext(seed, x, y, z, null, false, GameMode.SURVIVAL, null);
    }

    public RandomGenerator randomFor(ResourceId tableId) {
        if (sessionRandom != null) return sessionRandom;
        // Stable text hash, independent of JVM identity, registry/file order and platform charset.
        long text = 0xcbf29ce484222325L;
        String id = tableId.toString();
        for (int i = 0; i < id.length(); i++) text = (text ^ id.charAt(i)) * 0x100000001b3L;
        long hash = mix(worldSeed ^ 0x4c4f4f5443484553L);
        hash = mix(hash ^ x); hash = mix(hash ^ y); hash = mix(hash ^ z);
        return new SplittableRandom(mix(hash ^ text));
    }

    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
}

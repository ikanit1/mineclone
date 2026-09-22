package com.mineclone.world.entity;

import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.World;
import org.joml.Vector3f;

import java.util.List;

/** Morale, cover selection, daily routines and elite modifiers. */
public final class MobTactics {
    public enum Morale { STEADY, CALL_HELP, RETREAT, PANIC }
    public enum Routine { SLEEP, DRINK, GRAZE, HUNT, PATROL }
    public enum Elite { NONE, BURNING, VENOMOUS, FROST }

    private MobTactics() {}

    public static Morale morale(float healthFraction, boolean leaderAlive, int alliesNearby) {
        return morale(healthFraction, leaderAlive, alliesNearby, false);
    }

    /**
     * Мораль с поправкой на бесстрашие.
     *
     * <p><b>Нежить не отступает.</b> Раненый зомби, убегающий от игрока,
     * читается не как тактика, а как поломка: мертвецу нечего терять, и
     * погоня, обрывающаяся на добивании, ломает весь смысл встречи с ним.
     * Звать своих он по-прежнему может — это делает стаю опаснее, а не
     * пугливее.
     *
     * @param fearless вид не отступает ни при каких ранах
     */
    public static Morale morale(float healthFraction, boolean leaderAlive, int alliesNearby,
                                boolean fearless) {
        if (fearless)
            return alliesNearby > 1 ? Morale.CALL_HELP : Morale.STEADY;
        if (healthFraction < 0.18f) return alliesNearby > 1 ? Morale.RETREAT : Morale.PANIC;
        if (!leaderAlive) return alliesNearby > 2 ? Morale.CALL_HELP : Morale.RETREAT;
        return Morale.STEADY;
    }

    /** Кто не отступает: нежить прёт до конца. */
    public static boolean fearless(MobType type) {
        return type.temper == MobType.Temper.HOSTILE;
    }

    public static Routine routine(MobType type, float dayPhase, boolean thirsty, boolean hungry) {
        boolean night = dayPhase > 0.52f && dayPhase < 0.94f;
        if (type.hostile) return night ? Routine.HUNT : Routine.SLEEP;
        if (type == MobType.WOLF && hungry) return Routine.HUNT;
        if (thirsty) return Routine.DRINK;
        return night ? Routine.SLEEP : (type.temper == MobType.Temper.PASSIVE ? Routine.GRAZE : Routine.PATROL);
    }

    public static Elite eliteFor(long seed, int spawnIndex, boolean hostile) {
        if (!hostile) return Elite.NONE;
        long h = seed ^ (spawnIndex * 0x9E3779B97F4A7C15L);
        h ^= h >>> 30; h *= 0xbf58476d1ce4e5b9L; h ^= h >>> 27;
        if ((h & 31L) != 0L) return Elite.NONE;
        return Elite.values()[1 + (int)((h >>> 8) % 3)];
    }

    /** Finds a standable cell hidden from the threat by at least one solid voxel. */
    public static Vector3f findCover(World world, Vector3f from, Vector3f threat, int radius) {
        Vector3f best = null;
        float bestScore = Float.MAX_VALUE;
        int by = (int)Math.floor(from.y);
        for (int dx = -radius; dx <= radius; dx++)
            for (int dz = -radius; dz <= radius; dz++) {
                int x = (int)Math.floor(from.x) + dx, z = (int)Math.floor(from.z) + dz;
                int y = groundNear(world, x, by, z);
                if (y < 0 || !occluded(world, x + 0.5f, y + 1f, z + 0.5f, threat)) continue;
                float score = dx * dx + dz * dz - distanceSq(x + 0.5f, z + 0.5f, threat.x, threat.z) * 0.03f;
                if (score < bestScore) { bestScore = score; best = new Vector3f(x + 0.5f, y, z + 0.5f); }
            }
        return best;
    }

    public static boolean factionHostile(MobType a, MobType b) {
        if (a == b) return false;
        if (a.hostile) return !b.hostile;
        if (a == MobType.WOLF) return b.isPrey();
        return false;
    }

    public static boolean leaderAlive(List<Mob> mobs, Mob member, float radius) {
        float radiusSq = radius * radius;
        for (Mob m : mobs)
            if (!m.dead && m != member && m.type == member.type && m.elite != Elite.NONE
                    && m.position.distanceSquared(member.position) <= radiusSq)
                return true;
        return member.elite != Elite.NONE;
    }

    /** Обновляет мораль и распорядок без квадратичного поиска в каждом Mob. */
    public static void updateGroup(List<Mob> mobs, float dayPhase, float dt) {
        int budget = 8;
        for (Mob m : mobs) {
            if (m.dead) continue;
            m.moraleTimer = Math.max(0f, m.moraleTimer - dt);
            if (m.moraleTimer > 0f)
                continue;
            if (--budget < 0) break;
            int allies = 0;
            for (Mob o : mobs)
                if (o != m && !o.dead && o.type == m.type && o.position.distanceSquared(m.position) < 144f)
                    allies++;
            m.morale = morale(m.health / m.type.maxHealth, true, allies, fearless(m.type));
            boolean thirsty = ((m.animationTime + m.position.x * 0.13f) % 45f) > 40f;
            m.routine = routine(m.type, dayPhase, thirsty, m.hungerTimer <= 0f);
            // Group composition and daily routine cannot change meaningfully
            // every frame. Staggered half-second checks avoid an O(n²) pass
            // at render frequency while keeping reactions immediate enough.
            m.moraleTimer = 0.45f + (Math.abs(m.position.x * 0.037f + m.position.z * 0.019f) % 0.12f);
        }
    }

    /** Смерть элитного лидера на несколько секунд ломает строй ближайшей стаи. */
    public static void leaderFell(List<Mob> mobs, Mob leader) {
        if (leader.elite == Elite.NONE) return;
        for (Mob m : mobs) {
            if (m == leader || m.dead || m.type != leader.type
                    || m.position.distanceSquared(leader.position) > 18f * 18f) continue;
            int allies = 0;
            for (Mob o : mobs)
                if (o != m && !o.dead && o.type == m.type
                        && o.position.distanceSquared(m.position) < 12f * 12f) allies++;
            m.morale = morale(m.health / m.type.maxHealth, false, allies, fearless(m.type));
            m.moraleTimer = 8f;
        }
    }

    private static int groundNear(World w, int x, int y, int z) {
        for (int d = 2; d >= -3; d--) {
            int feet = y + d;
            if (feet > 0 && feet + 1 < Chunk.SIZE_Y && w.getBlock(x, feet - 1, z).solid
                    && !w.getBlock(x, feet, z).solid && !w.getBlock(x, feet + 1, z).solid)
                return feet;
        }
        return -1;
    }

    private static boolean occluded(World w, float x, float y, float z, Vector3f to) {
        float dx = to.x - x, dy = to.y - y, dz = to.z - z;
        int steps = Math.max(1, (int)(Math.sqrt(dx*dx + dy*dy + dz*dz) * 3f));
        for (int i = 1; i < steps; i++) {
            float t = i / (float)steps;
            BlockType b = w.getBlock((int)Math.floor(x + dx*t), (int)Math.floor(y + dy*t), (int)Math.floor(z + dz*t));
            if (b.solid && !b.transparent) return true;
        }
        return false;
    }

    private static float distanceSq(float ax, float az, float bx, float bz) {
        float dx=ax-bx, dz=az-bz; return dx*dx+dz*dz;
    }
}

package com.mineclone.world.entity;

import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.World;
import org.joml.Vector3f;

import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Спавн и деспавн мобов вокруг игрока.
 *
 * Мобы не сохраняются в сейв: после перезахода мир заселяется заново, поэтому
 * спавнеру не нужна персистентность — только капы и правила размещения.
 */
public class MobSpawner {
    /** Как часто Game зовёт trySpawn, секунды. */
    public static final float TICK_INTERVAL = 2f;
    public static final int PEACEFUL_CAP = 12;
    public static final int HOSTILE_CAP = 8;
    /** Кольцо вокруг игрока, в котором ищется место. */
    public static final int MIN_RADIUS = 20;
    public static final int MAX_RADIUS = 48;
    public static final float DESPAWN_RADIUS = 72f;
    /** Зомби спавнится только когда солнце ниже этого порога. */
    public static final float NIGHT_DAYLIGHT = 0.3f;
    /** Зомби не появляется ближе этого расстояния к игроку. */
    public static final float ZOMBIE_MIN_PLAYER_DIST = 16f;
    /** Попыток найти точку за один тик, на каждую категорию. */
    private static final int ATTEMPTS = 6;

    private final Random rnd;

    public MobSpawner(long seed) {
        this.rnd = new Random(seed);
    }

    /** Мирные животные появляются только на траве. */
    public static boolean peacefulSurfaceOk(BlockType surface) {
        return surface == BlockType.GRASS;
    }

    /** Зомби — только ночью. */
    public static boolean zombieTimeOk(float daylight) {
        return daylight < NIGHT_DAYLIGHT;
    }

    /** Убирает мобов дальше DESPAWN_RADIUS от игрока. */
    public void despawnFar(List<Mob> mobs, Vector3f playerPos) {
        float maxSq = DESPAWN_RADIUS * DESPAWN_RADIUS;
        Iterator<Mob> it = mobs.iterator();
        while (it.hasNext()) {
            Mob m = it.next();
            if (m.position.distanceSquared(playerPos) > maxSq)
                it.remove();
        }
    }

    /** Один тик спавна: докидывает мирных до капа и зомби ночью до капа. */
    public void trySpawn(World world, List<Mob> mobs, Vector3f playerPos, float daylight) {
        int peaceful = 0, hostile = 0;
        for (Mob m : mobs) {
            if (m.type.hostile)
                hostile++;
            else
                peaceful++;
        }

        if (peaceful < PEACEFUL_CAP)
            for (int i = 0; i < ATTEMPTS; i++)
                if (tryOne(world, mobs, playerPos, false))
                    break;

        if (hostile < HOSTILE_CAP && zombieTimeOk(daylight))
            for (int i = 0; i < ATTEMPTS; i++)
                if (tryOne(world, mobs, playerPos, true))
                    break;
    }

    private boolean tryOne(World world, List<Mob> mobs, Vector3f playerPos, boolean zombie) {
        double angle = rnd.nextDouble() * Math.PI * 2;
        double dist = MIN_RADIUS + rnd.nextDouble() * (MAX_RADIUS - MIN_RADIUS);
        int x = (int) Math.floor(playerPos.x + Math.cos(angle) * dist);
        int z = (int) Math.floor(playerPos.z + Math.sin(angle) * dist);

        if (zombie && dist < ZOMBIE_MIN_PLAYER_DIST)
            return false;

        int y = findSurfaceY(world, x, z);
        if (y < 0)
            return false;

        BlockType surface = world.getBlock(x, y, z);
        if (!zombie && !peacefulSurfaceOk(surface))
            return false;

        MobType type = zombie ? MobType.ZOMBIE
                : MobType.PEACEFUL[rnd.nextInt(MobType.PEACEFUL.length)];
        mobs.add(new Mob(type, x + 0.5f, y + 1.001f, z + 0.5f, new Random(rnd.nextLong())));
        return true;
    }

    /**
     * Верхний solid-блок с двумя блоками воздуха над ним.
     * Возвращает -1, если чанк не загружен или места нет: генерировать чанк
     * здесь нельзя — это тяжёлая операция посреди кадра.
     */
    private int findSurfaceY(World world, int x, int z) {
        int cx = Math.floorDiv(x, Chunk.SIZE_X);
        int cz = Math.floorDiv(z, Chunk.SIZE_Z);
        if (world.getChunkIfExists(cx, cz) == null)
            return -1;
        for (int y = Chunk.SIZE_Y - 3; y > 0; y--) {
            if (world.getBlock(x, y, z).solid
                    && !world.getBlock(x, y + 1, z).solid
                    && !world.getBlock(x, y + 2, z).solid)
                return y;
        }
        return -1;
    }
}

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
    /** Дикие звери живут своим капом: стая волков не должна вытеснять коров. */
    public static final int WILDLIFE_CAP = 9;
    /** Сколько волков в стае. */
    public static final int PACK_MIN = 2, PACK_MAX = 3;
    /** Кольцо вокруг игрока, в котором ищется место. */
    public static final int MIN_RADIUS = 20;
    public static final int MAX_RADIUS = 48;
    public static final float DESPAWN_RADIUS = 72f;
    /** Зомби спавнится только когда солнце ниже этого порога. */
    public static final float NIGHT_DAYLIGHT = 0.3f;
    /**
     * Максимальная освещённость точки (доля от MAX_LIGHT), при которой ещё
     * заводится враждебный моб. На открытой поверхности это ровно то же
     * условие, что {@link #NIGHT_DAYLIGHT}: там доля неба единица, и порог
     * упирается в время суток. Под землёй доля неба ноль, поэтому правило
     * работает круглосуточно — именно это и делает пещеры опасными.
     */
    public static final float HOSTILE_LIGHT_MAX = 0.3f;
    /** На сколько блоков вверх и вниз от игрока ищется тёмная площадка. */
    private static final int HOSTILE_BAND_DOWN = 28;
    private static final int HOSTILE_BAND_UP = 12;
    /** Попыток найти тёмную площадку по вертикали в одной колонке. */
    private static final int DEPTH_ATTEMPTS = 10;
    /** Зомби не появляется ближе этого расстояния к игроку. */
    public static final float ZOMBIE_MIN_PLAYER_DIST = 16f;
    /** Попыток найти точку за один тик, на каждую категорию. */
    private static final int ATTEMPTS = 6;

    private final Random rnd;
    private final long seed;
    private int spawnIndex;

    public MobSpawner(long seed) {
        this.seed = seed;
        this.rnd = new Random(seed);
    }

    /** Мирные животные появляются только на траве. */
    public static boolean peacefulSurfaceOk(BlockType surface) {
        return surface == BlockType.GRASS || surface == BlockType.DRY_GRASS;
    }

    /** Зомби — только ночью. Частный случай {@link #darkEnough} для открытого неба. */
    public static boolean zombieTimeOk(float daylight) {
        return daylight < NIGHT_DAYLIGHT;
    }

    /**
     * Достаточно ли темно в точке над площадкой, чтобы там завёлся зомби.
     * Свет считается так же, как в шейдере: небо гаснет вместе с солнцем,
     * блочный свет горит всегда — поэтому факел действительно отгоняет мобов.
     */
    public static boolean darkEnough(World world, int x, int y, int z, float daylight) {
        float sky = world.getSkyLight(x, y, z) / (float) Chunk.MAX_LIGHT;
        float block = world.getBlockLightWorld(x, y, z) / (float) Chunk.MAX_LIGHT;
        return Math.max(sky * daylight, block) < HOSTILE_LIGHT_MAX;
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
        int peaceful = 0, hostile = 0, wild = 0;
        for (Mob m : mobs) {
            if (m.type.hostile)
                hostile++;
            else if (m.type.temper == MobType.Temper.PASSIVE)
                peaceful++;
            else
                wild++;
        }

        if (wild < WILDLIFE_CAP)
            for (int i = 0; i < ATTEMPTS; i++)
                if (tryWild(world, mobs, playerPos, daylight))
                    break;

        if (peaceful < PEACEFUL_CAP)
            for (int i = 0; i < ATTEMPTS; i++)
                if (tryOne(world, mobs, playerPos, false, daylight))
                    break;

        // Ночного гейта здесь больше нет: темнота проверяется в самой точке,
        // и днём на поверхности она не выполняется, а в пещере — выполняется.
        if (hostile < HOSTILE_CAP)
            for (int i = 0; i < ATTEMPTS; i++)
                if (tryOne(world, mobs, playerPos, true, daylight))
                    break;
    }

    private boolean tryOne(World world, List<Mob> mobs, Vector3f playerPos, boolean zombie,
                           float daylight) {
        double angle = rnd.nextDouble() * Math.PI * 2;
        double dist = MIN_RADIUS + rnd.nextDouble() * (MAX_RADIUS - MIN_RADIUS);
        int x = (int) Math.floor(playerPos.x + Math.cos(angle) * dist);
        int z = (int) Math.floor(playerPos.z + Math.sin(angle) * dist);

        if (zombie && dist < ZOMBIE_MIN_PLAYER_DIST)
            return false;

        // Мирные животные живут на поверхности, враждебные — везде, где темно.
        int y = zombie
                ? findDarkSpawnY(world, x, z, (int) Math.floor(playerPos.y), daylight)
                : findSurfaceY(world, x, z);
        if (y < 0)
            return false;

        BlockType surface = world.getBlock(x, y, z);
        if (!zombie && !peacefulSurfaceOk(surface))
            return false;

        MobType type = zombie ? hostileFor(y, rnd.nextFloat())
                : MobType.PEACEFUL[rnd.nextInt(MobType.PEACEFUL.length)];
        mobs.add(create(type, x + 0.5f, y + 1.001f, z + 0.5f));
        return true;
    }

    /**
     * Дикий зверь по правилам своего биома.
     *
     * Вид выбирается по месту, а не наугад: волк на пляже пустыни и птица в
     * голой тундре выглядели бы ошибкой спавнера, а не природой.
     */
    private boolean tryWild(World world, List<Mob> mobs, Vector3f playerPos, float daylight) {
        double angle = rnd.nextDouble() * Math.PI * 2;
        double dist = MIN_RADIUS + rnd.nextDouble() * (MAX_RADIUS - MIN_RADIUS);
        int x = (int) Math.floor(playerPos.x + Math.cos(angle) * dist);
        int z = (int) Math.floor(playerPos.z + Math.sin(angle) * dist);
        int y = findSurfaceY(world, x, z);
        if (y < 0)
            return false;
        MobType type = wildTypeFor(world.biomes.biomeAt(x, z), world.getBlock(x, y, z), daylight,
                rnd.nextFloat());
        if (type == null)
            return false;
        int count = type == MobType.WOLF ? PACK_MIN + rnd.nextInt(PACK_MAX - PACK_MIN + 1) : 1;
        for (int i = 0; i < count; i++) {
            float ox = i == 0 ? 0f : (rnd.nextFloat() - 0.5f) * 3f;
            float oz = i == 0 ? 0f : (rnd.nextFloat() - 0.5f) * 3f;
            mobs.add(create(type, x + 0.5f + ox, y + 1.001f, z + 0.5f + oz));
        }
        return true;
    }

    /** Ниже этой высоты «под землёй»: там водится другая нежить. */
    public static final int UNDERGROUND_Y = com.mineclone.world.World.SEA_LEVEL - 6;

    /**
     * Кто из нежити появится на этой глубине.
     *
     * Пауки и крипер водятся и наверху, но пещеры — их дом: под землёй доля
     * зомби падает, а пауков растёт. Скелет встречается ровно, потому что
     * его стрельба одинаково опасна и в коридоре, и в поле.
     */
    static MobType hostileFor(int y, float roll) {
        boolean deep = y < UNDERGROUND_Y;
        if (roll < (deep ? 0.30f : 0.45f))
            return MobType.ZOMBIE;
        if (roll < (deep ? 0.55f : 0.70f))
            return MobType.SKELETON;
        if (roll < (deep ? 0.85f : 0.88f))
            return MobType.SPIDER;
        return MobType.CREEPER;
    }

    private Mob create(MobType type, float x, float y, float z) {
        Mob mob = new Mob(type, x, y, z, new Random(rnd.nextLong()));
        mob.elite = MobTactics.eliteFor(seed, spawnIndex++, type.hostile);
        return mob;
    }

    /**
     * Какой дикий зверь заводится на этой поверхности. Чистая функция —
     * правило места проверяется тестом.
     *
     * @param roll случайное 0..1 — выбор между подходящими видами
     * @return вид или null, если здесь дикому зверю не место
     */
    public static MobType wildTypeFor(com.mineclone.world.Biome biome, BlockType surface,
                                      float daylight, float roll) {
        boolean day = daylight > NIGHT_DAYLIGHT;
        switch (biome) {
            case FOREST, TAIGA, SWAMP -> {
                if (surface == BlockType.LEAVES)
                    return day ? MobType.BIRD : null;
                if (!surface.isSoil())
                    return null;
                if (roll < 0.35f)
                    return MobType.WOLF;
                return day && roll < 0.7f ? MobType.BIRD : MobType.RABBIT;
            }
            case PLAINS, SAVANNA -> {
                if (!surface.isSoil())
                    return null;
                return day && roll < 0.3f ? MobType.BIRD : MobType.RABBIT;
            }
            case TUNDRA, ALPINE -> {
                if (surface != BlockType.SNOWY_GRASS && surface != BlockType.SNOW_LAYER)
                    return null;
                return roll < 0.4f ? MobType.WOLF : MobType.RABBIT;
            }
            case DESERT, BADLANDS -> {
                return (surface == BlockType.SAND || surface == BlockType.RED_SAND) && roll < 0.5f ? MobType.RABBIT : null;
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * Верхний solid-блок с двумя блоками воздуха над ним.
     * Возвращает -1, если чанк не загружен или места нет: генерировать чанк
     * здесь нельзя — это тяжёлая операция посреди кадра.
     */
    /**
     * Тёмная площадка в вертикальной полосе вокруг игрока.
     *
     * Сканировать сверху, как {@link #findSurfaceY}, нельзя: он всегда
     * возвращает внешнюю поверхность, и пещеры остаются стерильно безопасными.
     * Поэтому пробуем случайные высоты в полосе и спускаемся до ближайшего пола.
     */
    private int findDarkSpawnY(World world, int x, int z, int playerY, float daylight) {
        int cx = Math.floorDiv(x, Chunk.SIZE_X);
        int cz = Math.floorDiv(z, Chunk.SIZE_Z);
        if (world.getChunkIfExists(cx, cz) == null)
            return -1;
        int lo = Math.max(2, playerY - HOSTILE_BAND_DOWN);
        int hi = Math.min(Chunk.SIZE_Y - 3, playerY + HOSTILE_BAND_UP);
        if (hi <= lo)
            return -1;
        for (int attempt = 0; attempt < DEPTH_ATTEMPTS; attempt++) {
            int y = lo + rnd.nextInt(hi - lo + 1);
            while (y > lo && !world.getBlock(x, y, z).solid)
                y--;
            if (!world.getBlock(x, y, z).solid)
                continue;
            // Два блока чистого воздуха над площадкой: в воде и в лаве не спавним.
            if (world.getBlock(x, y + 1, z) != BlockType.AIR
                    || world.getBlock(x, y + 2, z) != BlockType.AIR)
                continue;
            if (darkEnough(world, x, y + 1, z, daylight))
                return y;
        }
        return -1;
    }

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

package com.mineclone.world;

import org.joml.Vector3f;

import java.util.Random;

/**
 * Случайные тики блоков — механизм, из-за которого мир меняется сам.
 *
 * Каждый тик в каждом чанке рядом с игроком выбирается несколько случайных
 * позиций, и к ним применяются правила: трава расползается по голой земле,
 * зарастает под накрытым блоком, кактус тянется вверх, оторванная листва
 * осыпается. Ровно на этом же механизме дальше живут огонь, таяние снега и
 * печь — поэтому он вынесен отдельно и ничего не знает про игрока.
 *
 * Тикаются только чанки в радиусе {@link #RADIUS_CHUNKS}: за ним изменений
 * всё равно никто не увидит, а стоимость растёт линейно по числу чанков.
 */
public final class BlockTicker {

    /** Как часто {@code Game} зовёт {@link #tick}, секунды. */
    public static final float TICK_INTERVAL = 0.25f;
    /** Сколько случайных позиций проверяется в одном чанке за тик. */
    public static final int SAMPLES_PER_CHUNK = 3;
    /** Радиус тикающихся чанков вокруг игрока. */
    public static final int RADIUS_CHUNKS = 4;

    /** Минимальный свет, при котором трава расползается и не гибнет. */
    public static final int GRASS_LIGHT_MIN = 9;
    /** Предельная высота кактуса, считая от земли. */
    public static final int CACTUS_MAX_HEIGHT = 3;
    /** На каком расстоянии листва ещё держится за ствол. */
    public static final int LEAF_SUPPORT_RANGE = 4;
    /** Насколько сильными должны быть осадки, чтобы тушить открытый огонь. */
    public static final float RAIN_EXTINGUISH = 0.35f;
    /** Осадки слабее этого снега не наметают. */
    public static final float SNOWFALL_MIN = 0.25f;
    /** Предельная толщина покрова: 7 — почти полный блок. */
    public static final int SNOW_MAX_LEVEL = 7;
    private static final int[][] CARDINAL = {
            { 1, 0, 0 }, { -1, 0, 0 }, { 0, 1, 0 },
            { 0, -1, 0 }, { 0, 0, 1 }, { 0, 0, -1 }
    };

    private final Random rnd;

    public BlockTicker(long seed) {
        this.rnd = new Random(seed ^ 0x7F4A7C15L);
    }

    public void tick(World world, Vector3f playerPos, float weatherStrength) {
        boolean raining = weatherStrength >= RAIN_EXTINGUISH;
        boolean snowfall = weatherStrength >= SNOWFALL_MIN;
        int pcx = (int) Math.floor(playerPos.x / Chunk.SIZE_X);
        int pcz = (int) Math.floor(playerPos.z / Chunk.SIZE_Z);
        for (int cx = pcx - RADIUS_CHUNKS; cx <= pcx + RADIUS_CHUNKS; cx++)
            for (int cz = pcz - RADIUS_CHUNKS; cz <= pcz + RADIUS_CHUNKS; cz++) {
                Chunk c = world.getChunkIfExists(cx, cz);
                if (c == null)
                    continue;
                for (int i = 0; i < SAMPLES_PER_CHUNK; i++) {
                    int x = rnd.nextInt(Chunk.SIZE_X);
                    int y = rnd.nextInt(Chunk.SIZE_Y);
                    int z = rnd.nextInt(Chunk.SIZE_Z);
                    apply(world, cx * Chunk.SIZE_X + x, y, cz * Chunk.SIZE_Z + z,
                            raining, snowfall, weatherStrength);
                }
            }
    }

    /** Одно правило для одной позиции. Публичный, чтобы тесты били точечно. */
    public void apply(World world, int wx, int wy, int wz) {
        apply(world, wx, wy, wz, false, false);
    }

    public void apply(World world, int wx, int wy, int wz, boolean raining) {
        apply(world, wx, wy, wz, raining, false);
    }

    public void apply(World world, int wx, int wy, int wz, boolean raining, boolean snowfall) {
        apply(world, wx, wy, wz, raining, snowfall, snowfall ? 1f : (raining ? 0.5f : 0f));
    }

    private void apply(World world, int wx, int wy, int wz, boolean raining, boolean snowfall,
                       float weatherStrength) {
        switch (world.getBlock(wx, wy, wz)) {
            case AIR -> fallSnow(world, wx, wy, wz, snowfall);
            case SNOW_LAYER -> tickSnow(world, wx, wy, wz, snowfall);
            case DIRT -> spreadGrass(world, wx, wy, wz);
            case MUD -> dryMud(world, wx, wy, wz, raining);
            case GRASS, SNOWY_GRASS -> smotherGrass(world, wx, wy, wz);
            case CACTUS -> growCactus(world, wx, wy, wz);
            case LEAVES -> decayLeaves(world, wx, wy, wz, weatherStrength);
            case FIRE -> tickFire(world, wx, wy, wz, raining);
            case WATER -> freezeWater(world, wx, wy, wz);
            case THIN_ICE -> matureThinIce(world, wx, wy, wz);
            case ICE -> meltIce(world, wx, wy, wz);
            case STONE, COBBLE -> spreadMoss(world, wx, wy, wz);
            case MOSSY_COBBLE -> decayMoss(world, wx, wy, wz);
            case LAVA -> tickLava(world, wx, wy, wz);
            case ASH -> washAsh(world, wx, wy, wz, raining);
            default -> { }
        }
    }

    // ---- лёд ---------------------------------------------------------------

    /**
     * Открытая стоячая вода в тундре схватывается льдом. Только источник и
     * только под небом: течение не замерзает, а в пещере не так холодно.
     * Факел рядом не даёт воде замёрзнуть — тот же порог, что топит снег.
     */
    private void freezeWater(World world, int wx, int wy, int wz) {
        if (world.getBlock(wx, wy + 1, wz) != BlockType.AIR)
            return;
        if (world.biomes.biomeAt(wx, wz) != Biome.TUNDRA)
            return;
        if (world.getBlockLightWorld(wx, wy, wz) >= GRASS_LIGHT_MIN)
            return;
        if (!openToSky(world, wx, wy, wz) || rnd.nextInt(6) != 0)
            return;
        world.setBlock(wx, wy, wz, BlockType.THIN_ICE, (byte) 0);
    }

    private void matureThinIce(World world, int wx, int wy, int wz) {
        int age = world.getBlockMeta(wx, wy, wz) & 7;
        int light = maxNeighbourLight(world, wx, wy, wz);
        if (light >= GRASS_LIGHT_MIN + 1 || world.biomes.biomeAt(wx, wz) != Biome.TUNDRA) {
            if (rnd.nextInt(3) == 0) world.setBlock(wx, wy, wz, BlockType.WATER);
            return;
        }
        if (age >= 6) world.setBlock(wx, wy, wz, BlockType.ICE);
        else if (rnd.nextInt(2) == 0) world.setBlock(wx, wy, wz, BlockType.THIN_ICE, (byte)(age + 1));
    }

    /**
     * Лёд тает от огня рядом и, медленно, в тёплом биоме.
     *
     * Свет берётся у соседних клеток, а не у самого льда: блок непрозрачный,
     * и в его собственной клетке блочного света нет никогда.
     */
    private void meltIce(World world, int wx, int wy, int wz) {
        int light = maxNeighbourLight(world, wx, wy, wz);
        boolean lit = light >= GRASS_LIGHT_MIN + 1;
        boolean warm = world.biomes.biomeAt(wx, wz) != Biome.TUNDRA;
        if ((lit && rnd.nextInt(3) == 0) || (warm && rnd.nextInt(10) == 0))
            world.setBlock(wx, wy, wz, BlockType.WATER);
    }

    // ---- снег --------------------------------------------------------------

    /** Идёт ли снег именно здесь: осадки есть и биом холодный. */
    private static boolean snowingAt(World world, int wx, int wz, boolean snowfall) {
        return snowfall && world.biomes.biomeAt(wx, wz) == Biome.TUNDRA;
    }

    /**
     * Наметает первый слой снега на открытую поверхность.
     *
     * Ветка висит на AIR — самом частом исходе выборки, поэтому первым стоит
     * самый дешёвый отказ: без осадков не делаем вообще ничего.
     */
    private void fallSnow(World world, int wx, int wy, int wz, boolean snowfall) {
        if (!snowfall)
            return;
        if (!world.getBlock(wx, wy - 1, wz).solid)
            return;
        if (!openToSky(world, wx, wy, wz))
            return;
        if (!snowingAt(world, wx, wz, snowfall))
            return;
        world.setBlock(wx, wy, wz, BlockType.SNOW_LAYER, (byte) 0);
    }

    /**
     * Покров растёт в снегопад и тает, когда снегопад кончился или стало
     * тепло. Таяние идёт по слоям, а не разом: сугроб должен оседать.
     */
    private void tickSnow(World world, int wx, int wy, int wz, boolean snowfall) {
        int level = world.getBlockMeta(wx, wy, wz) & 0x7;
        boolean snowing = snowingAt(world, wx, wz, snowfall);
        boolean lit = world.getBlockLightWorld(wx, wy, wz) >= GRASS_LIGHT_MIN;

        if (snowing && level < SNOW_MAX_LEVEL && openToSky(world, wx, wy, wz)) {
            world.setSnowLevel(wx, wy, wz, level + 1);
            return;
        }
        // Снег топит блочный свет, а не солнце: иначе покров в тундре
        // исчезал бы каждый полдень и погода не оставляла бы следа.
        // Тёплый биом съедает покров всегда — туда снег попадает только
        // из рук игрока.
        boolean melting = lit || world.biomes.biomeAt(wx, wz) != Biome.TUNDRA;
        if (!melting || rnd.nextInt(3) != 0)
            return;
        if (level == 0)
            world.setBlock(wx, wy, wz, BlockType.AIR);
        else
            world.setSnowLevel(wx, wy, wz, level - 1);
    }

    // ---- огонь ------------------------------------------------------------

    /**
     * Огонь живёт, пока рядом есть чему гореть. Он гаснет от воды и от дождя
     * под открытым небом, расползается на соседние клетки у горючего и
     * постепенно съедает само топливо, перебираясь на его место.
     */
    private void tickFire(World world, int wx, int wy, int wz, boolean raining) {
        if (touchesWater(world, wx, wy, wz) || (raining && openToSky(world, wx, wy, wz))) {
            world.setBlock(wx, wy, wz, BlockType.AIR);
            return;
        }
        BlockType fuel = nearestFuel(world, wx, wy, wz);
        if (fuel == null) {
            // Без топлива костёр держится недолго. Иначе огонь на камне
            // горит вечно и превращается в бесплатный вечный факел.
            if (rnd.nextInt(4) == 0)
                world.setBlock(wx, wy, wz, BlockType.AIR);
            return;
        }
        int age = (world.getBlockMeta(wx, wy, wz) & 0xFF) + 1;
        int lifetime = burnTicks(fuel);
        if (age >= lifetime) {
            world.setBlock(wx, wy, wz, BlockType.AIR);
            return;
        }
        world.setBlock(wx, wy, wz, BlockType.FIRE, (byte) age);
        if (rnd.nextInt(3) == 0)
            spreadFire(world, wx, wy, wz);
        if (rnd.nextInt(Math.max(3, lifetime / 3)) == 0)
            consumeFuel(world, wx, wy, wz);
    }

    /** Зажигает пустую клетку рядом, если ей есть от чего заняться. */
    private void spreadFire(World world, int wx, int wy, int wz) {
        int dx = rnd.nextInt(3) - 1, dy = rnd.nextInt(3) - 1, dz = rnd.nextInt(3) - 1;
        if (dx == 0 && dy == 0 && dz == 0)
            return;
        int x = wx + dx, y = wy + dy, z = wz + dz;
        if (world.getBlock(x, y, z) != BlockType.AIR)
            return;
        if (!flammableAround(world, x, y, z))
            return;
        world.setBlock(x, y, z, BlockType.FIRE, (byte) 0);
    }

    /**
     * Сжигает один горючий блок по соседству. Огонь с заметной вероятностью
     * перебирается на его место — иначе пожар выедает дырку и гаснет, вместо
     * того чтобы идти по дереву.
     */
    private void consumeFuel(World world, int wx, int wy, int wz) {
        for (int attempt = 0; attempt < 6; attempt++) {
            int dx = rnd.nextInt(3) - 1, dy = rnd.nextInt(3) - 1, dz = rnd.nextInt(3) - 1;
            if (dx == 0 && dy == 0 && dz == 0)
                continue;
            int x = wx + dx, y = wy + dy, z = wz + dz;
            if (!world.getBlock(x, y, z).isFlammable())
                continue;
            BlockType fuel = world.getBlock(x, y, z);
            BlockType residue = fuel == BlockType.LEAVES || fuel == BlockType.ROPE
                    ? BlockType.AIR : BlockType.ASH;
            world.setBlock(x, y, z, rnd.nextInt(3) == 0 ? BlockType.FIRE : residue, (byte) 0);
            return;
        }
    }

    /** Different fuels burn for visibly different random-tick lifetimes. */
    public static int burnTicks(BlockType fuel) {
        return switch (fuel) {
            case LEAVES -> 4;
            case ROPE, JOURNAL -> 6;
            case PLANKS -> 12;
            case WOOD -> 20;
            default -> 3;
        };
    }

    private static BlockType nearestFuel(World world, int wx, int wy, int wz) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++) {
                    BlockType b = world.getBlock(wx + dx, wy + dy, wz + dz);
                    if (b.isFlammable()) return b;
                }
        return null;
    }

    private static boolean flammableAround(World world, int wx, int wy, int wz) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++)
                    if (world.getBlock(wx + dx, wy + dy, wz + dz).isFlammable())
                        return true;
        return false;
    }

    private static boolean touchesWater(World world, int wx, int wy, int wz) {
        for (int[] o : CARDINAL) {
            BlockType b = world.getBlock(wx + o[0], wy + o[1], wz + o[2]);
            if (b == BlockType.WATER || b == BlockType.WATER_FLOW)
                return true;
        }
        return false;
    }

    private static boolean openToSky(World world, int wx, int wy, int wz) {
        for (int y = wy + 1; y < Chunk.SIZE_Y; y++)
            if (world.getBlock(wx, y, wz).solid)
                return false;
        return true;
    }

    /**
     * Земля зарастает от соседнего дерна. Дёрн берётся у соседа, а не у биома:
     * так снежный дёрн расползается снегом, а обычный — травой, и правило не
     * знает про биомы вообще.
     */
    private void spreadGrass(World world, int wx, int wy, int wz) {
        if (!openToLight(world, wx, wy, wz))
            return;
        if (lightAt(world, wx, wy + 1, wz) < GRASS_LIGHT_MIN)
            return;
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++) {
                    BlockType n = world.getBlock(wx + dx, wy + dy, wz + dz);
                    if (n == BlockType.GRASS || n == BlockType.SNOWY_GRASS) {
                        // Сосед должен сам быть открыт небу, иначе трава
                        // «протекает» под навесы из-под соседнего склона.
                        if (openToLight(world, wx + dx, wy + dy, wz + dz)) {
                            world.setBlock(wx, wy, wz, n);
                            return;
                        }
                    }
                }
    }

    /** Накрытый дёрн вырождается в землю. */
    private void smotherGrass(World world, int wx, int wy, int wz) {
        if (openToLight(world, wx, wy, wz))
            return;
        world.setBlock(wx, wy, wz, BlockType.DIRT);
    }

    /** Кактус тянется вверх, пока не упрётся в потолок или в свой предел. */
    private void growCactus(World world, int wx, int wy, int wz) {
        if (world.getBlock(wx, wy + 1, wz) != BlockType.AIR)
            return;
        int height = 1;
        while (height <= CACTUS_MAX_HEIGHT
                && world.getBlock(wx, wy - height, wz) == BlockType.CACTUS)
            height++;
        if (height >= CACTUS_MAX_HEIGHT)
            return;
        // Растёт редко: иначе пустыня зарастает частоколом за минуту.
        if (rnd.nextInt(8) != 0)
            return;
        world.setBlock(wx, wy + 1, wz, BlockType.CACTUS);
    }

    /**
     * Листва без ствола поблизости осыпается. Без этого срубленное дерево
     * оставляет висеть в воздухе крону — самая заметная дыра в правдоподобии
     * из всех дешёвых.
     */
    private void decayLeaves(World world, int wx, int wy, int wz, float weatherStrength) {
        int r = LEAF_SUPPORT_RANGE;
        for (int dx = -r; dx <= r; dx++)
            for (int dy = -r; dy <= r; dy++)
                for (int dz = -r; dz <= r; dz++)
                    if (world.getBlock(wx + dx, wy + dy, wz + dz) == BlockType.WOOD) {
                        // A severe blizzard strips a few exposed leaves even from a live tree.
                        if (weatherStrength > 0.85f && openToSky(world, wx, wy, wz)
                                && rnd.nextInt(48) == 0)
                            world.setBlock(wx, wy, wz, BlockType.AIR);
                        return;
                    }
        world.setBlock(wx, wy, wz, BlockType.AIR);
    }

    private void dryMud(World world, int wx, int wy, int wz, boolean raining) {
        if (!raining && !touchesWater(world, wx, wy, wz) && rnd.nextInt(12) == 0)
            world.setBlock(wx, wy, wz, BlockType.DIRT);
    }

    private void spreadMoss(World world, int wx, int wy, int wz) {
        if (world.getSkyLight(wx, wy, wz) > 9 || !touchesWater(world, wx, wy, wz) || rnd.nextInt(8) != 0)
            return;
        world.setBlock(wx, wy, wz, BlockType.MOSSY_COBBLE);
    }

    private void decayMoss(World world, int wx, int wy, int wz) {
        if (!touchesWater(world, wx, wy, wz) && world.getSkyLight(wx, wy, wz) > 11 && rnd.nextInt(12) == 0)
            world.setBlock(wx, wy, wz, BlockType.COBBLE);
    }

    private void washAsh(World world, int wx, int wy, int wz, boolean raining) {
        if ((raining && openToSky(world, wx, wy, wz)) || touchesWater(world, wx, wy, wz))
            world.setBlock(wx, wy, wz, BlockType.AIR);
    }

    private void tickLava(World world, int wx, int wy, int wz) {
        for (int[] o : CARDINAL)
            if (FluidThermodynamics.isWater(world.getBlock(wx + o[0], wy + o[1], wz + o[2]))) {
                FluidThermodynamics.react(world, wx, wy, wz, wx + o[0], wy + o[1], wz + o[2]);
                return;
            }
        // One horizontal step roughly every five random ticks: lava is viscous.
        if (rnd.nextInt((int)FluidThermodynamics.viscosity(BlockType.LAVA)) != 0)
            return;
        if (world.getBlock(wx, wy - 1, wz) == BlockType.AIR) {
            world.setBlock(wx, wy - 1, wz, BlockType.LAVA, (byte)Math.min(7, (world.getBlockMeta(wx, wy, wz)&7)+1));
            return;
        }
        int[] side = CARDINAL[rnd.nextInt(4)];
        if (world.getBlock(wx + side[0], wy, wz + side[2]) == BlockType.AIR)
            world.setBlock(wx + side[0], wy, wz + side[2], BlockType.LAVA,
                    (byte)Math.min(7, (world.getBlockMeta(wx, wy, wz)&7)+1));
    }

    private static int maxNeighbourLight(World world, int wx, int wy, int wz) {
        int light = 0;
        for (int[] o : CARDINAL) light = Math.max(light, world.getBlockLightWorld(wx+o[0], wy+o[1], wz+o[2]));
        return light;
    }

    /** Есть ли над блоком место, куда может попасть свет. */
    private static boolean openToLight(World world, int wx, int wy, int wz) {
        BlockType above = world.getBlock(wx, wy + 1, wz);
        return above == BlockType.AIR || above.transparent || above.cutout;
    }

    private static int lightAt(World world, int wx, int wy, int wz) {
        return Math.max(world.getSkyLight(wx, wy, wz), world.getBlockLightWorld(wx, wy, wz));
    }
}

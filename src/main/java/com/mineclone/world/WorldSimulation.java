package com.mineclone.world;

import org.joml.Vector3f;

/**
 * Мировой тик: печи, случайные тики блоков, вода, лава и обвалы.
 *
 * <p>Вынут из {@code Game.updateActiveWorld}, где жил вперемешку с частицами,
 * следами, звуком и обратной связью камеры. Причина простая: у выделенного
 * сервера нет ни частиц, ни звука, ни камеры, а мир тикать надо — и тикать
 * <b>тем же самым кодом</b>, иначе сервер и игра разойдутся на первой же луже.
 * Всё, что относится к картинке, осталось в {@code Game}.
 *
 * <p>Ни одного вызова GL и ни одной ссылки на рендер: класс живёт в
 * {@code world} рядом с тем, чем он управляет, и проверяется обычным тестом.
 *
 * <p>Таймеры у каждого занятия свои, и это не случайность: вода тикает
 * вчетверо чаще лавы, а печи — чаще воды. Свести их к одному шагу значило бы
 * или гонять лаву впустую, или замедлить воду.
 */
public final class WorldSimulation {

    /** Как часто проверяется сток воды. */
    public static final float WATER_TICK = 0.25f;
    /** Как часто тикают печи. */
    public static final float FURNACE_TICK = 0.25f;
    /** В каком радиусе чанков вокруг игрока работают печи. */
    public static final int FURNACE_RADIUS = 4;

    private final BlockTicker blockTicker;

    private float blockTimer = BlockTicker.TICK_INTERVAL;
    private float waterTimer = WATER_TICK;
    private float lavaTimer = LavaSimulator.TICK_INTERVAL;
    private float furnaceTimer = FURNACE_TICK;

    /** Сколько наносекунд заняло каждое занятие: их читает профилировщик кадра. */
    private long blockNanos;
    private long waterNanos;
    private long lavaNanos;
    private long fallingNanos;

    public WorldSimulation(long seed) {
        this.blockTicker = new BlockTicker(seed);
    }

    /**
     * Прокрутить мир на {@code dt}.
     *
     * <p>Порядок занятий тот же, в каком они шли в кадре игры, и он значим:
     * пересвет идёт после воды и тиков блоков, потому что именно они пачкают
     * чанки, а обвалы — после пересвета, потому что снятая опора это уже
     * следствие.
     *
     * @param around        вокруг кого крутится мир: тики блоков и печи идут в
     *                      радиусе от этой точки
     * @param precipitation осадки фронта, а не местные: игрок может стоять в
     *                      пустыне, а снег обязан ложиться на соседнюю тундру
     * @param simulate      считаем ли мы мир сами. У участника сети — нет:
     *                      вода, лава, обвалы, случайные тики и печи живут
     *                      только у хозяина. Иначе две стороны разойдутся на
     *                      первой же луже, а выросшая у участника трава уедет
     *                      хозяину как просьба поставить блок
     */
    public void update(World world, float dt, Vector3f around, float precipitation,
            boolean simulate) {
        if (world == null)
            return;
        blockNanos = waterNanos = lavaNanos = fallingNanos = 0;

        furnaceTimer -= dt;
        if (due(furnaceTimer)) {
            furnaceTimer = FURNACE_TICK;
            if (simulate)
                tickFurnaces(world, around);
        }

        blockTimer -= dt;
        if (due(blockTimer)) {
            blockTimer = BlockTicker.TICK_INTERVAL;
            if (simulate) {
                long start = System.nanoTime();
                blockTicker.tick(world, around, precipitation);
                blockNanos = System.nanoTime() - start;
            }
        }

        waterTimer -= dt;
        if (due(waterTimer)) {
            waterTimer = WATER_TICK;
            if (simulate) {
                long start = System.nanoTime();
                WaterSimulator.tick(world);
                waterNanos = System.nanoTime() - start;
            }
        }

        lavaTimer -= dt;
        if (due(lavaTimer)) {
            lavaTimer = LavaSimulator.TICK_INTERVAL;
            if (simulate) {
                long start = System.nanoTime();
                LavaSimulator.tick(world);
                lavaNanos = System.nanoTime() - start;
            }
        }

        // Правки света от воды и блоков сливаются по чанку: текущая вода может
        // тронуть сотни ячеек за тик, а пересвет должен дойти один.
        world.processPendingSkyRelights(1);

        if (simulate) {
            long start = System.nanoTime();
            world.falling.update(dt);
            fallingNanos = System.nanoTime() - start;
        }
    }

    private static boolean due(float timer) {
        return timer <= 0f;
    }

    /**
     * Печи в радиусе {@link #FURNACE_RADIUS} чанков.
     *
     * <p>Чанк помечается изменённым только когда поменялись слоты: иначе
     * горящая печь переписывала бы свой файл четыре раза в секунду всё время
     * работы.
     */
    private void tickFurnaces(World world, Vector3f around) {
        if (around == null)
            return;
        int pcx = (int) Math.floor(around.x / Chunk.SIZE_X);
        int pcz = (int) Math.floor(around.z / Chunk.SIZE_Z);
        for (int cx = pcx - FURNACE_RADIUS; cx <= pcx + FURNACE_RADIUS; cx++)
            for (int cz = pcz - FURNACE_RADIUS; cz <= pcz + FURNACE_RADIUS; cz++) {
                Chunk c = world.getChunkIfExists(cx, cz);
                if (c == null || c.furnaces().isEmpty())
                    continue;
                for (Furnace f : c.furnaces().values())
                    if (f.tick(FURNACE_TICK))
                        c.modified = true;
            }
    }

    public BlockTicker blockTicker() {
        return blockTicker;
    }

    public long blockNanos() {
        return blockNanos;
    }

    public long waterNanos() {
        return waterNanos;
    }

    public long lavaNanos() {
        return lavaNanos;
    }

    public long fallingNanos() {
        return fallingNanos;
    }
}

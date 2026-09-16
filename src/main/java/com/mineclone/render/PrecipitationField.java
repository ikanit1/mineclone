package com.mineclone.render;

import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.World;

/**
 * Карта крыш вокруг игрока: для каждой колонны — высота, ниже которой осадки
 * не падают.
 *
 * Снежинки живут в шейдере и о мире ничего не знают; без этой карты снег шёл
 * бы сквозь крышу дома и сыпался в пещеру. Высота берётся по первому сверху
 * блоку, который останавливает осадки: твёрдому или воде.
 *
 * Сканирование идёт не от неба, а в полосе вокруг камеры: частицы всё равно
 * живут в коробке высотой в пару десятков блоков, и колонна, перекрытая
 * выше этой коробки, просто получает её верх. Чанк достаётся один раз на
 * колонну, дальше — прямой доступ к массиву.
 *
 * Без GL: {@link PrecipitationRenderer} только заливает массив в текстуру.
 */
public final class PrecipitationField {

    /** Сторона карты в блоках. Кратна двум — для текстуры без выравнивания. */
    public static final int SIZE = 64;

    private final float[] heights = new float[SIZE * SIZE];
    private int originX, originZ;
    private boolean valid;

    /**
     * Пересобирает карту с центром в колонне (centerX, centerZ).
     *
     * @param minY низ полосы сканирования (блоки ниже неё считаются полом)
     * @param maxY верх полосы: колонна, перекрытая выше, получает maxY
     */
    public void rebuild(World world, int centerX, int centerZ, int minY, int maxY) {
        originX = centerX - SIZE / 2;
        originZ = centerZ - SIZE / 2;
        int lo = Math.max(0, minY);
        int hi = Math.min(Chunk.SIZE_Y - 1, maxY);
        for (int dz = 0; dz < SIZE; dz++) {
            int wz = originZ + dz;
            for (int dx = 0; dx < SIZE; dx++) {
                int wx = originX + dx;
                Chunk c = world.getChunkIfExists(Math.floorDiv(wx, Chunk.SIZE_X),
                        Math.floorDiv(wz, Chunk.SIZE_Z));
                heights[dz * SIZE + dx] = c == null ? hi + 1 : columnTop(c,
                        Math.floorMod(wx, Chunk.SIZE_X), Math.floorMod(wz, Chunk.SIZE_Z), lo, hi);
            }
        }
        valid = true;
    }

    /**
     * Верх первого останавливающего блока в колонне. Над открытой полосой —
     * её низ: частица долетит до пола коробки.
     *
     * Перекрытие ВЫШЕ полосы тоже считается: иначе под скалой в сорок блоков
     * снег шёл бы, пока камера внизу.
     */
    private static float columnTop(Chunk c, int x, int z, int lo, int hi) {
        for (int y = Chunk.SIZE_Y - 1; y > hi; y--)
            if (stops(c.get(x, y, z)))
                return hi + 1;
        for (int y = hi; y >= lo; y--)
            if (stops(c.get(x, y, z)))
                return y + 1;
        return lo;
    }

    /** Останавливает ли блок осадки. */
    public static boolean stops(BlockType b) {
        return b.solid || b == BlockType.WATER || b == BlockType.WATER_FLOW;
    }

    /** Высота крыши в мировой колонне; вне карты — очень высоко (осадков нет). */
    public float heightAt(int wx, int wz) {
        int dx = wx - originX, dz = wz - originZ;
        if (!valid || dx < 0 || dz < 0 || dx >= SIZE || dz >= SIZE)
            return Float.MAX_VALUE;
        return heights[dz * SIZE + dx];
    }

    public float[] heights() { return heights; }
    public int originX() { return originX; }
    public int originZ() { return originZ; }
    public boolean isValid() { return valid; }

    public void invalidate() {
        valid = false;
    }

    /** Нужна ли пересборка: карта устарела или игрок ушёл от её центра. */
    public boolean needsRebuild(int centerX, int centerZ) {
        if (!valid)
            return true;
        return Math.abs(centerX - (originX + SIZE / 2)) >= 6
                || Math.abs(centerZ - (originZ + SIZE / 2)) >= 6;
    }
}

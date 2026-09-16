package com.mineclone.audio;

import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.World;

/**
 * Насколько замкнуто пространство вокруг головы.
 *
 * Шесть лучей по осям; доля упёршихся в блок и средняя длина свободного
 * пробега дают оценку «поле — комната — пещера». По ней подбирается пресет
 * реверберации.
 *
 * Шесть лучей, а не двадцать: разница между полем и пещерой видна уже по
 * осям, а каждый луч — это обращения к чанкам, и замер идёт в кадре.
 */
public final class AcousticProbe {

    /** Дальше этого луч считается ушедшим в открытое пространство. */
    public static final int MAX_DISTANCE = 24;

    private static final int[][] DIRS = {
            { 1, 0, 0 }, { -1, 0, 0 }, { 0, 1, 0 }, { 0, -1, 0 }, { 0, 0, 1 }, { 0, 0, -1 }
    };

    private AcousticProbe() {}

    /**
     * Замкнутость 0..1: 0 — чистое поле, 1 — тесная каменная коробка.
     *
     * Считается как доля перекрытых направлений, приглушённая средней длиной
     * пробега: шесть стен в тридцати блоках — это зал, а не шкаф, и звучать
     * они должны по-разному.
     */
    public static float enclosure(World world, float x, float y, float z) {
        if (world == null)
            return 0f;
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        // Земля под ногами неизбежно закрывает нижний луч и раньше давала
        // заметное эхо даже посреди поля. Если над головой луч уходит на всю
        // дистанцию, это открытый воздух: боковые скалы и деревья могут дать
        // отражения, но пещерный reverb-send здесь должен быть ровно нулём.
        if (freeRun(world, bx, by, bz, 0, 1, 0) >= MAX_DISTANCE)
            return 0f;
        int blocked = 0;
        int totalRun = 0;
        for (int[] d : DIRS) {
            int run = freeRun(world, bx, by, bz, d[0], d[1], d[2]);
            totalRun += run;
            if (run < MAX_DISTANCE)
                blocked++;
        }
        float closed = blocked / (float) DIRS.length;
        float meanRun = totalRun / (float) DIRS.length;
        // Чем короче средний пробег, тем теснее. Полное схлопывание при
        // четырёх блоках: это уже коридор.
        float tight = 1f - Math.min(1f, (meanRun - 4f) / (MAX_DISTANCE - 4f));
        return Math.max(0f, Math.min(1f, closed * 0.55f + tight * 0.45f));
    }

    /** Сколько блоков луч проходит до первой твёрдой преграды. */
    public static int freeRun(World world, int x, int y, int z, int dx, int dy, int dz) {
        for (int i = 1; i <= MAX_DISTANCE; i++) {
            int nx = x + dx * i, ny = y + dy * i, nz = z + dz * i;
            if (ny < 0 || ny >= Chunk.SIZE_Y)
                return i;
            BlockType b = world.getBlock(nx, ny, nz);
            if (b != BlockType.AIR && b.solid)
                return i;
        }
        return MAX_DISTANCE;
    }
}

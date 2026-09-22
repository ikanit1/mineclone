package com.mineclone.audio;

import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.World;

/**
 * Каково пространство вокруг головы: насколько оно замкнуто и насколько велико.
 *
 * Шесть лучей по осям дают обе величины разом, и разделить их обязательно:
 * реверберация зависит от них независимо. Замкнутость решает, сколько звука
 * возвращается, размер — как долго он затухает. Маленькая комната замкнута
 * сильно, а звучит коротко; пещерный зал замкнут так же, а тянется секунды.
 *
 * Пока обе выводились из одного числа, комната 5x5 получала хвост в 4.2 с —
 * длиннее, чем пещера 24x24, потому что «теснее» читалось как «гулче».
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
     * Пространство вокруг уха.
     *
     * @param closed доля перекрытых направлений 0..1: сколько звука вернётся
     * @param size   средний свободный пробег в блоках: как долго он будет затухать
     */
    public record Room(float closed, float size) {
        /** Под открытым небом отражать нечему. */
        public static final Room OPEN = new Room(0f, MAX_DISTANCE);
    }

    /**
     * Замеряет пространство вокруг точки.
     *
     * Земля под ногами неизбежно закрывает нижний луч и раньше давала
     * заметное эхо даже посреди поля. Если над головой луч уходит на всю
     * дистанцию, это открытый воздух: боковые скалы и деревья могут дать
     * отражения, но reverb-send здесь должен быть ровно нулём.
     */
    public static Room room(World world, float x, float y, float z) {
        if (world == null)
            return Room.OPEN;
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        if (freeRun(world, bx, by, bz, 0, 1, 0) >= MAX_DISTANCE)
            return Room.OPEN;
        int blocked = 0;
        int totalRun = 0;
        for (int[] d : DIRS) {
            int run = freeRun(world, bx, by, bz, d[0], d[1], d[2]);
            totalRun += run;
            if (run < MAX_DISTANCE)
                blocked++;
        }
        return new Room(blocked / (float) DIRS.length, totalRun / (float) DIRS.length);
    }

    /**
     * Одна замкнутость 0..1 — для вызывающих, которым размер безразличен.
     *
     * Теперь это честная доля перекрытых направлений, а не смесь её с
     * теснотой: тесноту у неё отобрал {@link Room#size()}.
     */
    public static float enclosure(World world, float x, float y, float z) {
        return room(world, x, y, z).closed();
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

package com.mineclone;

import com.mineclone.audio.AcousticProbe;
import com.mineclone.audio.RoomAcoustics;
import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.World;

/**
 * Эхо должно зависеть от размера помещения, а не только от его замкнутости.
 *
 * Прежний зонд отдавал одно число на обе величины, и комната 5x5 получала
 * 1.000 против 0.801 у пещеры 24x24 — то есть хвост в 4.2 секунды внутри
 * деревянного дома, длиннее пещерного. Здесь это проверяется числами.
 */
final class RoomAcousticsTests {
    static void runAll(NetworkTests.Runner r) {
        r.run("a small room has a shorter tail than a cavern", RoomAcousticsTests::ordering);
        r.run("room size grows with the cavity, enclosure does not", RoomAcousticsTests::monotonic);
        r.run("open sky stays completely dry", RoomAcousticsTests::outdoors);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void fill(World w, int x0, int y0, int z0, int x1, int y1, int z1, BlockType t) {
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++) {
                Chunk c = w.getChunk(Math.floorDiv(x, Chunk.SIZE_X), Math.floorDiv(z, Chunk.SIZE_Z));
                int lx = Math.floorMod(x, Chunk.SIZE_X), lz = Math.floorMod(z, Chunk.SIZE_Z);
                for (int y = y0; y <= y1; y++)
                    c.set(lx, y, lz, t);
            }
    }

    /** Каменная полость side x side в плане и height в высоту; ухо в середине пола. */
    private static AcousticProbe.Room cavity(int side, int height, boolean roof) {
        World w = new World(side * 31L + height);
        int y0 = 40, r = side / 2, pad = 3;
        fill(w, -r - pad, y0 - pad, -r - pad, r + pad, y0 + height + pad, r + pad, BlockType.STONE);
        fill(w, -r, y0, -r, r, y0 + height - 1, r, BlockType.AIR);
        if (!roof)
            fill(w, -r, y0 + height, -r, r, Chunk.SIZE_Y - 1, r, BlockType.AIR);
        return AcousticProbe.room(w, 0.5f, y0 + 1.6f, 0.5f);
    }

    /**
     * Главное свойство: время затухания растёт вместе с объёмом.
     *
     * Комната обязана отзвучать меньше чем за секунду — иначе внутри дома
     * стоит собор, и крик моба бьёт по ушам.
     */
    private static void ordering() {
        float room = RoomAcoustics.decayTime(cavity(5, 3, true).size());
        float hall = RoomAcoustics.decayTime(cavity(16, 10, true).size());
        float cave = RoomAcoustics.decayTime(cavity(24, 24, true).size());
        check(room < hall, "a room must ring shorter than a hall, got " + room + " vs " + hall);
        check(hall < cave, "a hall must ring shorter than a cavern, got " + hall + " vs " + cave);
        check(room < 1f, "a 5x5 room must settle inside a second, got " + room);
        check(cave > 1.5f, "a 24-block cavern must keep a real tail, got " + cave);
    }

    /**
     * Замкнутость и размер меряют разное, и это видно по их поведению: все
     * шесть направлений перекрыты в любой закрытой полости, поэтому
     * {@code closed} держится у единицы, пока {@code size} растёт втрое.
     */
    private static void monotonic() {
        int[] sides = { 4, 8, 16, 24 };
        float previous = -1f;
        for (int side : sides) {
            AcousticProbe.Room m = cavity(side, Math.max(3, side), true);
            check(m.size() > previous, "size must grow with the cavity at side " + side);
            check(m.closed() > 0.9f, "a sealed cavity is closed whatever its size, side " + side);
            previous = m.size();
        }
    }

    /** Под открытым небом send обязан быть ровно нулевым, а не «почти». */
    private static void outdoors() {
        AcousticProbe.Room open = cavity(24, 24, false);
        check(open.closed() == 0f, "open sky must be bone dry, got " + open.closed());
        check(RoomAcoustics.sendGain(open.closed()) == 0f, "no send under open sky");
    }
}

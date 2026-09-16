package com.mineclone.world.entity;

import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.World;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A* по воксельной сетке для наземных сущностей.
 *
 * Узел — клетка, в которой стоят ноги моба. Переходы: шаг в сторону по
 * ровному, подъём на блок вверх (прыжком) и спуск до {@link #MAX_FALL}
 * блоков. Диагоналей нет: в блочном мире они экономят мало, зато срезанный
 * угол — это проход сквозь щель между двумя кубами.
 *
 * Ни одного GL-вызова и ни одной ссылки на {@link Mob}, поэтому алгоритм
 * проверяется обычными тестами на сколоченном из блоков мире.
 */
public final class PathFinder {

    /** Потолок раскрытых узлов: без него тупик обшаривает пол-чанка. */
    public static final int MAX_NODES = 900;
    /** Длиннее этого пути не строим — цель всё равно успеет уйти. */
    public static final int MAX_PATH = 72;
    /** Сколько блоков сущность готова спрыгнуть по дороге. */
    public static final int MAX_FALL = 3;

    /** Надбавка за прыжок вверх: по ровному ходить приятнее. */
    private static final float JUMP_COST = 0.6f;
    /** Надбавка за каждый блок спуска. */
    private static final float FALL_COST = 0.4f;
    /** Надбавка за воду: вплавь медленнее, чем в обход по берегу. */
    private static final float WATER_COST = 2.0f;
    /** Надбавка за огонь: путь через костёр дороже крюка в шесть шагов. */
    private static final float FIRE_COST = 6.0f;
    /** Прыжок через провал дороже шага: он рискованнее, но дешевле крюка. */
    private static final float LEAP_COST = 1.8f;
    /** Самый широкий провал, через который моб прыгает, блоки. */
    public static final int MAX_LEAP_GAP = 2;
    /**
     * Надбавка за клетку в ярком блочном свете — для нежити. Не запрет: если
     * другой дороги нет, зомби всё равно пройдёт мимо факела, но крюк в
     * несколько шагов по темноте он предпочтёт.
     */
    private static final float LIGHT_COST = 3.5f;
    /** С какого блочного света клетка считается «под факелом». */
    public static final int LIT_CELL = 11;

    private static final int[] DX = { 1, -1, 0, 0 };
    private static final int[] DZ = { 0, 0, 1, -1 };

    private record Node(long key, float f) {}

    private PathFinder() {}

    /** Свободна ли клетка для прохода (не твёрдая). */
    public static boolean passable(World world, int x, int y, int z) {
        if (y < 0 || y >= Chunk.SIZE_Y)
            return false;
        return !world.getBlock(x, y, z).solid;
    }

    /**
     * Может ли моб ростом {@code height} блоков стоять ногами в (x, y, z):
     * колонна над клеткой свободна, а под ней есть опора.
     */
    public static boolean standable(World world, int x, int y, int z, int height) {
        if (y <= 0 || y + height > Chunk.SIZE_Y)
            return false;
        if (!world.getBlock(x, y - 1, z).solid)
            return false;
        return columnFree(world, x, y, z, height);
    }

    /** Свободна ли колонна ростом height — можно ли шагнуть в неё. */
    public static boolean columnFree(World world, int x, int y, int z, int height) {
        for (int i = 0; i < height; i++)
            if (!passable(world, x, y + i, z))
                return false;
        return true;
    }

    /**
     * Ближайшая по вертикали клетка, в которой можно стоять, рядом с (x, y, z).
     *
     * Цель редко оказывается на опоре ровно: игрок прыгает, стоит на краю или
     * висит над обрывом. Без этого поиска путь к прыгающему игроку пропадал бы
     * через кадр.
     *
     * @return уровень ног или {@link Integer#MIN_VALUE}, если опоры рядом нет
     */
    public static int groundNear(World world, int x, int y, int z, int height) {
        for (int d = 0; d <= 4; d++) {
            if (standable(world, x, y - d, z, height))
                return y - d;
            if (d > 0 && standable(world, x, y + d, z, height))
                return y + d;
        }
        return Integer.MIN_VALUE;
    }

    /**
     * Путь от ног в (sx, sy, sz) к (tx, ty, tz).
     *
     * @return центры клеток по порядку, без стартовой (пустой список — уже на
     *         месте); {@code null}, если пути нет или он не уложился в
     *         {@link #MAX_NODES}
     */
    public static List<Vector3f> find(World world, int sx, int sy, int sz,
                                      int tx, int ty, int tz, int height) {
        return find(world, sx, sy, sz, tx, ty, tz, height, false);
    }

    /**
     * @param avoidLight клетки под ярким блочным светом дороже — так нежить
     *                   обходит факелы, если есть обход по темноте
     */
    public static List<Vector3f> find(World world, int sx, int sy, int sz,
                                      int tx, int ty, int tz, int height, boolean avoidLight) {
        int startY = groundNear(world, sx, sy, sz, height);
        int goalY = groundNear(world, tx, ty, tz, height);
        if (startY == Integer.MIN_VALUE || goalY == Integer.MIN_VALUE)
            return null;
        long start = key(sx, startY, sz);
        long goal = key(tx, goalY, tz);
        if (start == goal)
            return new ArrayList<>();

        Map<Long, Float> best = new HashMap<>();
        Map<Long, Long> from = new HashMap<>();
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Float.compare(a.f, b.f));
        best.put(start, 0f);
        open.add(new Node(start, heuristic(sx, startY, sz, tx, goalY, tz)));

        int expanded = 0;
        while (!open.isEmpty() && expanded < MAX_NODES) {
            Node top = open.poll();
            long cur = top.key();
            if (cur == goal)
                return rebuild(from, start, goal);

            int cx = unpackX(cur), cy = unpackY(cur), cz = unpackZ(cur);
            float g = best.getOrDefault(cur, Float.MAX_VALUE);
            // Очередь без decrease-key: узел мог попасть в неё дважды, и вторая,
            // худшая копия должна просто пропускаться.
            if (top.f() > g + heuristic(cx, cy, cz, tx, goalY, tz) + 1e-4f)
                continue;
            expanded++;

            for (int d = 0; d < 4; d++) {
                int nx = cx + DX[d], nz = cz + DZ[d];
                int ny = Integer.MIN_VALUE;
                float step = 1f;

                if (standable(world, nx, cy, nz, height)) {
                    ny = cy;
                } else if (passable(world, cx, cy + height, cz)
                        && standable(world, nx, cy + 1, nz, height)) {
                    // Подъём на блок. Над собственной головой должно быть
                    // свободно, иначе прыжок упрётся в потолок.
                    ny = cy + 1;
                    step += JUMP_COST;
                } else if (columnFree(world, nx, cy, nz, height)) {
                    for (int drop = 1; drop <= MAX_FALL; drop++) {
                        if (standable(world, nx, cy - drop, nz, height)) {
                            ny = cy - drop;
                            step += drop * FALL_COST;
                            break;
                        }
                    }
                    if (ny == Integer.MIN_VALUE) {
                        // Под соседней клеткой провал глубже безопасного —
                        // пробуем перепрыгнуть его целиком.
                        int[] leap = leapAcross(world, cx, cy, cz, DX[d], DZ[d], height);
                        if (leap != null) {
                            nx = leap[0];
                            ny = leap[1];
                            nz = leap[2];
                            step = leap[3] + LEAP_COST;
                        }
                    }
                }
                if (ny == Integer.MIN_VALUE)
                    continue;

                long next = key(nx, ny, nz);
                float ng = g + step + terrainCost(world, nx, ny, nz, height)
                        + (avoidLight ? lightCost(world, nx, ny, nz) : 0f);
                if (ng >= best.getOrDefault(next, Float.MAX_VALUE))
                    continue;
                best.put(next, ng);
                from.put(next, cur);
                open.add(new Node(next, ng + heuristic(nx, ny, nz, tx, goalY, tz)));
            }
        }
        return null;
    }

    /**
     * Прыжок через провал из (cx, cy, cz) по направлению (dx, dz).
     *
     * Приземление — на той же высоте или на блок ниже, через один-два пустых
     * столба. Над всем полётом должно быть свободно на рост моба и ещё блок:
     * дуга прыжка поднимает голову выше обычного шага.
     *
     * @return {x, y, z, длина прыжка в клетках} или null
     */
    static int[] leapAcross(World world, int cx, int cy, int cz, int dx, int dz, int height) {
        if (!passable(world, cx, cy + height, cz))
            return null;                          // без места над головой не прыгнуть
        for (int gap = 1; gap <= MAX_LEAP_GAP; gap++) {
            int gx = cx + dx * gap, gz = cz + dz * gap;
            // Столб над провалом обязан быть свободен на весь рост и дугу.
            if (!columnFree(world, gx, cy, gz, height + 1))
                return null;
            int lx = cx + dx * (gap + 1), lz = cz + dz * (gap + 1);
            if (standable(world, lx, cy, lz, height) && passable(world, lx, cy + height, lz))
                return new int[] { lx, cy, lz, gap + 1 };
            if (standable(world, lx, cy - 1, lz, height))
                return new int[] { lx, cy - 1, lz, gap + 1 };
            // Под этим столбом тоже нет безопасного пола — провал шире.
            boolean floorBelow = false;
            for (int drop = 1; drop <= MAX_FALL; drop++)
                if (standable(world, gx, cy - drop, gz, height))
                    floorBelow = true;
            if (floorBelow)
                return null;                      // тогда проще спуститься, чем прыгать
        }
        return null;
    }

    /** Надбавка за клетку под ярким блочным светом. */
    private static float lightCost(World world, int x, int y, int z) {
        return world.getBlockLightWorld(x, y, z) >= LIT_CELL ? LIGHT_COST : 0f;
    }

    /** Цена стоять в этой клетке сверх базового шага: вода, огонь. */
    private static float terrainCost(World world, int x, int y, int z, int height) {
        float extra = 0f;
        for (int i = 0; i < height; i++) {
            BlockType b = world.getBlock(x, y + i, z);
            if (b == BlockType.WATER || b == BlockType.WATER_FLOW)
                extra += WATER_COST / height;
            else if (b == BlockType.FIRE)
                extra += FIRE_COST;
        }
        return extra;
    }

    private static float heuristic(int x, int y, int z, int tx, int ty, int tz) {
        return Math.abs(x - tx) + Math.abs(z - tz) + Math.abs(y - ty) * 0.5f;
    }

    private static List<Vector3f> rebuild(Map<Long, Long> from, long start, long goal) {
        List<Vector3f> out = new ArrayList<>();
        long cur = goal;
        while (cur != start) {
            out.add(new Vector3f(unpackX(cur) + 0.5f, unpackY(cur), unpackZ(cur) + 0.5f));
            Long prev = from.get(cur);
            // Слишком длинный путь выбрасывается целиком: цель успеет уйти
            // раньше, чем моб его пройдёт, а прямое наведение дешевле.
            if (prev == null || out.size() > MAX_PATH)
                return null;
            cur = prev;
        }
        Collections.reverse(out);
        return out;
    }

    // Упаковка в long: 21 бит на X и Z (мир ±1 млн блоков) и 9 на Y.
    // Публичная — чтобы тест мог проверить её на отрицательных координатах:
    // молчаливая потеря знака здесь превращает путь в мусор без единой ошибки.
    private static final int OFF = 1 << 20;
    private static final long MASK21 = 0x1FFFFFL;

    public static long key(int x, int y, int z) {
        return (((long) x + OFF) & MASK21) << 30
                | ((long) (y & 0x1FF)) << 21
                | (((long) z + OFF) & MASK21);
    }

    public static int unpackX(long k) {
        return (int) ((k >>> 30) & MASK21) - OFF;
    }

    public static int unpackY(long k) {
        return (int) ((k >>> 21) & 0x1FF);
    }

    public static int unpackZ(long k) {
        return (int) (k & MASK21) - OFF;
    }
}

package com.mineclone.world;

import java.util.ArrayList;
import java.util.List;

/**
 * Где и когда бьёт молния — чистая функция от сида и времени.
 *
 * Как и {@link Weather}, не хранится и не пересылается: в сохранении её нет,
 * в сетевом протоколе нет пакета удара. Хозяин и участник получают один и тот
 * же разряд в одной и той же точке, просто вычислив его.
 *
 * <p>Удары привязаны к <b>ячейкам мира</b>, а не к игроку, и это существенно.
 * Разыгрывать их вокруг камеры было бы проще, но тогда двое в одной грозе
 * видели бы в одном небе разные молнии, а стоящий рядом — ещё и в другой
 * момент. Ячейка же одна на всех: кто до неё достаёт взглядом, тот и видит
 * её разряд.
 *
 * <p>Время режется на окна {@link #WINDOW}; в каждом окне каждая ячейка
 * бросает свой жребий. Поэтому список ударов за любой промежуток собирается
 * без всякой истории — достаточно перебрать окна, которые в него попали.
 */
public final class Lightning {

    /** Сторона ячейки грозы в блоках. */
    public static final int CELL = 48;
    /** Окно розыгрыша, секунды мирового времени. */
    public static final float WINDOW = 6f;
    /** Слабее этой бури не бьёт вовсе: дождь — ещё не гроза. */
    public static final float STRIKE_THRESHOLD = 0.5f;
    /**
     * Шанс одной ячейки за одно окно при полной буре.
     *
     * Число маленькое, потому что ячеек в виду много: при радиусе 128 блоков
     * их тридцать шесть, и жребий бросает каждая. Отсюда удар примерно раз в
     * двадцать секунд — гроза, а не стробоскоп.
     */
    public static final float CELL_CHANCE = 0.0075f;
    /** Откуда падает разряд: высота облаков над точкой удара. */
    public static final int CLOUD_HEIGHT = 40;

    private Lightning() {}

    /**
     * Один разряд.
     *
     * @param x     куда бьёт по горизонтали
     * @param z     куда бьёт по горизонтали
     * @param time  мировое время удара, секунды
     * @param id    сид этого удара: из него растёт форма болта
     */
    public record Strike(int x, int z, float time, long id) {}

    /**
     * Все удары в промежутке {@code (from, to]} в квадрате {@code radius}
     * блоков вокруг точки.
     *
     * Промежуток полуоткрыт слева, поэтому кадры, идущие подряд, не покажут
     * один разряд дважды и не потеряют ни одного между собой.
     */
    public static List<Strike> collect(long seed, float from, float to,
                                       float storm, float snow, int px, int pz, int radius) {
        List<Strike> out = new ArrayList<>();
        float power = strikePower(storm, snow);
        if (power <= 0f || to <= from)
            return out;
        long firstWindow = (long) Math.floor(from / WINDOW);
        long lastWindow = (long) Math.floor(to / WINDOW);
        int c0x = Math.floorDiv(px - radius, CELL), c1x = Math.floorDiv(px + radius, CELL);
        int c0z = Math.floorDiv(pz - radius, CELL), c1z = Math.floorDiv(pz + radius, CELL);
        for (long w = firstWindow; w <= lastWindow; w++)
            for (int cx = c0x; cx <= c1x; cx++)
                for (int cz = c0z; cz <= c1z; cz++) {
                    Strike s = strikeIn(seed, cx, cz, w, power);
                    if (s != null && s.time() > from && s.time() <= to)
                        out.add(s);
                }
        return out;
    }

    /**
     * Насколько охотно бьёт: буря ниже порога и метель не дают ничего.
     *
     * Множитель на снег тот же, что уже стоит на громе: в метель грозы не
     * бывает, и видеть разряд сквозь снегопад было бы так же странно, как
     * слышать раскат.
     */
    public static float strikePower(float storm, float snow) {
        if (storm < STRIKE_THRESHOLD)
            return 0f;
        float dry = 1f - Math.max(0f, Math.min(1f, snow));
        float over = (storm - STRIKE_THRESHOLD) / (1f - STRIKE_THRESHOLD);
        return Math.max(0f, Math.min(1f, over)) * dry;
    }

    /** Жребий одной ячейки в одном окне. {@code null} — не в этот раз. */
    private static Strike strikeIn(long seed, int cellX, int cellZ, long window, float power) {
        long h = mix(seed ^ mix(cellX * 0x9E3779B97F4A7C15L + cellZ * 0xC2B2AE3D27D4EB4FL + window));
        if (unit(h) >= CELL_CHANCE * power)
            return null;
        long a = mix(h + 1), b = mix(h + 2), c = mix(h + 3);
        int x = cellX * CELL + (int) Math.floor(unit(a) * CELL);
        int z = cellZ * CELL + (int) Math.floor(unit(b) * CELL);
        float time = (window + unit(c)) * WINDOW;
        return new Strike(x, z, time, mix(h + 4));
    }

    // ---- форма болта -------------------------------------------------------

    /**
     * Ломаная разряда: плоский массив по шесть чисел на отрезок
     * {@code (x1,y1,z1, x2,y2,z2)}.
     *
     * Ствол идёт от облаков до точки удара, шагая вниз и виляя в стороны;
     * от части узлов отходят короткие ветви, которые не достают до земли.
     * Форма выводится из {@link Strike#id()}, поэтому одинакова у всех и
     * проверяется тестом без единого вызова GL.
     *
     * @param groundY высота, на которой разряд упирается в землю
     */
    public static float[] bolt(Strike strike, int groundY) {
        long r = strike.id();
        int steps = 12 + (int) (unit(r = mix(r)) * 6);
        float topY = groundY + CLOUD_HEIGHT;
        float stepY = (topY - groundY) / steps;
        List<float[]> segments = new ArrayList<>();
        float x = strike.x() + 0.5f, z = strike.z() + 0.5f, y = topY;
        // Вверху разряд гуляет широко, у земли сходится в точку удара:
        // иначе болт втыкается рядом с тем местом, куда он ударил.
        for (int i = 0; i < steps; i++) {
            float spreadNow = spread(i / (float) steps);
            float spreadNext = spread((i + 1) / (float) steps);
            float nx = strike.x() + 0.5f + (unit(r = mix(r)) * 2f - 1f) * spreadNext;
            float nz = strike.z() + 0.5f + (unit(r = mix(r)) * 2f - 1f) * spreadNext;
            float ny = y - stepY;
            segments.add(new float[] { x, y, z, nx, ny, nz });
            // Ветвь уходит вбок и гаснет, не дойдя до земли.
            if (i > 1 && i < steps - 2 && unit(r = mix(r)) < 0.28f) {
                float bx = nx + (unit(r = mix(r)) * 2f - 1f) * (2f + spreadNow);
                float bz = nz + (unit(r = mix(r)) * 2f - 1f) * (2f + spreadNow);
                segments.add(new float[] { nx, ny, nz, bx, ny - stepY * 1.4f, bz });
            }
            x = nx;
            y = ny;
            z = nz;
        }
        // Последний отрезок сажается ровно в точку удара.
        float[] last = segments.get(segments.size() - 1);
        last[3] = strike.x() + 0.5f;
        last[4] = groundY;
        last[5] = strike.z() + 0.5f;
        float[] flat = new float[segments.size() * 6];
        for (int i = 0; i < segments.size(); i++)
            System.arraycopy(segments.get(i), 0, flat, i * 6, 6);
        return flat;
    }

    /** Насколько широко разряд гуляет на этой доле пути сверху вниз. */
    private static float spread(float t) {
        float k = 1f - t;
        return k * k * 6f;
    }

    // ---- детерминированный шум --------------------------------------------

    /** SplitMix64: своя, чтобы форма не зависела от версии java.util.Random. */
    private static long mix(long v) {
        v += 0x9E3779B97F4A7C15L;
        v = (v ^ (v >>> 30)) * 0xBF58476D1CE4E5B9L;
        v = (v ^ (v >>> 27)) * 0x94D049BB133111EBL;
        return v ^ (v >>> 31);
    }

    /** Ровное 0..1 из перемешанных битов. */
    private static float unit(long v) {
        return (v >>> 40) / (float) (1 << 24);
    }
}

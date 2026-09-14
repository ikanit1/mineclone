package com.mineclone.render;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Инструментарий пиксель-арта для скинов — те же правила, что у блочных
 * текстур в {@code tools/GenBlockTextures.java}:
 *
 * <ul>
 *   <li>мастер 16×16 на тайл, апскейл nearest — пиксель остаётся пикселем;</li>
 *   <li>индексированные рампы <b>ровно из 5 стопов</b>, 0 = тень, 4 = блик;</li>
 *   <li>hue shifting — тени уводятся в холод, блики в тепло;</li>
 *   <li>переходы упорядоченным дизерингом 4×4 Bayer, а не альфой и не блендом.
 *       Полупрозрачных заливок здесь нет вообще: в пиксель-арте они дают
 *       грязь, которую GL_NEAREST потом честно растягивает.</li>
 * </ul>
 *
 * Инвариант «ровно 5 стопов» держится структурно: {@link Pal#ramp} принимает
 * ровно пять цветов, а {@link Sheet#shift} по этому шагу находит соседний стоп
 * той же рампы, не залезая в чужую.
 *
 * Координаты рисования — доли тайла (0..1), а не пиксели: поднять {@link #M}
 * или {@code MobSkins.TILE} можно без правки графики.
 */
public final class PixelArt {

    /** Мастер-разрешение одного тайла. */
    public static final int M = 16;
    /** Стопов в рампе. Меняется только вместе со всеми палитрами. */
    public static final int STOPS = 5;
    public static final int TRANSPARENT = -1;

    private PixelArt() {}

    // -------------------------------------------------------------------------
    //  Палитра
    // -------------------------------------------------------------------------

    /** Набор рамп одного скина. Индекс пикселя = base рампы + стоп 0..4. */
    public static final class Pal {
        private final List<Integer> colors = new ArrayList<>();

        /** Добавляет рампу из 5 HEX-стопов (тень → блик), возвращает её базу. */
        public int ramp(String s0, String s1, String s2, String s3, String s4) {
            int base = colors.size();
            for (String hex : new String[] { s0, s1, s2, s3, s4 })
                colors.add(0xFF000000 | Integer.parseInt(hex, 16));
            return base;
        }

        public int[] toArray() {
            int[] out = new int[colors.size()];
            for (int i = 0; i < out.length; i++)
                out[i] = colors.get(i);
            return out;
        }
    }

    // -------------------------------------------------------------------------
    //  Дизеринг и шум
    // -------------------------------------------------------------------------

    private static final int[][] BAYER4 = {
            {  0,  8,  2, 10 },
            { 12,  4, 14,  6 },
            {  3, 11,  1,  9 },
            { 15,  7, 13,  5 } };

    /** Порог упорядоченного дизеринга в точке. */
    public static float bayer(int x, int y) {
        return (BAYER4[Math.floorMod(y, 4)][Math.floorMod(x, 4)] + 0.5f) / 16f;
    }

    /**
     * Квантование 0..1 на 5 стопов рампы: дробная часть уходит в шахматку
     * сплошных пикселей вместо смешивания цветов.
     */
    public static int stop(float v, int x, int y) {
        v = clamp01(v);
        float t = v * (STOPS - 1);
        int i = (int) Math.floor(t);
        if (t - i > bayer(x, y)) i++;
        return Math.max(0, Math.min(STOPS - 1, i));
    }

    /** Хеш решётки, завёрнутый по period — поле замыкается на торе. */
    public static float hash(int x, int y, int period, int seed) {
        x = Math.floorMod(x, period);
        y = Math.floorMod(y, period);
        int n = x * 374761393 + y * 668265263 + seed * 1442695041;
        n = (n ^ (n >>> 13)) * 1274126177;
        n = n ^ (n >>> 16);
        return (n & 0xFFFFFF) / (float) 0xFFFFFF;
    }

    /** Value-noise: period ячеек решётки на тайл. */
    public static float noise(float px, float py, int period, int seed) {
        float fx = px * period / (float) M, fy = py * period / (float) M;
        int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
        float tx = fx - x0, ty = fy - y0;
        tx = tx * tx * (3 - 2 * tx);
        ty = ty * ty * (3 - 2 * ty);
        float a = hash(x0, y0, period, seed),     b = hash(x0 + 1, y0, period, seed);
        float c = hash(x0, y0 + 1, period, seed), d = hash(x0 + 1, y0 + 1, period, seed);
        return lerp(lerp(a, b, tx), lerp(c, d, tx), ty);
    }

    /**
     * Разброс, который ломает регулярность Bayer-шахматки: крупные пятна
     * (period 4) задают фактуру, мелкий хеш рассыпает границу стопов в крап.
     * Без второго слагаемого длинный пологий градиент превращается в ровную
     * клетку на пол-тайла — на модели это читается как сетка, а не как шерсть.
     */
    public static float jitter(int x, int y, int seed) {
        return 0.15f * (noise(x, y, 4, seed) - 0.5f)
             + 0.13f * (hash(x, y, M, seed + 977) - 0.5f);
    }

    public static float lerp(float a, float b, float t) { return a + (b - a) * t; }
    public static float clamp01(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    // -------------------------------------------------------------------------
    //  Лист тайлов
    // -------------------------------------------------------------------------

    /**
     * Сетка тайлов cols×rows, каждый — матрица индексов M×M. Все методы
     * рисования возвращают this, чтобы описание вида читалось цепочкой.
     */
    public static final class Sheet {
        private final int cols;
        private final int[][][] t;   // [tile][y][x]

        public Sheet(int cols, int rows) {
            this.cols = cols;
            this.t = new int[cols * rows][M][M];
            for (int[][] tile : t)
                for (int[] row : tile)
                    java.util.Arrays.fill(row, TRANSPARENT);
        }

        public int get(int tile, int x, int y) {
            return inside(x, y) ? t[tile][y][x] : TRANSPARENT;
        }

        public Sheet set(int tile, int x, int y, int idx) {
            if (inside(x, y)) t[tile][y][x] = idx;
            return this;
        }

        private static boolean inside(int x, int y) {
            return x >= 0 && x < M && y >= 0 && y < M;
        }

        // --- заливки -------------------------------------------------------

        /**
         * Вертикальный дизерный градиент по рампе на весь тайл: свет всегда
         * падает сверху, иначе тайлы не склеиваются в одну фигуру — каждый
         * читается как отдельная наклейка.
         */
        public Sheet grad(int tile, int base, float topV, float botV, int seed) {
            return rectGrad(tile, 0f, 0f, 1f, 1f, base, topV, botV, seed);
        }

        /** Тот же градиент, но в пределах прямоугольника (доли тайла). */
        public Sheet rectGrad(int tile, float fx, float fy, float fw, float fh,
                              int base, float topV, float botV, int seed) {
            int x0 = px(fx), y0 = px(fy);
            int x1 = Math.min(M, x0 + len(fw)), y1 = Math.min(M, y0 + len(fh));
            float span = Math.max(1, y1 - y0 - 1);
            for (int y = y0; y < y1; y++)
                for (int x = x0; x < x1; x++) {
                    float f = (y - y0) / span;
                    set(tile, x, y, base + stop(lerp(topV, botV, f) + jitter(x, y, seed), x, y));
                }
            return this;
        }

        /** Сплошной прямоугольник одним индексом. */
        public Sheet rect(int tile, float fx, float fy, float fw, float fh, int idx) {
            int x0 = px(fx), y0 = px(fy);
            int x1 = Math.min(M, x0 + len(fw)), y1 = Math.min(M, y0 + len(fh));
            for (int y = y0; y < y1; y++)
                for (int x = x0; x < x1; x++)
                    set(tile, x, y, idx);
            return this;
        }

        /** Один пиксель. */
        public Sheet dot(int tile, float fx, float fy, int idx) {
            return set(tile, px(fx), px(fy), idx);
        }

        /**
         * Комковатая заливка: value-noise квантуется по рампе. Так рисуется
         * шерсть и всё, где нужна не гладкая тень, а фактура из пятен.
         */
        public Sheet clumps(int tile, int base, float lo, float hi, int period, int seed) {
            for (int y = 0; y < M; y++)
                for (int x = 0; x < M; x++) {
                    float n = 0.6f * noise(x, y, period, seed)
                            + 0.4f * noise(x, y, period * 2, seed + 71);
                    set(tile, x, y, base + stop(lerp(lo, hi, n) + jitter(x, y, seed), x, y));
                }
            return this;
        }

        /** Пятно с рваным дизерным краем — для коровьих подпалин. */
        public Sheet splotch(int tile, float fcx, float fcy, float fr, int base, int seed) {
            float cx = fcx * M, cy = fcy * M, r = fr * M;
            for (int y = 0; y < M; y++)
                for (int x = 0; x < M; x++) {
                    float d = (float) Math.hypot(x + 0.5f - cx, y + 0.5f - cy);
                    float edge = r * (0.75f + 0.5f * noise(x, y, 4, seed));
                    if (d > edge) continue;
                    float v = 0.85f - 0.45f * (d / Math.max(0.001f, edge));
                    set(tile, x, y, base + stop(v, x, y));
                }
            return this;
        }

        // --- модификаторы --------------------------------------------------

        /**
         * Сдвигает пиксели прямоугольника на delta стопов, не выходя за свою
         * рампу — так делается локальная тень или подсветка поверх готовой
         * заливки, без второго слоя цвета.
         */
        public Sheet shift(int tile, float fx, float fy, float fw, float fh, int delta) {
            int x0 = px(fx), y0 = px(fy);
            int x1 = Math.min(M, x0 + len(fw)), y1 = Math.min(M, y0 + len(fh));
            for (int y = y0; y < y1; y++)
                for (int x = x0; x < x1; x++) {
                    int idx = get(tile, x, y);
                    if (idx < 0) continue;
                    int base = idx / STOPS * STOPS;
                    set(tile, x, y, base + Math.max(0, Math.min(STOPS - 1, idx - base + delta)));
                }
            return this;
        }

        /**
         * Рваная дизерная граница на строке fy: часть пикселей строки уходит в
         * indexAbove, часть в indexBelow. Так режется подол рубахи и кромка
         * шерсти — ровная линия читается как наклейка.
         */
        public Sheet ragged(int tile, float fy, float amp, int base, float v, int seed) {
            int y0 = px(fy);
            int span = Math.max(1, len(amp));
            for (int x = 0; x < M; x++) {
                int h = Math.round(span * noise(x, 0, 4, seed));
                for (int y = y0; y < y0 + h; y++)
                    set(tile, x, y, base + stop(v, x, y));
            }
            return this;
        }

        /**
         * Глаз 4×2: два пикселя белка и два зрачка. Зрачок ставится со стороны
         * pupilRight — на морде оба глаза должны смотреть к центру, иначе моб
         * выглядит косым. Белок обязан быть шириной в два пикселя: на одном он
         * теряется после апскейла и глаз читается сплошным чёрным квадратом.
         */
        public Sheet eye(int tile, float fx, float fy, int dark, int light, boolean pupilRight) {
            int x = px(fx), y = px(fy);
            for (int dy = 0; dy < 2; dy++)
                for (int dx = 0; dx < 4; dx++)
                    set(tile, x + dx, y + dy, light);
            int p = pupilRight ? x + 2 : x;
            for (int dy = 0; dy < 2; dy++)
                for (int dx = 0; dx < 2; dx++)
                    set(tile, p + dx, y + dy, dark);
            return this;
        }

        // --- вывод ---------------------------------------------------------

        /**
         * Собирает лист в изображение: каждый тайл занимает tilePx пикселей,
         * мастер растягивается ближайшим соседом (tilePx кратен M).
         */
        public BufferedImage toImage(int[] pal, int tilePx) {
            int rows = t.length / cols;
            BufferedImage img = new BufferedImage(cols * tilePx, rows * tilePx,
                    BufferedImage.TYPE_INT_ARGB);
            int scale = Math.max(1, tilePx / M);
            for (int tile = 0; tile < t.length; tile++) {
                int bx = tile % cols * tilePx, by = tile / cols * tilePx;
                for (int y = 0; y < tilePx; y++)
                    for (int x = 0; x < tilePx; x++) {
                        int idx = t[tile][Math.min(M - 1, y / scale)][Math.min(M - 1, x / scale)];
                        img.setRGB(bx + x, by + y, idx < 0 ? 0 : pal[idx]);
                    }
            }
            return img;
        }

        private static int px(float f) { return Math.round(f * M); }
        private static int len(float f) { return Math.max(1, Math.round(f * M)); }
    }
}

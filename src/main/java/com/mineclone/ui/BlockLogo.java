package com.mineclone.ui;

import com.mineclone.world.BlockType;

import java.util.Map;

/**
 * Логотип, сложенный из блоков.
 *
 * <p>Буквы — пиксельный шрифт 5×7, каждый пиксель — тайл атласа: верхний
 * открытый пиксель буквы — трава, два под ним — земля, глубже — камень. Буква
 * читается срезом рельефа, то есть тем, из чего сделан сам мир.
 *
 * <p>Не третий кегль TTF: крупный кегль с кириллицей не помещается в атлас
 * шрифта 1024×512, а логотипу кириллица и не нужна.
 */
final class BlockLogo {

    private BlockLogo() {
    }

    private static final Map<Character, String[]> GLYPHS = Map.of(
            'M', new String[] { "#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#" },
            'I', new String[] { "###", ".#.", ".#.", ".#.", ".#.", ".#.", "###" },
            'N', new String[] { "#...#", "##..#", "#.#.#", "#.#.#", "#..##", "#...#", "#...#" },
            'E', new String[] { "#####", "#....", "#....", "####.", "#....", "#....", "#####" },
            'C', new String[] { ".####", "#....", "#....", "#....", "#....", "#....", ".####" },
            'L', new String[] { "#....", "#....", "#....", "#....", "#....", "#....", "#####" },
            'O', new String[] { ".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###." });

    static final int ROWS = 7;

    /** Ширина слова в пикселях шрифта (с промежутком в пиксель между буквами). */
    static int columns(String word) {
        int cols = 0;
        for (int i = 0; i < word.length(); i++) {
            String[] g = GLYPHS.get(word.charAt(i));
            cols += (g != null ? g[0].length() : 3) + (i > 0 ? 1 : 0);
        }
        return cols;
    }

    /**
     * @param cx    центр слова по X
     * @param top   верх букв
     * @param pixel размер одного блока, px
     */
    static void draw(MenuTheme t, String word, float cx, float top, float pixel, float time) {
        float x0 = cx - columns(word) * pixel / 2f;
        int grass = BlockType.GRASS.sideTile;
        int dirt = BlockType.DIRT.sideTile;
        int stone = BlockType.STONE.sideTile;
        float shadow = pixel * 0.32f;

        // Тень целиком первым проходом: иначе тень следующей буквы ложилась
        // бы поверх предыдущей.
        for (int pass = 0; pass < 2; pass++) {
            float x = x0;
            for (int i = 0; i < word.length(); i++) {
                String[] g = GLYPHS.get(word.charAt(i));
                int w = g != null ? g[0].length() : 3;
                // Буквы чуть покачиваются волной — логотип живой, но не пляшет.
                float bob = (float) Math.sin(time * 1.3f + i * 0.55f) * pixel * 0.16f;
                if (g != null) {
                    for (int r = 0; r < ROWS; r++) {
                        for (int c = 0; c < w; c++) {
                            if (!on(g, r, c))
                                continue;
                            float px = x + c * pixel, py = top + r * pixel + bob;
                            if (pass == 0) {
                                t.quad(px + shadow, py + shadow, pixel, pixel, 0f, 0f, 0f, 0.55f);
                                continue;
                            }
                            int depth = exposure(g, r, c);
                            int tile = depth == 0 ? grass : depth <= 2 ? dirt : stone;
                            t.tile(tile, px, py, pixel, pixel, 1f, 1f);
                            float e = Math.max(1.5f, pixel * 0.12f);
                            if (!on(g, r - 1, c))
                                t.quad(px, py, pixel, e, 1f, 1f, 1f, 0.30f);
                            if (!on(g, r, c - 1))
                                t.quad(px, py, e, pixel, 1f, 1f, 1f, 0.16f);
                            if (!on(g, r + 1, c))
                                t.quad(px, py + pixel - e, pixel, e, 0f, 0f, 0f, 0.40f);
                            if (!on(g, r, c + 1))
                                t.quad(px + pixel - e, py, e, pixel, 0f, 0f, 0f, 0.30f);
                        }
                    }
                }
                x += (w + 1) * pixel;
            }
        }
    }

    private static boolean on(String[] g, int r, int c) {
        return r >= 0 && r < g.length && c >= 0 && c < g[r].length() && g[r].charAt(c) == '#';
    }

    /** Сколько заполненных пикселей над этим подряд — глубина под «поверхностью» буквы. */
    private static int exposure(String[] g, int r, int c) {
        int d = 0;
        while (on(g, r - d - 1, c))
            d++;
        return d;
    }
}

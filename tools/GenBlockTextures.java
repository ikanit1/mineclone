import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Procedural generator for the complete block-sprite set in
 * {@code assets/textures/blocks} — Minecraft-style pixel art under hard rules:
 *
 *   1. Hard 16x16 grid. Every pixel is placed explicitly; no anti-aliasing,
 *      no blur, no alpha gradients. The engine upscales 16 -> TILE with
 *      nearest-neighbour, so the source stays the pixel-art master.
 *   2. Indexed palettes. Each material is one "colour ramp" of exactly five
 *      stops, index 0 = darkest. Composite tiles (grass_side, door_top, ...)
 *      combine two ramps; ramp B lives at indices 5..9.
 *   3. Hue shifting. Shadows are rotated toward the cold end (blue/violet),
 *      highlights toward the warm end (yellow/gold). Never a plain
 *      brightness ramp of one hue — that is what makes a texture look muddy.
 *   4. Ordered dithering. Transitions between ramp stops are 4x4 Bayer
 *      checkerboards of solid pixels, never partial alpha.
 *   5. Seamless. All noise runs on a torus with a lattice period that divides
 *      16, and every hand-placed motif wraps, so tile edges join perfectly in
 *      both X and Y.
 *
 * Run from the repo root:  java tools\GenBlockTextures.java
 *
 * Дописывает только недостающие спрайты. Уже лежащие в папке не трогает:
 * часть из них перерисована руками и в большем разрешении, и слепой прогон
 * затирал их процедурными 16x16. Перегенерировать весь набор — {@code --force}.
 *
 * Side outputs: assets/atlas.png (debug dump of the packed atlas) and
 * docs/textures.html (256x256 pixelated preview + the index matrices).
 */
public class GenBlockTextures {

    static final int S = 16;            // pixel-art master resolution
    static final int TILE = 32;         // TextureAtlas.TILE — atlas dump scale
    static final int TILES_PER_ROW = 16;
    static final int T = -1;            // transparent index

    static final File BLOCKS = new File("assets/textures/blocks");

    /** Index offset of ramp B inside a two-ramp palette. */
    static final int RAMP_B = 5;

    // =====================================================================
    // Colour ramps — exactly 5 stops each, 0 = deepest shadow, 4 = highlight.
    // Hue shifting is baked in: read the hex left-to-right and the hue slides
    // from cold (violet/blue) to warm (gold).
    // =====================================================================

    /** Спальник: крашеная шерсть, тёплый красный. */
    static final int[] R_BEDROLL = pal("3A1418", "5A1F22", "7C2E2C", "9E4438", "BC6149");
    /** Подстилка спальника: светлая холстина. */
    static final int[] R_LINEN  = pal("4A4238", "6B6154", "8C8072", "AEA292", "CFC5B4");
    /** Печь: обожжённый камень, теплее и темнее обычного булыжника. */
    static final int[] R_KILN   = pal("2E2A2B", "423C39", "575049", "6E6459", "877C6C");
    /** Жерло печи: копоть с углями. */
    static final int[] R_SOOT   = pal("14100F", "231B18", "342722", "4A342B", "634434");
    /** Жареное мясо: корочка, а не сырая мякоть. */
    static final int[] R_ROAST  = pal("2E1710", "4A2415", "68361C", "8A4E28", "AB6B38");
    /** Оковка и замок сундука: тёмный металл с тёплым бликом. */
    static final int[] R_IRONBIT = pal("22201C", "38342C", "514B3E", "6E6553", "8E8269");
    /** След на снегу и песке: холодная тень, а не чёрная клякса. */
    static final int[] R_STEP   = pal("1A2130", "222B3B", "2C3647", "374252", "44505F");
    static final int[] R_STONE  = pal("3F414D", "55565F", "6B6B70", "81807C", "9A968A");
    static final int[] R_COBBLE = pal("2A2C37", "44464F", "5E5F66", "7A7975", "96938C");
    static final int[] R_DIRT   = pal("39292E", "4C3628", "61452C", "785634", "8F6D3D");
    static final int[] R_GRASS  = pal("27412F", "375930", "4A7032", "5D8935", "7DA43F");
    static final int[] R_SAND   = pal("8C7A66", "AB9670", "C4B182", "DACB98", "F0E5B6");
    static final int[] R_BARK   = pal("2B2126", "3E2D21", "503B27", "654E32", "7E653F");
    static final int[] R_WOOD   = pal("4E3D33", "6A5130", "82653B", "9B7C46", "B69657");
    static final int[] R_PLANK  = pal("42322D", "5F452C", "7A5A36", "966F41", "B28C57");
    static final int[] R_LEAF   = pal("18382A", "234E2A", "2F6631", "3E7F38", "579C45");
    static final int[] R_WATER  = pal("1B3B7C", "24509F", "2E63BE", "4079D4", "63A6E8");
    static final int[] R_BED    = pal("15151E", "25252F", "383842", "4B4A51", "605E5A");
    static final int[] R_SNOW   = pal("A6B3CC", "C0CBDE", "D8E0ED", "EDF2F8", "FFFDF6");
    /** Лёд: глубокий холодный синий в тенях, почти белый блик на гранях трещин. */
    static final int[] R_ICE    = pal("3E5E96", "5F86BB", "86ABD6", "B3D2EC", "E6F5FF");
    static final int[] R_CACTUS = pal("143620", "1D4A27", "285F30", "367539", "7FA455");
    static final int[] R_GLASS  = pal("62808F", "94B2BC", "BFD5DB", "E0EEF1", "FFFFFF");
    static final int[] R_TORCH  = pal("32251E", "6B4E2C", "C4802F", "F2C043", "FFF6BE");
    // Пламя горячее факела: нижний стоп уже оранжевый, верхний почти белый.
    static final int[] R_FIRE   = pal("6E1A06", "B33C08", "E4700F", "F9AE22", "FFE58A");
    static final int[] R_HEART  = pal("140A10", "3A2632", "A31E28", "E23A38", "FF938A");
    static final int[] R_WHITE  = pal("97A0B8", "C2CAD8", "DFE4EC", "F2F4F8", "FFFFFF");
    // Рампы вкраплений руды. Ложатся поверх R_STONE как вторая рампа (5..9),
    // поэтому холодные тени / тёплые блики считаются от них, а не от камня.
    static final int[] R_COAL    = pal("07070B", "121218", "1E1E26", "2D2D37", "45454F");
    static final int[] R_IRON    = pal("5B3C28", "7E5638", "A2764F", "C2986E", "E0BE95");
    static final int[] R_GOLD    = pal("6A4710", "976D19", "C49829", "E8C24C", "FFE9A0");
    static final int[] R_DIAMOND = pal("0E4E58", "18787F", "2BA3A6", "58CCCA", "A6F1EA");
    /** Рукоять инструмента: та же древесина, что у досок, но темнее и суше. */
    static final int[] R_HANDLE = pal("2E2018", "43301F", "5A4229", "715635", "8A6E45");
    /** Головка каменного инструмента — светлее камня, иначе сливается с ним. */
    static final int[] R_TOOLSTONE = pal("4A4C56", "63646C", "7C7C80", "979691", "B3B0A6");
    static final int[] R_TOOLIRON  = pal("6B6B72", "8D8D93", "AFAFB2", "CBCBCB", "E8E8E6");
    // Мясо: тёмная мякоть и светлый жир. Оттенки разведены по видам, иначе
    // четыре куска в хотбаре неразличимы.
    static final int[] R_BEEF    = pal("4A1418", "6E1E22", "94302E", "B8493F", "D2705F");
    static final int[] R_PORK    = pal("6B3038", "8E4A4E", "AE6A68", "C98A86", "E0ABA4");
    static final int[] R_CHICKEN = pal("7A5A34", "9E7947", "BE9A62", "D7B884", "ECD5AC");
    static final int[] R_MUTTON  = pal("55202A", "78303A", "9B4A4C", "BB6A65", "D69086");
    static final int[] R_FAT     = pal("8E8878", "AEA894", "C8C2AC", "DEDAC6", "F2EFE0");
    /** Окорочок в шкале сытости: тёплый коричневый, заметный на тёмном хотбаре. */
    static final int[] R_HUNGER  = pal("1A0C05", "5A2E12", "8A4A1E", "B87434", "E0A55C");
    static final int[] R_BONE    = pal("1A0C05", "8A8270", "B5AD98", "D8D1BC", "F2EEDC");

    /** Crack overlay: greyscale with baked alpha (0 = deepest, 4 = faintest). */
    static final int[] R_CRACK = { 0xD2000000, 0xB4090A0F, 0x8C1A1B22, 0x64303039, 0x3C4A4A54 };

    // =====================================================================
    // main
    // =====================================================================

    public static void main(String[] args) throws IOException {
        if (!BLOCKS.isDirectory())
            throw new IllegalStateException("run from the repo root — missing " + BLOCKS.getAbsolutePath());

        List<Tile> tiles = new ArrayList<>();

        tiles.add(new Tile("grass_top",   R_GRASS, grassTop()));
        tiles.add(new Tile("grass_side",  merge(R_DIRT, R_GRASS), grassSide()));
        tiles.add(new Tile("dirt",        R_DIRT,  dirt(0)));
        tiles.add(new Tile("stone",       R_STONE, stone()));
        tiles.add(new Tile("sand",        R_SAND,  sand()));
        tiles.add(new Tile("log_side",    R_BARK,  logSide()));
        tiles.add(new Tile("log_top",     merge(R_WOOD, R_BARK), logTop()));
        tiles.add(new Tile("leaves",      R_LEAF,  leaves()));
        tiles.add(new Tile("water",       R_WATER, waterFrame(0, true)));
        tiles.add(new Tile("bedrock",     R_BED,   bedrock()));
        tiles.add(new Tile("cobblestone", R_COBBLE, cobble()));
        tiles.add(new Tile("planks",      R_PLANK, planks()));
        tiles.add(new Tile("torch",       R_TORCH, torch()));
        tiles.add(new Tile("particle",    R_WHITE, particle()));
        tiles.add(new Tile("glass",       R_GLASS, glass()));
        tiles.add(new Tile("door",        R_PLANK, doorBottom()));
        tiles.add(new Tile("door_top",    merge(R_PLANK, R_GLASS), doorTop()));
        for (int f = 0; f < 16; f++)
            tiles.add(new Tile(String.format("water_flow_%02d", f), R_WATER, waterFrame(f, false)));
        tiles.add(new Tile("water_particle", R_WATER, waterParticle()));
        tiles.add(new Tile("heart_empty", R_HEART, heart(0)));
        tiles.add(new Tile("heart_full",  R_HEART, heart(2)));
        tiles.add(new Tile("heart_half",  R_HEART, heart(1)));
        int[][][] cr = cracks();
        for (int i = 0; i < 10; i++)
            tiles.add(new Tile("crack_" + i, R_CRACK, cr[i]));
        tiles.add(new Tile("snowy_grass_top",  R_SNOW, snowTop()));
        tiles.add(new Tile("snowy_grass_side", merge(R_DIRT, R_SNOW), snowSide()));
        tiles.add(new Tile("cactus_side", R_CACTUS, cactusSide()));
        tiles.add(new Tile("cactus_top",  R_CACTUS, cactusTop()));
        tiles.add(new Tile("coal_ore",    merge(R_STONE, R_COAL),    ore(311)));
        tiles.add(new Tile("iron_ore",    merge(R_STONE, R_IRON),    ore(457)));
        tiles.add(new Tile("gold_ore",    merge(R_STONE, R_GOLD),    ore(613)));
        tiles.add(new Tile("diamond_ore", merge(R_STONE, R_DIAMOND), ore(769)));
        tiles.add(new Tile("fire",        R_FIRE,  fire()));
        tiles.add(new Tile("snow",        R_SNOW,  snowLayer()));
        // Инструменты: три формы на четырёх материалах. Головка живёт во
        // второй рампе (индексы 5..9), рукоять — в первой.
        int[][] heads = { R_WOOD, R_TOOLSTONE, R_TOOLIRON, R_DIAMOND };
        String[] mats = { "wood", "stone", "iron", "diamond" };
        String[] kinds = { "pickaxe", "axe", "shovel" };
        for (int k = 0; k < kinds.length; k++)
            for (int m = 0; m < mats.length; m++)
                tiles.add(new Tile(mats[m] + "_" + kinds[k],
                        merge(R_HANDLE, heads[m]), tool(k)));
        tiles.add(new Tile("raw_beef",    merge(R_BEEF, R_FAT),    meat(11)));
        tiles.add(new Tile("raw_pork",    merge(R_PORK, R_FAT),    meat(29)));
        tiles.add(new Tile("raw_chicken", merge(R_CHICKEN, R_FAT), meat(47)));
        tiles.add(new Tile("raw_mutton",  merge(R_MUTTON, R_FAT),  meat(63)));
        tiles.add(new Tile("hunger_full",  merge(R_HUNGER, R_BONE), hunger(true)));
        tiles.add(new Tile("hunger_empty", merge(R_BED, R_BED),      hunger(false)));
        tiles.add(new Tile("footprint",   R_STEP, footprint()));
        tiles.add(new Tile("chest_side",  merge(R_PLANK, R_IRONBIT), chest(false, false)));
        tiles.add(new Tile("chest_top",   merge(R_PLANK, R_IRONBIT), chest(true,  false)));
        tiles.add(new Tile("chest_front", merge(R_PLANK, R_IRONBIT), chest(false, true)));
        tiles.add(new Tile("furnace_side",  merge(R_KILN, R_SOOT), furnace(false)));
        tiles.add(new Tile("furnace_front", merge(R_KILN, R_SOOT), furnace(true)));
        tiles.add(new Tile("furnace_top",   merge(R_KILN, R_SOOT), furnaceTop()));
        tiles.add(new Tile("cooked_beef",    merge(R_ROAST, R_FAT), meat(17)));
        tiles.add(new Tile("cooked_pork",    merge(R_ROAST, R_FAT), meat(35)));
        tiles.add(new Tile("cooked_chicken", merge(R_ROAST, R_FAT), meat(53)));
        tiles.add(new Tile("cooked_mutton",  merge(R_ROAST, R_FAT), meat(71)));
        tiles.add(new Tile("ice",            R_ICE, ice()));
        tiles.add(new Tile("bedroll_top",  merge(R_BEDROLL, R_LINEN), bedrollTop()));
        tiles.add(new Tile("bedroll_side", merge(R_BEDROLL, R_LINEN), bedrollSide()));

        // По умолчанию генератор дописывает недостающее и не трогает то,
        // что уже лежит в папке. Источник правды по текстурам — папка
        // ассетов, а не этот код: часть спрайтов перерисована руками и в
        // большем разрешении, и слепой прогон затирал их процедурными 16x16.
        boolean force = args.length > 0 && args[0].equals("--force");
        int written = 0, skipped = 0;
        for (Tile t : tiles) {
            File f = new File(BLOCKS, t.name + ".png");
            if (f.exists() && !force) {
                skipped++;
                continue;
            }
            ImageIO.write(t.toImage(), "png", f);
            written++;
        }
        System.out.println("wrote " + written + " sprites to " + BLOCKS.getPath()
                + (skipped > 0 ? ", kept " + skipped + " existing (--force overwrites)" : ""));

        // Дамп атласа и HTML-превью описывают процедурный набор, а не то, что
        // реально лежит в папке, — поэтому обновляются только при --force.
        if (force) {
            writeAtlasDump(tiles, new File("assets/atlas.png"));
            writePreview(tiles, new File("docs/textures.html"));
        }
    }

    // =====================================================================
    // Tile record + palette helpers
    // =====================================================================

    static final class Tile {
        final String name;
        final int[] pal;
        final int[][] idx;

        Tile(String name, int[] pal, int[][] idx) {
            this.name = name;
            this.pal = pal;
            this.idx = idx;
        }

        BufferedImage toImage() {
            BufferedImage img = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < S; y++)
                for (int x = 0; x < S; x++) {
                    int i = idx[y][x];
                    img.setRGB(x, y, i < 0 ? 0x00000000 : pal[i]);
                }
            return img;
        }
    }

    static int[] pal(String... hex) {
        int[] p = new int[hex.length];
        for (int i = 0; i < hex.length; i++)
            p[i] = 0xFF000000 | Integer.parseInt(hex[i], 16);
        return p;
    }

    static int[] merge(int[] a, int[] b) {
        int[] m = new int[a.length + b.length];
        System.arraycopy(a, 0, m, 0, a.length);
        System.arraycopy(b, 0, m, a.length, b.length);
        return m;
    }

    // =====================================================================
    // Seamless noise + ordered dithering
    // =====================================================================

    /** Hash of a lattice point, wrapped to `period` so the field tiles. */
    static float h(int x, int y, int period, int seed) {
        x = Math.floorMod(x, period);
        y = Math.floorMod(y, period);
        int n = x * 374761393 + y * 668265263 + seed * 1442695041;
        n = (n ^ (n >>> 13)) * 1274126177;
        n = n ^ (n >>> 16);
        return (n & 0xFFFFFF) / (float) 0xFFFFFF;
    }

    /** Value noise on a torus: `period` lattice cells across the 16 px tile. */
    static float noise(float px, float py, int period, int seed) {
        float fx = px * period / (float) S, fy = py * period / (float) S;
        int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
        float tx = fx - x0, ty = fy - y0;
        tx = tx * tx * (3 - 2 * tx);
        ty = ty * ty * (3 - 2 * ty);
        float a = h(x0, y0, period, seed),     b = h(x0 + 1, y0, period, seed);
        float c = h(x0, y0 + 1, period, seed), d = h(x0 + 1, y0 + 1, period, seed);
        return lerp(lerp(a, b, tx), lerp(c, d, tx), ty);
    }

    /** Fractal sum of tileable octaves; weights are normalised. */
    static float fbm(float x, float y, int seed, int[] periods, float[] w) {
        float sum = 0, tot = 0;
        for (int i = 0; i < periods.length; i++) {
            sum += w[i] * noise(x, y, periods[i], seed + i * 977);
            tot += w[i];
        }
        return sum / tot;
    }

    static final int[][] BAYER4 = {
            {  0,  8,  2, 10 },
            { 12,  4, 14,  6 },
            {  3, 11,  1,  9 },
            { 15,  7, 13,  5 } };

    static float bayer(int x, int y) {
        return (BAYER4[Math.floorMod(y, 4)][Math.floorMod(x, 4)] + 0.5f) / 16f;
    }

    /**
     * Quantise a 0..1 value onto `levels` ramp stops, using the 4x4 Bayer
     * matrix to dither the fractional part into a checkerboard of solid
     * pixels instead of blending.
     */
    static int q(float v, int x, int y, int levels) {
        v = clamp01(v);
        float t = v * (levels - 1);
        int i = (int) Math.floor(t);
        if (t - i > bayer(x, y)) i++;
        return Math.max(0, Math.min(levels - 1, i));
    }

    static int q(float v, int x, int y) { return q(v, x, y, 5); }

    static float lerp(float a, float b, float t) { return a + (b - a) * t; }
    static float clamp01(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }
    static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
    static int clampIdx(int i) { return Math.max(0, Math.min(4, i)); }
    static int wrap(int v) { return Math.floorMod(v, S); }

    /** Shortest signed distance on the 16-wide torus. */
    static float wrapDelta(float d) {
        if (d > S / 2f) d -= S;
        if (d < -S / 2f) d += S;
        return d;
    }

    static int[][] blank(int fill) {
        int[][] m = new int[S][S];
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) m[y][x] = fill;
        return m;
    }

    // =====================================================================
    // Ground materials
    // =====================================================================

    /** Grey rock: mid-heavy ramp, cool shadows, a few chipped pits. */
    static int[][] stone() {
        int[][] m = new int[S][S];
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                float n = fbm(x, y, 101, new int[] { 4, 8, 16 }, new float[] { 0.5f, 0.32f, 0.18f });
                m[y][x] = q(0.20f + 0.62f * n, x, y);
            }
        // Pits — small dark clusters that give the surface a read at distance.
        int[] px = { 2, 9, 13, 5, 11, 7 };
        int[] py = { 3, 2, 7, 11, 13, 9 };
        for (int i = 0; i < px.length; i++)
            for (int dy = 0; dy <= 1; dy++)
                for (int dx = 0; dx <= 1; dx++) {
                    if (dx == 1 && dy == 1 && i % 2 == 0) continue;
                    int X = wrap(px[i] + dx), Y = wrap(py[i] + dy);
                    m[Y][X] = clampIdx(m[Y][X] - 2);
                }
        // Warm chips catching the light.
        int[] cx = { 4, 12, 8 }, cy = { 6, 4, 12 };
        for (int i = 0; i < cx.length; i++)
            m[cy[i]][cx[i]] = 4;
        return m;
    }

    /**
     * Инструмент: рукоять по диагонали и головка сверху.
     *
     * Диагональ, а не вертикаль: в 16x16 вертикальная палка читается как
     * столб, а по диагонали силуэт узнаётся даже в слоте хотбара размером
     * с ноготь. Свет, как и везде, падает сверху-слева.
     *
     * @param kind 0 — кирка, 1 — топор, 2 — лопата
     */
    static int[][] tool(int kind) {
        int[][] m = blank(-1);
        // Рукоять: диагональ из нижнего правого угла в верхний левый.
        // Верхний конец приходится на (2,2) — именно туда и садится головка.
        for (int i = 0; i < 12; i++) {
            int x = 13 - i, y = 13 - i;
            put(m, x, y, 2);
            put(m, x + 1, y, 1);      // тень справа
            put(m, x - 1, y - 1, 3);  // блик сверху-слева
        }

        // Головка — вторая рампа, индексы 5..9.
        switch (kind) {
            case 0 -> {               // кирка: дуга поперёк верха рукояти
                for (int i = 0; i <= 9; i++) {
                    int y = 1 + (int) Math.round(Math.abs(i - 4.5) * 0.55);
                    put(m, i, y, i < 5 ? 9 : 8);
                    put(m, i, y + 1, 6);
                }
                put(m, 0, 4, 5);
                put(m, 9, 4, 5);
            }
            case 1 -> {               // топор: клин слева от рукояти
                for (int y = 0; y <= 7; y++)
                    for (int x = 0; x <= 6; x++) {
                        int edge = Math.abs(y - 3);
                        if (x > 6 - edge * 2) continue;
                        put(m, x, y, x <= 1 ? 9 : (edge == 0 ? 8 : 6));
                    }
            }
            default -> {              // лопата: прямоугольный совок
                for (int y = 0; y <= 5; y++)
                    for (int x = 0; x <= 5; x++)
                        put(m, x, y, (y == 0 || x == 0) ? 9 : ((y == 5 || x == 5) ? 6 : 7));
            }
        }
        return m;
    }

    /**
     * Кусок сырого мяса: скруглённый ломоть с прожилками жира.
     *
     * Форма важнее анатомии: в шестнадцати пикселях узнаётся силуэт, а не
     * детали, поэтому это скруглённый прямоугольник с тёмным контуром и
     * двумя-тремя светлыми прожилками второй рампой.
     *
     * @param seed сдвигает прожилки, чтобы четыре вида не совпали
     */
    static int[][] meat(int seed) {
        int[][] m = blank(-1);
        int x0 = 2, x1 = 13, y0 = 3, y1 = 12;
        for (int y = y0; y <= y1; y++)
            for (int x = x0; x <= x1; x++) {
                // Срезаем углы: прямоугольный кусок читается как кирпич.
                boolean corner = (x <= x0 + 1 && y <= y0 + 1) || (x >= x1 - 1 && y <= y0 + 1)
                        || (x <= x0 + 1 && y >= y1 - 1) || (x >= x1 - 1 && y >= y1 - 1);
                if (corner && Math.abs(x - (x <= 7 ? x0 : x1)) + Math.abs(y - (y <= 7 ? y0 : y1)) < 2)
                    continue;
                boolean edge = x == x0 || x == x1 || y == y0 || y == y1;
                float n = fbm(x, y, seed, new int[] { 4, 8 }, new float[] { 0.6f, 0.4f });
                m[y][x] = edge ? 0 : q(0.35f + 0.5f * n, x, y);
            }
        // Прожилки жира — вторая рампа (5..9).
        for (int i = 0; i < 3; i++) {
            int fy = y0 + 2 + i * 3;
            int fx = x0 + 1 + (int) (h(i, i, 5, seed) * 3);
            int len = 4 + (int) (h(i, 0, 4, seed + 7) * 4);
            for (int d = 0; d < len && fx + d <= x1 - 1; d++)
                put(m, fx + d, fy + (d > len / 2 ? 1 : 0), 7 + (d % 2));
        }
        return m;
    }

    /**
     * Значок голода: окорочок на кости. Пустой — тот же силуэт в серых тонах,
     * чтобы полоска читалась как одна шкала, а не как два разных значка.
     */
    static int[][] hunger(boolean full) {
        int[][] m = blank(-1);
        // Мякоть — крупный круг, почти во весь тайл: в шкале значок
        // показывается в 14 пикселей, и мелкая деталь там пропадает.
        for (int y = 0; y <= 12; y++)
            for (int x = 0; x <= 12; x++) {
                float dx = (x - 6f) / 6.2f, dy = (y - 6f) / 6.2f;
                float d = dx * dx + dy * dy;
                if (d > 1f) continue;
                m[y][x] = d > 0.78f ? 0 : (full ? q(0.30f + 0.65f * (1f - d), x, y) : 1);
            }
        // Кость — короткая ножка вправо-вниз, второй рампой (5..9).
        for (int i = 0; i < 5; i++) {
            int bx = 10 + i, by = 10 + i / 2;
            put(m, bx, by, full ? 8 : 5);
            put(m, bx, by + 1, full ? 6 : 5);
            put(m, bx, by + 2, 5);
        }
        put(m, 14, 13, full ? 9 : 5);
        put(m, 15, 13, 5);
        return m;
    }

    /** Ставит индекс, молча игнорируя выход за тайл. */
    static void put(int[][] m, int x, int y, int idx) {
        if (x < 0 || y < 0 || x >= S || y >= S) return;
        m[y][x] = idx;
    }

    /**
     * Свежий снег: почти ровное белое поле с редким холодным крапом.
     *
     * Соблазн навалить шума велик, но снег на расстоянии обязан читаться как
     * сплошная белая плоскость — иначе поле превращается в шипящий телевизор.
     * Поэтому основная масса сидит на двух верхних стопах рампы, а нижние
     * появляются редкими одиночными пикселями.
     */
    static int[][] snowLayer() {
        int[][] m = new int[S][S];
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                float n = fbm(x, y, 404, new int[] { 4, 8 }, new float[] { 0.6f, 0.4f });
                m[y][x] = q(0.62f + 0.36f * n, x, y);
            }
        // Редкие голубоватые впадины — след ветра, а не шум.
        int[] dx = { 3, 11, 6, 14 }, dy = { 5, 2, 12, 9 };
        for (int i = 0; i < dx.length; i++) {
            m[wrap(dy[i])][wrap(dx[i])] = 1;
            m[wrap(dy[i])][wrap(dx[i] + 1)] = 2;
        }
        return m;
    }

    /**
     * Пламя: язык огня с прозрачным фоном, горячий у основания и рваный
     * сверху. Рисуется как крест (две плоскости), поэтому силуэт важнее
     * объёма — читаться должен с любой стороны.
     *
     * Бесшовность здесь не нужна и вредна: огонь стоит отдельным спрайтом,
     * а не тайлится, поэтому по краям он обязан уходить в прозрачность.
     */
    static int[][] fire() {
        int[][] m = blank(-1);
        for (int y = 0; y < S; y++) {
            // v — высота от основания: 0 внизу тайла, 1 наверху.
            float v = (S - 1 - y) / (float) (S - 1);
            // Ширина языка сужается кверху, но не линейно: у основания
            // пламя лежит широкой лужей, к вершине собирается в острие.
            float half = (0.46f - 0.40f * v * v) * S;
            // Две волны разной частоты гуляют по оси: без них язык выходит
            // симметричной каплей и читается как лист, а не как огонь.
            float sway = (float) (Math.sin(v * 7.0) * 1.5 + Math.sin(v * 3.1 + 1.2) * 1.0);
            float cx = S * 0.5f + sway;
            for (int x = 0; x < S; x++) {
                float d = Math.abs(x + 0.5f - cx) / Math.max(0.6f, half);
                if (d > 1f)
                    continue;
                // Ядро горячее краёв, основание горячее вершины: в пламени
                // самая горячая точка внизу, а кверху оно остывает и рвётся.
                float heat = (1f - d * d) * (1.05f - 0.55f * v);
                // Крап по краю: ровная кромка выглядит пластиковой.
                heat += (h(x, y, 16, 909) - 0.5f) * 0.22f;
                if (heat < 0.10f)
                    continue;
                m[y][x] = q(Math.min(0.99f, heat), x, y);
            }
        }
        return m;
    }

    /**
     * Руда: камень плюс несколько вкраплений второй рампой.
     *
     * Каждое вкрапление — не пятно одного цвета, а маленький объём: тёмный
     * контур, светлое ядро, блик со стороны света (сверху-слева, как у всех
     * остальных тайлов). Иначе на расстоянии руда читается как грязь.
     *
     * Всё считается по тору через wrap(), поэтому тайл остаётся бесшовным.
     *
     * @param seed сдвигает раскладку вкраплений, чтобы четыре руды не совпали
     */
    static int[][] ore(int seed) {
        int[][] m = stone();
        // Дрожащая решётка 2x2 вместо чистого хэша: хэш кучкует вкрапления
        // в одном углу и оставляет половину тайла голой.
        int G = 2, cell = S / G;
        for (int i = 0; i < G * G; i++) {
            int gx = i % G, gy = i / G;
            int cx = gx * cell + (int) (h(gx, gy, 8, seed) * cell);
            int cy = gy * cell + (int) (h(gy, gx, 8, seed + 31) * cell);
            int r = 1 + (int) (h(i, i, 3, seed + 57) * 2);   // радиус 1..2
            for (int dy = -r - 1; dy <= r + 1; dy++)
                for (int dx = -r - 1; dx <= r + 1; dx++) {
                    int d2 = dx * dx + dy * dy;
                    if (d2 > (r + 1) * (r + 1))
                        continue;
                    int X = wrap(cx + dx), Y = wrap(cy + dy);
                    int idx;
                    if (d2 > r * r)
                        idx = 5;                       // тёмный контур
                    else if (dx <= 0 && dy <= 0)
                        idx = d2 <= 1 ? 9 : 8;         // блик со стороны света
                    else
                        idx = d2 <= 1 ? 7 : 6;
                    m[Y][X] = idx;
                }
        }
        return m;
    }

    /**
     * Cobblestone: nine jittered stones on a torus, separated by dark mortar.
     * Each stone is lit from the top-left, so the surface reads as volume
     * rather than noise.
     */
    static int[][] cobble() {
        int G = 3;
        float cell = S / (float) G;
        float[] sx = new float[G * G], sy = new float[G * G];
        int[] tone = new int[G * G];
        Random r = new Random(7);
        for (int gy = 0; gy < G; gy++)
            for (int gx = 0; gx < G; gx++) {
                int i = gy * G + gx;
                sx[i] = (gx + 0.28f + 0.44f * r.nextFloat()) * cell;
                sy[i] = (gy + 0.28f + 0.44f * r.nextFloat()) * cell;
                tone[i] = 2 + r.nextInt(2);
            }

        int[][] m = new int[S][S];
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                // Warp the sample point so stone borders wobble like real rock.
                float wx = x + 2.2f * (noise(x, y, 4, 31) - 0.5f);
                float wy = y + 2.2f * (noise(x, y, 4, 57) - 0.5f);
                int best = 0;
                float d1 = 1e9f, d2 = 1e9f;
                for (int i = 0; i < sx.length; i++) {
                    float dx = wrapDelta(wx - sx[i]), dy = wrapDelta(wy - sy[i]);
                    float d = (float) Math.sqrt(dx * dx + dy * dy);
                    if (d < d1) { d2 = d1; d1 = d; best = i; }
                    else if (d < d2) { d2 = d; }
                }
                if (d2 - d1 < 1.15f) {          // mortar gap between stones
                    m[y][x] = 0;
                    continue;
                }
                float ox = wrapDelta(x - sx[best]), oy = wrapDelta(y - sy[best]);
                float lit = -(ox + oy) / (cell * 0.9f);   // top-left = light
                float grain = noise(x, y, 8, 13) - 0.5f;
                m[y][x] = clampIdx(tone[best] + Math.round(clamp(lit, -1.4f, 1.4f) + grain * 0.9f));
            }
        return m;
    }

    /** Loose earth: warm ochre body, violet-shifted shadows, gritty specks. */
    static int[][] dirt(int seedOff) {
        int[][] m = new int[S][S];
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                float n = fbm(x, y, 211 + seedOff, new int[] { 4, 8, 16 },
                        new float[] { 0.35f, 0.40f, 0.25f });
                m[y][x] = q(0.22f + 0.60f * n, x, y);
            }
        Random r = new Random(3 + seedOff);
        for (int i = 0; i < 22; i++) {                 // grit
            int x = r.nextInt(S), y = r.nextInt(S);
            m[y][x] = clampIdx(m[y][x] + (r.nextBoolean() ? 1 : -2));
        }
        for (int i = 0; i < 5; i++) {                  // small pebbles
            int x = r.nextInt(S), y = r.nextInt(S);
            m[y][x] = 4;
            m[wrap(y + 1)][x] = clampIdx(m[wrap(y + 1)][x] - 1);
        }
        return m;
    }

    /** Grass from above: clumped noise plus scattered bright/dark blades. */
    static int[][] grassTop() {
        int[][] m = new int[S][S];
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                float n = fbm(x, y, 313, new int[] { 4, 8, 16 },
                        new float[] { 0.30f, 0.40f, 0.30f });
                m[y][x] = q(0.18f + 0.66f * n, x, y);
            }
        Random r = new Random(19);
        for (int i = 0; i < 26; i++) {
            int x = r.nextInt(S), y = r.nextInt(S);
            m[y][x] = clampIdx(m[y][x] + (r.nextInt(3) == 0 ? 2 : -2));
        }
        return m;
    }

    /**
     * Grass side: dirt body with a grass cap whose lower edge drips down in
     * irregular tongues, the way the overlay does in Minecraft.
     */
    static int[][] grassSide() {
        int[][] m = dirt(9);
        for (int x = 0; x < S; x++) {
            float n = noise(x, 0, 8, 71) * 0.6f + noise(x, 0, 4, 137) * 0.4f;
            int hgt = 3 + Math.round(n * 3.4f);        // 3..6 px of cap
            for (int y = 0; y < hgt; y++) {
                float shade = 0.30f + 0.55f * fbm(x, y, 401,
                        new int[] { 4, 8 }, new float[] { 0.5f, 0.5f });
                m[y][x] = RAMP_B + q(shade, x, y);
            }
            // Dithered fringe: single blades hanging one row lower.
            if (bayer(x, hgt) < 0.45f && hgt < S)
                m[hgt][x] = RAMP_B + q(0.25f + 0.3f * noise(x, hgt, 8, 23), x, hgt);
        }
        // Shadow line where the cap meets the earth.
        for (int x = 0; x < S; x++)
            for (int y = 1; y < S; y++)
                if (m[y][x] < RAMP_B && m[y - 1][x] >= RAMP_B)
                    m[y][x] = clampIdx(m[y][x] - 1);
        return m;
    }

    /** Sand: bright warm grains, cool shadow pockets, heavy fine dithering. */
    static int[][] sand() {
        int[][] m = new int[S][S];
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                float n = fbm(x, y, 509, new int[] { 8, 16 }, new float[] { 0.45f, 0.55f });
                m[y][x] = q(0.34f + 0.56f * n, x, y);
            }
        Random r = new Random(11);
        for (int i = 0; i < 18; i++) {
            int x = r.nextInt(S), y = r.nextInt(S);
            m[y][x] = clampIdx(m[y][x] - 2);           // dark grains
        }
        return m;
    }

    /** Snow from above: near-white with cool blue-grey dithered hollows. */
    static int[][] snowTop() {
        int[][] m = new int[S][S];
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                float n = fbm(x, y, 601, new int[] { 4, 8, 16 },
                        new float[] { 0.45f, 0.35f, 0.20f });
                m[y][x] = q(0.48f + 0.52f * n, x, y);
            }
        Random r = new Random(29);
        for (int i = 0; i < 10; i++)
            m[r.nextInt(S)][r.nextInt(S)] = 1;         // sparkle hollows
        return m;
    }

    /** Snow cap over dirt, with an uneven wind-blown lower edge. */
    static int[][] snowSide() {
        int[][] m = dirt(17);
        for (int x = 0; x < S; x++) {
            float n = noise(x, 0, 8, 83) * 0.65f + noise(x, 0, 16, 149) * 0.35f;
            int hgt = 4 + Math.round(n * 3.0f);
            for (int y = 0; y < hgt; y++) {
                float shade = 0.45f + 0.5f * fbm(x, y, 701,
                        new int[] { 4, 8 }, new float[] { 0.5f, 0.5f });
                m[y][x] = RAMP_B + q(shade, x, y);
            }
            if (bayer(x + 2, hgt) < 0.4f && hgt < S)
                m[hgt][x] = RAMP_B + q(0.30f, x, hgt);
        }
        for (int x = 0; x < S; x++)
            for (int y = 1; y < S; y++)
                if (m[y][x] < RAMP_B && m[y - 1][x] >= RAMP_B)
                    m[y][x] = clampIdx(m[y][x] - 1);
        return m;
    }

    /** Bedrock: chunky 2x2 blotches of near-black, no readable structure. */
    static int[][] bedrock() {
        int[][] m = new int[S][S];
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                float blot = h(x / 2, y / 2, S / 2, 811);
                float fine = noise(x, y, 16, 823);
                m[y][x] = q(0.10f + 0.60f * blot + 0.20f * fine, x, y);
            }
        return m;
    }

    // =====================================================================
    // Wood
    // =====================================================================

    /** Bark: vertical fibres with two wandering crevices, warm highlights. */
    static int[][] logSide() {
        int[][] m = new int[S][S];
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                float col = noise(x, 0, 16, 907) * 0.6f + noise(x, 0, 8, 911) * 0.4f;
                float along = noise(x, y, 4, 919);      // slow vertical variation
                m[y][x] = q(0.22f + 0.52f * col + 0.22f * along, x, y);
            }
        for (int base : new int[] { 3, 11 }) {          // crevices
            for (int y = 0; y < S; y++) {
                int cx = wrap(base + Math.round(2f * (noise(0, y, 4, 929 + base) - 0.5f)));
                m[y][wrap(cx - 1)] = clampIdx(m[y][wrap(cx - 1)] + 1);
                m[y][wrap(cx + 1)] = clampIdx(m[y][wrap(cx + 1)] - 1);
                m[y][cx] = 0;
            }
        }
        return m;
    }

    /**
     * Log end grain: annual rings around the pith, framed by a 2 px bark
     * border so neighbouring log tops butt together cleanly. The ring radius
     * is only lightly perturbed — enough to break the compass-drawn circle,
     * not enough to lose the concentric read.
     */
    static int[][] logTop() {
        int[][] m = new int[S][S];
        float cx = 7.5f, cy = 7.5f;
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                float dx = x - cx, dy = y - cy;
                float r = (float) Math.sqrt(dx * dx + dy * dy)
                        + 0.7f * (noise(x, y, 4, 1013) - 0.5f);
                int band = Math.round(r * 0.85f);
                int idx = band % 2 == 0 ? 3 : 2;
                if (band % 4 == 1) idx = 1;             // darker growth ring
                if (r < 1.3f) idx = 1;                  // pith
                m[y][x] = clampIdx(idx + (bayer(x, y) < 0.20f ? 1 : 0));
            }
        // Bark frame — clearly darker than any heartwood tone.
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                int edge = Math.min(Math.min(x, S - 1 - x), Math.min(y, S - 1 - y));
                if (edge == 0)
                    m[y][x] = RAMP_B + q(0.05f + 0.30f * noise(x, y, 16, 1021), x, y);
                else if (edge == 1)
                    m[y][x] = RAMP_B + q(0.25f + 0.35f * noise(x, y, 16, 1031), x, y);
            }
        return m;
    }

    /** Four horizontal boards, grain streaks, seams and staggered end-cuts. */
    static int[][] planks() {
        int[][] m = new int[S][S];
        for (int y = 0; y < S; y++) {
            int board = y / 4;
            float boardTone = (h(board, 0, 4, 1103) - 0.5f) * 0.18f;
            for (int x = 0; x < S; x++) {
                float grain = noise(x, y, 16, 1109 + board * 13) * 0.55f
                        + noise(x, y, 8, 1117) * 0.45f;
                float v = 0.34f + 0.42f * grain + boardTone;
                if (y % 4 == 0) v += 0.16f;             // lit top edge of a board
                m[y][x] = q(v, x, y);
            }
            if (y % 4 == 3)                              // seam between boards
                for (int x = 0; x < S; x++)
                    m[y][x] = bayer(x, y) < 0.75f ? 0 : 1;
        }
        int[] jointBoard = { 0, 2 }, jointX = { 11, 5 };
        for (int i = 0; i < jointBoard.length; i++)
            for (int y = jointBoard[i] * 4; y < jointBoard[i] * 4 + 3; y++)
                m[y][jointX[i]] = 0;
        return m;
    }

    // =====================================================================
    // Foliage
    // =====================================================================

    /** Leaves: clumped green with real cutout holes and darkened rims. */
    static int[][] leaves() {
        int[][] m = new int[S][S];
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                float n = fbm(x, y, 1201, new int[] { 4, 8, 16 },
                        new float[] { 0.30f, 0.42f, 0.28f });
                m[y][x] = q(0.04f + 0.94f * n, x, y);
            }
        // Holes: clustered and seamless because the mask itself tiles. The
        // cut-off is a quantile, not a fixed value, so the hole count is
        // exact no matter how the noise happens to be distributed.
        float[][] mask = new float[S][S];
        float[] sorted = new float[S * S];
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                mask[y][x] = fbm(x, y, 1213, new int[] { 4, 8 }, new float[] { 0.65f, 0.35f });
                sorted[y * S + x] = mask[y][x];
            }
        java.util.Arrays.sort(sorted);
        float cut = sorted[(int) (0.15f * S * S)];       // 15 % of the tile is gaps
        boolean[][] hole = new boolean[S][S];
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) hole[y][x] = mask[y][x] < cut;
        // Darken the pixels bordering a hole so gaps read as depth.
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                if (hole[y][x]) continue;
                if (hole[wrap(y - 1)][x] || hole[wrap(y + 1)][x]
                        || hole[y][wrap(x - 1)] || hole[y][wrap(x + 1)])
                    m[y][x] = clampIdx(m[y][x] - 2);
            }
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++)
                if (hole[y][x]) m[y][x] = T;
        return m;
    }

    /** Cactus flank: four vertical ribs with grooves on the tile seam. */
    static int[][] cactusSide() {
        int[][] m = new int[S][S];
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                float rib = -(float) Math.cos(2 * Math.PI * x / 4.0);   // groove at x=0,4,8,12
                float v = 0.44f + 0.34f * rib + 0.14f * (noise(x, y, 4, 1301) - 0.5f);
                m[y][x] = q(v, x, y, 4);                 // flesh uses stops 0..3
            }
        // Spines on two of the four rib crests — enough to read as a cactus,
        // few enough that they do not turn into noise.
        for (int cxi : new int[] { 2, 10 })
            for (int y = cxi == 2 ? 1 : 3; y < S; y += 4)
                m[y][cxi] = 4;
        return m;
    }

    /** Cactus crown: dark rim, ribbed flesh, spined areole in the middle. */
    static int[][] cactusTop() {
        int[][] m = new int[S][S];
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                int edge = Math.min(Math.min(x, S - 1 - x), Math.min(y, S - 1 - y));
                float n = noise(x, y, 8, 1409);
                if (edge == 0) m[y][x] = 0;
                else if (edge == 1) m[y][x] = 1;
                else if (edge == 3) m[y][x] = 1;         // inset groove
                else m[y][x] = q(0.55f + 0.40f * n, x, y, 4);
            }
        // Areole: dark centre with four spine tufts, like the crown of a cactus.
        m[7][7] = m[7][8] = m[8][7] = m[8][8] = 0;
        m[6][7] = m[6][8] = m[9][7] = m[9][8] = 4;
        m[7][6] = m[8][6] = m[7][9] = m[8][9] = 4;
        return m;
    }

    // =====================================================================
    // Water — one 16-frame loop, each frame seamless on its own
    // =====================================================================

    /**
     * @param frame 0..15; the pattern scrolls exactly one pixel per frame so
     *              frame 16 lands back on frame 0.
     * @param still the calm surface variant used for the WATER block.
     */
    static int[][] waterFrame(int frame, boolean still) {
        int[][] m = new int[S][S];
        float amp = still ? 0.16f : 0.26f;
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                int yy = wrap(y - frame);
                double warp = 2.0 * Math.sin(2 * Math.PI * x / (double) S);
                float wave = (float) Math.sin(2 * Math.PI * (yy + warp) / (double) S);
                float n = fbm(x, yy, 1511, new int[] { 4, 8 }, new float[] { 0.6f, 0.4f });
                m[y][x] = q(0.46f + amp * wave + 0.30f * (n - 0.5f), x, y);
            }
        // Foam crests: keep only some of the brightest pixels so the highlight
        // reads as scattered sparkle rather than a solid band.
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++)
                if (m[y][x] == 4 && h(x, wrap(y - frame), S, 1523) < 0.45f)
                    m[y][x] = 3;
        return m;
    }

    /** Splash droplet — blob with a two-pixel tail, transparent elsewhere. */
    static int[][] waterParticle() {
        return fromArt(new String[] {
                "................",
                "................",
                "......2233......",
                ".....2233333....",
                "....22333344....",
                "....2333344444..",
                "....2333444444..",
                ".....23344444...",
                "......334444....",
                ".......334......",
                ".......22.......",
                "................",
                "................",
                "................",
                "................",
                "................" });
    }

    // =====================================================================
    // Props: torch, particle, glass, doors
    // =====================================================================

    /** Torch billboard: warm stick, three-stop flame, hot core on top. */
    static int[][] torch() {
        return fromArt(new String[] {
                ".......44.......",
                "......34433.....",
                "......33433.....",
                ".......332......",
                ".......21.......",
                ".......11.......",
                ".......10.......",
                ".......11.......",
                ".......10.......",
                ".......11.......",
                ".......10.......",
                ".......11.......",
                ".......10.......",
                ".......11.......",
                ".......10.......",
                ".......10......." });
    }

    /** Round white sprite tinted at runtime; hard rim, dithered falloff. */
    static int[][] particle() {
        int[][] m = blank(T);
        float cx = 7.5f, cy = 7.5f;
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                float d = (float) Math.hypot(x - cx, y - cy);
                if (d > 7.6f) continue;
                if (d <= 5.0f) m[y][x] = 4;
                else if (d <= 6.4f) m[y][x] = bayer(x, y) < 0.55f ? 4 : 3;
                else if (d <= 7.2f) m[y][x] = bayer(x, y) < 0.50f ? 3 : 2;
                else if (bayer(x, y) < 0.35f) m[y][x] = 2;
            }
        return m;
    }

    /**
     * Лёд: гладкое поле средних стопов с глубокими пятнами и сетью трещин.
     *
     * Трещина — это пара пикселей: тёмная щель и светлый скол рядом, иначе на
     * шестнадцати пикселях она читается как грязь. Линии идут наискось и
     * заворачивают через край тайла, чтобы замёрзшее озеро не складывалось в
     * клетку. Пузырьки воздуха — одиночные светлые точки.
     */
    static int[][] ice() {
        int[][] m = new int[S][S];
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                float n = fbm(x, y, 1777, new int[] { 4, 8 }, new float[] { 0.65f, 0.35f });
                m[y][x] = q(0.38f + 0.42f * n, x, y);
            }
        // Две длинные трещины наискось через весь тайл, с заворотом по краю.
        int[][] cracks = { { 0, 3, 1, 1 }, { 5, 15, 1, -1 } };
        for (int[] c : cracks) {
            int x = c[0], y = c[1];
            for (int i = 0; i < 18; i++) {
                m[wrap(y)][wrap(x)] = 0;
                m[wrap(y + 1)][wrap(x)] = 4;
                x += c[2];
                if (i % 3 == 2)
                    y += c[3];
            }
        }
        int[] bx = { 3, 12, 9, 6 }, by = { 11, 6, 13, 2 };
        for (int i = 0; i < bx.length; i++)
            m[by[i]][bx[i]] = 4;
        return m;
    }

    /** Glass pane: bright frame, corner shadows, one diagonal glint. */
    static int[][] glass() {
        int[][] m = blank(T);
        for (int i = 0; i < S; i++) {
            m[0][i] = 3; m[S - 1][i] = 2; m[i][0] = 3; m[i][S - 1] = 2;
        }
        m[0][0] = 4; m[0][S - 1] = 3; m[S - 1][0] = 3; m[S - 1][S - 1] = 1;
        m[1][1] = 1; m[1][S - 2] = 1; m[S - 2][1] = 1; m[S - 2][S - 2] = 0;
        for (int i = 0; i < 3; i++) m[5 + i][10 - i] = 4;   // main glint
        m[9][6] = 3; m[10][5] = 3;                          // its tail
        m[11][11] = 4; m[12][10] = 3;                       // second, smaller glint
        m[3][12] = 2; m[12][4] = 2;                         // dust specks
        return m;
    }

    /** Lower door half: vertical boards, stiles, sunken panel, threshold. */
    static int[][] doorBottom() {
        int[][] m = doorBody(2251);
        panel(m, 2, 1, 13, 12);
        for (int x = 0; x < S; x++) m[S - 1][x] = 0;
        return m;
    }

    /** Upper door half: same boards, glazed window, brass handle. */
    static int[][] doorTop() {
        int[][] m = doorBody(2267);
        for (int x = 0; x < S; x++) m[0][x] = 0;         // head rail
        panel(m, 2, 2, 13, 13);
        // Four-pane window with wooden mullions and a dark frame.
        for (int y = 4; y <= 9; y++)
            for (int x = 4; x <= 11; x++) {
                if (x == 7 || x == 8 || y == 6 || y == 7) { m[y][x] = 1; continue; }
                int shade = y < 6 ? 3 : 2;
                if (bayer(x, y) < 0.3f) shade = Math.min(4, shade + 1);
                m[y][x] = RAMP_B + shade;
            }
        for (int y = 3; y <= 10; y++) { m[y][3] = 0; m[y][12] = 0; }
        for (int x = 3; x <= 12; x++) { m[3][x] = 0; m[10][x] = 0; }
        // Brass handle on the swing side, at the door's mid height. Drawn last
        // so the panel edge underneath does not eat the outline.
        m[11][11] = 0; m[11][12] = 0;
        m[12][11] = 4; m[12][12] = 3;
        m[13][11] = 2; m[13][12] = 0;
        return m;
    }

    /** Shared door substrate: vertical boards with grain and edge stiles. */
    static int[][] doorBody(int seed) {
        int[][] m = new int[S][S];
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                float grain = noise(x, y, 16, seed) * 0.5f + noise(x, y, 4, seed + 7) * 0.5f;
                float v = 0.34f + 0.42f * grain;
                if (x % 5 == 0) v -= 0.28f;              // board seams
                m[y][x] = q(v, x, y);
            }
        for (int y = 0; y < S; y++) { m[y][0] = 1; m[y][S - 1] = 0; }
        return m;
    }

    /** Sunken rectangular panel: dark top/left edge, lit bottom/right edge. */
    static void panel(int[][] m, int x0, int y0, int x1, int y1) {
        for (int x = x0; x <= x1; x++) {
            m[y0][x] = 0;
            m[y1][x] = clampIdx(m[y1][x] + 2);
        }
        for (int y = y0; y <= y1; y++) {
            m[y][x0] = 0;
            m[y][x1] = clampIdx(m[y][x1] + 2);
        }
    }

    // =====================================================================
    // HUD hearts
    // =====================================================================

    /** @param fill 0 = empty, 1 = half, 2 = full. */
    static int[][] heart(int fill) {
        String[] shape = {
                "................",
                "................",
                "................",
                "....##....##....",
                "...####..####...",
                "..############..",
                "..############..",
                "..############..",
                "...##########...",
                "....########....",
                ".....######.....",
                "......####......",
                ".......##.......",
                "................",
                "................",
                "................" };
        boolean[][] solid = new boolean[S][S];
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) solid[y][x] = shape[y].charAt(x) == '#';

        int[][] m = blank(T);
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                if (!solid[y][x]) continue;
                boolean rim = !get(solid, x - 1, y) || !get(solid, x + 1, y)
                        || !get(solid, x, y - 1) || !get(solid, x, y + 1);
                if (rim) { m[y][x] = 0; continue; }      // black outline
                boolean lit = fill == 2 || (fill == 1 && x < 8);
                if (!lit) { m[y][x] = 1; continue; }     // drained interior
                m[y][x] = x + y > 17 ? 2 : 3;            // bottom-right shadow
            }
        if (fill > 0) {                                  // specular drop
            m[6][4] = 4; m[6][5] = 4; m[7][4] = 4;
        }
        return m;
    }

    static boolean get(boolean[][] a, int x, int y) {
        return x >= 0 && x < S && y >= 0 && y < S && a[y][x];
    }

    // =====================================================================
    // Mining crack overlay — 10 stages of one growing fracture network
    // =====================================================================

    static int[][][] cracks() {
        int[][] birth = new int[S][S];
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) birth[y][x] = 99;

        Random r = new Random(20240915L);
        // Walker = {x, y, angle, firstStep, lastStep}
        List<double[]> walkers = new ArrayList<>();
        int branches = 5;
        for (int b = 0; b < branches; b++)
            walkers.add(new double[] { 7.5, 7.5,
                    2 * Math.PI * b / branches + (r.nextDouble() - 0.5) * 0.6, 0, 20 });

        for (int i = 0; i < walkers.size(); i++) {
            double[] w = walkers.get(i);
            double px = w[0], py = w[1], ang = w[2];
            for (int s = (int) w[3]; s < (int) w[4]; s++) {
                int ix = (int) Math.round(px), iy = (int) Math.round(py);
                if (ix < 0 || ix >= S || iy < 0 || iy >= S) break;
                int stage = Math.min(9, (int) Math.floor(s * 10.0 / 20.0));
                birth[iy][ix] = Math.min(birth[iy][ix], stage);
                ang += (r.nextDouble() - 0.5) * 0.95;
                px += Math.cos(ang) * 1.05;
                py += Math.sin(ang) * 1.05;
                if (s >= 6 && s < 16 && r.nextInt(5) == 0 && walkers.size() < 14)
                    walkers.add(new double[] { px, py,
                            ang + (r.nextBoolean() ? 1.1 : -1.1), s + 1, Math.min(20, s + 8) });
            }
        }

        int[][][] out = new int[10][][];
        for (int stage = 0; stage < 10; stage++) {
            int[][] m = blank(T);
            for (int y = 0; y < S; y++)
                for (int x = 0; x < S; x++)
                    if (birth[y][x] <= stage) m[y][x] = 0;
            // One-pixel faint halo so the fracture reads on bright blocks too.
            int[][] halo = blank(T);
            for (int y = 0; y < S; y++)
                for (int x = 0; x < S; x++) {
                    if (m[y][x] == 0) continue;
                    boolean near = (x > 0 && m[y][x - 1] == 0) || (x < S - 1 && m[y][x + 1] == 0)
                            || (y > 0 && m[y - 1][x] == 0) || (y < S - 1 && m[y + 1][x] == 0);
                    if (near) halo[y][x] = bayer(x, y) < 0.6f ? 3 : 4;
                }
            for (int y = 0; y < S; y++)
                for (int x = 0; x < S; x++)
                    if (m[y][x] == T && halo[y][x] != T) m[y][x] = halo[y][x];
            out[stage] = m;
        }
        return out;
    }

    /**
     * Спальник сверху: свёрнутая скатка с подушкой по центру.
     *
     * Рисунок симметричен по обеим осям намеренно. Мешер не умеет крутить
     * текстуру по мете, и у несимметричного узора подушка оказывалась бы
     * поперёк спальника, стоило положить его другой стороной.
     */
    static int[][] bedrollTop() {
        int[][] m = blank(0);
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                float n = fbm(x, y, 33, new int[] { 4, 8 }, new float[] { 0.6f, 0.4f });
                m[y][x] = q(0.35f + 0.5f * n, x, y);
            }
        // Кайма по краю — край скатки.
        for (int i = 0; i < S; i++) {
            put(m, i, 0, 0);
            put(m, i, S - 1, 0);
            put(m, 0, i, 0);
            put(m, S - 1, i, 0);
        }
        // Подушка: светлый прямоугольник ровно по центру.
        for (int y = 5; y <= 10; y++)
            for (int x = 4; x <= 11; x++) {
                boolean rim = y == 5 || y == 10 || x == 4 || x == 11;
                put(m, x, y, RAMP_B + (rim ? 1 : 3));
            }
        return m;
    }

    /** Спальник сбоку: полоса ткани с подстилкой понизу. */
    static int[][] bedrollSide() {
        int[][] m = blank(0);
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                float n = fbm(x, y, 41, new int[] { 4, 8 }, new float[] { 0.6f, 0.4f });
                // Нижняя четверть — холстина, выше — шерсть.
                m[y][x] = y >= 12 ? RAMP_B + q(0.3f + 0.5f * n, x, y) / 2
                        : q(0.35f + 0.5f * n, x, y);
            }
        for (int x = 0; x < S; x++)
            put(m, x, 11, 0);
        return m;
    }

    /**
     * Печь: кладка из обожжённого камня.
     *
     * У лицевой грани — жерло с решёткой. Без него печь неотличима от куска
     * стены, и непонятно, с какой стороны она «работает».
     */
    static int[][] furnace(boolean front) {
        int[][] m = blank(0);
        // Кладка: ряды кирпича со смещением через ряд.
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                int row = y / 4;
                int shift = (row % 2) * 2;
                boolean mortar = (y % 4 == 0) || ((x + shift) % 8 == 0);
                float n = fbm(x, y, 21, new int[] { 4, 8 }, new float[] { 0.6f, 0.4f });
                m[y][x] = mortar ? 1 : q(0.4f + 0.5f * n, x, y);
            }
        if (!front)
            return m;
        // Жерло: тёмный проём с двумя перекладинами решётки.
        for (int y = 6; y <= 12; y++)
            for (int x = 3; x <= 12; x++) {
                boolean rim = y == 6 || y == 12 || x == 3 || x == 12;
                put(m, x, y, RAMP_B + (rim ? 2 : 0));
            }
        for (int x = 4; x <= 11; x++) {
            put(m, x, 8, RAMP_B + 3);
            put(m, x, 11, RAMP_B + 3);
        }
        return m;
    }

    /** Крышка печи: та же кладка, но с закопчённым устьем по центру. */
    static int[][] furnaceTop() {
        int[][] m = furnace(false);
        for (int y = 5; y <= 10; y++)
            for (int x = 5; x <= 10; x++) {
                boolean rim = y == 5 || y == 10 || x == 5 || x == 10;
                put(m, x, y, RAMP_B + (rim ? 3 : 1));
            }
        return m;
    }

    /**
     * Сундук: доски с оковкой.
     *
     * Крышка отделена полосой оковки по горизонтали — по ней сундук
     * опознаётся с любой стороны. У лицевой грани сверх того замок по центру,
     * иначе перед и бок неразличимы, и непонятно, куда смотреть.
     */
    static int[][] chest(boolean top, boolean front) {
        int[][] m = blank(0);
        // Доски: три горизонтальных полосы с щелями между ними.
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                int band = (y / 5) % 2;
                int idx = band == 0 ? 2 : 3;
                if (y % 5 == 0) idx = 0;                 // щель между досками
                if (x == 0 || x == S - 1) idx = 1;       // тёмный край
                m[y][x] = idx;
            }
        // Оковка по краям — рамка в один пиксель.
        for (int i = 0; i < S; i++) {
            put(m, i, 0, RAMP_B + 1);
            put(m, i, S - 1, RAMP_B + 1);
            put(m, 0, i, RAMP_B + 1);
            put(m, S - 1, i, RAMP_B + 1);
        }
        if (top) {
            // Крышка: оковка двумя полосами поперёк, как петли.
            for (int y = 0; y < S; y++) {
                put(m, 3, y, RAMP_B + 2);
                put(m, 4, y, RAMP_B + 1);
                put(m, 11, y, RAMP_B + 2);
                put(m, 12, y, RAMP_B + 1);
            }
            return m;
        }
        // Бока и перёд: линия раздела крышки и короба.
        for (int x = 0; x < S; x++) {
            put(m, x, 5, RAMP_B);
            put(m, x, 6, RAMP_B + 3);
        }
        if (front) {
            // Замок: накладка с язычком по центру, чуть ниже раздела.
            for (int y = 4; y <= 9; y++)
                for (int x = 7; x <= 8; x++)
                    put(m, x, y, RAMP_B + 2);
            put(m, 7, 5, RAMP_B + 4);
            put(m, 8, 8, RAMP_B);
        }
        return m;
    }

    /**
     * Отпечаток подошвы, носком вверх.
     *
     * Рисуется тенью: декаль кладётся поверх снега или песка с обычным
     * смешиванием, и тёмный силуэт работает на любом грунте. Носок и пятка
     * разделены — иначе след читается овалом, а не обувью.
     */
    static int[][] footprint() {
        return fromArt(new String[] {
                "................",
                "......0000......",
                ".....001100.....",
                "....00111100....",
                "....01122110....",
                "....01122110....",
                "....00111100....",
                ".....001100.....",
                "......0110......",
                "......0110......",
                ".....001100.....",
                "....00111100....",
                "....01122110....",
                ".....001100.....",
                "......0000......",
                "................",
        });
    }

    // =====================================================================
    // ASCII helper for hand-authored sprites
    // =====================================================================

    /** '.' = transparent, '0'..'9' = palette index. */
    static int[][] fromArt(String[] art) {
        int[][] m = new int[S][S];
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                char c = art[y].charAt(x);
                m[y][x] = c == '.' ? T : c - '0';
            }
        return m;
    }

    // =====================================================================
    // Outputs: atlas dump + HTML preview
    // =====================================================================

    static void writeAtlasDump(List<Tile> tiles, File out) throws IOException {
        int size = TILE * TILES_PER_ROW;
        BufferedImage atlas = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        int scale = TILE / S;
        for (int i = 0; i < tiles.size(); i++) {
            BufferedImage t = tiles.get(i).toImage();
            int col = i % TILES_PER_ROW, row = i / TILES_PER_ROW;
            for (int y = 0; y < TILE; y++)
                for (int x = 0; x < TILE; x++)
                    atlas.setRGB(col * TILE + x, row * TILE + y, t.getRGB(x / scale, y / scale));
        }
        ImageIO.write(atlas, "png", out);
        System.out.println("wrote " + out.getPath());
    }

    static void writePreview(List<Tile> tiles, File out) throws IOException {
        StringBuilder js = new StringBuilder("const TILES = [\n");
        for (Tile t : tiles) {
            js.append("{name:\"").append(t.name).append("\",pal:[");
            for (int i = 0; i < t.pal.length; i++) {
                if (i > 0) js.append(',');
                js.append('"').append(String.format("#%08X", t.pal[i])).append('"');
            }
            js.append("],idx:[\n");
            for (int y = 0; y < S; y++) {
                js.append("  [");
                for (int x = 0; x < S; x++) {
                    if (x > 0) js.append(',');
                    js.append(String.format("%2d", t.idx[y][x]));
                }
                js.append(y == S - 1 ? "]\n" : "],\n");
            }
            js.append("]},\n");
        }
        js.append("];\n");

        String html = PREVIEW_HTML.replace("/*__TILES__*/", js.toString());
        File parent = out.getParentFile();
        if (parent != null) parent.mkdirs();
        Files.write(out.toPath(), html.getBytes(StandardCharsets.UTF_8));
        System.out.println("wrote " + out.getPath());
    }

    static final String PREVIEW_HTML = """
        <!doctype html>
        <html lang="ru">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Mineclone — block texture sheet</title>
        <style>
          :root { color-scheme: dark; --bg:#14151a; --card:#1d1f26; --line:#2c2f38;
                  --ink:#e6e8ee; --dim:#9aa0ad; --acc:#7dd3a0; }
          * { box-sizing:border-box; }
          body { margin:0; padding:28px; background:var(--bg); color:var(--ink);
                 font:14px/1.5 ui-monospace,SFMono-Regular,Menlo,Consolas,monospace; }
          h1 { font-size:18px; margin:0 0 4px; letter-spacing:.04em; }
          p.sub { color:var(--dim); margin:0 0 22px; max-width:78ch; }
          .bar { display:flex; gap:18px; align-items:center; margin-bottom:20px; flex-wrap:wrap; }
          label { color:var(--dim); display:flex; gap:6px; align-items:center; cursor:pointer; }
          .grid { display:grid; grid-template-columns:repeat(auto-fill,minmax(190px,1fr)); gap:16px; }
          .grid.big { grid-template-columns:repeat(auto-fill,minmax(288px,1fr)); }
          .card { background:var(--card); border:1px solid var(--line); border-radius:8px;
                  padding:10px; }
          canvas { image-rendering:pixelated; image-rendering:crisp-edges;
                   width:100%; height:auto; display:block; border-radius:4px;
                   background:repeating-conic-gradient(#3a3d47 0 25%, #2b2e36 0 50%) 0 0/16px 16px; }
          .grid.big canvas { width:256px; height:256px; margin:0 auto; }
          .name { margin-top:8px; font-size:12px; letter-spacing:.03em; }
          .ramp { display:flex; margin-top:6px; height:12px; border-radius:3px; overflow:hidden;
                  border:1px solid var(--line); }
          .ramp i { flex:1; }
          details { margin-top:8px; }
          summary { cursor:pointer; color:var(--acc); font-size:11px; }
          pre { margin:6px 0 0; padding:8px; background:#101116; border-radius:4px;
                font-size:10px; line-height:1.35; overflow-x:auto; color:var(--dim); }
        </style>
        </head>
        <body>
        <h1>Mineclone — block texture sheet</h1>
        <p class="sub">Все тайлы блочного атласа. Мастер-разрешение 16&times;16, индексированные
        палитры по 5 стопов с hue shifting, упорядоченный дизеринг 4&times;4 Bayer, бесшовность
        по X и Y. Превью масштабируется nearest-neighbour, <code>imageSmoothingEnabled = false</code>.</p>
        <div class="bar">
          <label><input type="checkbox" id="big"> 1:1 preview 256&times;256</label>
          <label><input type="checkbox" id="tile" checked> тайлинг 2&times;2 (проверка швов)</label>
        </div>
        <div class="grid" id="grid"></div>
        <script>
        /*__TILES__*/
        const grid = document.getElementById('grid');
        const bigBox = document.getElementById('big');
        const tileBox = document.getElementById('tile');

        function argb(h) {              // "#AARRGGBB" -> "rgba(...)"
          const a = parseInt(h.slice(1, 3), 16) / 255;
          return 'rgba(' + parseInt(h.slice(3, 5), 16) + ',' + parseInt(h.slice(5, 7), 16)
               + ',' + parseInt(h.slice(7, 9), 16) + ',' + a.toFixed(3) + ')';
        }

        function draw(cv, t, tiled) {
          const n = tiled ? 2 : 1;
          const px = 256 / (16 * n);
          cv.width = 256; cv.height = 256;
          const g = cv.getContext('2d');
          g.imageSmoothingEnabled = false;
          g.clearRect(0, 0, 256, 256);
          for (let ty = 0; ty < n; ty++)
            for (let tx = 0; tx < n; tx++)
              for (let y = 0; y < 16; y++)
                for (let x = 0; x < 16; x++) {
                  const i = t.idx[y][x];
                  if (i < 0) continue;
                  g.fillStyle = argb(t.pal[i]);
                  g.fillRect((tx * 16 + x) * px, (ty * 16 + y) * px, px, px);
                }
        }

        for (const t of TILES) {
          const card = document.createElement('div');
          card.className = 'card';
          const cv = document.createElement('canvas');
          card.appendChild(cv);
          const nm = document.createElement('div');
          nm.className = 'name';
          nm.textContent = t.name;
          card.appendChild(nm);
          const ramp = document.createElement('div');
          ramp.className = 'ramp';
          for (const c of t.pal) {
            const i = document.createElement('i');
            i.style.background = argb(c);
            i.title = c;
            ramp.appendChild(i);
          }
          card.appendChild(ramp);
          const d = document.createElement('details');
          d.innerHTML = '<summary>index matrix + palette</summary><pre>'
            + 'palette: ' + t.pal.join(' ') + '\\n\\n'
            + t.idx.map(r => r.map(v => String(v).padStart(2)).join(' ')).join('\\n')
            + '</pre>';
          card.appendChild(d);
          grid.appendChild(card);
          t._cv = cv;
        }

        function redraw() {
          grid.classList.toggle('big', bigBox.checked);
          for (const t of TILES) draw(t._cv, t, tileBox.checked);
        }
        bigBox.onchange = redraw;
        tileBox.onchange = redraw;
        redraw();
        </script>
        </body>
        </html>
        """;
}

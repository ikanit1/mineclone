package com.mineclone.render;

import com.mineclone.core.AppPaths;
import com.mineclone.render.PixelArt.Pal;
import com.mineclone.render.PixelArt.Sheet;
import com.mineclone.world.entity.MobType;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * Скины мобов: пиксельные текстуры из 8 тайлов (4 колонки × 2 ряда).
 *
 * <pre>
 * 0 head_front   1 head_side   2 head_top   3 body_side
 * 4 body_top     5 limb        6 accent     7 (запас)
 * </pre>
 *
 * Каждая часть тела в {@link MobRenderer} берёт три тайла: front (грань −Z),
 * side (±X и +Z), top (±Y) — так «лицо» остаётся только на морде.
 *
 * Графика подчиняется тем же правилам, что и блочные текстуры (см.
 * {@link PixelArt} и {@code tools/GenBlockTextures.java}): мастер 16×16 на
 * тайл, индексированные рампы ровно из 5 стопов с hue shifting, объём —
 * упорядоченным дизерингом, а не полупрозрачными накладками. Координаты в
 * долях тайла, поэтому поднять {@link #TILE} с 32 на 64 можно без правки
 * рисунка.
 *
 * Файл {@code assets/mobs/<type>.png} (той же раскладки) переопределяет
 * процедурный скин; иной размер масштабируется nearest-neighbour. Только AWT —
 * никакого GL, поэтому класс тестируется без окна.
 */
public final class MobSkins {
    public static final int TILE = 32;
    public static final int COLS = 4;
    public static final int ROWS = 2;
    public static final int WIDTH = TILE * COLS;   // 128
    public static final int HEIGHT = TILE * ROWS;  // 64

    public static final int T_HEAD_FRONT = 0;
    public static final int T_HEAD_SIDE = 1;
    public static final int T_HEAD_TOP = 2;
    public static final int T_BODY_SIDE = 3;
    public static final int T_BODY_TOP = 4;
    public static final int T_LIMB = 5;
    public static final int T_ACCENT = 6;
    /** Запасной тайл — виден только если модель на него сошлётся. */
    static final int T_SPARE = 7;

    public static final String OVERRIDE_DIR = "assets/mobs";

    /** Доля тайла на один пиксель мастера — вся геометрия кратна ей. */
    private static final float P = 1f / PixelArt.M;

    private MobSkins() {}

    /** Override с диска, иначе процедурный скин. */
    public static BufferedImage load(MobType type) {
        File f = new File(AppPaths.file(OVERRIDE_DIR),
                type.name().toLowerCase(Locale.ROOT) + ".png");
        if (f.exists()) {
            try {
                BufferedImage raw = ImageIO.read(f);
                if (raw != null)
                    return normalize(raw);
            } catch (IOException e) {
                System.err.println("Failed to load mob skin " + f + ": " + e.getMessage());
            }
        }
        return generate(type);
    }

    /** Приводит override к WIDTH×HEIGHT ARGB (nearest — пиксель-арт не мылится). */
    static BufferedImage normalize(BufferedImage raw) {
        if (raw.getWidth() == WIDTH && raw.getHeight() == HEIGHT
                && raw.getType() == BufferedImage.TYPE_INT_ARGB)
            return raw;
        BufferedImage out = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(raw, 0, 0, WIDTH, HEIGHT, null);
        g.dispose();
        return out;
    }

    public static BufferedImage generate(MobType type) {
        Pal pal = new Pal();
        Sheet s = new Sheet(COLS, ROWS);
        switch (type) {
            case COW -> cow(s, pal);
            case PIG -> pig(s, pal);
            case SHEEP -> sheep(s, pal);
            case CHICKEN -> chicken(s, pal);
            case ZOMBIE -> zombie(s, pal);
            case RABBIT -> rabbit(s, pal);
            case WOLF -> wolf(s, pal);
            case BIRD -> bird(s, pal);
            case SKELETON -> skeleton(s, pal);
            case SPIDER -> spider(s, pal);
            case CREEPER -> creeper(s, pal);
        }
        return s.toImage(pal.toArray(), TILE);
    }

    /**
     * Кролик: серо-коричневый мех комками, светлое брюшко, розовое нутро
     * ушей. Мех — комки, а не градиент: гладкий кролик выглядит пластмассовым.
     */
    private static void rabbit(Sheet s, Pal p) {
        int fur   = p.ramp("3D3129", "58473A", "74604D", "8E7862", "A99378");
        int belly = p.ramp("8C8274", "ABA192", "C6BDAE", "DDD6C9", "F2EDE2");
        int pink  = p.ramp("6A3D44", "8F5760", "B0747A", "C89399", "DDB2B5");
        int dark  = p.ramp("110D0C", "1C1614", "28201D", "352B27", "443833");

        s.clumps(T_BODY_SIDE, fur, 0.30f, 0.85f, 4, 131);
        s.rectGrad(T_BODY_SIDE, 0f, 11 * P, 1f, 5 * P, belly, 0.70f, 0.45f, 132);
        s.ragged(T_BODY_SIDE, 10 * P, 2 * P, fur, 0.55f, 133);
        s.clumps(T_BODY_TOP, fur, 0.40f, 0.90f, 4, 134);

        // Морда: большие глаза по бокам, светлое пятно носа с раздвоенной губой.
        s.clumps(T_HEAD_FRONT, fur, 0.40f, 0.90f, 4, 135);
        s.rectGrad(T_HEAD_FRONT, 5 * P, 8 * P, 6 * P, 6 * P, belly, 0.85f, 0.55f, 136);
        s.rect(T_HEAD_FRONT, 7 * P, 9 * P, 2 * P, P, pink + 3);
        s.rect(T_HEAD_FRONT, 7 * P, 11 * P, P, 2 * P, dark + 2);
        s.rect(T_HEAD_FRONT, 8 * P, 11 * P, P, 2 * P, dark + 2);
        s.eye(T_HEAD_FRONT, 0f, 5 * P, dark, belly + 4, true);
        s.eye(T_HEAD_FRONT, 12 * P, 5 * P, dark, belly + 4, false);

        s.clumps(T_HEAD_SIDE, fur, 0.40f, 0.88f, 4, 137);
        s.rectGrad(T_HEAD_SIDE, 0f, 9 * P, 5 * P, 5 * P, belly, 0.8f, 0.5f, 138);
        s.eye(T_HEAD_SIDE, 6 * P, 5 * P, dark, belly + 4, true);
        s.clumps(T_HEAD_TOP, fur, 0.45f, 0.90f, 4, 139);

        // Лапы и хвост-помпон.
        s.clumps(T_LIMB, fur, 0.30f, 0.75f, 4, 140);
        s.rectGrad(T_LIMB, 0f, 12 * P, 1f, 4 * P, belly, 0.7f, 0.5f, 141);
        // Ухо: розовое нутро в меховой кромке.
        s.clumps(T_ACCENT, fur, 0.45f, 0.85f, 4, 142);
        s.rectGrad(T_ACCENT, 4 * P, 2 * P, 8 * P, 13 * P, pink, 0.85f, 0.55f, 143);
        s.clumps(T_SPARE, belly, 0.6f, 1.0f, 4, 144);
    }

    /**
     * Волк: серая шерсть с тёмным «чепраком» по хребту и светлой грудью,
     * жёлтые глаза. Чепрак — главное, что отличает силуэт волка от собаки.
     */
    private static void wolf(Sheet s, Pal p) {
        int fur   = p.ramp("2E3033", "474A4E", "63666A", "7F8285", "9EA09F");
        int pale  = p.ramp("77756F", "969389", "B3AFA3", "CBC8BC", "E2DFD4");
        int saddle = p.ramp("18191B", "242629", "313336", "3F4144", "4E5053");
        int eyeC  = p.ramp("5A3A08", "8A5F12", "B8871E", "D8AA34", "F0CF5C");
        int dark  = p.ramp("0B0B0C", "141415", "1E1E20", "29292B", "353537");

        s.clumps(T_BODY_SIDE, fur, 0.30f, 0.85f, 4, 151);
        s.rectGrad(T_BODY_SIDE, 0f, 0f, 1f, 5 * P, saddle, 0.8f, 0.4f, 152);
        s.ragged(T_BODY_SIDE, 5 * P, 2 * P, saddle, 0.45f, 153);
        s.rectGrad(T_BODY_SIDE, 0f, 12 * P, 1f, 4 * P, pale, 0.65f, 0.45f, 154);
        s.clumps(T_BODY_TOP, saddle, 0.35f, 0.85f, 4, 155);

        // Морда: светлая маска вокруг носа, тёмная переносица, жёлтые глаза.
        s.clumps(T_HEAD_FRONT, fur, 0.40f, 0.90f, 4, 156);
        s.rectGrad(T_HEAD_FRONT, 3 * P, 9 * P, 10 * P, 7 * P, pale, 0.85f, 0.55f, 157);
        s.rect(T_HEAD_FRONT, 7 * P, 3 * P, 2 * P, 7 * P, saddle + 2);
        s.rect(T_HEAD_FRONT, 6 * P, 10 * P, 4 * P, 2 * P, dark + 1);
        s.eye(T_HEAD_FRONT, 2 * P, 5 * P, dark, eyeC + 3, true);
        s.eye(T_HEAD_FRONT, 10 * P, 5 * P, dark, eyeC + 3, false);

        s.clumps(T_HEAD_SIDE, fur, 0.40f, 0.88f, 4, 158);
        s.rectGrad(T_HEAD_SIDE, 0f, 9 * P, 6 * P, 6 * P, pale, 0.80f, 0.50f, 159);
        s.eye(T_HEAD_SIDE, 6 * P, 5 * P, dark, eyeC + 3, true);
        s.clumps(T_HEAD_TOP, saddle, 0.40f, 0.85f, 4, 160);

        s.clumps(T_LIMB, fur, 0.35f, 0.80f, 4, 161);
        s.rectGrad(T_LIMB, 0f, 13 * P, 1f, 3 * P, dark, 0.6f, 0.3f, 162);
        // Уши и морда-«нос» — тёмная шерсть; хвост — светлый кончик.
        s.clumps(T_ACCENT, saddle, 0.40f, 0.85f, 4, 163);
        s.rectGrad(T_ACCENT, 0f, 12 * P, 1f, 4 * P, pale, 0.9f, 0.6f, 164);
        s.clumps(T_SPARE, pale, 0.55f, 0.95f, 4, 165);
    }

    /**
     * Птица: синяя спинка, оранжевая грудка, тёмные маховые перья и
     * жёлтый клюв. Контраст спины и груди нужен, чтобы в полёте на фоне неба
     * птица читалась, а не превращалась в тёмную точку.
     */
    private static void bird(Sheet s, Pal p) {
        int back  = p.ramp("15223F", "1F3565", "2C4B8C", "3F66AE", "6189CC");
        int chest = p.ramp("6B2D10", "9A461A", "C66527", "E28A3E", "F2B267");
        int wing  = p.ramp("0B1020", "141C33", "1F2A4A", "2C3A61", "3C4D78");
        int beak  = p.ramp("6E5410", "9A781A", "C49E2A", "E2C04A", "F6E08A");
        int dark  = p.ramp("080808", "111111", "1A1A1A", "242424", "2F2F2F");

        s.grad(T_BODY_SIDE, back, 0.85f, 0.45f, 171);
        s.rectGrad(T_BODY_SIDE, 0f, 8 * P, 1f, 8 * P, chest, 0.85f, 0.55f, 172);
        s.ragged(T_BODY_SIDE, 7 * P, 2 * P, back, 0.60f, 173);
        s.grad(T_BODY_TOP, back, 0.80f, 0.55f, 174);

        s.grad(T_HEAD_FRONT, back, 0.85f, 0.50f, 175);
        s.rectGrad(T_HEAD_FRONT, 3 * P, 9 * P, 10 * P, 7 * P, chest, 0.9f, 0.6f, 176);
        s.eye(T_HEAD_FRONT, P, 5 * P, dark, chest + 4, true);
        s.eye(T_HEAD_FRONT, 11 * P, 5 * P, dark, chest + 4, false);
        s.grad(T_HEAD_SIDE, back, 0.85f, 0.50f, 177);
        s.eye(T_HEAD_SIDE, 5 * P, 5 * P, dark, chest + 4, true);
        s.grad(T_HEAD_TOP, back, 0.80f, 0.60f, 178);

        s.grad(T_LIMB, beak, 0.75f, 0.45f, 179);
        // Крыло: маховые полосами, тёмный задний край.
        s.grad(T_ACCENT, wing, 0.85f, 0.50f, 180);
        for (int i = 0; i < 4; i++)
            s.rect(T_ACCENT, P, (2 + i * 3) * P, 14 * P, P, wing + 4);
        s.grad(T_SPARE, beak, 0.9f, 0.6f, 181);
    }

    // -------------------------------------------------------------------------
    //  Виды. Рампы читаются слева направо: тень (холодная) → блик (тёплый).
    // -------------------------------------------------------------------------

    private static void cow(Sheet s, Pal p) {
        int hide  = p.ramp("2A1D20", "3E2B1D", "553D24", "6C5130", "87693C");
        int cream = p.ramp("8C8375", "AEA493", "CAC1AF", "E2DBCA", "F7F2E4");
        int muzz  = p.ramp("5C3A3E", "80555A", "A1716F", "BD8D87", "D6AC9F");
        int horn  = p.ramp("6A6052", "897F6D", "A69B87", "C0B5A0", "D9CEB7");
        int dark  = p.ramp("100B0D", "1B1416", "271E20", "342A2A", "433736");

        // Корпус: подпалины крупными пятнами, не мелким крапом — иначе с
        // трёх метров корова читается как бурый шум.
        s.grad(T_BODY_SIDE, hide, 0.80f, 0.30f, 11);
        s.splotch(T_BODY_SIDE, 0.26f, 0.30f, 0.22f, cream, 12);
        s.splotch(T_BODY_SIDE, 0.70f, 0.62f, 0.26f, cream, 13);
        s.splotch(T_BODY_SIDE, 0.40f, 0.86f, 0.14f, cream, 14);
        s.grad(T_BODY_TOP, hide, 0.72f, 0.44f, 15);
        s.splotch(T_BODY_TOP, 0.32f, 0.36f, 0.24f, cream, 16);
        s.splotch(T_BODY_TOP, 0.74f, 0.72f, 0.16f, cream, 17);

        // Морда: проточина сужается ко лбу и доходит до носа, рожки растут из
        // верхних углов — деталь, оторванная от края тайла, читается как
        // наклейка, а не как часть головы.
        s.grad(T_HEAD_FRONT, hide, 0.82f, 0.38f, 21);
        s.rectGrad(T_HEAD_FRONT, 5 * P, 0f, 6 * P, 5 * P, cream, 0.90f, 0.62f, 22);
        s.rectGrad(T_HEAD_FRONT, 6 * P, 5 * P, 4 * P, 4 * P, cream, 0.62f, 0.50f, 22);
        s.rectGrad(T_HEAD_FRONT, 0f, 0f, 3 * P, 2 * P, horn, 0.85f, 0.50f, 18);
        s.rectGrad(T_HEAD_FRONT, 13 * P, 0f, 3 * P, 2 * P, horn, 0.85f, 0.50f, 19);
        s.rectGrad(T_HEAD_FRONT, 3 * P, 9 * P, 10 * P, 7 * P, muzz, 0.78f, 0.40f, 23);
        s.rect(T_HEAD_FRONT, 3 * P, 9 * P, 10 * P, P, muzz + 1);
        s.rect(T_HEAD_FRONT, 5 * P, 11 * P, 2 * P, 2 * P, dark + 1);
        s.rect(T_HEAD_FRONT, 9 * P, 11 * P, 2 * P, 2 * P, dark + 1);
        s.eye(T_HEAD_FRONT, P, 5 * P, dark, cream + 4, true);
        s.eye(T_HEAD_FRONT, 11 * P, 5 * P, dark, cream + 4, false);

        // Профиль: рог от макушки, ухо под ним, морда до левого края.
        s.grad(T_HEAD_SIDE, hide, 0.80f, 0.36f, 24);
        s.rectGrad(T_HEAD_SIDE, 0f, 8 * P, 5 * P, 6 * P, muzz, 0.72f, 0.42f, 25);
        s.rectGrad(T_HEAD_SIDE, 10 * P, 0f, 4 * P, 4 * P, horn, 0.85f, 0.45f, 20);
        s.rectGrad(T_HEAD_SIDE, 11 * P, 4 * P, 5 * P, 3 * P, hide, 0.30f, 0.15f, 26);
        s.eye(T_HEAD_SIDE, 4 * P, 5 * P, dark, cream + 4, true);

        // Сверху — рога в стороны от краёв тайла.
        s.grad(T_HEAD_TOP, hide, 0.74f, 0.50f, 27);
        s.rectGrad(T_HEAD_TOP, 0f, 5 * P, 5 * P, 4 * P, horn, 0.85f, 0.40f, 28);
        s.rectGrad(T_HEAD_TOP, 11 * P, 5 * P, 5 * P, 4 * P, horn, 0.85f, 0.40f, 29);
        s.shift(T_HEAD_TOP, 0f, 8 * P, 5 * P, P, -2);
        s.shift(T_HEAD_TOP, 11 * P, 8 * P, 5 * P, P, -2);

        hoofedLimb(s, hide, dark, 29);
        s.grad(T_ACCENT, cream, 0.85f, 0.50f, 30);
        s.grad(T_SPARE, hide, 0.70f, 0.40f, 31);
    }

    private static void pig(Sheet s, Pal p) {
        int skin  = p.ramp("6E4247", "955F5E", "B87C78", "D49A90", "EBBBAA");
        int snout = p.ramp("52303A", "74484C", "915F5E", "AB7671", "C29188");
        int dark  = p.ramp("140D11", "1F1518", "2B1F21", "38292A", "473635");
        int bone  = p.ramp("8A8078", "A69C93", "BFB5AB", "D6CCC1", "F0E7DA");

        s.grad(T_BODY_SIDE, skin, 0.78f, 0.34f, 41);
        s.grad(T_BODY_TOP, skin, 0.72f, 0.46f, 42);

        // Морда: пятак с двумя ноздрями и складка над ним.
        s.grad(T_HEAD_FRONT, skin, 0.82f, 0.40f, 43);
        s.rectGrad(T_HEAD_FRONT, 4 * P, 8 * P, 8 * P, 7 * P, snout, 0.76f, 0.38f, 44);
        s.rect(T_HEAD_FRONT, 4 * P, 8 * P, 8 * P, P, snout + 1);
        s.rect(T_HEAD_FRONT, 6 * P, 11 * P, 2 * P, 2 * P, dark + 1);
        s.rect(T_HEAD_FRONT, 9 * P, 11 * P, 2 * P, 2 * P, dark + 1);
        s.eye(T_HEAD_FRONT, 2 * P, 4 * P, dark, bone + 4, true);
        s.eye(T_HEAD_FRONT, 10 * P, 4 * P, dark, bone + 4, false);

        s.grad(T_HEAD_SIDE, skin, 0.80f, 0.38f, 45);
        s.rectGrad(T_HEAD_SIDE, 0f, 8 * P, 5 * P, 5 * P, snout, 0.72f, 0.40f, 46);
        s.rectGrad(T_HEAD_SIDE, 9 * P, 0f, 5 * P, 4 * P, snout, 0.55f, 0.28f, 47);   // ухо
        s.eye(T_HEAD_SIDE, 5 * P, 5 * P, dark, bone + 4, true);

        // Уши сверху — двумя ступеньками, чтобы читался треугольник.
        s.grad(T_HEAD_TOP, skin, 0.74f, 0.50f, 48);
        s.rectGrad(T_HEAD_TOP, P, 0f, 4 * P, 3 * P, snout, 0.62f, 0.34f, 49);
        s.rect(T_HEAD_TOP, 2 * P, 3 * P, 2 * P, 2 * P, snout + 1);
        s.rectGrad(T_HEAD_TOP, 11 * P, 0f, 4 * P, 3 * P, snout, 0.62f, 0.34f, 50);
        s.rect(T_HEAD_TOP, 12 * P, 3 * P, 2 * P, 2 * P, snout + 1);

        hoofedLimb(s, skin, dark, 51);
        s.grad(T_ACCENT, snout, 0.72f, 0.42f, 52);
        s.grad(T_SPARE, skin, 0.70f, 0.42f, 53);
    }

    private static void sheep(Sheet s, Pal p) {
        int wool = p.ramp("94918A", "B2AEA4", "CCC8BC", "E3DFD2", "F8F4E8");
        int face = p.ramp("5E3F42", "805858", "9C7370", "B68D85", "CCA79A");
        int dark = p.ramp("161314", "23201E", "302B28", "3D3733", "4C443E");

        // Шерсть — комки value-noise, а не градиент: гладкая тень читается как
        // пластик, ровный крап — как шум. Комок даёт объём.
        for (int t : new int[] { T_BODY_SIDE, T_BODY_TOP, T_HEAD_TOP, T_ACCENT, T_SPARE })
            s.clumps(t, wool, 0.30f, 1.0f, 4, 61 + t);
        s.shift(T_BODY_SIDE, 0f, 12 * P, 1f, 4 * P, -1);      // тень под брюхо
        s.shift(T_BODY_SIDE, 0f, 0f, 1f, 2 * P, 1);           // свет по хребту

        // Морда розовая, лоб закрыт шерстью с рваной кромкой.
        s.grad(T_HEAD_FRONT, face, 0.78f, 0.40f, 71);
        s.clumps(T_HEAD_FRONT, wool, 0.45f, 1.0f, 4, 72);
        s.rectGrad(T_HEAD_FRONT, 2 * P, 5 * P, 12 * P, 11 * P, face, 0.78f, 0.42f, 73);
        s.ragged(T_HEAD_FRONT, 5 * P, 2 * P, wool, 0.55f, 74);
        s.eye(T_HEAD_FRONT, 2 * P, 7 * P, dark, wool + 4, true);
        s.eye(T_HEAD_FRONT, 10 * P, 7 * P, dark, wool + 4, false);
        s.rect(T_HEAD_FRONT, 6 * P, 11 * P, P, 2 * P, face);
        s.rect(T_HEAD_FRONT, 9 * P, 11 * P, P, 2 * P, face);
        s.rect(T_HEAD_FRONT, 5 * P, 14 * P, 6 * P, P, dark + 1);

        s.clumps(T_HEAD_SIDE, wool, 0.40f, 1.0f, 4, 75);
        s.rectGrad(T_HEAD_SIDE, 0f, 6 * P, 6 * P, 8 * P, face, 0.74f, 0.40f, 76);
        s.rectGrad(T_HEAD_SIDE, 9 * P, 4 * P, 4 * P, 3 * P, wool, 0.35f, 0.20f, 77);  // ухо
        s.eye(T_HEAD_SIDE, 5 * P, 7 * P, dark, wool + 4, true);

        s.clumps(T_LIMB, wool, 0.35f, 0.85f, 4, 78);
        s.rectGrad(T_LIMB, 0f, 12 * P, 1f, 4 * P, dark, 0.55f, 0.20f, 79);
    }

    private static void chicken(Sheet s, Pal p) {
        int feath = p.ramp("9A968D", "BAB6AB", "D4D0C4", "E9E5D9", "FDFAEE");
        int beak  = p.ramp("6E4413", "94601B", "B47D26", "D09A35", "E9BC53");
        int comb  = p.ramp("5A161C", "7A2020", "9B2C26", "BA3B31", "D75C46");
        int dark  = p.ramp("121013", "1D1A1B", "282423", "342E2C", "423B37");

        s.grad(T_BODY_SIDE, feath, 0.85f, 0.42f, 81);
        // Перья: ряды коротких штрихов со сдвигом через ряд.
        for (int row = 0; row < 5; row++)
            for (int i = 0; i < 4; i++)
                s.rect(T_BODY_SIDE, (1 + i * 4 + (row % 2)) * P, (4 + row * 2) * P,
                        2 * P, P, feath + 1);
        s.grad(T_BODY_TOP, feath, 0.80f, 0.52f, 82);

        // Голова: гребень, клюв, бородка.
        s.grad(T_HEAD_FRONT, feath, 0.88f, 0.46f, 83);
        s.rectGrad(T_HEAD_FRONT, 5 * P, 0f, 6 * P, 3 * P, comb, 0.80f, 0.45f, 84);
        s.rectGrad(T_HEAD_FRONT, 6 * P, 7 * P, 4 * P, 4 * P, beak, 0.85f, 0.45f, 85);
        s.rect(T_HEAD_FRONT, 6 * P, 10 * P, 4 * P, P, beak);
        s.rectGrad(T_HEAD_FRONT, 6 * P, 11 * P, 4 * P, 3 * P, comb, 0.65f, 0.35f, 86);
        s.eye(T_HEAD_FRONT, P, 4 * P, dark, feath + 4, true);
        s.eye(T_HEAD_FRONT, 12 * P, 4 * P, dark, feath + 4, false);

        s.grad(T_HEAD_SIDE, feath, 0.86f, 0.44f, 87);
        s.rectGrad(T_HEAD_SIDE, 5 * P, 0f, 6 * P, 3 * P, comb, 0.80f, 0.45f, 88);
        s.rectGrad(T_HEAD_SIDE, 0f, 6 * P, 4 * P, 4 * P, beak, 0.85f, 0.45f, 89);
        s.eye(T_HEAD_SIDE, 5 * P, 4 * P, dark, feath + 4, true);

        s.grad(T_HEAD_TOP, feath, 0.82f, 0.52f, 90);
        s.rectGrad(T_HEAD_TOP, 6 * P, 0f, 4 * P, 1f, comb, 0.80f, 0.40f, 91);

        // Лапа чешуйчатая, коготь темнее.
        s.grad(T_LIMB, beak, 0.78f, 0.40f, 92);
        for (int i = 0; i < 7; i++)
            s.rect(T_LIMB, 2 * P, (1 + i * 2) * P, 12 * P, P, beak + 1);
        s.rectGrad(T_LIMB, 0f, 14 * P, 1f, 2 * P, beak, 0.35f, 0.15f, 93);

        // Крыло: маховые перья полосами и тёмный задний край.
        s.grad(T_ACCENT, feath, 0.90f, 0.55f, 94);
        for (int i = 0; i < 5; i++)
            s.rect(T_ACCENT, P, (2 + i * 3) * P, 14 * P, 2 * P, feath + (i % 2 == 0 ? 1 : 3));
        s.shift(T_ACCENT, 0f, 13 * P, 1f, 3 * P, -1);
        s.grad(T_SPARE, feath, 0.80f, 0.50f, 95);
    }

    /**
     * Скелет: кость с тенями в провалах, пустые глазницы, рёбра на груди.
     *
     * Кость светлая почти до белого, поэтому тени приходится класть
     * отдельно — на ровном светлом рёбра и швы иначе не читаются.
     */
    private static void skeleton(Sheet s, Pal p) {
        int bone  = p.ramp("6E6A5C", "8C8879", "A9A493", "C4BFAC", "DCD7C4");
        int hollow = p.ramp("0A0B0C", "141618", "1E2124", "2A2D31", "383C41");

        // Череп: глазницы глубоко, между ними переносица, зубы полосой.
        s.grad(T_HEAD_FRONT, bone, 0.86f, 0.42f, 301);
        s.rect(T_HEAD_FRONT, 2 * P, 5 * P, 4 * P, 4 * P, hollow);
        s.rect(T_HEAD_FRONT, 10 * P, 5 * P, 4 * P, 4 * P, hollow);
        s.rect(T_HEAD_FRONT, 7 * P, 8 * P, 2 * P, 2 * P, hollow + 1);
        s.rect(T_HEAD_FRONT, 4 * P, 12 * P, 8 * P, 2 * P, hollow);
        for (int i = 0; i < 4; i++)
            s.rect(T_HEAD_FRONT, (4 + i * 2) * P, 12 * P, P, 2 * P, bone + 4);

        s.grad(T_HEAD_SIDE, bone, 0.84f, 0.40f, 302);
        s.rect(T_HEAD_SIDE, 4 * P, 5 * P, 3 * P, 4 * P, hollow);
        s.rect(T_HEAD_SIDE, 3 * P, 12 * P, 9 * P, 2 * P, hollow + 1);
        s.grad(T_HEAD_TOP, bone, 0.80f, 0.46f, 303);

        // Грудь: рёбра парами и тёмный провал между ними.
        s.grad(T_BODY_SIDE, hollow, 0.55f, 0.28f, 304);
        for (int i = 0; i < 5; i++) {
            s.rect(T_BODY_SIDE, 3 * P, (2 + i * 3) * P, 10 * P, P, bone + 3);
            s.rect(T_BODY_SIDE, 3 * P, (3 + i * 3) * P, 10 * P, P, bone + 1);
        }
        s.rect(T_BODY_SIDE, 7 * P, 0f, 2 * P, 1f, bone + 4);          // позвоночник
        s.grad(T_BODY_TOP, bone, 0.74f, 0.44f, 305);

        // Кости конечностей: светлая середина, тёмные суставы по концам.
        s.grad(T_LIMB, hollow, 0.50f, 0.26f, 306);
        s.rect(T_LIMB, 5 * P, 0f, 6 * P, 1f, bone + 2);
        s.rect(T_LIMB, 4 * P, 0f, 8 * P, 2 * P, bone + 4);
        s.rect(T_LIMB, 4 * P, 14 * P, 8 * P, 2 * P, bone + 4);
        s.grad(T_ACCENT, hollow, 0.50f, 0.26f, 307);
        s.rect(T_ACCENT, 5 * P, 0f, 6 * P, 1f, bone + 2);
        s.rect(T_ACCENT, 4 * P, 0f, 8 * P, 2 * P, bone + 4);
        s.rect(T_ACCENT, 4 * P, 14 * P, 8 * P, 2 * P, bone + 4);
        s.grad(T_SPARE, bone, 0.76f, 0.44f, 308);
    }

    /**
     * Паук: хитин почти чёрный, поэтому вся читаемость держится на
     * восьми красных глазах и волосках — на ровном чёрном не видно ничего.
     */
    private static void spider(Sheet s, Pal p) {
        int chitin = p.ramp("0C0A0D", "16131A", "201C26", "2C2733", "3A3442");
        int eye    = p.ramp("3A0C0C", "6B1414", "9C1E1E", "C63A2C", "E86A4A");

        // Морда: два больших глаза и шесть малых вокруг них.
        s.clumps(T_HEAD_FRONT, chitin, 0.34f, 0.80f, 5, 321);
        s.rect(T_HEAD_FRONT, 3 * P, 6 * P, 3 * P, 3 * P, eye + 3);
        s.rect(T_HEAD_FRONT, 10 * P, 6 * P, 3 * P, 3 * P, eye + 3);
        s.rect(T_HEAD_FRONT, 4 * P, 7 * P, P, P, eye + 4);
        s.rect(T_HEAD_FRONT, 11 * P, 7 * P, P, P, eye + 4);
        for (int i = 0; i < 3; i++) {
            s.rect(T_HEAD_FRONT, (3 + i * 2) * P, 3 * P, P, P, eye + 2);
            s.rect(T_HEAD_FRONT, (10 + i * 2) * P, 3 * P, P, P, eye + 2);
        }
        s.rect(T_HEAD_FRONT, 6 * P, 12 * P, 4 * P, 2 * P, chitin);     // жвалы

        s.clumps(T_HEAD_SIDE, chitin, 0.32f, 0.78f, 5, 322);
        s.rect(T_HEAD_SIDE, 4 * P, 6 * P, 2 * P, 2 * P, eye + 2);
        s.clumps(T_HEAD_TOP, chitin, 0.30f, 0.76f, 4, 323);

        // Брюшко: комки хитина и редкий волос.
        s.clumps(T_BODY_SIDE, chitin, 0.36f, 0.86f, 6, 324);
        s.splotch(T_BODY_SIDE, 0.50f, 0.55f, 0.22f, chitin + 1, 325);
        s.ragged(T_BODY_SIDE, 2 * P, 2 * P, chitin + 4, 0.30f, 326);
        s.clumps(T_BODY_TOP, chitin, 0.34f, 0.80f, 5, 327);

        // Ноги: тонкие, с волосками по краю.
        s.grad(T_LIMB, chitin, 0.72f, 0.34f, 328);
        s.ragged(T_LIMB, 3 * P, 2 * P, chitin + 4, 0.35f, 329);
        s.grad(T_ACCENT, chitin, 0.70f, 0.32f, 330);
        s.ragged(T_ACCENT, 3 * P, 2 * P, chitin + 4, 0.35f, 331);
        s.grad(T_SPARE, chitin, 0.68f, 0.34f, 332);
    }

    /**
     * Крипер: крапчатая зелень и знакомая морда. Лицо — единственное, что
     * отличает его от куста, поэтому оно рисуется жёстко, без дизеринга.
     */
    private static void creeper(Sheet s, Pal p) {
        int skin = p.ramp("16321A", "1F4A24", "2A6330", "357D3B", "4C9A4F");
        int dark = p.ramp("050905", "0A1109", "10180D", "161F12", "1D2717");

        s.clumps(T_HEAD_FRONT, skin, 0.30f, 0.84f, 4, 341);
        s.rect(T_HEAD_FRONT, 3 * P, 4 * P, 3 * P, 3 * P, dark);        // глаза
        s.rect(T_HEAD_FRONT, 10 * P, 4 * P, 3 * P, 3 * P, dark);
        s.rect(T_HEAD_FRONT, 6 * P, 7 * P, 4 * P, 4 * P, dark);        // нос-рот
        s.rect(T_HEAD_FRONT, 4 * P, 11 * P, 3 * P, 3 * P, dark);       // клыки вниз
        s.rect(T_HEAD_FRONT, 9 * P, 11 * P, 3 * P, 3 * P, dark);

        s.clumps(T_HEAD_SIDE, skin, 0.30f, 0.82f, 4, 342);
        s.clumps(T_HEAD_TOP, skin, 0.28f, 0.80f, 4, 343);
        s.clumps(T_BODY_SIDE, skin, 0.32f, 0.84f, 5, 344);
        s.clumps(T_BODY_TOP, skin, 0.30f, 0.80f, 4, 345);
        s.clumps(T_LIMB, skin, 0.30f, 0.78f, 4, 346);
        s.clumps(T_ACCENT, skin, 0.30f, 0.78f, 4, 347);
        s.clumps(T_SPARE, skin, 0.28f, 0.76f, 4, 348);
    }

    private static void zombie(Sheet s, Pal p) {
        int flesh = p.ramp("1E3018", "2C4620", "3A5C28", "497331", "5F8E3E");
        int shirt = p.ramp("11313A", "1A4854", "245F6C", "2F7684", "43929E");
        int pants = p.ramp("161C2C", "212940", "2C3653", "384467", "4A587F");
        int rot   = p.ramp("080D07", "10160C", "181F11", "212917", "2A341D");
        int bone  = p.ramp("857F6E", "9F9987", "B7B19E", "CDC7B3", "E4DECA");

        // Голова: волосы шапкой, пустые глазницы, приоткрытый рот.
        s.grad(T_HEAD_FRONT, flesh, 0.82f, 0.38f, 101);
        s.rectGrad(T_HEAD_FRONT, 0f, 0f, 1f, 2 * P, rot, 0.70f, 0.35f, 102);
        s.rect(T_HEAD_FRONT, 2 * P, 5 * P, 4 * P, 3 * P, rot);
        s.rect(T_HEAD_FRONT, 10 * P, 5 * P, 4 * P, 3 * P, rot);
        s.rect(T_HEAD_FRONT, 3 * P, 6 * P, P, P, flesh + 1);
        s.rect(T_HEAD_FRONT, 12 * P, 6 * P, P, P, flesh + 1);
        s.rect(T_HEAD_FRONT, 4 * P, 11 * P, 8 * P, 3 * P, rot);
        for (int i = 0; i < 3; i++)
            s.rect(T_HEAD_FRONT, (5 + i * 2) * P, 11 * P, P, P, bone + 3);
        s.shift(T_HEAD_FRONT, 0f, 14 * P, 1f, 2 * P, -1);

        s.grad(T_HEAD_SIDE, flesh, 0.80f, 0.36f, 103);
        s.rectGrad(T_HEAD_SIDE, 0f, 0f, 1f, 3 * P, rot, 0.70f, 0.35f, 104);
        s.rect(T_HEAD_SIDE, 3 * P, 5 * P, 3 * P, 3 * P, rot);
        s.splotch(T_HEAD_SIDE, 0.74f, 0.66f, 0.11f, flesh, 105);    // рваная щека
        s.grad(T_HEAD_TOP, rot, 0.62f, 0.30f, 106);

        // Рубаха: шов по центру, рваный подол, из-под него зелёная кожа.
        s.grad(T_BODY_SIDE, shirt, 0.80f, 0.36f, 107);
        s.rect(T_BODY_SIDE, 7 * P, 0f, 2 * P, 1f, shirt + 1);
        s.rectGrad(T_BODY_SIDE, 0f, 12 * P, 1f, 4 * P, flesh, 0.60f, 0.32f, 108);
        s.ragged(T_BODY_SIDE, 12 * P, 3 * P, shirt, 0.45f, 109);
        s.grad(T_BODY_TOP, shirt, 0.74f, 0.48f, 110);

        // Штаны со швом и тёмной обувью.
        s.grad(T_LIMB, pants, 0.78f, 0.36f, 111);
        s.rect(T_LIMB, 7 * P, 0f, 2 * P, 1f, pants + 1);
        s.rectGrad(T_LIMB, 0f, 14 * P, 1f, 2 * P, rot, 0.55f, 0.25f, 112);

        // Руки: голая кожа, у плеча остаток рукава с рваным краем.
        s.grad(T_ACCENT, flesh, 0.80f, 0.36f, 113);
        s.rectGrad(T_ACCENT, 0f, 0f, 1f, 5 * P, shirt, 0.78f, 0.42f, 114);
        s.ragged(T_ACCENT, 5 * P, 2 * P, shirt, 0.40f, 115);
        s.splotch(T_ACCENT, 0.62f, 0.70f, 0.12f, rot, 116);         // рана
        s.grad(T_SPARE, flesh, 0.72f, 0.40f, 117);
    }

    // -------------------------------------------------------------------------

    /**
     * Нога копытного: тело сверху, тёмное копыто снизу. Верх ноги светлее
     * корпуса — тёмная база плюс копыто плюс тень дают почти чёрный столбик.
     */
    private static void hoofedLimb(Sheet s, int coat, int hoof, int seed) {
        s.grad(T_LIMB, coat, 0.80f, 0.45f, seed);
        s.rectGrad(T_LIMB, 0f, 12 * P, 1f, 4 * P, hoof, 0.60f, 0.25f, seed + 1);
        s.rect(T_LIMB, 0f, 12 * P, 1f, P, hoof + 3);
    }
}

package com.mineclone.render;

import com.mineclone.core.AppPaths;
import com.mineclone.world.entity.MobType;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Random;

/**
 * Скины мобов: пиксельные текстуры из 8 тайлов (4 колонки × 2 ряда).
 *
 * <pre>
 * 0 head_front   1 head_side   2 head_top   3 body_side
 * 4 body_top     5 limb        6 accent     7 (свободный)
 * </pre>
 *
 * Каждая часть тела в {@link MobRenderer} берёт три тайла: front (грань −Z),
 * side (±X и +Z), top (±Y) — так «лицо» остаётся только на морде.
 *
 * Рисование идёт в ДОЛЯХ тайла, а не в пикселях: поднять {@link #TILE} с 32 на
 * 64 достаточно, чтобы вся графика отмасштабировалась без правки кода. Тон
 * набирается слоями — база, крап, вертикальная растушёвка, детали — иначе
 * плоские заливки читаются как пластик.
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

    public static final String OVERRIDE_DIR = "assets/mobs";

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
    private static BufferedImage normalize(BufferedImage raw) {
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
        BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        // Фиксированный seed на вид: крап получается стабильным между запусками,
        // иначе текстура «дышала» бы при каждом старте игры.
        Random rnd = new Random(0xB0B5EEDL ^ type.ordinal());
        switch (type) {
            case COW -> cow(g, rnd);
            case PIG -> pig(g, rnd);
            case SHEEP -> sheep(g, rnd);
            case CHICKEN -> chicken(g, rnd);
            case ZOMBIE -> zombie(g, rnd);
        }
        g.dispose();
        return img;
    }

    // -------------------------------------------------------------------------
    //  Виды
    // -------------------------------------------------------------------------

    private static void cow(Graphics2D g, Random rnd) {
        // База светлее «настоящей» коровьей шкуры: на тёмно-бурой основе детали
        // морды не читались вовсе, а под вечерним светом моб превращался в
        // силуэт.
        Color hide = new Color(0x6B4A2C);
        Color hideDark = new Color(0x543A22);
        Color patch = new Color(0xF0E8D9);
        Color hoof = new Color(0x2A1F16);
        Color muzzle = new Color(0xD5A29F);

        for (int t : new int[] { T_HEAD_FRONT, T_HEAD_SIDE, T_HEAD_TOP, T_BODY_SIDE, T_BODY_TOP }) {
            fill(g, t, hide);
            dapple(g, t, hideDark, 10, 0.10f, 0.20f, rnd);
        }

        // Бок и спина: крупные белые пятна с рваным краем, не прямоугольники.
        blob(g, T_BODY_SIDE, 0.10f, 0.16f, 0.34f, 0.30f, patch, rnd);
        blob(g, T_BODY_SIDE, 0.55f, 0.48f, 0.36f, 0.34f, patch, rnd);
        blob(g, T_BODY_SIDE, 0.30f, 0.72f, 0.22f, 0.20f, patch, rnd);
        blob(g, T_BODY_TOP, 0.22f, 0.20f, 0.40f, 0.40f, patch, rnd);
        blob(g, T_BODY_TOP, 0.62f, 0.60f, 0.26f, 0.26f, patch, rnd);
        volume(g, T_BODY_SIDE, 26, 46);
        volume(g, T_BODY_TOP, 30, 16);

        // Морда: белая проточина, розовый нос с ноздрями, глаза с блеском.
        box(g, T_HEAD_FRONT, 0.34f, 0f, 0.32f, 0.34f, patch);
        box(g, T_HEAD_FRONT, 0.22f, 0.56f, 0.56f, 0.34f, muzzle);
        box(g, T_HEAD_FRONT, 0.22f, 0.56f, 0.56f, 0.06f, new Color(0xB07E7C));
        box(g, T_HEAD_FRONT, 0.34f, 0.68f, 0.09f, 0.12f, new Color(0x7C5150));
        box(g, T_HEAD_FRONT, 0.57f, 0.68f, 0.09f, 0.12f, new Color(0x7C5150));
        eye(g, T_HEAD_FRONT, 0.14f, 0.34f);
        eye(g, T_HEAD_FRONT, 0.66f, 0.34f);
        shadeBottom(g, T_HEAD_FRONT, 3, 26);

        // Профиль головы: ухо и намёк на скулу.
        box(g, T_HEAD_SIDE, 0.62f, 0.16f, 0.22f, 0.16f, hideDark);
        box(g, T_HEAD_SIDE, 0.10f, 0.52f, 0.34f, 0.22f, muzzle);
        eye(g, T_HEAD_SIDE, 0.30f, 0.30f);
        shadeBottom(g, T_HEAD_SIDE, 3, 26);

        // Рога сверху.
        box(g, T_HEAD_TOP, 0.06f, 0.34f, 0.18f, 0.14f, patch);
        box(g, T_HEAD_TOP, 0.76f, 0.34f, 0.18f, 0.14f, patch);
        box(g, T_HEAD_TOP, 0.02f, 0.30f, 0.10f, 0.10f, new Color(0xCFC6B4));
        box(g, T_HEAD_TOP, 0.88f, 0.30f, 0.10f, 0.10f, new Color(0xCFC6B4));

        // Нога светлее корпуса: иначе тёмная база плюс копыто плюс тень дают
        // почти чёрный столбик.
        limb(g, new Color(0x7B5734), hideDark, hoof, rnd);
        fill(g, T_ACCENT, patch);
        dapple(g, T_ACCENT, new Color(0xDED5C4), 8, 0.10f, 0.20f, rnd);
        volume(g, T_ACCENT, 24, 28);
    }

    private static void pig(Graphics2D g, Random rnd) {
        Color skin = new Color(0xEC9E9B);
        Color skinDark = new Color(0xD1817E);
        Color snout = new Color(0xC97673);
        Color hoof = new Color(0x6B4A48);

        for (int t : new int[] { T_HEAD_FRONT, T_HEAD_SIDE, T_HEAD_TOP, T_BODY_SIDE, T_BODY_TOP }) {
            fill(g, t, skin);
            dapple(g, t, skinDark, 8, 0.10f, 0.20f, rnd);
        }
        volume(g, T_BODY_SIDE, 26, 46);
        volume(g, T_BODY_TOP, 30, 16);

        // Пятачок с ноздрями и характерная складка над ним.
        box(g, T_HEAD_FRONT, 0.28f, 0.52f, 0.44f, 0.34f, snout);
        box(g, T_HEAD_FRONT, 0.28f, 0.52f, 0.44f, 0.06f, new Color(0xA65E5C));
        box(g, T_HEAD_FRONT, 0.37f, 0.64f, 0.09f, 0.13f, new Color(0x6E3E3D));
        box(g, T_HEAD_FRONT, 0.54f, 0.64f, 0.09f, 0.13f, new Color(0x6E3E3D));
        eye(g, T_HEAD_FRONT, 0.16f, 0.26f);
        eye(g, T_HEAD_FRONT, 0.68f, 0.26f);
        shadeBottom(g, T_HEAD_FRONT, 3, 22);

        box(g, T_HEAD_SIDE, 0.08f, 0.48f, 0.28f, 0.24f, snout);
        box(g, T_HEAD_SIDE, 0.58f, 0.10f, 0.26f, 0.22f, skinDark);   // ухо
        eye(g, T_HEAD_SIDE, 0.32f, 0.26f);
        shadeBottom(g, T_HEAD_SIDE, 3, 22);

        // Уши сверху — треугольниками из двух ступенек.
        box(g, T_HEAD_TOP, 0.10f, 0.04f, 0.24f, 0.16f, skinDark);
        box(g, T_HEAD_TOP, 0.14f, 0.20f, 0.16f, 0.10f, snout);
        box(g, T_HEAD_TOP, 0.66f, 0.04f, 0.24f, 0.16f, skinDark);
        box(g, T_HEAD_TOP, 0.70f, 0.20f, 0.16f, 0.10f, snout);

        limb(g, skin, skinDark, hoof, rnd);
        fill(g, T_ACCENT, skinDark);
        dapple(g, T_ACCENT, snout, 14, 0.06f, 0.12f, rnd);
    }

    private static void sheep(Graphics2D g, Random rnd) {
        Color wool = new Color(0xE9E6DF);
        Color woolMid = new Color(0xD5D1C8);
        Color woolDark = new Color(0xBFBAB0);
        Color face = new Color(0xCE9A97);
        Color hoof = new Color(0x585048);

        for (int t : new int[] { T_HEAD_SIDE, T_HEAD_TOP, T_BODY_SIDE, T_BODY_TOP, T_ACCENT }) {
            fill(g, t, wool);
            // Шерсть = крупные комки в три тона. Мелкий частый крап давал шум,
            // в котором форма комков не читалась вообще.
            dapple(g, t, woolMid, 11, 0.16f, 0.30f, rnd);
            dapple(g, t, woolDark, 6, 0.12f, 0.22f, rnd);
            dapple(g, t, new Color(0xF7F5F0), 9, 0.12f, 0.24f, rnd);
        }
        volume(g, T_BODY_SIDE, 28, 44);
        volume(g, T_BODY_TOP, 32, 14);
        volume(g, T_ACCENT, 24, 30);

        // Морда: шерсть на лбу, розовая мордочка, тёмный рот.
        fill(g, T_HEAD_FRONT, face);
        dapple(g, T_HEAD_FRONT, new Color(0xB98685), 7, 0.09f, 0.16f, rnd);
        box(g, T_HEAD_FRONT, 0f, 0f, 1f, 0.28f, wool);
        dapple(g, T_HEAD_FRONT, woolMid, 5, 0.10f, 0.18f, rnd);
        box(g, T_HEAD_FRONT, 0f, 0f, 1f, 0.06f, woolDark);
        eye(g, T_HEAD_FRONT, 0.14f, 0.36f);
        eye(g, T_HEAD_FRONT, 0.68f, 0.36f);
        box(g, T_HEAD_FRONT, 0.36f, 0.74f, 0.28f, 0.08f, new Color(0x6E4645));
        box(g, T_HEAD_FRONT, 0.44f, 0.60f, 0.05f, 0.07f, new Color(0x8A5C5B));
        box(g, T_HEAD_FRONT, 0.53f, 0.60f, 0.05f, 0.07f, new Color(0x8A5C5B));
        shadeBottom(g, T_HEAD_FRONT, 3, 22);

        box(g, T_HEAD_SIDE, 0.06f, 0.40f, 0.30f, 0.30f, face);   // мордочка в профиль
        box(g, T_HEAD_SIDE, 0.60f, 0.30f, 0.22f, 0.14f, woolDark); // ухо
        eye(g, T_HEAD_SIDE, 0.34f, 0.34f);

        limb(g, woolMid, woolDark, hoof, rnd);
    }

    private static void chicken(Graphics2D g, Random rnd) {
        Color body = new Color(0xF2F0EA);
        Color shade = new Color(0xD9D6CE);
        Color beak = new Color(0xE8A33C);
        Color comb = new Color(0xC8332C);
        Color leg = new Color(0xD9922F);

        for (int t : new int[] { T_HEAD_FRONT, T_HEAD_SIDE, T_HEAD_TOP, T_BODY_SIDE, T_BODY_TOP }) {
            fill(g, t, body);
            dapple(g, t, shade, 6, 0.12f, 0.22f, rnd);
        }
        // Перья на боку — ряды коротких штрихов.
        for (int row = 0; row < 5; row++) {
            float y = 0.24f + row * 0.15f;
            for (int i = 0; i < 4; i++) {
                float x = 0.08f + i * 0.22f + (row % 2 == 0 ? 0f : 0.08f);
                box(g, T_BODY_SIDE, x, y, 0.13f, 0.05f, shade);
            }
        }
        shadeBottom(g, T_BODY_SIDE, 3, 30);

        // Клюв, гребень, бородка.
        box(g, T_HEAD_FRONT, 0.36f, 0.48f, 0.28f, 0.18f, beak);
        box(g, T_HEAD_FRONT, 0.40f, 0.66f, 0.20f, 0.08f, new Color(0xBE7F26));
        box(g, T_HEAD_FRONT, 0.40f, 0.78f, 0.20f, 0.16f, comb);
        box(g, T_HEAD_FRONT, 0.34f, 0f, 0.32f, 0.14f, comb);
        eye(g, T_HEAD_FRONT, 0.10f, 0.24f);
        eye(g, T_HEAD_FRONT, 0.72f, 0.24f);

        box(g, T_HEAD_SIDE, 0.04f, 0.44f, 0.26f, 0.16f, beak);
        box(g, T_HEAD_SIDE, 0.30f, 0f, 0.36f, 0.14f, comb);
        eye(g, T_HEAD_SIDE, 0.40f, 0.26f);

        box(g, T_HEAD_TOP, 0.34f, 0.10f, 0.30f, 0.30f, comb);
        box(g, T_HEAD_TOP, 0.40f, 0.02f, 0.18f, 0.10f, new Color(0xA82823));

        // Лапы чешуйчатые.
        fill(g, T_LIMB, leg);
        for (int i = 0; i < 7; i++)
            box(g, T_LIMB, 0.1f, 0.08f + i * 0.13f, 0.8f, 0.05f, new Color(0xB87824));
        box(g, T_LIMB, 0f, 0.86f, 1f, 0.14f, new Color(0x9C6420));

        // Крыло — маховые перья тремя тонами.
        fill(g, T_ACCENT, body);
        for (int i = 0; i < 5; i++) {
            float y = 0.12f + i * 0.17f;
            box(g, T_ACCENT, 0.06f, y, 0.88f, 0.10f, i % 2 == 0 ? shade : new Color(0xE6E3DB));
        }
        box(g, T_ACCENT, 0f, 0.82f, 1f, 0.18f, new Color(0xC9C5BC));
    }

    private static void zombie(Graphics2D g, Random rnd) {
        Color skin = new Color(0x4C7A38);
        Color skinDark = new Color(0x3A5E2A);
        Color skinLight = new Color(0x5E8E45);
        Color shirt = new Color(0x2F6E7C);
        Color shirtDark = new Color(0x235662);
        Color pants = new Color(0x2C3A55);
        Color pantsDark = new Color(0x212C42);
        Color socket = new Color(0x14200E);

        for (int t : new int[] { T_HEAD_FRONT, T_HEAD_SIDE }) {
            fill(g, t, skin);
            dapple(g, t, skinDark, 9, 0.10f, 0.20f, rnd);
            dapple(g, t, skinLight, 5, 0.09f, 0.16f, rnd);
        }

        // Волосы сверху и на затылке — тёмная шапка.
        fill(g, T_HEAD_TOP, new Color(0x2A4420));
        dapple(g, T_HEAD_TOP, new Color(0x1F3318), 18, 0.06f, 0.14f, rnd);
        box(g, T_HEAD_SIDE, 0f, 0f, 1f, 0.18f, new Color(0x2A4420));
        box(g, T_HEAD_FRONT, 0f, 0f, 1f, 0.12f, new Color(0x2A4420));

        // Пустые глазницы с еле заметным блеском внутри — читается как «мёртвый».
        box(g, T_HEAD_FRONT, 0.14f, 0.30f, 0.24f, 0.18f, socket);
        box(g, T_HEAD_FRONT, 0.62f, 0.30f, 0.24f, 0.18f, socket);
        box(g, T_HEAD_FRONT, 0.20f, 0.36f, 0.07f, 0.06f, new Color(0x2E4A22));
        box(g, T_HEAD_FRONT, 0.68f, 0.36f, 0.07f, 0.06f, new Color(0x2E4A22));
        // Приоткрытый рот с зубами.
        box(g, T_HEAD_FRONT, 0.30f, 0.70f, 0.40f, 0.12f, new Color(0x24160F));
        box(g, T_HEAD_FRONT, 0.34f, 0.70f, 0.06f, 0.05f, new Color(0xC9C4B4));
        box(g, T_HEAD_FRONT, 0.46f, 0.70f, 0.06f, 0.05f, new Color(0xC9C4B4));
        box(g, T_HEAD_FRONT, 0.58f, 0.70f, 0.06f, 0.05f, new Color(0xC9C4B4));
        box(g, T_HEAD_SIDE, 0.16f, 0.32f, 0.20f, 0.16f, socket);
        shadeBottom(g, T_HEAD_FRONT, 3, 24);
        shadeBottom(g, T_HEAD_SIDE, 3, 24);

        // Рубаха: шов по центру, рваный подол, из-под него зелёная кожа.
        fill(g, T_BODY_SIDE, shirt);
        dapple(g, T_BODY_SIDE, shirtDark, 8, 0.10f, 0.20f, rnd);
        box(g, T_BODY_SIDE, 0.46f, 0f, 0.08f, 1f, shirtDark);
        box(g, T_BODY_SIDE, 0f, 0.80f, 1f, 0.20f, skin);
        dapple(g, T_BODY_SIDE, skinDark, 4, 0.08f, 0.14f, rnd);
        // Рваный край подола ступеньками.
        for (int i = 0; i < 6; i++)
            box(g, T_BODY_SIDE, i * 0.17f, 0.74f + (i % 2) * 0.06f, 0.17f, 0.08f, shirt);
        volume(g, T_BODY_SIDE, 24, 44);

        fill(g, T_BODY_TOP, shirt);
        dapple(g, T_BODY_TOP, shirtDark, 7, 0.11f, 0.20f, rnd);
        volume(g, T_BODY_TOP, 28, 14);

        // Штаны с тёмным швом.
        fill(g, T_LIMB, pants);
        dapple(g, T_LIMB, pantsDark, 7, 0.10f, 0.20f, rnd);
        box(g, T_LIMB, 0.44f, 0f, 0.12f, 1f, pantsDark);
        box(g, T_LIMB, 0f, 0.88f, 1f, 0.12f, new Color(0x191F2E));   // обувь
        shadeBottom(g, T_LIMB, 3, 30);

        // Руки: голая кожа, у плеча остаток рукава.
        fill(g, T_ACCENT, skin);
        dapple(g, T_ACCENT, skinDark, 8, 0.10f, 0.18f, rnd);
        dapple(g, T_ACCENT, skinLight, 4, 0.08f, 0.14f, rnd);
        box(g, T_ACCENT, 0f, 0f, 1f, 0.30f, shirt);
        box(g, T_ACCENT, 0f, 0.28f, 1f, 0.05f, shirtDark);
        volume(g, T_ACCENT, 20, 28);
    }

    // -------------------------------------------------------------------------
    //  Примитивы рисования. Все координаты — доли тайла (0..1).
    // -------------------------------------------------------------------------

    /** Нога: база, крап, тёмное копыто внизу, растушёвка. */
    private static void limb(Graphics2D g, Color base, Color dark, Color hoof, Random rnd) {
        fill(g, T_LIMB, base);
        dapple(g, T_LIMB, dark, 16, 0.06f, 0.14f, rnd);
        box(g, T_LIMB, 0f, 0.78f, 1f, 0.22f, hoof);
        box(g, T_LIMB, 0f, 0.74f, 1f, 0.05f, dark);
        shadeBottom(g, T_LIMB, 3, 30);
    }

    /** Глаз: тёмный зрачок и светлый блик — без блика взгляд «мёртвый». */
    private static void eye(Graphics2D g, int tile, float fx, float fy) {
        box(g, tile, fx, fy, 0.20f, 0.16f, new Color(0x140F0C));
        box(g, tile, fx + 0.03f, fy + 0.03f, 0.07f, 0.06f, new Color(0xE8E4DC));
    }

    private static void fill(Graphics2D g, int tile, Color c) {
        box(g, tile, 0f, 0f, 1f, 1f, c);
    }

    /** Случайные пятнышки одного тона — крап, который убирает «пластиковость». */
    private static void dapple(Graphics2D g, int tile, Color c, int count,
                               float minSize, float maxSize, Random rnd) {
        for (int i = 0; i < count; i++) {
            float s = minSize + rnd.nextFloat() * (maxSize - minSize);
            float x = rnd.nextFloat() * (1f - s);
            float y = rnd.nextFloat() * (1f - s);
            box(g, tile, x, y, s, s * (0.6f + rnd.nextFloat() * 0.8f), c);
        }
    }

    /** Пятно с рваным краем: ядро плюс несколько случайных выступов. */
    private static void blob(Graphics2D g, int tile, float fx, float fy, float fw, float fh,
                             Color c, Random rnd) {
        box(g, tile, fx, fy, fw, fh, c);
        for (int i = 0; i < 6; i++) {
            float s = fw * (0.20f + rnd.nextFloat() * 0.3f);
            float x = fx - s * 0.5f + rnd.nextFloat() * fw;
            float y = fy - s * 0.5f + rnd.nextFloat() * fh;
            box(g, tile, clamp01(x, s), clamp01(y, s), s, s, c);
        }
    }

    /**
     * Объём одним движением: светлая полоса сверху и тень книзу. Свет во всей
     * текстуре падает сверху — без этого правила тайлы не склеиваются в одну
     * фигуру, каждый читается как отдельная наклейка.
     */
    private static void volume(Graphics2D g, int tile, int topLight, int bottomShade) {
        box(g, tile, 0f, 0f, 1f, 0.10f, new Color(255, 255, 255, topLight));
        box(g, tile, 0f, 0.10f, 1f, 0.08f, new Color(255, 255, 255, topLight / 2));
        shadeBottom(g, tile, 3, bottomShade);
    }

    /** Вертикальная растушёвка: несколько полупрозрачных полос книзу. */
    private static void shadeBottom(Graphics2D g, int tile, int steps, int maxAlpha) {
        for (int i = 0; i < steps; i++) {
            float h = 0.5f / steps;
            float y = 1f - h * (i + 1);
            int alpha = maxAlpha * (i + 1) / steps;
            box(g, tile, 0f, y, 1f, h, new Color(0, 0, 0, alpha));
        }
    }

    private static float clamp01(float v, float size) {
        return Math.max(0f, Math.min(1f - size, v));
    }

    /** Прямоугольник в долях тайла. */
    private static void box(Graphics2D g, int tile, float fx, float fy, float fw, float fh,
                            Color c) {
        int bx = (tile % COLS) * TILE;
        int by = (tile / COLS) * TILE;
        int x = Math.round(fx * TILE);
        int y = Math.round(fy * TILE);
        int w = Math.max(1, Math.round(fw * TILE));
        int h = Math.max(1, Math.round(fh * TILE));
        // Не выпускаем кисть за пределы своего тайла — иначе сосед пачкается.
        w = Math.min(w, TILE - x);
        h = Math.min(h, TILE - y);
        if (w <= 0 || h <= 0)
            return;
        g.setColor(c);
        g.fillRect(bx + x, by + y, w, h);
    }
}

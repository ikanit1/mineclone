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

/**
 * Скины мобов: пиксельные текстуры 64×32 (8 тайлов 16×16, 4 колонки × 2 ряда).
 *
 * <pre>
 * 0 head_front   1 head_side   2 head_top   3 body_side
 * 4 body_top     5 limb        6 accent     7 (свободный)
 * </pre>
 *
 * Каждая часть тела в {@link MobRenderer} берёт три тайла: front (грань −Z),
 * side (±X и +Z), top (±Y) — так «лицо» остаётся только на морде.
 *
 * Файл {@code assets/mobs/<type>.png} (той же раскладки) переопределяет
 * процедурный скин; иной размер масштабируется nearest-neighbour. Только AWT —
 * никакого GL, поэтому класс тестируется без окна.
 */
public final class MobSkins {
    public static final int TILE = 16;
    public static final int COLS = 4;
    public static final int ROWS = 2;
    public static final int WIDTH = TILE * COLS;   // 64
    public static final int HEIGHT = TILE * ROWS;  // 32

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

    /** Приводит override к 64×32 ARGB (nearest — пиксель-арт не мылится). */
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
        switch (type) {
            case COW -> {
                Color hide = new Color(0x4B3621);
                Color patch = new Color(0xEDE6DA);
                fill(g, T_HEAD_FRONT, hide);
                fill(g, T_HEAD_SIDE, hide);
                fill(g, T_HEAD_TOP, hide);
                fill(g, T_BODY_SIDE, hide);
                fill(g, T_BODY_TOP, hide);
                fill(g, T_LIMB, new Color(0x3A2A1A));
                fill(g, T_ACCENT, patch);
                rect(g, T_HEAD_FRONT, 4, 9, 8, 5, new Color(0xD9A6A6));   // морда
                rect(g, T_HEAD_FRONT, 3, 4, 3, 3, Color.BLACK);           // глаза
                rect(g, T_HEAD_FRONT, 10, 4, 3, 3, Color.BLACK);
                rect(g, T_HEAD_TOP, 1, 6, 3, 3, patch);                   // рога
                rect(g, T_HEAD_TOP, 12, 6, 3, 3, patch);
                rect(g, T_BODY_SIDE, 2, 3, 5, 4, patch);                  // пятна
                rect(g, T_BODY_SIDE, 9, 8, 5, 5, patch);
                rect(g, T_BODY_TOP, 5, 2, 6, 6, patch);
            }
            case PIG -> {
                Color skin = new Color(0xF0A5A2);
                fill(g, T_HEAD_FRONT, skin);
                fill(g, T_HEAD_SIDE, skin);
                fill(g, T_HEAD_TOP, skin);
                fill(g, T_BODY_SIDE, skin);
                fill(g, T_BODY_TOP, new Color(0xE79C99));
                fill(g, T_LIMB, new Color(0xD98E8B));
                fill(g, T_ACCENT, new Color(0xD98E8B));
                rect(g, T_HEAD_FRONT, 5, 8, 6, 5, new Color(0xD9807D));   // пятачок
                rect(g, T_HEAD_FRONT, 6, 10, 1, 2, new Color(0x8A4A48));  // ноздри
                rect(g, T_HEAD_FRONT, 9, 10, 1, 2, new Color(0x8A4A48));
                rect(g, T_HEAD_FRONT, 3, 3, 3, 3, Color.BLACK);
                rect(g, T_HEAD_FRONT, 10, 3, 3, 3, Color.BLACK);
                rect(g, T_HEAD_TOP, 2, 1, 4, 4, new Color(0xD9807D));     // уши
                rect(g, T_HEAD_TOP, 10, 1, 4, 4, new Color(0xD9807D));
            }
            case SHEEP -> {
                Color wool = new Color(0xEFEFEF);
                Color face = new Color(0xD9A6A6);
                fill(g, T_HEAD_FRONT, face);
                fill(g, T_HEAD_SIDE, wool);
                fill(g, T_HEAD_TOP, wool);
                fill(g, T_BODY_SIDE, wool);
                fill(g, T_BODY_TOP, wool);
                fill(g, T_LIMB, new Color(0xD8D8D8));
                fill(g, T_ACCENT, wool);
                rect(g, T_HEAD_FRONT, 0, 0, 16, 5, wool);                 // шерсть на лбу
                rect(g, T_HEAD_FRONT, 3, 6, 3, 3, Color.BLACK);
                rect(g, T_HEAD_FRONT, 10, 6, 3, 3, Color.BLACK);
                rect(g, T_HEAD_FRONT, 6, 12, 4, 2, new Color(0x8A4A48));  // рот
                // Комки шерсти, чтобы бок не был плоским
                rect(g, T_BODY_SIDE, 2, 4, 4, 4, new Color(0xDCDCDC));
                rect(g, T_BODY_SIDE, 9, 7, 5, 5, new Color(0xDCDCDC));
            }
            case CHICKEN -> {
                Color body = new Color(0xF7F7F7);
                Color beak = new Color(0xFFB000);
                fill(g, T_HEAD_FRONT, body);
                fill(g, T_HEAD_SIDE, body);
                fill(g, T_HEAD_TOP, body);
                fill(g, T_BODY_SIDE, body);
                fill(g, T_BODY_TOP, new Color(0xE9E9E9));
                fill(g, T_LIMB, beak);
                fill(g, T_ACCENT, new Color(0xE0E0E0));
                rect(g, T_HEAD_FRONT, 6, 8, 4, 4, beak);                  // клюв
                rect(g, T_HEAD_FRONT, 3, 3, 3, 3, Color.BLACK);
                rect(g, T_HEAD_FRONT, 10, 3, 3, 3, Color.BLACK);
                rect(g, T_HEAD_FRONT, 6, 13, 4, 3, new Color(0xD82B2B));  // бородка
                rect(g, T_HEAD_TOP, 6, 1, 4, 4, new Color(0xD82B2B));     // гребень
            }
            case ZOMBIE -> {
                Color skin = new Color(0x4E7A38);
                Color shirt = new Color(0x35657E);
                fill(g, T_HEAD_FRONT, skin);
                fill(g, T_HEAD_SIDE, skin);
                fill(g, T_HEAD_TOP, new Color(0x2F4A22));                 // волосы
                fill(g, T_BODY_SIDE, shirt);
                fill(g, T_BODY_TOP, shirt);
                fill(g, T_LIMB, new Color(0x2B3A52));                     // штаны
                fill(g, T_ACCENT, skin);                                  // руки
                rect(g, T_HEAD_FRONT, 3, 5, 3, 3, new Color(0x101A0C));   // пустые глазницы
                rect(g, T_HEAD_FRONT, 10, 5, 3, 3, new Color(0x101A0C));
                rect(g, T_HEAD_FRONT, 5, 11, 6, 2, new Color(0x2F4A22));  // рот
                rect(g, T_BODY_SIDE, 6, 0, 4, 16, new Color(0x2E5771));   // застёжка
            }
        }
        g.dispose();
        return img;
    }

    private static void fill(Graphics2D g, int tile, Color c) {
        rect(g, tile, 0, 0, TILE, TILE, c);
    }

    /** Прямоугольник в координатах внутри тайла (0..15). */
    private static void rect(Graphics2D g, int tile, int x, int y, int w, int h, Color c) {
        int bx = (tile % COLS) * TILE;
        int by = (tile / COLS) * TILE;
        g.setColor(c);
        g.fillRect(bx + x, by + y, w, h);
    }
}

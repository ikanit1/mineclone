package com.mineclone.render;

import com.mineclone.core.AppPaths;
import com.mineclone.render.PixelArt.Pal;
import com.mineclone.render.PixelArt.Sheet;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * Скин игрока — та же раскладка из 8 тайлов, что у {@link MobSkins}, и те же
 * правила пиксель-арта ({@link PixelArt}): мастер 16×16, рампы по 5 стопов с
 * hue shifting, объём дизерингом.
 *
 * Сейчас из него рисуется только рука от первого лица
 * ({@link HeldItemRenderer}), поэтому вылизаны три тайла: {@link
 * MobSkins#T_LIMB} и {@link MobSkins#T_ACCENT} — длинные грани руки, {@link
 * MobSkins#T_BODY_TOP} — торец с кулаком. Остальные заполнены как обычный
 * скин, чтобы текстуру можно было взять под модель от третьего лица, не
 * переделывая раскладку.
 *
 * Важное соглашение: на тайлах руки плечо сверху, кисть снизу — как на ногах
 * мобов. Разворотом кисти вверх занимается поза, а не текстура.
 *
 * Override — {@code assets/mobs/player.png}.
 */
public final class PlayerSkin {

    private static final float P = 1f / PixelArt.M;

    private PlayerSkin() {}

    /** Override с диска, иначе процедурный скин. */
    public static BufferedImage load() {
        File f = new File(AppPaths.file(MobSkins.OVERRIDE_DIR), "player.png");
        if (f.exists()) {
            try {
                BufferedImage raw = ImageIO.read(f);
                if (raw != null)
                    return MobSkins.normalize(raw);
            } catch (IOException e) {
                System.err.println("Failed to load player skin " + f + ": " + e.getMessage());
            }
        }
        return generate();
    }

    public static BufferedImage generate() {
        Pal p = new Pal();
        int skin  = p.ramp("6B4230", "8C5A3D", "AC7452", "C58F6C", "DDAE8A");
        int shirt = p.ramp("103A44", "17505D", "1F6875", "2A818D", "3C9DA6");
        int hair  = p.ramp("1E140F", "2C1E14", "3A2A1A", "483722", "58452C");
        int denim = p.ramp("1B2438", "26304A", "313D5E", "3D4B72", "4C5C88");
        int bone  = p.ramp("8E8A7E", "A9A497", "C0BBAC", "D5D0C0", "EDE8D6");
        int dark  = p.ramp("0C0A0B", "151213", "1F1A1B", "2A2323", "372E2D");

        Sheet s = new Sheet(MobSkins.COLS, MobSkins.ROWS);

        // --- рука: то, ради чего скин и существует ------------------------
        arm(s, MobSkins.T_LIMB, skin, shirt, dark, 301);
        arm(s, MobSkins.T_ACCENT, skin, shirt, dark, 302);
        fist(s, MobSkins.T_BODY_TOP, skin, 303);

        // --- остальное: обычный скин под будущую модель -------------------
        s.grad(MobSkins.T_HEAD_FRONT, skin, 0.82f, 0.46f, 311);
        s.rectGrad(MobSkins.T_HEAD_FRONT, 0f, 0f, 1f, 4 * P, hair, 0.75f, 0.40f, 312);
        s.ragged(MobSkins.T_HEAD_FRONT, 4 * P, 2 * P, hair, 0.55f, 313);
        s.eye(MobSkins.T_HEAD_FRONT, 2 * P, 7 * P, dark, bone + 4, true);
        s.eye(MobSkins.T_HEAD_FRONT, 10 * P, 7 * P, dark, bone + 4, false);
        s.rect(MobSkins.T_HEAD_FRONT, 6 * P, 12 * P, 4 * P, P, skin);
        s.shift(MobSkins.T_HEAD_FRONT, 0f, 13 * P, 1f, 3 * P, -1);   // тень под скулой

        s.grad(MobSkins.T_HEAD_SIDE, skin, 0.80f, 0.44f, 314);
        s.rectGrad(MobSkins.T_HEAD_SIDE, 0f, 0f, 1f, 5 * P, hair, 0.72f, 0.38f, 315);
        s.rectGrad(MobSkins.T_HEAD_SIDE, 9 * P, 5 * P, 7 * P, 5 * P, hair, 0.55f, 0.30f, 316);
        s.eye(MobSkins.T_HEAD_SIDE, 3 * P, 7 * P, dark, bone + 4, true);
        s.grad(MobSkins.T_HEAD_TOP, hair, 0.70f, 0.36f, 317);

        s.grad(MobSkins.T_BODY_SIDE, shirt, 0.80f, 0.40f, 318);
        s.rect(MobSkins.T_BODY_SIDE, 0f, 0f, 1f, 2 * P, shirt + 1);  // ворот
        s.grad(MobSkins.T_SPARE, denim, 0.76f, 0.36f, 319);
        s.rect(MobSkins.T_SPARE, 7 * P, 0f, 2 * P, 1f, denim + 1);

        return s.toImage(p.toArray(), MobSkins.TILE);
    }

    /**
     * Длинная грань руки: рукав сверху, манжет, голое предплечье, кулак с
     * костяшками снизу. Свет падает сверху — как на всех остальных тайлах.
     */
    private static void arm(Sheet s, int tile, int skin, int shirt, int dark, int seed) {
        s.grad(tile, skin, 0.86f, 0.52f, seed);
        s.rectGrad(tile, 0f, 0f, 1f, 7 * P, shirt, 0.84f, 0.46f, seed + 1);
        s.ragged(tile, 7 * P, 2 * P, shirt, 0.40f, seed + 2);        // край рукава
        s.rect(tile, 0f, 7 * P, 1f, P, shirt);                       // манжет
        // Кулак: чуть темнее предплечья, с двумя бороздами между костяшками.
        s.rectGrad(tile, 0f, 12 * P, 1f, 4 * P, skin, 0.70f, 0.44f, seed + 3);
        s.rect(tile, 0f, 12 * P, 1f, P, skin);                       // запястье
        s.rect(tile, 5 * P, 13 * P, P, 3 * P, skin);
        s.rect(tile, 10 * P, 13 * P, P, 3 * P, skin);
        s.shift(tile, 0f, 15 * P, 1f, P, -1);
    }

    /** Торец руки — сжатый кулак: четыре костяшки и большой палец сбоку. */
    private static void fist(Sheet s, int tile, int skin, int seed) {
        s.grad(tile, skin, 0.84f, 0.50f, seed);
        for (int i = 0; i < 3; i++)
            s.rect(tile, (4 + i * 3) * P, 3 * P, P, 10 * P, skin);   // борозды
        s.rectGrad(tile, 0f, 5 * P, 3 * P, 6 * P, skin, 0.92f, 0.66f, seed + 1); // большой палец
        s.rect(tile, 3 * P, 5 * P, P, 6 * P, skin);
        s.shift(tile, 0f, 13 * P, 1f, 3 * P, -1);
    }
}

package com.mineclone.render;

import com.mineclone.world.BlockType;
import com.mineclone.world.ItemStack;

/**
 * Иконка предмета в слоте — общая для хотбара и всех окон.
 *
 * <p>Раньше это жило в {@code Hud} и умело ровно одно: куб или плоский тайл.
 * Ступени, снежный слой и спальник показывались полным кубом — то есть врали
 * о том, что игрок держит в руках. Форма иконки берётся из того же описания
 * блока, что и форма в мире.
 *
 * <p>Яркость граней — та же, что печёт мешер: кубик в интерфейсе и блок в
 * мире обязаны выглядеть из одной игры.
 */
public final class ItemIcons {

    /** Поворот кубика в покое: ровно та косая проекция 2:1, что была. */
    public static final float ICON_YAW = 45f;
    /** Скорость вращения кубика в выбранном слоте и под курсором, градусы в секунду. */
    public static final float ICON_SPIN = 55f;
    /** Высота ребра и подъём крышки в долях слота — пропорции прежней иконки. */
    private static final float ICON_SIDE_H = 0.643f, ICON_TILT = 0.5f;

    /** Сколько чисел занимает одна грань: 8 координат, яркость и «это крышка». */
    public static final int FLOATS_PER_FACE = 10;
    /** Потолок граней у самой сложной иконки — две коробки по пять граней. */
    public static final int MAX_FACES = 12;

    private final UiRenderer ui;
    private final TextureAtlas atlas;
    private Font small;

    // Буферы переиспользуются: иконок в кадре сотни, и каждая из них не имеет
    // права порождать мусор.
    private final float[] faces = new float[MAX_FACES * FLOATS_PER_FACE];
    private final float[] quad = new float[8];
    private final float[] shadow = new float[8];

    public ItemIcons(UiRenderer ui, TextureAtlas atlas) {
        this.ui = ui;
        this.atlas = atlas;
    }

    /** Шрифт для знака «?» на заглушке неизвестного предмета. */
    public void setFont(Font small) {
        this.small = small;
    }

    // -------------------------------------------------------------- рисование

    public void draw(ItemStack s, float x, float y, float size, float alpha) {
        draw(s, x, y, size, alpha, ICON_YAW);
    }

    /**
     * @param yawDeg поворот кубика вокруг вертикали; у плоской иконки не значит
     *               ничего
     */
    public void draw(ItemStack s, float x, float y, float size, float alpha, float yawDeg) {
        if (s == null || alpha <= 0f)
            return;
        if (s.item.missing) {
            drawMissing(x, y, size, alpha);
        } else {
            BlockType b = s.block();
            if (isFlat(b))
                drawTile(s.iconTile(), x, y, size, alpha);
            else
                drawShape(b, x, y, size, alpha, yawDeg);
        }
        if (s.hasDurability())
            drawDurabilityBar(s, x, y, size);
    }

    /** Счётчик стопки — в правом нижнем углу слота, с обводкой. */
    public void drawCount(TextRenderer text, Font font, int count,
            float x, float y, float size, int screenW, int screenH) {
        if (count <= 1)
            return;
        String s = Integer.toString(count);
        float cw = font.textWidth(s);
        text.drawOutlined(font, s, x + size - cw - 2f, y + size - 3f, screenW, screenH, 1f, 1f, 1f);
    }

    /** Кубом рисуется настоящий блок; крест, жидкость и не-блок — плоско. */
    public static boolean isFlat(BlockType b) {
        return b == null || b == BlockType.AIR || b.isCross()
                || b == BlockType.WATER || b == BlockType.WATER_FLOW || b == BlockType.LAVA;
    }

    private void drawTile(int tile, float x, float y, float size, float alpha) {
        float[] uv = TextureAtlas.uv(tile);
        ui.texQuad(x + 3f, y + 4f, size, size, atlas.getTextureId(),
                uv[0], uv[1], uv[2], uv[3], 0f, 0f, 0f, 0.25f * alpha);
        ui.texQuad(x, y, size, size, atlas.getTextureId(),
                uv[0], uv[1], uv[2], uv[3], 1f, 1f, 1f, alpha);
    }

    /**
     * Заглушка снесённого предмета: пурпурно-чёрная шахматка и знак вопроса.
     *
     * <p>Тот же язык, что у отсутствующей текстуры блока. Пустой слот сказал
     * бы игроку, что вещь пропала, а она просто стала непонятной.
     */
    private void drawMissing(float x, float y, float size, float alpha) {
        float h = size / 2f;
        for (int i = 0; i < 4; i++) {
            boolean magenta = (i % 2) == (i / 2 % 2);
            ui.quad(x + (i % 2) * h, y + (i / 2) * h, h, h,
                    magenta ? 0.93f : 0.06f, 0f, magenta ? 0.93f : 0.06f, alpha);
        }
        if (small != null) {
            float w = small.textWidth("?");
            ui.glyphs(small, "?", x + size / 2f - w / 2f, y + size * 0.72f, 1f, 1f, 1f, alpha);
        }
    }

    /**
     * Объёмная иконка: одна или две коробки по форме блока.
     *
     * <p>Куб чуть уже слота, чтобы остались поля и цифра количества не наезжала
     * на грань. Масштаб не зависит от поворота — иначе кубик «дышал» бы по
     * ширине, вращаясь.
     */
    private void drawShape(BlockType b, float x, float y, float size, float alpha, float yawDeg) {
        int tid = atlas.getTextureId();
        float[] topUv = TextureAtlas.uv(b.topTile);
        float[] sideUv = TextureAtlas.uv(b.sideTile);
        float scale = size * 0.88f / (float) Math.sqrt(2.0);
        float cx = x + size / 2f, cy = y + size * 0.46f;

        // Контактная тень: приплюснутый ромб под иконкой.
        float bottom = y + size * 0.88f;
        float rise = size * 0.88f * 0.25f, hw = size * 0.44f;
        float sy = bottom - rise * 0.30f, sh = rise * 0.42f, sw = hw * 1.08f;
        shadow[0] = cx - sw; shadow[1] = sy;
        shadow[2] = cx;      shadow[3] = sy - sh;
        shadow[4] = cx + sw; shadow[5] = sy;
        shadow[6] = cx;      shadow[7] = sy + sh;
        ui.quad4(shadow, 0f, 0f, 0f, 0.26f * alpha);

        int count = shapeFaces(b, yawDeg, faces);
        for (int f = 0; f < count; f++) {
            int o = f * FLOATS_PER_FACE;
            for (int i = 0; i < 4; i++) {
                quad[i * 2] = cx + faces[o + i * 2] * scale;
                quad[i * 2 + 1] = cy + faces[o + i * 2 + 1] * scale;
            }
            float[] uv = faces[o + 9] == 0f ? topUv : sideUv;
            float l = faces[o + 8];
            ui.texQuad4(quad, tid, uv[0], uv[1], uv[2], uv[3], l, l, l, alpha);
        }
    }

    private void drawDurabilityBar(ItemStack s, float x, float y, float size) {
        float k = s.condition();
        if (k >= 1f)
            return;
        float barH = Math.max(2f, size * 0.10f);
        float by = y + size - barH;
        ui.quad(x, by, size, barH, 0.10f, 0.10f, 0.10f, 0.9f);
        ui.quad(x, by, size * k, barH, 1f - k, 0.15f + 0.75f * k, 0.12f, 1f);
    }

    // --------------------------------------------------------------- геометрия

    /**
     * Грани иконки блока: коробки складываются по форме блока в мире.
     *
     * <p>Дальняя коробка рисуется первой — порядок считается по глубине после
     * поворота, а не задаётся списком: при вращении «дальше» и «ближе»
     * меняются местами.
     *
     * @return сколько граней записано в {@code out}
     */
    public static int shapeFaces(BlockType b, float yawDeg, float[] out) {
        if (b == BlockType.STAIRS) {
            // Нижний полублок и верхняя задняя четверть — тот же силуэт, что
            // у ступени в мире.
            float lowZ = 0.5f, highZ = 0.25f;
            double a = Math.toRadians(yawDeg);
            float depthLow = depth(lowZ - 0.5f, a);
            float depthHigh = depth(highZ - 0.5f, a);
            int n;
            if (depthHigh <= depthLow) {
                n = boxFaces(0f, 0.5f, 0f, 1f, 1f, 0.5f, yawDeg, out, 0);
                n += boxFaces(0f, 0f, 0f, 1f, 0.5f, 1f, yawDeg, out, n);
            } else {
                n = boxFaces(0f, 0f, 0f, 1f, 0.5f, 1f, yawDeg, out, 0);
                n += boxFaces(0f, 0.5f, 0f, 1f, 1f, 0.5f, yawDeg, out, n);
            }
            return n;
        }
        if (b == BlockType.SNOW_LAYER)
            return boxFaces(0f, 0f, 0f, 1f, 2f / 8f, 1f, yawDeg, out, 0);
        if (b == BlockType.BEDROLL)
            return boxFaces(0f, 0f, 0f, 1f, 4f / 8f, 1f, yawDeg, out, 0);
        return boxFaces(0f, 0f, 0f, 1f, 1f, 1f, yawDeg, out, 0);
    }

    /** Глубина центра коробки после поворота; обе коробки стоят по центру x. */
    private static float depth(float dz, double a) {
        return (float) (dz * Math.cos(a));
    }

    /**
     * Видимые грани коробки в единицах блока, повёрнутой на {@code yawDeg}
     * вокруг вертикали, в координатах иконки (y вниз, как на экране).
     *
     * <p>Пишет в переданный буфер и ничего не выделяет: иконок в кадре сотни.
     * Раскладка одной грани — {x0,y0, x1,y1, x2,y2, x3,y3, яркость,
     * 0 крышка / 1 бок}; углы по кругу, первый — левый верхний угол текстуры.
     *
     * @return сколько граней записано
     */
    public static int boxFaces(float x0, float y0, float z0, float x1, float y1, float z1,
            float yawDeg, float[] out, int offset) {
        double a = Math.toRadians(yawDeg);
        float c = (float) Math.cos(a), s = (float) Math.sin(a);
        float lx0 = x0 - 0.5f, lx1 = x1 - 0.5f, lz0 = z0 - 0.5f, lz1 = z1 - 0.5f;
        float ax = lx0 * c + lz0 * s, az = -lx0 * s + lz0 * c;
        float bx = lx1 * c + lz0 * s, bz = -lx1 * s + lz0 * c;
        float dx = lx1 * c + lz1 * s, dz = -lx1 * s + lz1 * c;
        float ex = lx0 * c + lz1 * s, ez = -lx0 * s + lz1 * c;

        int n = 0;
        n += side(ax, az, bx, bz, y0, y1, out, offset + n);
        n += side(bx, bz, dx, dz, y0, y1, out, offset + n);
        n += side(dx, dz, ex, ez, y0, y1, out, offset + n);
        n += side(ex, ez, ax, az, y0, y1, out, offset + n);

        // Крышка последней: она всегда сверху и всегда видна.
        int o = (offset + n) * FLOATS_PER_FACE;
        out[o] = ax;     out[o + 1] = screenY(y1, az);
        out[o + 2] = bx; out[o + 3] = screenY(y1, bz);
        out[o + 4] = dx; out[o + 5] = screenY(y1, dz);
        out[o + 6] = ex; out[o + 7] = screenY(y1, ez);
        out[o + 8] = 1f;
        out[o + 9] = 0f;
        return n + 1;
    }

    /**
     * Одна боковая грань между двумя соседними углами крышки.
     *
     * @return 1, если грань видна, иначе 0
     */
    private static int side(float px, float pz, float qx, float qz,
            float bottom, float top, float[] out, int face) {
        float nx = qz - pz, nz = -(qx - px);      // внешняя нормаль ребра
        if (nz <= 1e-4f)
            return 0;
        float len = (float) Math.hypot(nx, nz);
        // Свет слева: грань, повёрнутая влево, ярче — как FACE_LIGHT в мире.
        // Нормировка на 45° даёт ровно прежние 0.80 и 0.62.
        float t = Math.max(0f, Math.min(1f, 0.5f - 0.5f * (nx / len) / 0.7071f));
        float light = 0.62f + 0.18f * t;
        // У видимой грани обход p→q идёт справа налево: левый верхний угол
        // текстуры — это q, иначе бока выходят зеркальными.
        int o = face * FLOATS_PER_FACE;
        out[o] = qx;     out[o + 1] = screenY(top, qz);
        out[o + 2] = px; out[o + 3] = screenY(top, pz);
        out[o + 4] = px; out[o + 5] = screenY(bottom, pz);
        out[o + 6] = qx; out[o + 7] = screenY(bottom, qz);
        out[o + 8] = light;
        out[o + 9] = 1f;
        return 1;
    }

    /** Высота блока и глубина после поворота — в экранную вертикаль. */
    private static float screenY(float height, float rotatedZ) {
        return ICON_SIDE_H * (0.5f - height) + rotatedZ * ICON_TILT;
    }
}

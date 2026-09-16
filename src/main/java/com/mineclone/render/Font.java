package com.mineclone.render;

import org.lwjgl.stb.STBTTAlignedQuad;
import org.lwjgl.stb.STBTTPackContext;
import org.lwjgl.stb.STBTTPackedchar;
import org.lwjgl.stb.STBTruetype;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL30.GL_R8;

/**
 * Запекает TTF в одноканальный атлас через упаковщик stb_truetype.
 *
 * Раньше запекался один непрерывный диапазон ASCII 32..127 — единственное,
 * что умеет {@code stbtt_BakeFontBitmap}. Шрифт при этом содержит и кириллицу,
 * и Latin-1, а часть строк интерфейса уже была русской: они молча рисовались
 * пустыми, потому что символов не было в атласе. Упаковщик кладёт в один
 * атлас несколько диапазонов, поэтому здесь лежат ASCII, Latin-1, кириллица и
 * горстка типографских знаков, которые реально встречаются в строках игры.
 */
public class Font {
    /**
     * Диапазоны кодовых точек: {первая, сколько}. Символа, которого нет ни в
     * одном диапазоне, в строке просто нет — как и раньше.
     */
    private static final int[][] RANGES = {
            { 32, 95 },       // ASCII
            { 0xA0, 96 },     // Latin-1: «» ° · ×
            { 0x400, 96 },    // кириллица, включая Ё/ё
            { 0x2014, 1 },    // —
            { 0x2022, 1 },    // •
            { 0x2026, 1 },    // …
            { 0x201C, 1 },    // “
            { 0x201E, 1 },    // „
    };

    private final int texId;
    private final int bitmapW = 1024, bitmapH = 512;
    private final STBTTPackedchar.Buffer[] ranges = new STBTTPackedchar.Buffer[RANGES.length];
    private final float pixelHeight;

    public Font(String ttfPath, float pixelHeight) throws IOException {
        this.pixelHeight = pixelHeight;
        ByteBuffer ttf = readFileToDirectBuffer(ttfPath);
        ByteBuffer bitmap = MemoryUtil.memCalloc(bitmapW * bitmapH);

        try (STBTTPackContext pc = STBTTPackContext.malloc()) {
            // Отступ в пиксель между глифами: выборка GL_NEAREST на краю квада
            // иначе цепляет соседнюю букву.
            if (!STBTruetype.stbtt_PackBegin(pc, bitmap, bitmapW, bitmapH, 0, 1, MemoryUtil.NULL))
                throw new IOException("stbtt_PackBegin failed for " + ttfPath);
            STBTruetype.stbtt_PackSetOversampling(pc, 1, 1);
            for (int i = 0; i < RANGES.length; i++) {
                ranges[i] = STBTTPackedchar.malloc(RANGES[i][1]);
                if (!STBTruetype.stbtt_PackFontRange(pc, ttf, 0, pixelHeight,
                        RANGES[i][0], ranges[i]))
                    System.err.println("Font: glyph range U+" + Integer.toHexString(RANGES[i][0])
                            + " did not fit into the atlas");
            }
            STBTruetype.stbtt_PackEnd(pc);
        }

        texId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texId);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, bitmapW, bitmapH, 0, GL_RED, GL_UNSIGNED_BYTE, bitmap);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);

        MemoryUtil.memFree(bitmap);
        MemoryUtil.memFree(ttf);
    }

    public int getTexture() { return texId; }
    public int getBitmapW() { return bitmapW; }
    public int getBitmapH() { return bitmapH; }
    public float getPixelHeight() { return pixelHeight; }

    /** Номер диапазона, в котором лежит символ, или −1. */
    private static int rangeOf(int c) {
        for (int i = 0; i < RANGES.length; i++)
            if (c >= RANGES[i][0] && c < RANGES[i][0] + RANGES[i][1])
                return i;
        return -1;
    }

    /**
     * Builds vertex data (pos.xy + uv.xy interleaved) for a string at (x, y) in screen pixels.
     * Returns the number of vertices written.
     */
    public int buildString(String s, float x, float y, FloatBuffer out) {
        int verts = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer xb = stack.floats(x);
            FloatBuffer yb = stack.floats(y);
            STBTTAlignedQuad q = STBTTAlignedQuad.malloc(stack);
            for (int i = 0; i < s.length(); i++) {
                int c = s.charAt(i);
                int r = rangeOf(c);
                if (r < 0) continue;
                if (out.remaining() < 24) break;
                STBTruetype.stbtt_GetPackedQuad(ranges[r], bitmapW, bitmapH, c - RANGES[r][0],
                        xb, yb, q, true);

                float x0 = q.x0(), y0 = q.y0(), x1 = q.x1(), y1 = q.y1();
                float s0 = q.s0(), t0 = q.t0(), s1 = q.s1(), t1 = q.t1();

                // two triangles, 6 vertices, pos+uv each
                out.put(x0).put(y0).put(s0).put(t0);
                out.put(x1).put(y0).put(s1).put(t0);
                out.put(x1).put(y1).put(s1).put(t1);

                out.put(x0).put(y0).put(s0).put(t0);
                out.put(x1).put(y1).put(s1).put(t1);
                out.put(x0).put(y1).put(s0).put(t1);
                verts += 6;
            }
        }
        return verts;
    }

    /** Pixel width of a string when rendered. */
    public float textWidth(String s) {
        float w = 0;
        for (int i = 0; i < s.length(); i++) {
            int c = s.charAt(i);
            int r = rangeOf(c);
            if (r < 0) continue;
            w += ranges[r].get(c - RANGES[r][0]).xadvance();
        }
        return w;
    }

    public void destroy() {
        glDeleteTextures(texId);
        for (STBTTPackedchar.Buffer b : ranges)
            if (b != null)
                b.free();
    }

    private static ByteBuffer readFileToDirectBuffer(String path) throws IOException {
        byte[] bytes = Files.readAllBytes(Paths.get(path));
        ByteBuffer buf = MemoryUtil.memAlloc(bytes.length);
        buf.put(bytes).flip();
        return buf;
    }
}

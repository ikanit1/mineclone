package com.mineclone.render;

import org.lwjgl.stb.STBTTAlignedQuad;
import org.lwjgl.stb.STBTTBakedChar;
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
 * Bakes a TTF into a single-channel bitmap atlas via stb_truetype.
 * Covers ASCII 32..127. For each character we know its UV in the atlas and pen advance.
 */
public class Font {
    private static final int FIRST_CHAR = 32;
    private static final int NUM_CHARS  = 96;

    private final int texId;
    private final int bitmapW = 512, bitmapH = 512;
    private final STBTTBakedChar.Buffer charData;
    private final float pixelHeight;

    public Font(String ttfPath, float pixelHeight) throws IOException {
        this.pixelHeight = pixelHeight;
        ByteBuffer ttf = readFileToDirectBuffer(ttfPath);

        ByteBuffer bitmap = MemoryUtil.memAlloc(bitmapW * bitmapH);
        charData = STBTTBakedChar.malloc(NUM_CHARS);
        STBTruetype.stbtt_BakeFontBitmap(ttf, pixelHeight, bitmap, bitmapW, bitmapH, FIRST_CHAR, charData);

        texId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texId);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, bitmapW, bitmapH, 0, GL_RED, GL_UNSIGNED_BYTE, bitmap);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

        MemoryUtil.memFree(bitmap);
        MemoryUtil.memFree(ttf);
    }

    public int getTexture() { return texId; }
    public int getBitmapW() { return bitmapW; }
    public int getBitmapH() { return bitmapH; }
    public float getPixelHeight() { return pixelHeight; }

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
                if (c < FIRST_CHAR || c >= FIRST_CHAR + NUM_CHARS) continue;
                STBTruetype.stbtt_GetBakedQuad(charData, bitmapW, bitmapH, c - FIRST_CHAR, xb, yb, q, true);

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
            if (c < FIRST_CHAR || c >= FIRST_CHAR + NUM_CHARS) continue;
            w += charData.get(c - FIRST_CHAR).xadvance();
        }
        return w;
    }

    public void destroy() {
        glDeleteTextures(texId);
        charData.free();
    }

    private static ByteBuffer readFileToDirectBuffer(String path) throws IOException {
        byte[] bytes = Files.readAllBytes(Paths.get(path));
        ByteBuffer buf = MemoryUtil.memAlloc(bytes.length);
        buf.put(bytes).flip();
        return buf;
    }
}

package com.mineclone.render;

import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Превью мира для списка миров: уменьшенный кадр без интерфейса.
 *
 * <p>Кадр берётся из полноразмерного резольва {@link Backdrop} — он уже
 * односэмпловый и снят ровно между композитом и HUD. Уменьшение делает
 * видеокарта одним блитом в маленький буфер, поэтому из GPU читается
 * 256×144 пикселя, а не весь кадр: полный glReadPixels в 1080p — это
 * заметная пауза прямо в момент сохранения.
 */
public final class Thumbnail {

    public static final int WIDTH = 256, HEIGHT = 144;

    private int fbo, tex;

    /**
     * Прямоугольник кадра {x, y, w, h} с пропорциями превью, по центру.
     * Лишнее срезается, а не сплющивается: сжатый по высоте пейзаж выглядит
     * ошибкой, а обрезанный — кадрированием.
     */
    public static int[] crop(int sw, int sh, int tw, int th) {
        if ((long) sw * th > (long) sh * tw) {
            int w = (int) ((long) sh * tw / th);
            return new int[] { (sw - w) / 2, 0, w, sh };
        }
        int h = (int) ((long) sw * th / tw);
        return new int[] { 0, (sh - h) / 2, sw, h };
    }

    /**
     * Снять превью из буфера кадра.
     *
     * @return ARGB-пиксели построчно сверху вниз или null, если буфер не собрался
     */
    public int[] capture(int srcFbo, int sw, int sh) {
        if (srcFbo == 0 || sw <= 0 || sh <= 0)
            return null;
        ensureTarget();
        if (fbo == 0)
            return null;
        int[] r = crop(sw, sh, WIDTH, HEIGHT);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, srcFbo);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbo);
        glBlitFramebuffer(r[0], r[1], r[0] + r[2], r[1] + r[3], 0, 0, WIDTH, HEIGHT,
                GL_COLOR_BUFFER_BIT, GL_LINEAR);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
        ByteBuffer px = BufferUtils.createByteBuffer(WIDTH * HEIGHT * 4);
        glPixelStorei(GL_PACK_ALIGNMENT, 1);
        glReadPixels(0, 0, WIDTH, HEIGHT, GL_RGBA, GL_UNSIGNED_BYTE, px);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        int[] out = new int[WIDTH * HEIGHT];
        for (int y = 0; y < HEIGHT; y++) {
            int row = (HEIGHT - 1 - y) * WIDTH;   // GL читает снизу вверх
            for (int x = 0; x < WIDTH; x++) {
                int p = (y * WIDTH + x) * 4;
                out[row + x] = 0xff000000 | (px.get(p) & 255) << 16 | (px.get(p + 1) & 255) << 8
                        | (px.get(p + 2) & 255);
            }
        }
        return out;
    }

    private void ensureTarget() {
        if (fbo != 0)
            return;
        fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, WIDTH, HEIGHT, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
        boolean ok = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        if (!ok)
            destroy();
    }

    public void destroy() {
        if (fbo != 0) {
            glDeleteFramebuffers(fbo);
            glDeleteTextures(tex);
        }
        fbo = tex = 0;
    }
}

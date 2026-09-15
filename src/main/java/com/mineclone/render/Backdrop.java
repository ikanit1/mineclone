package com.mineclone.render;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Размытая копия готового кадра — то, что видно сквозь стеклянные панели
 * интерфейса.
 *
 * Снимается после композита и до интерфейса: в нём мир, но ещё нет самого HUD.
 * Экранный буфер окна многосэмпловый, а блит из многосэмплового буфера с
 * масштабированием запрещён — поэтому сначала полноразмерное схлопывание, и
 * только потом уменьшение в четыре раза и два прохода гаусса. На четверти
 * разрешения это дешевле одного полноэкранного прохода, а размытие получается
 * широким, как у настоящего матового стекла.
 */
public final class Backdrop {

    /** Во сколько раз копия меньше экрана. */
    public static final int DOWNSCALE = 4;

    private final Shader blurShader;
    private final int emptyVao;
    private int fullFbo, fullTex;
    private final int[] fbo = new int[2];
    private final int[] tex = new int[2];
    private int screenW, screenH, w, h;
    private boolean ready;
    private boolean captured;

    public Backdrop() {
        blurShader = new Shader(Shaders.POST_VERTEX, Shaders.POST_BLUR);
        emptyVao = glGenVertexArrays();
    }

    private void resize(int sw, int sh) {
        if (sw == screenW && sh == screenH && ready)
            return;
        destroyTargets();
        screenW = sw;
        screenH = sh;
        w = Math.max(1, sw / DOWNSCALE);
        h = Math.max(1, sh / DOWNSCALE);
        int[] full = target(sw, sh);
        fullFbo = full[0];
        fullTex = full[1];
        boolean ok = full[2] == 1;
        for (int i = 0; i < 2; i++) {
            int[] t = target(w, h);
            fbo[i] = t[0];
            tex[i] = t[1];
            ok &= t[2] == 1;
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        ready = ok;
    }

    private static int[] target(int w, int h) {
        int fb = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fb);
        int tx = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tx);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tx, 0);
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        return new int[] { fb, tx, status == GL_FRAMEBUFFER_COMPLETE ? 1 : 0 };
    }

    /** Снимает текущее содержимое экрана и размывает его. */
    public void capture(int sw, int sh) {
        resize(Math.max(1, sw), Math.max(1, sh));
        captured = false;
        if (!ready)
            return;
        glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fullFbo);
        glBlitFramebuffer(0, 0, sw, sh, 0, 0, sw, sh, GL_COLOR_BUFFER_BIT, GL_NEAREST);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, fullFbo);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbo[0]);
        glBlitFramebuffer(0, 0, sw, sh, 0, 0, w, h, GL_COLOR_BUFFER_BIT, GL_LINEAR);

        glDisable(GL_DEPTH_TEST);
        glDisable(GL_BLEND);
        glDisable(GL_CULL_FACE);
        glBindVertexArray(emptyVao);
        blurShader.bind();
        blurShader.setInt("uTex", 0);
        glViewport(0, 0, w, h);
        for (int pass = 0; pass < 2; pass++) {
            float scale = pass == 0 ? 1.2f : 2.4f;
            glBindFramebuffer(GL_FRAMEBUFFER, fbo[1]);
            blurShader.setVec2("uDir", scale / w, 0f);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, tex[0]);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            glBindFramebuffer(GL_FRAMEBUFFER, fbo[0]);
            blurShader.setVec2("uDir", 0f, scale / h);
            glBindTexture(GL_TEXTURE_2D, tex[1]);
            glDrawArrays(GL_TRIANGLES, 0, 3);
        }
        blurShader.unbind();
        glBindVertexArray(0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, sw, sh);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
        captured = true;
    }

    /**
     * Полноразмерный односэмпловый резольв кадра, снятый в этом же capture,
     * или 0. Из него берётся превью мира.
     */
    public int resolvedFramebuffer() {
        return ready && captured ? fullFbo : 0;
    }

    /** Текстура размытого кадра, или 0 — в этом кадре снять не удалось. */
    public int texture() {
        return ready && captured ? tex[0] : 0;
    }

    private void destroyTargets() {
        if (fullFbo != 0) {
            glDeleteFramebuffers(fullFbo);
            glDeleteTextures(fullTex);
        }
        for (int i = 0; i < 2; i++)
            if (fbo[i] != 0) {
                glDeleteFramebuffers(fbo[i]);
                glDeleteTextures(tex[i]);
            }
        fullFbo = fbo[0] = fbo[1] = 0;
        ready = false;
    }

    public void destroy() {
        destroyTargets();
        glDeleteVertexArrays(emptyVao);
        blurShader.destroy();
    }
}

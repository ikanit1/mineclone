package com.mineclone.render;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Кроссфейд между кадрами фона меню: снимок готового кадра и наплыв поверх
 * следующего.
 *
 * <p>Снимок — единственный способ смешать два пролёта: они стоят в разных
 * местах мира, и нарисовать их в один буфер нельзя — глубина перемешала бы
 * геометрию двух пейзажей. Замороженная на секунду картинка, из которой
 * проявляется живой кадр, читается ровно как кинематографический наплыв.
 *
 * <p>Снимается там же, где {@link Backdrop}: после композита и до интерфейса.
 * Блит из многосэмплового экранного буфера в односэмпловую цель того же
 * размера драйвер разрешает — он и резольвит.
 */
public final class MenuDissolve {

    private final Shader shader;
    private final int emptyVao;
    private int fbo, tex;
    private int w, h;
    private boolean ready, captured;

    public MenuDissolve() {
        shader = new Shader(Shaders.POST_VERTEX, Shaders.POST_FADE);
        emptyVao = glGenVertexArrays();
    }

    private void resize(int sw, int sh) {
        if (sw == w && sh == h && ready)
            return;
        destroyTarget();
        w = sw;
        h = sh;
        fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE,
                (java.nio.ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
        ready = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        captured = false;
    }

    /** Запоминает текущее содержимое экрана — будущий «уходящий» кадр. */
    public void capture(int sw, int sh) {
        resize(Math.max(1, sw), Math.max(1, sh));
        if (!ready)
            return;
        glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbo);
        glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL_COLOR_BUFFER_BIT, GL_NEAREST);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        captured = true;
    }

    /** Есть ли что показывать. */
    public boolean hasFrame() {
        return ready && captured;
    }

    /**
     * Накладывает снимок поверх текущего кадра.
     *
     * @param alpha 1 — виден только снимок, 0 — только живой кадр
     */
    public void render(float alpha) {
        if (!hasFrame() || alpha <= 0.001f)
            return;
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glBindVertexArray(emptyVao);
        shader.bind();
        shader.setInt("uScene", 0);
        shader.setFloat("uAlpha", Math.min(1f, alpha));
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, tex);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        shader.unbind();
        glBindVertexArray(0);
        glDisable(GL_BLEND);
        glEnable(GL_CULL_FACE);
        glEnable(GL_DEPTH_TEST);
    }

    private void destroyTarget() {
        if (fbo != 0) {
            glDeleteFramebuffers(fbo);
            glDeleteTextures(tex);
        }
        fbo = 0;
        tex = 0;
        ready = false;
        captured = false;
    }

    public void destroy() {
        destroyTarget();
        shader.destroy();
        glDeleteVertexArrays(emptyVao);
    }
}

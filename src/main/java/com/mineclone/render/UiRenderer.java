package com.mineclone.render;

import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Immediate-mode 2D quad renderer in screen-pixel coordinates (top-left origin).
 * One draw call per quad - fine for a HUD with a few dozen quads.
 */
public class UiRenderer {
    private final Shader shader;
    private final int vao, vbo;
    private final FloatBuffer buf; // 6 verts * (x,y,u,v)

    private int screenW, screenH;

    public UiRenderer() {
        shader = new Shader(Shaders.UI_VERTEX, Shaders.UI_FRAGMENT);
        buf = MemoryUtil.memAllocFloat(6 * 4);

        vao = glGenVertexArrays();
        vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) 6 * 4 * Float.BYTES, GL_DYNAMIC_DRAW);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 4 * Float.BYTES, 0L);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 4 * Float.BYTES, 2L * Float.BYTES);
        glEnableVertexAttribArray(1);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    /** Call once before a batch of quad/texQuad calls. */
    public void begin(int screenW, int screenH) {
        this.screenW = screenW;
        this.screenH = screenH;
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        shader.bind();
        shader.setVec2("uScreenSize", screenW, screenH);
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
    }

    public void end() {
        glBindVertexArray(0);
        shader.unbind();
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
    }

    /** Solid colored rectangle. */
    public void quad(float x, float y, float w, float h, float r, float g, float b, float a) {
        shader.setInt("uUseTexture", 0);
        shader.setVec4("uColor", r, g, b, a);
        upload(x, y, w, h, 0, 0, 0, 0);
        glDrawArrays(GL_TRIANGLES, 0, 6);
    }

    /** Textured rectangle sampled from the bound atlas, tinted by (r,g,b,a). */
    public void texQuad(float x, float y, float w, float h, int texId,
                        float u0, float v0, float u1, float v1,
                        float r, float g, float b, float a) {
        shader.setInt("uUseTexture", 1);
        shader.setInt("uTex", 0);
        shader.setVec4("uColor", r, g, b, a);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texId);
        upload(x, y, w, h, u0, v0, u1, v1);
        glDrawArrays(GL_TRIANGLES, 0, 6);
    }

    private void upload(float x, float y, float w, float h,
                        float u0, float v0, float u1, float v1) {
        float x0 = x, y0 = y, x1 = x + w, y1 = y + h;
        buf.clear();
        buf.put(x0).put(y0).put(u0).put(v0);
        buf.put(x1).put(y0).put(u1).put(v0);
        buf.put(x1).put(y1).put(u1).put(v1);
        buf.put(x0).put(y0).put(u0).put(v0);
        buf.put(x1).put(y1).put(u1).put(v1);
        buf.put(x0).put(y1).put(u0).put(v1);
        buf.flip();
        glBufferSubData(GL_ARRAY_BUFFER, 0L, buf);
    }

    public int screenW() { return screenW; }
    public int screenH() { return screenH; }

    public void destroy() {
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        shader.destroy();
        MemoryUtil.memFree(buf);
    }
}

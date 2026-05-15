package com.mineclone.render;

import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/** Pixel-coordinate text renderer. Pos units = screen pixels (top-left origin). */
public class TextRenderer {
    private static final int MAX_CHARS = 1024;
    private static final int VERTS_PER_QUAD = 6;
    private static final int FLOATS_PER_VERT = 4;
    private static final int CAPACITY_FLOATS = MAX_CHARS * VERTS_PER_QUAD * FLOATS_PER_VERT;

    private final Shader shader;
    private final int vao, vbo;
    private final FloatBuffer cpuBuf;

    public TextRenderer() {
        this.shader = new Shader(Shaders.TEXT_VERTEX, Shaders.TEXT_FRAGMENT);
        this.cpuBuf = MemoryUtil.memAllocFloat(CAPACITY_FLOATS);

        vao = glGenVertexArrays();
        vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) CAPACITY_FLOATS * Float.BYTES, GL_DYNAMIC_DRAW);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, FLOATS_PER_VERT * Float.BYTES, 0L);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, FLOATS_PER_VERT * Float.BYTES, 2L * Float.BYTES);
        glEnableVertexAttribArray(1);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    public void draw(Font font, String text, float x, float y, int screenW, int screenH,
                     float r, float g, float b, float a) {
        if (text == null || text.isEmpty()) return;
        cpuBuf.clear();
        int verts = font.buildString(text, x, y, cpuBuf);
        if (verts == 0) return;
        cpuBuf.flip();

        boolean depthOn = glGetBoolean(GL_DEPTH_TEST);
        boolean blendOn = glGetBoolean(GL_BLEND);
        boolean cullOn  = glGetBoolean(GL_CULL_FACE);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        shader.bind();
        shader.setVec2("uScreenSize", screenW, screenH);
        shader.setVec4("uColor", r, g, b, a);
        shader.setInt("uFont", 0);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, font.getTexture());

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0L, cpuBuf);
        glDrawArrays(GL_TRIANGLES, 0, verts);
        glBindVertexArray(0);

        shader.unbind();
        if (depthOn) glEnable(GL_DEPTH_TEST);
        if (!blendOn) glDisable(GL_BLEND);
        if (cullOn) glEnable(GL_CULL_FACE);
    }

    /** Draws shadow (1px down/right) then main color. */
    public void drawShadowed(Font font, String text, float x, float y, int screenW, int screenH,
                             float r, float g, float b) {
        draw(font, text, x + 1, y + 1, screenW, screenH, 0f, 0f, 0f, 0.7f);
        draw(font, text, x, y, screenW, screenH, r, g, b, 1f);
    }

    public void destroy() {
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        shader.destroy();
        MemoryUtil.memFree(cpuBuf);
    }
}

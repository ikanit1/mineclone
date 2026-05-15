package com.mineclone.render;

import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

public class Crosshair {
    private final int vao, vbo;
    private final Shader shader;

    public Crosshair() {
        shader = new Shader(Shaders.HUD_VERTEX, Shaders.HUD_FRAGMENT);
        vao = glGenVertexArrays();
        vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, 64L, GL_DYNAMIC_DRAW);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 0, 0);
        glEnableVertexAttribArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    public void render(int width, int height) {
        float sx = 12f / width;
        float sy = 12f / height;
        float[] verts = { -sx, 0,  sx, 0,  0, -sy,  0, sy };
        FloatBuffer fb = MemoryUtil.memAllocFloat(verts.length);
        fb.put(verts).flip();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, fb);
        MemoryUtil.memFree(fb);

        glDisable(GL_DEPTH_TEST);
        shader.bind();
        shader.setVec4("uColor", new org.joml.Vector4f(1f, 1f, 1f, 1f));
        glBindVertexArray(vao);
        glLineWidth(2f);
        glDrawArrays(GL_LINES, 0, 4);
        glBindVertexArray(0);
        shader.unbind();
        glEnable(GL_DEPTH_TEST);
    }

    public void destroy() {
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        shader.destroy();
    }
}

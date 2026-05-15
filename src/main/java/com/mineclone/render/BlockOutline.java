package com.mineclone.render;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

public class BlockOutline {
    private final int vao, vbo;
    private final Shader shader;

    public BlockOutline() {
        shader = new Shader(Shaders.LINE_VERTEX, Shaders.LINE_FRAGMENT);
        // 12 edges of a unit cube
        float[] e = {
                0,0,0, 1,0,0,   1,0,0, 1,0,1,   1,0,1, 0,0,1,   0,0,1, 0,0,0,
                0,1,0, 1,1,0,   1,1,0, 1,1,1,   1,1,1, 0,1,1,   0,1,1, 0,1,0,
                0,0,0, 0,1,0,   1,0,0, 1,1,0,   1,0,1, 1,1,1,   0,0,1, 0,1,1
        };
        vao = glGenVertexArrays();
        vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        FloatBuffer fb = MemoryUtil.memAllocFloat(e.length);
        fb.put(e).flip();
        glBufferData(GL_ARRAY_BUFFER, fb, GL_STATIC_DRAW);
        MemoryUtil.memFree(fb);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0);
        glEnableVertexAttribArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    public void render(Matrix4f projection, Matrix4f view, int x, int y, int z) {
        Matrix4f model = new Matrix4f().translate(x - 0.001f, y - 0.001f, z - 0.001f).scale(1.002f);
        shader.bind();
        shader.setMat4("uProjection", projection);
        shader.setMat4("uView", view);
        shader.setMat4("uModel", model);
        shader.setVec4("uColor", new Vector4f(0f, 0f, 0f, 1f));
        glBindVertexArray(vao);
        glLineWidth(1.5f);
        glDrawArrays(GL_LINES, 0, 24);
        glBindVertexArray(0);
        shader.unbind();
    }

    public void destroy() {
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        shader.destroy();
    }
}

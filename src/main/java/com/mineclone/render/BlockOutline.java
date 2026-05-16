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

    // Max 2 boxes × 12 edges × 2 verts × 3 floats = 144
    private static final int CAPACITY = 144;

    public BlockOutline() {
        shader = new Shader(Shaders.LINE_VERTEX, Shaders.LINE_FRAGMENT);
        vao = glGenVertexArrays();
        vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, CAPACITY * 4L, GL_DYNAMIC_DRAW);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0);
        glEnableVertexAttribArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    /** Full-cube outline (regular solid blocks). */
    public void render(Matrix4f proj, Matrix4f view, int x, int y, int z) {
        draw(proj, view, x, y, z, box(0, 0, 0, 1, 1, 1));
    }

    /** L-shaped stair outline: slab + step. */
    public void renderStairs(Matrix4f proj, Matrix4f view, int x, int y, int z, byte meta) {
        int facing = meta & 0x3;
        float sx0 = 0, sz0 = 0, sx1 = 1, sz1 = 1;
        if (facing == 0)      sz1 = 0.5f;
        else if (facing == 1) sx0 = 0.5f;
        else if (facing == 2) sz0 = 0.5f;
        else                  sx1 = 0.5f;
        float[] verts = concat(box(0, 0, 0, 1, 0.5f, 1),
                               box(sx0, 0.5f, sz0, sx1, 1, sz1));
        draw(proj, view, x, y, z, verts);
    }

    /** Thin-slab outline matching the door geometry. */
    public void renderDoor(Matrix4f proj, Matrix4f view, int x, int y, int z,
                           byte meta, boolean open) {
        float th = 3f / 16f;
        int facing = meta & 0x3;
        float x0 = 0, z0 = 0, x1 = 1, z1 = 1;
        if (facing == 0)      { if (open) x0 = 1 - th; else z0 = 1 - th; }
        else if (facing == 1) { if (open) z0 = 1 - th; else x1 = th; }
        else if (facing == 2) { if (open) x1 = th;     else z1 = th; }
        else                  { if (open) z1 = th;      else x0 = 1 - th; }
        draw(proj, view, x, y, z, box(x0, 0, z0, x1, 1, z1));
    }

    // -------------------------------------------------------------------------

    /** 12 edges of an axis-aligned box as 24 line-endpoint vertices, slightly expanded. */
    private static float[] box(float x0, float y0, float z0,
                               float x1, float y1, float z1) {
        float e = 0.001f;
        x0 -= e; y0 -= e; z0 -= e;
        x1 += e; y1 += e; z1 += e;
        return new float[] {
            x0,y0,z0, x1,y0,z0,   x1,y0,z0, x1,y0,z1,   x1,y0,z1, x0,y0,z1,   x0,y0,z1, x0,y0,z0,
            x0,y1,z0, x1,y1,z0,   x1,y1,z0, x1,y1,z1,   x1,y1,z1, x0,y1,z1,   x0,y1,z1, x0,y1,z0,
            x0,y0,z0, x0,y1,z0,   x1,y0,z0, x1,y1,z0,   x1,y0,z1, x1,y1,z1,   x0,y0,z1, x0,y1,z1,
        };
    }

    private static float[] concat(float[] a, float[] b) {
        float[] out = new float[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private void draw(Matrix4f proj, Matrix4f view, int bx, int by, int bz, float[] verts) {
        FloatBuffer fb = MemoryUtil.memAllocFloat(verts.length);
        try {
            fb.put(verts).flip();
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferSubData(GL_ARRAY_BUFFER, 0, fb);
            glBindBuffer(GL_ARRAY_BUFFER, 0);
        } finally {
            MemoryUtil.memFree(fb);
        }
        Matrix4f model = new Matrix4f().translate(bx, by, bz);
        shader.bind();
        shader.setMat4("uProjection", proj);
        shader.setMat4("uView", view);
        shader.setMat4("uModel", model);
        shader.setVec4("uColor", new Vector4f(0f, 0f, 0f, 1f));
        glBindVertexArray(vao);
        glLineWidth(1.5f);
        glDrawArrays(GL_LINES, 0, verts.length / 3);
        glBindVertexArray(0);
        shader.unbind();
    }

    public void destroy() {
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        shader.destroy();
    }
}

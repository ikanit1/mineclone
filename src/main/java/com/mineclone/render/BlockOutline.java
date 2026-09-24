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
    public void render(Matrix4f proj, Matrix4f view, int x, int y, int z, float linearOut) {
        draw(proj, view, x, y, z, box(0, 0, 0, 1, 1, 1), linearOut);
    }

    /** L-shaped stair outline: slab + step. */
    public void renderStairs(Matrix4f proj, Matrix4f view, int x, int y, int z, byte meta,
                             float linearOut) {
        int facing = meta & 0x3;
        float sx0 = 0, sz0 = 0, sx1 = 1, sz1 = 1;
        if (facing == 0)      sz1 = 0.5f;
        else if (facing == 1) sx0 = 0.5f;
        else if (facing == 2) sz0 = 0.5f;
        else                  sx1 = 0.5f;
        float[] verts = concat(box(0, 0, 0, 1, 0.5f, 1),
                               box(sx0, 0.5f, sz0, sx1, 1, sz1));
        draw(proj, view, x, y, z, verts, linearOut);
    }

    /**
     * Бокс двери в долях блока — тот же, что рисует {@link #renderDoor}:
     * minX, minY, minZ, maxX, maxY, maxZ.
     */
    public static float[] doorBox(byte meta, boolean open) {
        float th = 3f / 16f;
        int facing = meta & 0x3;
        float x0 = 0, z0 = 0, x1 = 1, z1 = 1;
        if (facing == 0)      { if (open) x0 = 1 - th; else z0 = 1 - th; }
        else if (facing == 1) { if (open) z0 = 1 - th; else x1 = th; }
        else if (facing == 2) { if (open) x1 = th;     else z1 = th; }
        else                  { if (open) z1 = th;      else x0 = 1 - th; }
        return new float[] { x0, 0f, z0, x1, 1f, z1 };
    }

    /** Thin-slab outline matching the door geometry. */
    public void renderDoor(Matrix4f proj, Matrix4f view, int x, int y, int z,
                           byte meta, boolean open, float linearOut) {
        float th = 3f / 16f;
        int facing = meta & 0x3;
        float x0 = 0, z0 = 0, x1 = 1, z1 = 1;
        if (facing == 0)      { if (open) x0 = 1 - th; else z0 = 1 - th; }
        else if (facing == 1) { if (open) z0 = 1 - th; else x1 = th; }
        else if (facing == 2) { if (open) x1 = th;     else z1 = th; }
        else                  { if (open) z1 = th;      else x0 = 1 - th; }
        draw(proj, view, x, y, z, box(x0, 0, z0, x1, 1, z1), linearOut);
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

    private void draw(Matrix4f proj, Matrix4f view, int bx, int by, int bz, float[] verts,
                      float linearOut) {
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
        shader.setFloat("uLinearOut", linearOut);
        shader.setFloat("uEmissive", 0f);
        glBindVertexArray(vao);
        // Width 1 is the only portable line width in a forward-compatible core context.
        glLineWidth(1f);
        glDrawArrays(GL_LINES, 0, verts.length / 3);
        glBindVertexArray(0);
        shader.unbind();
    }

    // -------------------------------------------------------------------------
    //  Тонкий контур выделения
    // -------------------------------------------------------------------------

    private int ribbonVao, ribbonVbo;
    private java.nio.FloatBuffer ribbonBuf;
    /** 12 рёбер × 6 вершин × 3 числа. */
    private static final int RIBBON_FLOATS = 12 * 6 * 3;

    private static final int[][] EDGES = {
            { 0, 1 }, { 1, 2 }, { 2, 3 }, { 3, 0 },
            { 4, 5 }, { 5, 6 }, { 6, 7 }, { 7, 4 },
            { 0, 4 }, { 1, 5 }, { 2, 6 }, { 3, 7 } };

    /**
     * Неброский тёмный контур вокруг выбранного блока.
     *
     * Линии в core-профиле толще пикселя не рисуются, поэтому каждое ребро —
     * это лента из двух треугольников, развёрнутая к камере, с шириной,
     * растущей с расстоянием: так рамка на дальнем блоке не истончается до
     * пропадания. Контур не излучает свет и не попадает в bloom.
     *
     * @param box   minX, minY, minZ, maxX, maxY, maxZ
     * @param alpha 0..1 — плавное появление и исчезновение
     */
    public void renderBox(Matrix4f proj, Matrix4f view, float[] box, org.joml.Vector3f camPos,
                          float alpha, float linearOut) {
        if (alpha <= 0.001f)
            return;
        if (ribbonVao == 0) {
            ribbonVao = glGenVertexArrays();
            ribbonVbo = glGenBuffers();
            ribbonBuf = MemoryUtil.memAllocFloat(RIBBON_FLOATS);
            glBindVertexArray(ribbonVao);
            glBindBuffer(GL_ARRAY_BUFFER, ribbonVbo);
            glBufferData(GL_ARRAY_BUFFER, (long) RIBBON_FLOATS * Float.BYTES, GL_DYNAMIC_DRAW);
            glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0);
            glEnableVertexAttribArray(0);
            glBindBuffer(GL_ARRAY_BUFFER, 0);
            glBindVertexArray(0);
        }
        float e = 0.003f;
        float[][] c = {
                { box[0] - e, box[1] - e, box[2] - e }, { box[3] + e, box[1] - e, box[2] - e },
                { box[3] + e, box[1] - e, box[5] + e }, { box[0] - e, box[1] - e, box[5] + e },
                { box[0] - e, box[4] + e, box[2] - e }, { box[3] + e, box[4] + e, box[2] - e },
                { box[3] + e, box[4] + e, box[5] + e }, { box[0] - e, box[4] + e, box[5] + e } };

        glEnable(GL_BLEND);
        glDepthMask(false);
        glDisable(GL_CULL_FACE);
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(-1f, -2f);
        shader.bind();
        shader.setMat4("uProjection", proj);
        shader.setMat4("uView", view);
        shader.setMat4("uModel", new Matrix4f());
        shader.setFloat("uLinearOut", linearOut);
        glBindVertexArray(ribbonVao);

        // Одна тонкая полупрозрачная линия, без белого ядра и свечения.
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        drawRibbons(c, camPos, 0.65f);
        shader.setFloat("uEmissive", 0f);
        shader.setVec4("uColor", new Vector4f(0f, 0f, 0f, 0.40f * alpha));
        glDrawArrays(GL_TRIANGLES, 0, 72);

        glBindVertexArray(0);
        shader.unbind();
        glPolygonOffset(0f, 0f);
        glDisable(GL_POLYGON_OFFSET_FILL);
        glDepthMask(true);
        glEnable(GL_CULL_FACE);
        glDisable(GL_BLEND);
    }

    /** Заливает в буфер 12 лент заданной относительной ширины. */
    private void drawRibbons(float[][] c, org.joml.Vector3f cam, float widthScale) {
        ribbonBuf.clear();
        for (int[] edge : EDGES) {
            float[] a = c[edge[0]], b = c[edge[1]];
            float dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
            float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            float mx = (a[0] + b[0]) * 0.5f - cam.x, my = (a[1] + b[1]) * 0.5f - cam.y,
                  mz = (a[2] + b[2]) * 0.5f - cam.z;
            float dist = (float) Math.sqrt(mx * mx + my * my + mz * mz);
            // Сторона ленты — перпендикуляр к ребру и к взгляду.
            float sx = dy * mz - dz * my, sy = dz * mx - dx * mz, sz = dx * my - dy * mx;
            float sl = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
            if (sl < 1e-6f) { sx = 0f; sy = 1f; sz = 0f; sl = 1f; }
            float w = (0.0035f + dist * 0.0011f) * widthScale;
            sx *= w / sl; sy *= w / sl; sz *= w / sl;
            // Концы ленты вытянуты на её ширину — углы рамки без щелей.
            float ex = dx / len * w, ey = dy / len * w, ez = dz / len * w;
            float ax = a[0] - ex, ay = a[1] - ey, az = a[2] - ez;
            float bx = b[0] + ex, by = b[1] + ey, bz = b[2] + ez;
            ribbonBuf.put(ax - sx).put(ay - sy).put(az - sz)
                     .put(ax + sx).put(ay + sy).put(az + sz)
                     .put(bx + sx).put(by + sy).put(bz + sz)
                     .put(ax - sx).put(ay - sy).put(az - sz)
                     .put(bx + sx).put(by + sy).put(bz + sz)
                     .put(bx - sx).put(by - sy).put(bz - sz);
        }
        ribbonBuf.flip();
        glBindBuffer(GL_ARRAY_BUFFER, ribbonVbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, ribbonBuf);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    public void destroy() {
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        if (ribbonVao != 0) {
            glDeleteBuffers(ribbonVbo);
            glDeleteVertexArrays(ribbonVao);
            MemoryUtil.memFree(ribbonBuf);
        }
        shader.destroy();
    }
}

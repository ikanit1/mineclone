package com.mineclone.render;

import com.mineclone.world.shape.Shapes;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Неброский тёмный контур вокруг выбранного блока — по рёбрам его формы
 * ({@link Shapes#edges}, BLK-02): Г-образный у ступени, тонкий у двери и факела.
 */
public class BlockOutline {
    private final Shader shader;

    private int ribbonVao, ribbonVbo;
    private java.nio.FloatBuffer ribbonBuf;
    /** Шесть вершин ленты по три числа — на каждое из рёбер самой сложной формы. */
    private static final int RIBBON_FLOATS = Shapes.MAX_EDGES * 6 * 3;
    /** Насколько рамка отступает от граней наружу, блоки. */
    private static final float LIFT = 0.003f;

    private final float[] boxEdges = new float[12 * 6];
    private final float[] center = new float[3], endA = new float[3], endB = new float[3];

    public BlockOutline() {
        shader = new Shader(Shaders.LINE_VERTEX, Shaders.LINE_FRAGMENT);
    }

    /**
     * Контур одного бокса — двенадцать рёбер.
     *
     * @param box   minX, minY, minZ, maxX, maxY, maxZ
     * @param alpha 0..1 — плавное появление и исчезновение
     */
    public void renderBox(Matrix4f proj, Matrix4f view, float[] box, org.joml.Vector3f camPos,
                          float alpha, float linearOut) {
        renderEdges(proj, view, boxEdges, OutlineAnimator.boxEdges(box, boxEdges), camPos, alpha, linearOut);
    }

    /**
     * Контур по рёбрам.
     *
     * Линии в core-профиле толще пикселя не рисуются, поэтому каждое ребро —
     * это лента из двух треугольников, развёрнутая к камере, с шириной,
     * растущей с расстоянием: так рамка на дальнем блоке не истончается до
     * пропадания. Контур не излучает свет и не попадает в bloom.
     *
     * @param edges шесть чисел на ребро: два конца в мировых координатах
     * @param alpha 0..1 — плавное появление и исчезновение
     */
    public void renderEdges(Matrix4f proj, Matrix4f view, float[] edges, int count,
                            org.joml.Vector3f camPos, float alpha, float linearOut) {
        if (alpha <= 0.001f || count <= 0)
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
        count = Math.min(count, Shapes.MAX_EDGES);

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
        fillRibbons(edges, count, camPos, 0.65f);
        shader.setFloat("uEmissive", 0f);
        shader.setVec4("uColor", new Vector4f(0f, 0f, 0f, 0.40f * alpha));
        glDrawArrays(GL_TRIANGLES, 0, count * 6);

        glBindVertexArray(0);
        shader.unbind();
        glPolygonOffset(0f, 0f);
        glDisable(GL_POLYGON_OFFSET_FILL);
        glDepthMask(true);
        glEnable(GL_CULL_FACE);
        glDisable(GL_BLEND);
    }

    /**
     * Заливает в буфер ленты заданной относительной ширины. Каждый конец ребра
     * отступает на {@link #LIFT} от центра формы: внешние рёбра выходят из
     * граней наружу, как прежде у бокса, а ребро во внутреннем углу ступени
     * остаётся на месте.
     */
    private void fillRibbons(float[] edges, int count, org.joml.Vector3f cam, float widthScale) {
        for (int axis = 0; axis < 3; axis++) {
            float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
            for (int e = 0; e < count; e++) {
                lo = Math.min(lo, Math.min(edges[e * 6 + axis], edges[e * 6 + 3 + axis]));
                hi = Math.max(hi, Math.max(edges[e * 6 + axis], edges[e * 6 + 3 + axis]));
            }
            center[axis] = (lo + hi) * 0.5f;
        }
        ribbonBuf.clear();
        float[] a = endA, b = endB;
        for (int e = 0; e < count; e++) {
            for (int axis = 0; axis < 3; axis++) {
                a[axis] = lift(edges[e * 6 + axis], center[axis]);
                b[axis] = lift(edges[e * 6 + 3 + axis], center[axis]);
            }
            float dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
            float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1e-6f) len = 1e-6f;
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

    private static float lift(float value, float center) {
        return value < center - 1e-4f ? value - LIFT : value > center + 1e-4f ? value + LIFT : value;
    }

    public void destroy() {
        if (ribbonVao != 0) {
            glDeleteBuffers(ribbonVbo);
            glDeleteVertexArrays(ribbonVao);
            MemoryUtil.memFree(ribbonBuf);
        }
        shader.destroy();
    }
}

package com.mineclone.render;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Разряд молнии: ломаная из {@link com.mineclone.world.Lightning#bolt}.
 *
 * Та же программа {@code LINE}, что рисует рамку выделения, но с поднятым
 * {@code uEmissive}: болт обязан уйти далеко за единицу, чтобы из него вырос
 * bloom. Тогда разряд светится сам, а не просто окрашен белым.
 *
 * Болт живёт доли секунды и рисуется двумя проходами: широкий полупрозрачный
 * ореол и узкое ядро поверх. Одна линия в блочном мире выглядит царапиной на
 * экране, две — разрядом.
 */
public final class LightningRenderer {

    /** Сколько отрезков переживёт самый ветвистый болт. */
    private static final int MAX_SEGMENTS = 64;
    private static final int CAPACITY = MAX_SEGMENTS * 6;

    /** Толщина ореола и ядра. */
    private static final float HALO_WIDTH = 7f, CORE_WIDTH = 2.2f;
    /** Во сколько раз ядро ярче единицы: отсюда растёт засветка. */
    private static final float CORE_EMISSIVE = 6f, HALO_EMISSIVE = 2f;

    private final int vao, vbo;
    private final Shader shader;

    public LightningRenderer() {
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

    /**
     * Рисует болт в мировых координатах.
     *
     * @param bolt    плоский массив отрезков от {@code Lightning.bolt}
     * @param fade    1 в момент удара, 0 когда разряд погас
     */
    public void render(Matrix4f proj, Matrix4f view, float[] bolt, float fade, float linearOut) {
        if (bolt == null || bolt.length == 0 || fade <= 0f)
            return;
        int floats = Math.min(bolt.length, CAPACITY);
        FloatBuffer fb = MemoryUtil.memAllocFloat(floats);
        try {
            fb.put(bolt, 0, floats).flip();
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferSubData(GL_ARRAY_BUFFER, 0, fb);
            glBindBuffer(GL_ARRAY_BUFFER, 0);
        } finally {
            MemoryUtil.memFree(fb);
        }
        shader.bind();
        shader.setMat4("uProjection", proj);
        shader.setMat4("uView", view);
        shader.setMat4("uModel", new Matrix4f());
        shader.setFloat("uLinearOut", linearOut);
        glBindVertexArray(vao);
        // Глубина читается, но не пишется: разряд за холмом закрыт холмом, а
        // сам он ничего не заслоняет — иначе ореол выел бы дыру в дожде.
        glDepthMask(false);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE);
        int verts = floats / 3;
        pass(HALO_WIDTH, new Vector4f(0.62f, 0.72f, 1f, 0.40f * fade), HALO_EMISSIVE * fade, verts);
        pass(CORE_WIDTH, new Vector4f(0.96f, 0.97f, 1f, fade), CORE_EMISSIVE * fade, verts);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(true);
        glBindVertexArray(0);
        shader.unbind();
    }

    private void pass(float width, Vector4f color, float emissive, int verts) {
        shader.setVec4("uColor", color);
        shader.setFloat("uEmissive", emissive);
        glLineWidth(width);
        glDrawArrays(GL_LINES, 0, verts);
    }

}

package com.mineclone.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.List;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/** Пунктирный предпросмотр баллистической дуги заряжаемого броска. */
public final class TrajectoryRenderer {
    private static final int SAMPLES = 28;
    private final int vao = glGenVertexArrays();
    private final int vbo = glGenBuffers();
    private final Shader shader = new Shader(Shaders.LINE_VERTEX, Shaders.LINE_FRAGMENT);

    public TrajectoryRenderer() {
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) SAMPLES * 3 * Float.BYTES, GL_DYNAMIC_DRAW);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0);
        glEnableVertexAttribArray(0);
        glBindVertexArray(0);
    }

    public void render(Matrix4f projection, Matrix4f view, Vector3f start, Vector3f launch,
                       float linearOut, float charge) {
        List<Vector3f> arc = ProceduralEffects.throwArc(start, new Vector3f(launch).normalize(),
                new Vector3f(), launch.length(), 24f, 1.45f, SAMPLES);
        FloatBuffer data = MemoryUtil.memAllocFloat(SAMPLES * 3);
        for (Vector3f p : arc) data.put(p.x).put(p.y).put(p.z);
        data.flip();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, data);
        MemoryUtil.memFree(data);

        shader.bind();
        shader.setMat4("uProjection", projection);
        shader.setMat4("uView", view);
        shader.setMat4("uModel", new Matrix4f());
        shader.setVec4("uColor", new Vector4f(0.50f, 0.88f, 1f, 0.35f + charge * 0.5f));
        shader.setFloat("uLinearOut", linearOut);
        shader.setFloat("uEmissive", 1.2f);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE);
        glDepthMask(false);
        glBindVertexArray(vao);
        glLineWidth(2f);
        glDrawArrays(GL_LINE_STRIP, 0, SAMPLES);
        glBindVertexArray(0);
        glDepthMask(true);
        glDisable(GL_BLEND);
        shader.unbind();
    }

    public void destroy() {
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        shader.destroy();
    }
}

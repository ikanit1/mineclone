package com.mineclone.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/** Camera-facing quad particles with gravity. Used for block-break feedback. */
public class ParticleSystem {
    private static final int MAX = 512;
    private static final float GRAVITY = 14f;

    private static final class P {
        float x, y, z, vx, vy, vz;
        float life, size;
        float[] color;
        float u0, v0, u1, v1;
    }

    private final List<P> particles = new ArrayList<>();
    private final Random rnd = new Random();
    private final int vao, vbo;
    private final Shader shader;

    public ParticleSystem() {
        shader = new Shader(Shaders.PARTICLE_VERTEX, Shaders.PARTICLE_FRAGMENT);
        float[] quad = {
                -0.5f, -0.5f,  0.5f, -0.5f,  0.5f, 0.5f,
                -0.5f, -0.5f,  0.5f,  0.5f, -0.5f, 0.5f
        };
        vao = glGenVertexArrays();
        vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        FloatBuffer fb = MemoryUtil.memAllocFloat(quad.length);
        fb.put(quad).flip();
        glBufferData(GL_ARRAY_BUFFER, fb, GL_STATIC_DRAW);
        MemoryUtil.memFree(fb);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 0, 0);
        glEnableVertexAttribArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    public void emitBlockBreak(int bx, int by, int bz, float[] color, int sideTile) {
        int count = 5 + rnd.nextInt(6); // 5-10
        for (int i = 0; i < count && particles.size() < MAX; i++) {
            P p = new P();
            p.x = bx + 0.2f + rnd.nextFloat() * 0.6f;
            p.y = by + 0.2f + rnd.nextFloat() * 0.6f;
            p.z = bz + 0.2f + rnd.nextFloat() * 0.6f;
            p.vx = (rnd.nextFloat() - 0.5f) * 4f;
            p.vy = 2f + rnd.nextFloat() * 3f;
            p.vz = (rnd.nextFloat() - 0.5f) * 4f;
            p.life = 0.5f + rnd.nextFloat() * 0.4f;
            p.size = 0.08f + rnd.nextFloat() * 0.06f;
            p.color = color;
            float[] uv = TextureAtlas.uv(sideTile);
            float span = 4f / TextureAtlas.ATLAS_SIZE; // 4px sub-region
            float maxU = uv[2] - uv[0] - span;
            float maxV = uv[3] - uv[1] - span;
            p.u0 = uv[0] + rnd.nextFloat() * Math.max(0f, maxU);
            p.v0 = uv[1] + rnd.nextFloat() * Math.max(0f, maxV);
            p.u1 = p.u0 + span;
            p.v1 = p.v0 + span;
            particles.add(p);
        }
    }

    public void update(float dt) {
        Iterator<P> it = particles.iterator();
        while (it.hasNext()) {
            P p = it.next();
            p.life -= dt;
            if (p.life <= 0f) { it.remove(); continue; }
            p.vy -= GRAVITY * dt;
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            p.z += p.vz * dt;
            float drag = Math.max(0f, 1f - 2f * dt);
            p.vx *= drag;
            p.vz *= drag;
        }
    }

    public void render(Matrix4f proj, Matrix4f view, Vector3f camRight, Vector3f camUp, TextureAtlas atlas) {
        if (particles.isEmpty()) return;
        shader.bind();
        atlas.bind(0);
        shader.setInt("uAtlas", 0);
        shader.setMat4("uProjection", proj);
        shader.setMat4("uView", view);
        shader.setVec3("uRight", camRight);
        shader.setVec3("uUp", camUp);
        glBindVertexArray(vao);
        Vector3f center = new Vector3f();
        for (P p : particles) {
            shader.setVec3("uCenter", center.set(p.x, p.y, p.z));
            shader.setFloat("uSize", p.size);
            shader.setVec4("uColor", p.color[0], p.color[1], p.color[2], 1f);
            shader.setVec2("uUv0", p.u0, p.v0);
            shader.setVec2("uUv1", p.u1, p.v1);
            glDrawArrays(GL_TRIANGLES, 0, 6);
        }
        glBindVertexArray(0);
        shader.unbind();
    }

    public void destroy() {
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        shader.destroy();
    }
}

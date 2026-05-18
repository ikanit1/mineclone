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

public class ParticleSystem {
    private static final int   MAX     = 768;
    private static final float GRAVITY = 14f;

    /** Tile index in the atlas used for solid-color flame/smoke particles. */
    public static final int PARTICLE_TILE = 13;

    private static final class P {
        float x, y, z, vx, vy, vz;
        float life, maxLife, size, growRate;
        float gravityScale = 1f;
        /** RGB tint applied on top of the texture sample. */
        float cr, cg, cb;
        /** Base alpha (further faded by remaining life). */
        float baseAlpha = 1f;
        float u0, v0, u1, v1;
        /**
         * World light sampled at emission, as 0..1 sky/block fractions.
         * When {@code worldLit} the particle is shaded with the exact same
         * curve blocks use; otherwise it stays full-bright (emissive — e.g.
         * torch flame, which is itself a light source).
         */
        float skyL, blockL;
        boolean worldLit;
    }

    private final List<P>  particles = new ArrayList<>();
    private final Random   rnd       = new Random();
    private final int      vao, vbo;
    private final Shader   shader;

    public ParticleSystem() {
        shader = new Shader(Shaders.PARTICLE_VERTEX, Shaders.PARTICLE_FRAGMENT);
        float[] quad = {
            -0.5f,-0.5f,  0.5f,-0.5f,  0.5f,0.5f,
            -0.5f,-0.5f,  0.5f, 0.5f, -0.5f,0.5f
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

    // -------------------------------------------------------------------------
    //  Public emitters
    // -------------------------------------------------------------------------

    public void emitBlockBreak(int bx, int by, int bz, float[] color, int sideTile,
                               float skyFrac, float blockFrac) {
        int count = 5 + rnd.nextInt(6);
        for (int i = 0; i < count && particles.size() < MAX; i++) {
            P p = new P();
            p.worldLit = true;
            p.skyL = skyFrac;
            p.blockL = blockFrac;
            p.x = bx + 0.2f + rnd.nextFloat() * 0.6f;
            p.y = by + 0.2f + rnd.nextFloat() * 0.6f;
            p.z = bz + 0.2f + rnd.nextFloat() * 0.6f;
            p.vx = (rnd.nextFloat() - 0.5f) * 4f;
            p.vy = 2f + rnd.nextFloat() * 3f;
            p.vz = (rnd.nextFloat() - 0.5f) * 4f;
            p.life = p.maxLife = 0.5f + rnd.nextFloat() * 0.4f;
            p.size = 0.08f + rnd.nextFloat() * 0.06f;
            p.gravityScale = 1f;
            p.cr = 1f; p.cg = 1f; p.cb = 1f; // white tint — texture provides colour
            p.baseAlpha = 1f;
            float[] uv = TextureAtlas.uv(sideTile);
            float span = 4f / TextureAtlas.ATLAS_SIZE;
            float maxU = uv[2] - uv[0] - span;
            float maxV = uv[3] - uv[1] - span;
            p.u0 = uv[0] + rnd.nextFloat() * Math.max(0f, maxU);
            p.v0 = uv[1] + rnd.nextFloat() * Math.max(0f, maxV);
            p.u1 = p.u0 + span;
            p.v1 = p.v0 + span;
            particles.add(p);
        }
    }

    /**
     * Emit a small burst of flame and smoke from a torch block.
     * Call this every ~70 ms for nearby torches.
     */
    public void emitTorchEffects(int bx, int by, int bz) {
        float[] uvP = TextureAtlas.uv(PARTICLE_TILE);

        // Flame: ~65 % chance per call
        if (rnd.nextFloat() < 0.65f && particles.size() < MAX) {
            P p = new P();
            p.x = bx + 0.42f + rnd.nextFloat() * 0.16f;
            p.y = by + 0.68f + rnd.nextFloat() * 0.06f;
            p.z = bz + 0.42f + rnd.nextFloat() * 0.16f;
            p.vx = (rnd.nextFloat() - 0.5f) * 0.18f;
            p.vy = 0.35f + rnd.nextFloat() * 0.25f;
            p.vz = (rnd.nextFloat() - 0.5f) * 0.18f;
            p.life = p.maxLife = 0.22f + rnd.nextFloat() * 0.14f;
            p.size = 0.045f + rnd.nextFloat() * 0.025f;
            p.gravityScale = 0f;
            p.growRate = 0f;
            // Random warm flame colour
            float[] cols = {
                0.95f + rnd.nextFloat() * 0.05f,
                0.45f + rnd.nextFloat() * 0.25f,
                0.02f + rnd.nextFloat() * 0.08f
            };
            p.cr = cols[0]; p.cg = cols[1]; p.cb = cols[2];
            p.baseAlpha = 0.9f;
            p.u0 = uvP[0]; p.v0 = uvP[1]; p.u1 = uvP[2]; p.v1 = uvP[3];
            particles.add(p);
        }

        // Smoke: ~28 % chance per call
        if (rnd.nextFloat() < 0.28f && particles.size() < MAX) {
            P p = new P();
            p.x = bx + 0.38f + rnd.nextFloat() * 0.24f;
            p.y = by + 0.78f + rnd.nextFloat() * 0.04f;
            p.z = bz + 0.38f + rnd.nextFloat() * 0.24f;
            p.vx = (rnd.nextFloat() - 0.5f) * 0.12f;
            p.vy = 0.12f + rnd.nextFloat() * 0.10f;
            p.vz = (rnd.nextFloat() - 0.5f) * 0.12f;
            p.life = p.maxLife = 0.7f + rnd.nextFloat() * 0.5f;
            p.size = 0.05f + rnd.nextFloat() * 0.04f;
            p.gravityScale = 0f;
            p.growRate = 0.05f;
            float g = 0.12f + rnd.nextFloat() * 0.08f;
            p.cr = g; p.cg = g; p.cb = g;
            p.baseAlpha = 0.50f;
            p.u0 = uvP[0]; p.v0 = uvP[1]; p.u1 = uvP[2]; p.v1 = uvP[3];
            particles.add(p);
        }
    }

    public void emitWaterSplash(float x, float y, float z, float skyFrac, float blockFrac) {
        float[] uvP = TextureAtlas.uv(PARTICLE_TILE);
        int count = 8 + rnd.nextInt(7);
        for (int i = 0; i < count && particles.size() < MAX; i++) {
            P p = new P();
            p.worldLit = true;
            p.skyL = skyFrac;
            p.blockL = blockFrac;
            float angle = rnd.nextFloat() * (float) (Math.PI * 2);
            float horiz = 1.5f + rnd.nextFloat() * 2.5f;
            p.x = x + (rnd.nextFloat() - 0.5f) * 0.6f;
            p.y = y;
            p.z = z + (rnd.nextFloat() - 0.5f) * 0.6f;
            p.vx = (float) Math.cos(angle) * horiz;
            p.vy = 2.5f + rnd.nextFloat() * 3f;
            p.vz = (float) Math.sin(angle) * horiz;
            p.life = p.maxLife = 0.35f + rnd.nextFloat() * 0.35f;
            p.size = 0.06f + rnd.nextFloat() * 0.07f;
            p.gravityScale = 1f;
            p.cr = 0.5f + rnd.nextFloat() * 0.4f;
            p.cg = 0.7f + rnd.nextFloat() * 0.25f;
            p.cb = 1.0f;
            p.baseAlpha = 0.7f + rnd.nextFloat() * 0.3f;
            p.u0 = uvP[0]; p.v0 = uvP[1]; p.u1 = uvP[2]; p.v1 = uvP[3];
            particles.add(p);
        }
    }

    // -------------------------------------------------------------------------
    //  Update & render
    // -------------------------------------------------------------------------

    public void update(float dt) {
        Iterator<P> it = particles.iterator();
        while (it.hasNext()) {
            P p = it.next();
            p.life -= dt;
            if (p.life <= 0f) { it.remove(); continue; }
            p.vy  -= GRAVITY * p.gravityScale * dt;
            p.x   += p.vx * dt;
            p.y   += p.vy * dt;
            p.z   += p.vz * dt;
            p.size = Math.max(0.01f, p.size + p.growRate * dt);
            float drag = Math.max(0f, 1f - 2f * dt);
            p.vx *= drag; p.vz *= drag;
        }
    }

    public void render(Matrix4f proj, Matrix4f view, Vector3f camRight, Vector3f camUp, TextureAtlas atlas,
                       float daylight, float ambient, float brightness) {
        if (particles.isEmpty()) return;
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
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
            // Fade alpha based on remaining life fraction
            float fade = Math.min(1f, p.life / (p.maxLife * 0.35f));
            // Same shading curve as the chunk fragment shader so break debris
            // matches the block it came from. Emissive particles skip it.
            float mul = 1f;
            if (p.worldLit) {
                float combined = Math.max(p.skyL * daylight, p.blockL);
                float shaped = (float) Math.pow(Math.max(ambient, combined), 0.75) * brightness;
                mul = Math.min(shaped, 1f);
            }
            shader.setVec3("uCenter", center.set(p.x, p.y, p.z));
            shader.setFloat("uSize", p.size);
            shader.setVec4("uColor", p.cr * mul, p.cg * mul, p.cb * mul, fade * p.baseAlpha);
            shader.setVec2("uUv0", p.u0, p.v0);
            shader.setVec2("uUv1", p.u1, p.v1);
            glDrawArrays(GL_TRIANGLES, 0, 6);
        }
        glBindVertexArray(0);
        shader.unbind();
        glDisable(GL_BLEND);
    }

    public void destroy() {
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        shader.destroy();
    }
}

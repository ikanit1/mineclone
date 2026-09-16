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
    /** Tile index for the water-drop splash particle sprite. */
    public static final int WATER_PARTICLE_TILE = 33;

    /** Ветер кадра, блоков в секунду: сносит дым и искры. */
    private float windX, windZ;

    public void setWind(float x, float z) {
        windX = x;
        windZ = z;
    }

    private static final class P {
        float x, y, z, vx, vy, vz;
        float life, maxLife, size, growRate;
        float gravityScale = 1f;
        /** Насколько частицу сносит ветром: дым — сильно, обломки — никак. */
        float windScale;
        /** Мерцание яркости (искры): 0 — ровный свет. */
        float flicker;
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
        float floorY = Float.NEGATIVE_INFINITY;
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
            p.windScale = 0.4f;
            float g = 0.12f + rnd.nextFloat() * 0.08f;
            p.cr = g; p.cg = g; p.cb = g;
            p.baseAlpha = 0.50f;
            p.u0 = uvP[0]; p.v0 = uvP[1]; p.u1 = uvP[2]; p.v1 = uvP[3];
            particles.add(p);
        }

        // Редкая искра: факел не костёр, но совсем без них пламя мёртвое.
        if (rnd.nextFloat() < 0.08f)
            emitEmber(bx + 0.5f, by + 0.72f, bz + 0.5f, 0.8f);
    }

    /**
     * Пламя костра: тот же принцип, что у факела, но язык выше, искр больше
     * и дым гуще — иначе огонь на экране неотличим от факела и не читается
     * как опасность.
     */
    /**
     * Пар от дыхания на морозе: два медленных полупрозрачных облачка,
     * уплывающих вперёд по взгляду и растущих на лету.
     */
    public void emitBreath(float x, float y, float z, float dirX, float dirZ) {
        float[] uvP = TextureAtlas.uv(PARTICLE_TILE);
        for (int i = 0; i < 2; i++) {
            if (particles.size() >= MAX)
                return;
            P p = new P();
            p.x = x + (rnd.nextFloat() - 0.5f) * 0.1f;
            p.y = y + (rnd.nextFloat() - 0.5f) * 0.06f;
            p.z = z + (rnd.nextFloat() - 0.5f) * 0.1f;
            p.vx = dirX * (0.35f + rnd.nextFloat() * 0.25f) + (rnd.nextFloat() - 0.5f) * 0.1f;
            p.vy = 0.10f + rnd.nextFloat() * 0.08f;
            p.vz = dirZ * (0.35f + rnd.nextFloat() * 0.25f) + (rnd.nextFloat() - 0.5f) * 0.1f;
            p.life = p.maxLife = 0.9f + rnd.nextFloat() * 0.6f;
            p.size = 0.045f + rnd.nextFloat() * 0.03f;
            p.gravityScale = 0f;
            p.growRate = 0.10f;
            p.windScale = 0.25f;
            p.cr = 0.92f; p.cg = 0.95f; p.cb = 1.0f;
            p.baseAlpha = 0.30f;
            p.u0 = uvP[0]; p.v0 = uvP[1]; p.u1 = uvP[2]; p.v1 = uvP[3];
            particles.add(p);
        }
    }

    public void emitFire(int bx, int by, int bz) {
        float[] uvP = TextureAtlas.uv(PARTICLE_TILE);

        for (int i = 0; i < 2; i++) {
            if (particles.size() >= MAX)
                break;
            P p = new P();
            p.x = bx + 0.25f + rnd.nextFloat() * 0.5f;
            p.y = by + 0.1f + rnd.nextFloat() * 0.25f;
            p.z = bz + 0.25f + rnd.nextFloat() * 0.5f;
            p.vx = (rnd.nextFloat() - 0.5f) * 0.3f;
            p.vy = 0.9f + rnd.nextFloat() * 0.7f;
            p.vz = (rnd.nextFloat() - 0.5f) * 0.3f;
            p.life = p.maxLife = 0.32f + rnd.nextFloat() * 0.22f;
            p.size = 0.07f + rnd.nextFloat() * 0.05f;
            p.gravityScale = 0f;
            p.growRate = -0.06f;
            p.cr = 1.0f;
            p.cg = 0.42f + rnd.nextFloat() * 0.38f;
            p.cb = 0.04f + rnd.nextFloat() * 0.08f;
            p.baseAlpha = 0.95f;
            p.u0 = uvP[0]; p.v0 = uvP[1]; p.u1 = uvP[2]; p.v1 = uvP[3];
            particles.add(p);
        }

        if (rnd.nextFloat() < 0.5f && particles.size() < MAX) {
            P p = new P();
            p.x = bx + 0.3f + rnd.nextFloat() * 0.4f;
            p.y = by + 0.85f + rnd.nextFloat() * 0.2f;
            p.z = bz + 0.3f + rnd.nextFloat() * 0.4f;
            p.vx = (rnd.nextFloat() - 0.5f) * 0.25f;
            p.vy = 0.3f + rnd.nextFloat() * 0.25f;
            p.vz = (rnd.nextFloat() - 0.5f) * 0.25f;
            p.life = p.maxLife = 1.6f + rnd.nextFloat() * 1.2f;
            p.size = 0.09f + rnd.nextFloat() * 0.06f;
            p.gravityScale = -0.015f;   // горячий дым сам тянется вверх
            p.growRate = 0.16f;
            p.windScale = 0.6f;
            float g = 0.10f + rnd.nextFloat() * 0.07f;
            p.cr = g; p.cg = g; p.cb = g;
            p.baseAlpha = 0.45f;
            p.u0 = uvP[0]; p.v0 = uvP[1]; p.u1 = uvP[2]; p.v1 = uvP[3];
            particles.add(p);
        }

        // Костёр стреляет искрами щедро — это и отличает его от факела издалека.
        if (rnd.nextFloat() < 0.45f)
            emitEmber(bx + 0.3f + rnd.nextFloat() * 0.4f, by + 0.5f, bz + 0.3f + rnd.nextFloat() * 0.4f, 1.6f);
    }

    public void emitWaterSplash(float x, float y, float z, float skyFrac, float blockFrac) {
        float[] uvP = TextureAtlas.uv(WATER_PARTICLE_TILE);
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
            p.cr = 1f; p.cg = 1f; p.cb = 1f;
            p.baseAlpha = 0.85f + rnd.nextFloat() * 0.15f;
            p.u0 = uvP[0]; p.v0 = uvP[1]; p.u1 = uvP[2]; p.v1 = uvP[3];
            particles.add(p);
        }
    }

    public void emitLandingPuff(float x, float y, float z, int sideTile,
                                float skyFrac, float blockFrac, int count) {
        float[] uv = TextureAtlas.uv(sideTile);
        float span = 4f / TextureAtlas.ATLAS_SIZE;
        for (int i = 0; i < count && particles.size() < MAX; i++) {
            P p = new P();
            p.worldLit = true;
            p.skyL = skyFrac;
            p.blockL = blockFrac;
            p.x = x + (rnd.nextFloat() - 0.5f) * 1.0f;
            p.y = y + 0.05f;
            p.z = z + (rnd.nextFloat() - 0.5f) * 1.0f;
            float angle = rnd.nextFloat() * (float) (Math.PI * 2);
            float horiz = 1.2f + rnd.nextFloat() * 2.8f;
            p.vx = (float) Math.cos(angle) * horiz;
            p.vy = 0.8f + rnd.nextFloat() * 1.8f;
            p.vz = (float) Math.sin(angle) * horiz;
            p.life = p.maxLife = 0.25f + rnd.nextFloat() * 0.25f;
            p.size = 0.05f + rnd.nextFloat() * 0.08f;
            p.growRate = 0f;
            p.gravityScale = 1.5f;
            p.cr = 1f; p.cg = 1f; p.cb = 1f;
            p.baseAlpha = 1f;
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
     * Струйка серого дыма от горящего на солнце моба.
     * Зовётся выборочно (не каждый кадр) — иначе дым забивает буфер частиц.
     */
    public void emitMobSmoke(float x, float y, float z) {
        if (particles.size() >= MAX)
            return;
        float[] uvP = TextureAtlas.uv(PARTICLE_TILE);
        P p = new P();
        p.x = x + (rnd.nextFloat() - 0.5f) * 0.5f;
        p.y = y + rnd.nextFloat() * 0.5f;
        p.z = z + (rnd.nextFloat() - 0.5f) * 0.5f;
        p.vx = (rnd.nextFloat() - 0.5f) * 0.2f;
        p.vy = 0.5f + rnd.nextFloat() * 0.4f;
        p.vz = (rnd.nextFloat() - 0.5f) * 0.2f;
        p.life = p.maxLife = 0.6f + rnd.nextFloat() * 0.4f;
        p.size = 0.07f + rnd.nextFloat() * 0.05f;
        p.gravityScale = 0f;
        p.growRate = 0.06f;
        p.windScale = 0.5f;
        float grey = 0.18f + rnd.nextFloat() * 0.1f;
        p.cr = grey;
        p.cg = grey;
        p.cb = grey;
        p.baseAlpha = 0.55f;
        p.u0 = uvP[0];
        p.v0 = uvP[1];
        p.u1 = uvP[2];
        p.v1 = uvP[3];
        particles.add(p);
    }

    /** Язычок пламени на горящем мобе — тот же рецепт, что у факела. */
    public void emitMobFlame(float x, float y, float z) {
        if (particles.size() >= MAX)
            return;
        float[] uvP = TextureAtlas.uv(PARTICLE_TILE);
        P p = new P();
        p.x = x + (rnd.nextFloat() - 0.5f) * 0.6f;
        p.y = y + (rnd.nextFloat() - 0.5f) * 0.8f;
        p.z = z + (rnd.nextFloat() - 0.5f) * 0.6f;
        p.vx = (rnd.nextFloat() - 0.5f) * 0.3f;
        p.vy = 0.6f + rnd.nextFloat() * 0.5f;
        p.vz = (rnd.nextFloat() - 0.5f) * 0.3f;
        p.life = p.maxLife = 0.25f + rnd.nextFloat() * 0.2f;
        p.size = 0.07f + rnd.nextFloat() * 0.05f;
        p.gravityScale = 0f;
        p.growRate = 0f;
        p.cr = 0.95f + rnd.nextFloat() * 0.05f;
        p.cg = 0.45f + rnd.nextFloat() * 0.25f;
        p.cb = 0.03f + rnd.nextFloat() * 0.08f;
        p.baseAlpha = 0.9f;
        p.u0 = uvP[0];
        p.v0 = uvP[1];
        p.u1 = uvP[2];
        p.v1 = uvP[3];
        particles.add(p);
    }

    /** Облако частиц цвета моба в момент смерти. */
    public void emitMobDeath(float x, float y, float z, float[] color) {
        float[] uvP = TextureAtlas.uv(PARTICLE_TILE);
        int count = 14 + rnd.nextInt(8);
        for (int i = 0; i < count && particles.size() < MAX; i++) {
            P p = new P();
            float angle = rnd.nextFloat() * (float) (Math.PI * 2);
            float horiz = 0.8f + rnd.nextFloat() * 1.8f;
            p.x = x + (rnd.nextFloat() - 0.5f) * 0.6f;
            p.y = y + (rnd.nextFloat() - 0.5f) * 0.6f;
            p.z = z + (rnd.nextFloat() - 0.5f) * 0.6f;
            p.vx = (float) Math.cos(angle) * horiz;
            p.vy = 1.2f + rnd.nextFloat() * 1.6f;
            p.vz = (float) Math.sin(angle) * horiz;
            p.life = p.maxLife = 0.45f + rnd.nextFloat() * 0.35f;
            p.size = 0.07f + rnd.nextFloat() * 0.06f;
            p.gravityScale = 0.6f;
            p.cr = color[0];
            p.cg = color[1];
            p.cb = color[2];
            p.baseAlpha = 1f;
            p.u0 = uvP[0];
            p.v0 = uvP[1];
            p.u1 = uvP[2];
            p.v1 = uvP[3];
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
            if (p.life <= 0f || p.y <= p.floorY) { it.remove(); continue; }
            p.vy  -= GRAVITY * p.gravityScale * dt;
            // Ветер тянет скорость к своей, а не прибавляет её: иначе дым за
            // секунду разгонялся бы быстрее самого ветра.
            if (p.windScale > 0f) {
                p.vx += (windX * p.windScale - p.vx) * Math.min(1f, dt * 1.5f);
                p.vz += (windZ * p.windScale - p.vz) * Math.min(1f, dt * 1.5f);
            }
            p.x   += p.vx * dt;
            p.y   += p.vy * dt;
            p.z   += p.vz * dt;
            p.size = Math.max(0.01f, p.size + p.growRate * dt);
            float drag = Math.max(0f, 1f - 2f * dt);
            p.vx *= drag; p.vz *= drag;
        }
    }

    // -------------------------------------------------------------------------
    //  Искры, снег из-под ног, удары
    // -------------------------------------------------------------------------

    /**
     * Искра от огня: крошечная яркая точка, которая взлетает на тёплом воздухе,
     * виляет и гаснет, мерцая. Без искр пламя выглядит нарисованным: живой огонь
     * всё время что-то выбрасывает вверх.
     */
    public void emitEmber(float x, float y, float z, float lift) {
        if (particles.size() >= MAX - 32)
            return;
        float[] uvP = TextureAtlas.uv(PARTICLE_TILE);
        P p = new P();
        p.x = x; p.y = y; p.z = z;
        p.vx = (rnd.nextFloat() - 0.5f) * 0.5f;
        p.vy = lift * (0.8f + rnd.nextFloat() * 0.9f);
        p.vz = (rnd.nextFloat() - 0.5f) * 0.5f;
        p.life = p.maxLife = 0.6f + rnd.nextFloat() * 0.9f;
        p.size = 0.018f + rnd.nextFloat() * 0.016f;
        // Отрицательная гравитация — это подъёмная сила горячего воздуха.
        p.gravityScale = -0.02f;
        p.windScale = 0.35f;
        p.flicker = 1f;
        p.cr = 1f; p.cg = 0.55f + rnd.nextFloat() * 0.3f; p.cb = 0.12f;
        p.baseAlpha = 1f;
        p.u0 = uvP[0]; p.v0 = uvP[1]; p.u1 = uvP[2]; p.v1 = uvP[3];
        particles.add(p);
    }

    /**
     * Снег из-под ноги: хлопья летят назад и чуть вверх — против шага, как
     * настоящий снег, который отбрасывает носок ботинка. На бегу выше и больше.
     *
     * @param dirX,dirZ направление шага
     */
    public void emitSnowKick(float x, float y, float z, float dirX, float dirZ, boolean sprint,
                             float skyFrac, float blockFrac) {
        float[] uvP = TextureAtlas.uv(PARTICLE_TILE);
        int count = sprint ? 9 : 5;
        for (int i = 0; i < count && particles.size() < MAX; i++) {
            P p = new P();
            p.worldLit = true;
            p.skyL = skyFrac;
            p.blockL = blockFrac;
            p.x = x + (rnd.nextFloat() - 0.5f) * 0.3f;
            p.y = y + 0.05f;
            p.z = z + (rnd.nextFloat() - 0.5f) * 0.3f;
            float back = (sprint ? 1.6f : 0.9f) * (0.5f + rnd.nextFloat());
            p.vx = -dirX * back + (rnd.nextFloat() - 0.5f) * 1.2f;
            p.vz = -dirZ * back + (rnd.nextFloat() - 0.5f) * 1.2f;
            p.vy = (sprint ? 2.2f : 1.4f) + rnd.nextFloat() * 1.2f;
            p.life = p.maxLife = 0.35f + rnd.nextFloat() * 0.35f;
            p.size = 0.035f + rnd.nextFloat() * 0.045f;
            p.gravityScale = 0.75f;
            p.floorY = y - 0.3f;
            p.cr = 0.95f; p.cg = 0.97f; p.cb = 1f;
            p.baseAlpha = 0.9f;
            p.u0 = uvP[0]; p.v0 = uvP[1]; p.u1 = uvP[2]; p.v1 = uvP[3];
            particles.add(p);
        }
    }

    /**
     * Попадание по мобу: сноп искр от точки удара в сторону удара, облачко
     * пыли и брызги цвета моба. Крит — звёздочки ярче и вдвое больше.
     *
     * @param dirX,dirY,dirZ направление удара (от игрока к мобу), единичное
     * @param color          цвет брызг — у зомби гнилой зелёный, у животных бурый
     */
    public void emitHitImpact(float x, float y, float z, float dirX, float dirY, float dirZ,
                              boolean crit, float[] color, float skyFrac, float blockFrac) {
        float[] uvP = TextureAtlas.uv(PARTICLE_TILE);
        int sparks = crit ? 16 : 8;
        for (int i = 0; i < sparks && particles.size() < MAX; i++) {
            P p = new P();
            float speed = (crit ? 4.5f : 3.2f) * (0.5f + rnd.nextFloat());
            p.x = x; p.y = y; p.z = z;
            p.vx = (dirX + (rnd.nextFloat() - 0.5f) * 1.6f) * speed;
            p.vy = (dirY + 0.35f + (rnd.nextFloat() - 0.2f) * 1.4f) * speed;
            p.vz = (dirZ + (rnd.nextFloat() - 0.5f) * 1.6f) * speed;
            p.life = p.maxLife = 0.12f + rnd.nextFloat() * (crit ? 0.30f : 0.18f);
            p.size = (crit ? 0.05f : 0.03f) + rnd.nextFloat() * 0.025f;
            p.gravityScale = 0.9f;
            p.flicker = crit ? 1f : 0f;
            p.cr = 1f;
            p.cg = crit ? 0.95f : 0.85f;
            p.cb = crit ? 0.55f : 0.60f;
            p.baseAlpha = 1f;
            p.u0 = uvP[0]; p.v0 = uvP[1]; p.u1 = uvP[2]; p.v1 = uvP[3];
            particles.add(p);
        }
        int drops = crit ? 10 : 6;
        for (int i = 0; i < drops && particles.size() < MAX; i++) {
            P p = new P();
            p.worldLit = true;
            p.skyL = skyFrac;
            p.blockL = blockFrac;
            p.x = x + (rnd.nextFloat() - 0.5f) * 0.2f;
            p.y = y + (rnd.nextFloat() - 0.5f) * 0.2f;
            p.z = z + (rnd.nextFloat() - 0.5f) * 0.2f;
            float speed = 1.4f + rnd.nextFloat() * 1.8f;
            p.vx = (dirX * 0.7f + (rnd.nextFloat() - 0.5f)) * speed;
            p.vy = (0.6f + rnd.nextFloat()) * speed;
            p.vz = (dirZ * 0.7f + (rnd.nextFloat() - 0.5f)) * speed;
            p.life = p.maxLife = 0.35f + rnd.nextFloat() * 0.3f;
            p.size = 0.035f + rnd.nextFloat() * 0.035f;
            p.gravityScale = 1.4f;
            float dark = 0.55f + rnd.nextFloat() * 0.25f;
            p.cr = color[0] * dark; p.cg = color[1] * dark; p.cb = color[2] * dark;
            p.baseAlpha = 1f;
            p.u0 = uvP[0]; p.v0 = uvP[1]; p.u1 = uvP[2]; p.v1 = uvP[3];
            particles.add(p);
        }
    }

    public void render(Matrix4f proj, Matrix4f view, Vector3f camRight, Vector3f camUp, TextureAtlas atlas,
                       float daylight, float ambient, float brightness, float linearOut) {
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
        shader.setFloat("uLinearOut", linearOut);
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
            // Искры и пламя светятся сами — в HDR им положен запас за 1.0,
            // иначе bloom их не подхватит.
            float glow = p.flicker > 0f
                    ? 0.55f + 0.45f * (float) Math.sin(p.life * 41f + p.x * 13f) : 1f;
            shader.setFloat("uEmissive", p.worldLit ? 0f : (p.flicker > 0f ? 2.6f : 1.1f));
            shader.setVec3("uCenter", center.set(p.x, p.y, p.z));
            shader.setFloat("uSize", p.size);
            shader.setVec4("uColor", p.cr * mul * glow, p.cg * mul * glow, p.cb * mul * glow,
                    fade * p.baseAlpha);
            shader.setVec2("uUv0", p.u0, p.v0);
            shader.setVec2("uUv1", p.u1, p.v1);
            glDrawArrays(GL_TRIANGLES, 0, 6);
        }
        glBindVertexArray(0);
        shader.unbind();
        glDisable(GL_BLEND);
    }

    /**
     * Брызги упавшей капли: две-три мелкие капли подскакивают над поверхностью.
     * На воде — кольцом шире и светлее, на земле — ниже и короче.
     *
     * Сами осадки живут на видеокарте, здесь только их след на земле.
     */
    public void emitRainSplash(float x, float y, float z, boolean onWater, float skyFrac) {
        if (particles.size() >= MAX - 64)
            return;
        float[] uv = TextureAtlas.uv(WATER_PARTICLE_TILE);
        int count = onWater ? 3 : 2;
        for (int i = 0; i < count; i++) {
            P p = new P();
            p.worldLit = true;
            p.skyL = Math.max(0.35f, skyFrac);
            p.blockL = 0f;
            float angle = rnd.nextFloat() * (float) (Math.PI * 2);
            float horiz = (onWater ? 0.9f : 0.6f) + rnd.nextFloat() * 0.6f;
            p.x = x;
            p.y = y;
            p.z = z;
            p.vx = (float) Math.cos(angle) * horiz;
            p.vz = (float) Math.sin(angle) * horiz;
            p.vy = (onWater ? 1.8f : 1.3f) + rnd.nextFloat() * 1.0f;
            p.floorY = y - 0.05f;
            p.life = p.maxLife = 0.16f + rnd.nextFloat() * 0.12f;
            p.size = 0.025f + rnd.nextFloat() * 0.02f;
            p.gravityScale = 1.1f;
            p.cr = 0.78f; p.cg = 0.86f; p.cb = 1f;
            p.baseAlpha = onWater ? 0.75f : 0.55f;
            p.u0 = uv[0]; p.v0 = uv[1]; p.u1 = uv[2]; p.v1 = uv[3];
            particles.add(p);
        }
    }

    public void destroy() {
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        shader.destroy();
    }
    /** Sparse biome ambience using the existing particle budget. */
    public void emitNature(float x, float y, float z, boolean snow, boolean firefly,
                           float skyFrac) {
        if (particles.size() >= MAX - 96) return;
        P p = new P();
        p.x = x; p.y = y; p.z = z;
        p.vx = 0.12f + rnd.nextFloat() * 0.15f;
        p.vz = (rnd.nextFloat() - 0.5f) * 0.22f;
        p.vy = firefly ? 0.06f : snow ? -0.35f : -0.22f;
        p.life = p.maxLife = 3f + rnd.nextFloat() * 3f;
        p.size = firefly ? 0.045f : snow ? 0.035f : 0.065f;
        p.gravityScale = 0f;
        p.worldLit = !firefly;
        p.skyL = skyFrac;
        p.baseAlpha = firefly ? 0.85f : 0.7f;
        p.cr = firefly ? 1f : snow ? 0.92f : 0.55f;
        p.cg = firefly ? 0.9f : snow ? 0.96f : 0.75f;
        p.cb = firefly ? 0.3f : snow ? 1f : 0.3f;
        float[] uv = TextureAtlas.uv(PARTICLE_TILE);
        p.u0 = uv[0]; p.v0 = uv[1]; p.u1 = uv[2]; p.v1 = uv[3];
        particles.add(p);
    }

    /** Маленькая рыба-силуэт; направление задаётся прочь от недавнего всплеска. */
    public void emitFish(float x, float y, float z, float awayX, float awayZ, float skyFrac) {
        if (particles.size() >= MAX - 96) return;
        float len = (float) Math.sqrt(awayX * awayX + awayZ * awayZ);
        if (len < 0.01f) { awayX = 1f; awayZ = 0f; len = 1f; }
        P p = new P();
        p.x = x; p.y = y; p.z = z;
        float speed = 0.35f + rnd.nextFloat() * 0.45f;
        p.vx = awayX / len * speed;
        p.vz = awayZ / len * speed;
        p.vy = (rnd.nextFloat() - 0.5f) * 0.08f;
        p.life = p.maxLife = 2.2f + rnd.nextFloat() * 1.8f;
        p.size = 0.07f + rnd.nextFloat() * 0.04f;
        p.gravityScale = 0f;
        p.worldLit = true;
        p.skyL = skyFrac;
        p.baseAlpha = 0.78f;
        p.cr = 0.28f; p.cg = 0.58f; p.cb = 0.72f;
        float[] uv = TextureAtlas.uv(PARTICLE_TILE);
        p.u0 = uv[0]; p.v0 = uv[1]; p.u1 = uv[2]; p.v1 = uv[3];
        particles.add(p);
    }
}

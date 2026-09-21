package com.mineclone.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.Random;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

public class ParticleSystem {
    private static final int   MAX     = 768;
    /**
     * Сколько частиц разрешено сейчас. Массивы всегда на {@link #MAX}, а
     * настройка режет только потолок живых: пересоздавать полтора десятка
     * массивов на каждый щелчок в меню незачем, а ноль здесь выключает
     * частицы целиком, не трогая ни одного вызывающего.
     */
    private int budget = MAX;
    private static final float GRAVITY = 14f;

    /** Tile index in the atlas used for solid-color flame/smoke particles. */
    public static final int PARTICLE_TILE = 13;
    /** Tile index for the water-drop splash particle sprite. */
    public static final int WATER_PARTICLE_TILE = 33;

    /** Ветер кадра, блоков в секунду: сносит дым и искры. */
    private float windX, windZ;

    /** Потолок живых частиц: 0 — частиц нет вовсе. */
    public void setBudget(int max) {
        budget = Math.max(0, Math.min(MAX, max));
        while (count > budget)
            remove(count - 1);
    }

    /** Максимум, который вообще умеет система — предел ползунка в настройках. */
    public static int maxParticles() {
        return MAX;
    }

    public void setWind(float x, float z) {
        windX = x;
        windZ = z;
    }

    private int count;
    private final GpuParticlePhysics gpu;
    private final int indirect;
    private final boolean[] pendingSpawn = new boolean[MAX];
    private final float[] x = new float[MAX];
    private final float[] y = new float[MAX];
    private final float[] z = new float[MAX];
    private final float[] vx = new float[MAX];
    private final float[] vy = new float[MAX];
    private final float[] vz = new float[MAX];
    private final float[] life = new float[MAX];
    private final float[] maxLife = new float[MAX];
    private final float[] size = new float[MAX];
    private final float[] growRate = new float[MAX];
    private final float[] gravityScale = new float[MAX];
    private final float[] windScale = new float[MAX];
    private final float[] flicker = new float[MAX];
    private final float[] cr = new float[MAX];
    private final float[] cg = new float[MAX];
    private final float[] cb = new float[MAX];
    private final float[] baseAlpha = new float[MAX];
    private final float[] u0 = new float[MAX];
    private final float[] v0 = new float[MAX];
    private final float[] u1 = new float[MAX];
    private final float[] v1 = new float[MAX];
    private final float[] skyL = new float[MAX];
    private final float[] blockL = new float[MAX];
    private final float[] floorY = new float[MAX];
    private final boolean[] worldLit = new boolean[MAX];
    private final FloatBuffer instanceData = MemoryUtil.memAllocFloat(MAX * 13);
    private final StreamingBuffer instances;
    private final org.joml.FrustumIntersection frustum = new org.joml.FrustumIntersection();
    private final Matrix4f viewProjection = new Matrix4f();

    private int acquire() {
        int p = count++;
        pendingSpawn[p] = true;
        this.x[p] = 0f;
        this.y[p] = 0f;
        this.z[p] = 0f;
        this.vx[p] = 0f;
        this.vy[p] = 0f;
        this.vz[p] = 0f;
        this.life[p] = 0f;
        this.maxLife[p] = 0f;
        this.size[p] = 0f;
        this.growRate[p] = 0f;
        this.gravityScale[p] = 0f;
        this.windScale[p] = 0f;
        this.flicker[p] = 0f;
        this.cr[p] = 0f;
        this.cg[p] = 0f;
        this.cb[p] = 0f;
        this.baseAlpha[p] = 0f;
        this.u0[p] = 0f;
        this.v0[p] = 0f;
        this.u1[p] = 0f;
        this.v1[p] = 0f;
        this.skyL[p] = 0f;
        this.blockL[p] = 0f;
        this.floorY[p] = 0f;
        this.gravityScale[p] = this.baseAlpha[p] = 1f;
        this.floorY[p] = Float.NEGATIVE_INFINITY;
        this.worldLit[p] = false;
        return p;
    }

    private void remove(int p) {
        int last = --count;
        if (gpu != null && !pendingSpawn[last]) gpu.move(last, p);
        pendingSpawn[p] = pendingSpawn[last];
        this.x[p] = this.x[last];
        this.y[p] = this.y[last];
        this.z[p] = this.z[last];
        this.vx[p] = this.vx[last];
        this.vy[p] = this.vy[last];
        this.vz[p] = this.vz[last];
        this.life[p] = this.life[last];
        this.maxLife[p] = this.maxLife[last];
        this.size[p] = this.size[last];
        this.growRate[p] = this.growRate[last];
        this.gravityScale[p] = this.gravityScale[last];
        this.windScale[p] = this.windScale[last];
        this.flicker[p] = this.flicker[last];
        this.cr[p] = this.cr[last];
        this.cg[p] = this.cg[last];
        this.cb[p] = this.cb[last];
        this.baseAlpha[p] = this.baseAlpha[last];
        this.u0[p] = this.u0[last];
        this.v0[p] = this.v0[last];
        this.u1[p] = this.u1[last];
        this.v1[p] = this.v1[last];
        this.skyL[p] = this.skyL[last];
        this.blockL[p] = this.blockL[last];
        this.floorY[p] = this.floorY[last];
        this.worldLit[p] = this.worldLit[last];
    }

    private final Random   rnd       = new Random();
    private final int      vao, vbo;
    private final Shader   shader;

    public ParticleSystem() {
        indirect = org.lwjgl.opengl.GL.getCapabilities().OpenGL40 ? glGenBuffers() : 0;
        // The SSBO path is opt-in until the render vertex stream is fully GPU resident;
        // the default keeps particle transforms in the same instanced buffer as color/UV.
        gpu = org.lwjgl.opengl.GL.getCapabilities().OpenGL43
                && Boolean.getBoolean("mineclone.gpuParticles")
                && !Boolean.getBoolean("mineclone.cpuParticles")
                ? new GpuParticlePhysics(MAX) : null;
        shader = new Shader(Shaders.INSTANCED_PARTICLE_VERTEX, Shaders.INSTANCED_PARTICLE_FRAGMENT);
        instances = new StreamingBuffer(MAX * 13 * 4);
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
        for (int i = 0; i < count && this.count < budget; i++) {
            int p = acquire();
            this.worldLit[p] = true;
            this.skyL[p] = skyFrac;
            this.blockL[p] = blockFrac;
            this.x[p] = bx + 0.2f + rnd.nextFloat() * 0.6f;
            this.y[p] = by + 0.2f + rnd.nextFloat() * 0.6f;
            this.z[p] = bz + 0.2f + rnd.nextFloat() * 0.6f;
            this.vx[p] = (rnd.nextFloat() - 0.5f) * 4f;
            this.vy[p] = 2f + rnd.nextFloat() * 3f;
            this.vz[p] = (rnd.nextFloat() - 0.5f) * 4f;
            this.life[p] = this.maxLife[p] = 0.5f + rnd.nextFloat() * 0.4f;
            this.size[p] = 0.08f + rnd.nextFloat() * 0.06f;
            this.gravityScale[p] = 1f;
            this.cr[p] = 1f; this.cg[p] = 1f; this.cb[p] = 1f; // white tint — texture provides colour
            this.baseAlpha[p] = 1f;
            float[] uv = TextureAtlas.uv(sideTile);
            float span = 4f / TextureAtlas.ATLAS_SIZE;
            float maxU = uv[2] - uv[0] - span;
            float maxV = uv[3] - uv[1] - span;
            this.u0[p] = uv[0] + rnd.nextFloat() * Math.max(0f, maxU);
            this.v0[p] = uv[1] + rnd.nextFloat() * Math.max(0f, maxV);
            this.u1[p] = this.u0[p] + span;
            this.v1[p] = this.v0[p] + span;

        }
    }

    /** Short, restrained texture-chip burst when a block snaps into place. */
    public void emitBlockPlace(int bx, int by, int bz, int sideTile,
                               float skyFrac, float blockFrac) {
        float[] uv = TextureAtlas.uv(sideTile);
        float span = 3f / TextureAtlas.ATLAS_SIZE;
        for (int i = 0; i < 5 && this.count < budget; i++) {
            int p = acquire();
            this.worldLit[p] = true;
            this.skyL[p] = skyFrac;
            this.blockL[p] = blockFrac;
            this.x[p] = bx + 0.18f + rnd.nextFloat() * 0.64f;
            this.y[p] = by + 0.12f + rnd.nextFloat() * 0.45f;
            this.z[p] = bz + 0.18f + rnd.nextFloat() * 0.64f;
            this.vx[p] = (rnd.nextFloat() - 0.5f) * 1.1f;
            this.vy[p] = 0.45f + rnd.nextFloat() * 0.85f;
            this.vz[p] = (rnd.nextFloat() - 0.5f) * 1.1f;
            this.life[p] = this.maxLife[p] = 0.18f + rnd.nextFloat() * 0.18f;
            this.size[p] = 0.045f + rnd.nextFloat() * 0.035f;
            this.gravityScale[p] = 1f;
            this.cr[p] = this.cg[p] = this.cb[p] = 1f;
            this.baseAlpha[p] = 0.9f;
            this.u0[p] = uv[0] + rnd.nextFloat() * Math.max(0f, uv[2] - uv[0] - span);
            this.v0[p] = uv[1] + rnd.nextFloat() * Math.max(0f, uv[3] - uv[1] - span);
            this.u1[p] = this.u0[p] + span;
            this.v1[p] = this.v0[p] + span;
        }
    }

    /**
     * Emit a small burst of flame and smoke from a torch block.
     * Call this every ~70 ms for nearby torches.
     */
    public void emitTorchEffects(int bx, int by, int bz) {
        float[] uvP = TextureAtlas.uv(125);

        // Flame: ~65 % chance per call
        if (rnd.nextFloat() < 0.65f && this.count < budget) {
            int p = acquire();
            this.x[p] = bx + 0.42f + rnd.nextFloat() * 0.16f;
            this.y[p] = by + 0.68f + rnd.nextFloat() * 0.06f;
            this.z[p] = bz + 0.42f + rnd.nextFloat() * 0.16f;
            this.vx[p] = (rnd.nextFloat() - 0.5f) * 0.18f;
            this.vy[p] = 0.35f + rnd.nextFloat() * 0.25f;
            this.vz[p] = (rnd.nextFloat() - 0.5f) * 0.18f;
            this.life[p] = this.maxLife[p] = 0.22f + rnd.nextFloat() * 0.14f;
            this.size[p] = 0.045f + rnd.nextFloat() * 0.025f;
            this.gravityScale[p] = 0f;
            this.growRate[p] = 0f;
            // Random warm flame colour
            float[] cols = {
                0.95f + rnd.nextFloat() * 0.05f,
                0.45f + rnd.nextFloat() * 0.25f,
                0.02f + rnd.nextFloat() * 0.08f
            };
            this.cr[p] = cols[0]; this.cg[p] = cols[1]; this.cb[p] = cols[2];
            this.baseAlpha[p] = 0.9f;
            this.u0[p] = uvP[0]; this.v0[p] = uvP[1]; this.u1[p] = uvP[2]; this.v1[p] = uvP[3];

        }

        // Smoke: ~28 % chance per call
        uvP = TextureAtlas.uv(124);
        if (rnd.nextFloat() < 0.28f && this.count < budget) {
            int p = acquire();
            this.x[p] = bx + 0.38f + rnd.nextFloat() * 0.24f;
            this.y[p] = by + 0.78f + rnd.nextFloat() * 0.04f;
            this.z[p] = bz + 0.38f + rnd.nextFloat() * 0.24f;
            this.vx[p] = (rnd.nextFloat() - 0.5f) * 0.12f;
            this.vy[p] = 0.12f + rnd.nextFloat() * 0.10f;
            this.vz[p] = (rnd.nextFloat() - 0.5f) * 0.12f;
            this.life[p] = this.maxLife[p] = 0.7f + rnd.nextFloat() * 0.5f;
            this.size[p] = 0.05f + rnd.nextFloat() * 0.04f;
            this.gravityScale[p] = 0f;
            this.growRate[p] = 0.05f;
            this.windScale[p] = 0.4f;
            float g = 0.12f + rnd.nextFloat() * 0.08f;
            this.cr[p] = g; this.cg[p] = g; this.cb[p] = g;
            this.baseAlpha[p] = 0.50f;
            this.u0[p] = uvP[0]; this.v0[p] = uvP[1]; this.u1[p] = uvP[2]; this.v1[p] = uvP[3];

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
        float[] uvP = TextureAtlas.uv(124);
        for (int i = 0; i < 2; i++) {
            if (this.count >= budget)
                return;
            int p = acquire();
            this.x[p] = x + (rnd.nextFloat() - 0.5f) * 0.1f;
            this.y[p] = y + (rnd.nextFloat() - 0.5f) * 0.06f;
            this.z[p] = z + (rnd.nextFloat() - 0.5f) * 0.1f;
            this.vx[p] = dirX * (0.35f + rnd.nextFloat() * 0.25f) + (rnd.nextFloat() - 0.5f) * 0.1f;
            this.vy[p] = 0.10f + rnd.nextFloat() * 0.08f;
            this.vz[p] = dirZ * (0.35f + rnd.nextFloat() * 0.25f) + (rnd.nextFloat() - 0.5f) * 0.1f;
            this.life[p] = this.maxLife[p] = 0.9f + rnd.nextFloat() * 0.6f;
            this.size[p] = 0.045f + rnd.nextFloat() * 0.03f;
            this.gravityScale[p] = 0f;
            this.growRate[p] = 0.10f;
            this.windScale[p] = 0.25f;
            this.cr[p] = 0.92f; this.cg[p] = 0.95f; this.cb[p] = 1.0f;
            this.baseAlpha[p] = 0.30f;
            this.u0[p] = uvP[0]; this.v0[p] = uvP[1]; this.u1[p] = uvP[2]; this.v1[p] = uvP[3];

        }
    }

    public void emitFire(int bx, int by, int bz) {
        float[] uvP = TextureAtlas.uv(125);

        for (int i = 0; i < 2; i++) {
            if (this.count >= budget)
                break;
            int p = acquire();
            this.x[p] = bx + 0.25f + rnd.nextFloat() * 0.5f;
            this.y[p] = by + 0.1f + rnd.nextFloat() * 0.25f;
            this.z[p] = bz + 0.25f + rnd.nextFloat() * 0.5f;
            this.vx[p] = (rnd.nextFloat() - 0.5f) * 0.3f;
            this.vy[p] = 0.9f + rnd.nextFloat() * 0.7f;
            this.vz[p] = (rnd.nextFloat() - 0.5f) * 0.3f;
            this.life[p] = this.maxLife[p] = 0.32f + rnd.nextFloat() * 0.22f;
            this.size[p] = 0.07f + rnd.nextFloat() * 0.05f;
            this.gravityScale[p] = 0f;
            this.growRate[p] = -0.06f;
            this.cr[p] = 1.0f;
            this.cg[p] = 0.42f + rnd.nextFloat() * 0.38f;
            this.cb[p] = 0.04f + rnd.nextFloat() * 0.08f;
            this.baseAlpha[p] = 0.95f;
            this.u0[p] = uvP[0]; this.v0[p] = uvP[1]; this.u1[p] = uvP[2]; this.v1[p] = uvP[3];

        }

        uvP = TextureAtlas.uv(124);
        if (rnd.nextFloat() < 0.5f && this.count < budget) {
            int p = acquire();
            this.x[p] = bx + 0.3f + rnd.nextFloat() * 0.4f;
            this.y[p] = by + 0.85f + rnd.nextFloat() * 0.2f;
            this.z[p] = bz + 0.3f + rnd.nextFloat() * 0.4f;
            this.vx[p] = (rnd.nextFloat() - 0.5f) * 0.25f;
            this.vy[p] = 0.3f + rnd.nextFloat() * 0.25f;
            this.vz[p] = (rnd.nextFloat() - 0.5f) * 0.25f;
            this.life[p] = this.maxLife[p] = 1.6f + rnd.nextFloat() * 1.2f;
            this.size[p] = 0.09f + rnd.nextFloat() * 0.06f;
            this.gravityScale[p] = -0.015f;   // горячий дым сам тянется вверх
            this.growRate[p] = 0.16f;
            this.windScale[p] = 0.6f;
            float g = 0.10f + rnd.nextFloat() * 0.07f;
            this.cr[p] = g; this.cg[p] = g; this.cb[p] = g;
            this.baseAlpha[p] = 0.45f;
            this.u0[p] = uvP[0]; this.v0[p] = uvP[1]; this.u1[p] = uvP[2]; this.v1[p] = uvP[3];

        }

        // Костёр стреляет искрами щедро — это и отличает его от факела издалека.
        if (rnd.nextFloat() < 0.45f)
            emitEmber(bx + 0.3f + rnd.nextFloat() * 0.4f, by + 0.5f, bz + 0.3f + rnd.nextFloat() * 0.4f, 1.6f);
    }

    public void emitWaterSplash(float x, float y, float z, float skyFrac, float blockFrac) {
        emitWaterSplash(x, y, z, skyFrac, blockFrac, 1f);
    }

    /** One glowing pixel droplet, emitted sparsely from exposed lava surfaces. */
    public void emitLavaPop(float x, float y, float z) {
        if (count >= budget - 32) return;
        int p = acquire();
        this.x[p]=x; this.y[p]=y; this.z[p]=z;
        vx[p]=(rnd.nextFloat()-.5f)*.9f;
        vz[p]=(rnd.nextFloat()-.5f)*.9f;
        vy[p]=1.8f+rnd.nextFloat()*1.8f;
        life[p]=maxLife[p]=.6f+rnd.nextFloat()*.35f;
        size[p]=.11f+rnd.nextFloat()*.08f;
        gravityScale[p]=.45f;
        floorY[p]=y-.02f;
        cr[p]=cg[p]=cb[p]=baseAlpha[p]=1f;
        flicker[p]=1f;
        float[] uv=TextureAtlas.uv(126);
        u0[p]=uv[0]; v0[p]=uv[1]; u1[p]=uv[2]; v1[p]=uv[3];
    }

    public void emitWaterSplash(float x, float y, float z, float skyFrac, float blockFrac,
                                float intensity) {
        intensity = Math.max(0.15f, Math.min(1.6f, intensity));
        float[] uvP = TextureAtlas.uv(WATER_PARTICLE_TILE);
        int count = Math.max(3, Math.round((8 + rnd.nextInt(7)) * intensity));
        for (int i = 0; i < count && this.count < budget; i++) {
            int p = acquire();
            this.worldLit[p] = true;
            this.skyL[p] = skyFrac;
            this.blockL[p] = blockFrac;
            float angle = rnd.nextFloat() * (float) (Math.PI * 2);
            float horiz = (1.0f + rnd.nextFloat() * 2.3f) * (0.65f + intensity * 0.55f);
            this.x[p] = x + (rnd.nextFloat() - 0.5f) * 0.6f;
            this.y[p] = y;
            this.z[p] = z + (rnd.nextFloat() - 0.5f) * 0.6f;
            this.vx[p] = (float) Math.cos(angle) * horiz;
            this.vy[p] = (1.8f + rnd.nextFloat() * 2.8f) * (0.65f + intensity * 0.5f);
            this.vz[p] = (float) Math.sin(angle) * horiz;
            this.life[p] = this.maxLife[p] = 0.35f + rnd.nextFloat() * 0.35f;
            this.size[p] = 0.06f + rnd.nextFloat() * 0.07f;
            this.gravityScale[p] = 1f;
            this.cr[p] = 1f; this.cg[p] = 1f; this.cb[p] = 1f;
            this.baseAlpha[p] = 0.85f + rnd.nextFloat() * 0.15f;
            this.u0[p] = uvP[0]; this.v0[p] = uvP[1]; this.u1[p] = uvP[2]; this.v1[p] = uvP[3];

        }
    }

    public void emitLandingPuff(float x, float y, float z, int sideTile,
                                float skyFrac, float blockFrac, int count) {
        float[] uv = TextureAtlas.uv(sideTile);
        float span = 4f / TextureAtlas.ATLAS_SIZE;
        for (int i = 0; i < count && this.count < budget; i++) {
            int p = acquire();
            this.worldLit[p] = true;
            this.skyL[p] = skyFrac;
            this.blockL[p] = blockFrac;
            this.x[p] = x + (rnd.nextFloat() - 0.5f) * 1.0f;
            this.y[p] = y + 0.05f;
            this.z[p] = z + (rnd.nextFloat() - 0.5f) * 1.0f;
            float angle = rnd.nextFloat() * (float) (Math.PI * 2);
            float horiz = 1.2f + rnd.nextFloat() * 2.8f;
            this.vx[p] = (float) Math.cos(angle) * horiz;
            this.vy[p] = 0.8f + rnd.nextFloat() * 1.8f;
            this.vz[p] = (float) Math.sin(angle) * horiz;
            this.life[p] = this.maxLife[p] = 0.25f + rnd.nextFloat() * 0.25f;
            this.size[p] = 0.05f + rnd.nextFloat() * 0.08f;
            this.growRate[p] = 0f;
            this.gravityScale[p] = 1.5f;
            this.cr[p] = 1f; this.cg[p] = 1f; this.cb[p] = 1f;
            this.baseAlpha[p] = 1f;
            float maxU = uv[2] - uv[0] - span;
            float maxV = uv[3] - uv[1] - span;
            this.u0[p] = uv[0] + rnd.nextFloat() * Math.max(0f, maxU);
            this.v0[p] = uv[1] + rnd.nextFloat() * Math.max(0f, maxV);
            this.u1[p] = this.u0[p] + span;
            this.v1[p] = this.v0[p] + span;

        }
    }

    /**
     * Струйка серого дыма от горящего на солнце моба.
     * Зовётся выборочно (не каждый кадр) — иначе дым забивает буфер частиц.
     */
    public void emitMobSmoke(float x, float y, float z) {
        if (this.count >= budget)
            return;
        float[] uvP = TextureAtlas.uv(PARTICLE_TILE);
        int p = acquire();
        this.x[p] = x + (rnd.nextFloat() - 0.5f) * 0.5f;
        this.y[p] = y + rnd.nextFloat() * 0.5f;
        this.z[p] = z + (rnd.nextFloat() - 0.5f) * 0.5f;
        this.vx[p] = (rnd.nextFloat() - 0.5f) * 0.2f;
        this.vy[p] = 0.5f + rnd.nextFloat() * 0.4f;
        this.vz[p] = (rnd.nextFloat() - 0.5f) * 0.2f;
        this.life[p] = this.maxLife[p] = 0.6f + rnd.nextFloat() * 0.4f;
        this.size[p] = 0.07f + rnd.nextFloat() * 0.05f;
        this.gravityScale[p] = 0f;
        this.growRate[p] = 0.06f;
        this.windScale[p] = 0.5f;
        float grey = 0.18f + rnd.nextFloat() * 0.1f;
        this.cr[p] = grey;
        this.cg[p] = grey;
        this.cb[p] = grey;
        this.baseAlpha[p] = 0.55f;
        this.u0[p] = uvP[0];
        this.v0[p] = uvP[1];
        this.u1[p] = uvP[2];
        this.v1[p] = uvP[3];

    }

    /** Язычок пламени на горящем мобе — тот же рецепт, что у факела. */
    public void emitMobFlame(float x, float y, float z) {
        if (this.count >= budget)
            return;
        float[] uvP = TextureAtlas.uv(PARTICLE_TILE);
        int p = acquire();
        this.x[p] = x + (rnd.nextFloat() - 0.5f) * 0.6f;
        this.y[p] = y + (rnd.nextFloat() - 0.5f) * 0.8f;
        this.z[p] = z + (rnd.nextFloat() - 0.5f) * 0.6f;
        this.vx[p] = (rnd.nextFloat() - 0.5f) * 0.3f;
        this.vy[p] = 0.6f + rnd.nextFloat() * 0.5f;
        this.vz[p] = (rnd.nextFloat() - 0.5f) * 0.3f;
        this.life[p] = this.maxLife[p] = 0.25f + rnd.nextFloat() * 0.2f;
        this.size[p] = 0.07f + rnd.nextFloat() * 0.05f;
        this.gravityScale[p] = 0f;
        this.growRate[p] = 0f;
        this.cr[p] = 0.95f + rnd.nextFloat() * 0.05f;
        this.cg[p] = 0.45f + rnd.nextFloat() * 0.25f;
        this.cb[p] = 0.03f + rnd.nextFloat() * 0.08f;
        this.baseAlpha[p] = 0.9f;
        this.u0[p] = uvP[0];
        this.v0[p] = uvP[1];
        this.u1[p] = uvP[2];
        this.v1[p] = uvP[3];

    }

    /** Облако частиц цвета моба в момент смерти. */
    public void emitMobDeath(float x, float y, float z, float[] color) {
        float[] uvP = TextureAtlas.uv(PARTICLE_TILE);
        int count = 14 + rnd.nextInt(8);
        for (int i = 0; i < count && this.count < budget; i++) {
            int p = acquire();
            float angle = rnd.nextFloat() * (float) (Math.PI * 2);
            float horiz = 0.8f + rnd.nextFloat() * 1.8f;
            this.x[p] = x + (rnd.nextFloat() - 0.5f) * 0.6f;
            this.y[p] = y + (rnd.nextFloat() - 0.5f) * 0.6f;
            this.z[p] = z + (rnd.nextFloat() - 0.5f) * 0.6f;
            this.vx[p] = (float) Math.cos(angle) * horiz;
            this.vy[p] = 1.2f + rnd.nextFloat() * 1.6f;
            this.vz[p] = (float) Math.sin(angle) * horiz;
            this.life[p] = this.maxLife[p] = 0.45f + rnd.nextFloat() * 0.35f;
            this.size[p] = 0.07f + rnd.nextFloat() * 0.06f;
            this.gravityScale[p] = 0.6f;
            this.cr[p] = color[0];
            this.cg[p] = color[1];
            this.cb[p] = color[2];
            this.baseAlpha[p] = 1f;
            this.u0[p] = uvP[0];
            this.v0[p] = uvP[1];
            this.u1[p] = uvP[2];
            this.v1[p] = uvP[3];

        }
    }

    // -------------------------------------------------------------------------
    //  Update & render
    // -------------------------------------------------------------------------

    private void flushSpawns() {
        if (gpu == null) return;
        for (int i = 0; i < count; i++) if (pendingSpawn[i]) {
            gpu.spawn(i, x[i], y[i], z[i], size[i], vx[i], vy[i], vz[i], gravityScale[i], growRate[i], windScale[i], floorY[i]);
            pendingSpawn[i] = false;
        }
    }

    public void update(float dt) {
        flushSpawns();
        for (int p = 0; p < count; p++) {
            this.life[p] -= dt;
            if (this.life[p] <= 0f || (gpu == null && this.y[p] <= this.floorY[p])) { remove(p--); continue; }
            if (gpu != null) continue;
            this.vy[p]  -= GRAVITY * this.gravityScale[p] * dt;
            // Ветер тянет скорость к своей, а не прибавляет её: иначе дым за
            // секунду разгонялся бы быстрее самого ветра.
            if (this.windScale[p] > 0f) {
                this.vx[p] += (windX * this.windScale[p] - this.vx[p]) * Math.min(1f, dt * 1.5f);
                this.vz[p] += (windZ * this.windScale[p] - this.vz[p]) * Math.min(1f, dt * 1.5f);
            }
            this.x[p]   += this.vx[p] * dt;
            this.y[p]   += this.vy[p] * dt;
            this.z[p]   += this.vz[p] * dt;
            this.size[p] = Math.max(0.01f, this.size[p] + this.growRate[p] * dt);
            float drag = Math.max(0f, 1f - 2f * dt);
            this.vx[p] *= drag; this.vz[p] *= drag;
        }
        if (gpu != null) gpu.update(count, dt, windX, windZ);
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
        if (this.count >= budget - 32)
            return;
        float[] uvP = TextureAtlas.uv(PARTICLE_TILE);
        int p = acquire();
        this.x[p] = x; this.y[p] = y; this.z[p] = z;
        this.vx[p] = (rnd.nextFloat() - 0.5f) * 0.5f;
        this.vy[p] = lift * (0.8f + rnd.nextFloat() * 0.9f);
        this.vz[p] = (rnd.nextFloat() - 0.5f) * 0.5f;
        this.life[p] = this.maxLife[p] = 0.6f + rnd.nextFloat() * 0.9f;
        this.size[p] = 0.018f + rnd.nextFloat() * 0.016f;
        // Отрицательная гравитация — это подъёмная сила горячего воздуха.
        this.gravityScale[p] = -0.02f;
        this.windScale[p] = 0.35f;
        this.flicker[p] = 1f;
        this.cr[p] = 1f; this.cg[p] = 0.55f + rnd.nextFloat() * 0.3f; this.cb[p] = 0.12f;
        this.baseAlpha[p] = 1f;
        this.u0[p] = uvP[0]; this.v0[p] = uvP[1]; this.u1[p] = uvP[2]; this.v1[p] = uvP[3];

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
        for (int i = 0; i < count && this.count < budget; i++) {
            int p = acquire();
            this.worldLit[p] = true;
            this.skyL[p] = skyFrac;
            this.blockL[p] = blockFrac;
            this.x[p] = x + (rnd.nextFloat() - 0.5f) * 0.3f;
            this.y[p] = y + 0.05f;
            this.z[p] = z + (rnd.nextFloat() - 0.5f) * 0.3f;
            float back = (sprint ? 1.6f : 0.9f) * (0.5f + rnd.nextFloat());
            this.vx[p] = -dirX * back + (rnd.nextFloat() - 0.5f) * 1.2f;
            this.vz[p] = -dirZ * back + (rnd.nextFloat() - 0.5f) * 1.2f;
            this.vy[p] = (sprint ? 2.2f : 1.4f) + rnd.nextFloat() * 1.2f;
            this.life[p] = this.maxLife[p] = 0.35f + rnd.nextFloat() * 0.35f;
            this.size[p] = 0.035f + rnd.nextFloat() * 0.045f;
            this.gravityScale[p] = 0.75f;
            this.floorY[p] = y - 0.3f;
            this.cr[p] = 0.95f; this.cg[p] = 0.97f; this.cb[p] = 1f;
            this.baseAlpha[p] = 0.9f;
            this.u0[p] = uvP[0]; this.v0[p] = uvP[1]; this.u1[p] = uvP[2]; this.v1[p] = uvP[3];

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
        for (int i = 0; i < sparks && this.count < budget; i++) {
            int p = acquire();
            float speed = (crit ? 4.5f : 3.2f) * (0.5f + rnd.nextFloat());
            this.x[p] = x; this.y[p] = y; this.z[p] = z;
            this.vx[p] = (dirX + (rnd.nextFloat() - 0.5f) * 1.6f) * speed;
            this.vy[p] = (dirY + 0.35f + (rnd.nextFloat() - 0.2f) * 1.4f) * speed;
            this.vz[p] = (dirZ + (rnd.nextFloat() - 0.5f) * 1.6f) * speed;
            this.life[p] = this.maxLife[p] = 0.12f + rnd.nextFloat() * (crit ? 0.30f : 0.18f);
            this.size[p] = (crit ? 0.05f : 0.03f) + rnd.nextFloat() * 0.025f;
            this.gravityScale[p] = 0.9f;
            this.flicker[p] = crit ? 1f : 0f;
            this.cr[p] = 1f;
            this.cg[p] = crit ? 0.95f : 0.85f;
            this.cb[p] = crit ? 0.55f : 0.60f;
            this.baseAlpha[p] = 1f;
            this.u0[p] = uvP[0]; this.v0[p] = uvP[1]; this.u1[p] = uvP[2]; this.v1[p] = uvP[3];

        }
        int drops = crit ? 10 : 6;
        for (int i = 0; i < drops && this.count < budget; i++) {
            int p = acquire();
            this.worldLit[p] = true;
            this.skyL[p] = skyFrac;
            this.blockL[p] = blockFrac;
            this.x[p] = x + (rnd.nextFloat() - 0.5f) * 0.2f;
            this.y[p] = y + (rnd.nextFloat() - 0.5f) * 0.2f;
            this.z[p] = z + (rnd.nextFloat() - 0.5f) * 0.2f;
            float speed = 1.4f + rnd.nextFloat() * 1.8f;
            this.vx[p] = (dirX * 0.7f + (rnd.nextFloat() - 0.5f)) * speed;
            this.vy[p] = (0.6f + rnd.nextFloat()) * speed;
            this.vz[p] = (dirZ * 0.7f + (rnd.nextFloat() - 0.5f)) * speed;
            this.life[p] = this.maxLife[p] = 0.35f + rnd.nextFloat() * 0.3f;
            this.size[p] = 0.035f + rnd.nextFloat() * 0.035f;
            this.gravityScale[p] = 1.4f;
            float dark = 0.55f + rnd.nextFloat() * 0.25f;
            this.cr[p] = color[0] * dark; this.cg[p] = color[1] * dark; this.cb[p] = color[2] * dark;
            this.baseAlpha[p] = 1f;
            this.u0[p] = uvP[0]; this.v0[p] = uvP[1]; this.u1[p] = uvP[2]; this.v1[p] = uvP[3];

        }
    }

    public void render(Matrix4f proj, Matrix4f view, Vector3f camRight, Vector3f camUp, TextureAtlas atlas,
                       float daylight, float ambient, float brightness, float linearOut) {
        if (count == 0) return;
        flushSpawns();
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
        frustum.set(viewProjection.set(proj).mul(view));
        instanceData.clear();
        int visible = 0;
        for (int p = 0; p < count; p++) {
            if (gpu == null && !frustum.testSphere(this.x[p], this.y[p], this.z[p], this.size[p])) continue;
            visible++;
            // Fade alpha based on remaining life fraction
            float fade = Math.min(1f, this.life[p] / (this.maxLife[p] * 0.35f));
            // Same shading curve as the chunk fragment shader so break debris
            // matches the block it came from. Emissive particles skip it.
            float mul = 1f;
            if (this.worldLit[p]) {
                float combined = Math.max(this.skyL[p] * daylight, this.blockL[p]);
                float shaped = (float) Math.pow(Math.max(ambient, combined), 0.75) * brightness;
                mul = Math.min(shaped, 1f);
            }
            // Искры и пламя светятся сами — в HDR им положен запас за 1.0,
            // иначе bloom их не подхватит.
            float glow = this.flicker[p] > 0f
                    ? 0.55f + 0.45f * (float) Math.sin(this.life[p] * 41f + this.x[p] * 13f) : 1f;
            instanceData.put(this.x[p]).put(this.y[p]).put(this.z[p]).put(this.size[p]);
            instanceData.put(this.cr[p] * mul * glow).put(this.cg[p] * mul * glow).put(this.cb[p] * mul * glow).put(fade * this.baseAlpha[p]);
            instanceData.put(this.u0[p]).put(this.v0[p]).put(this.u1[p]).put(this.v1[p]);
            instanceData.put(this.worldLit[p] ? 0f : (this.flicker[p] > 0f ? 2.6f : 1.1f));
        }
        instanceData.flip();
        long offset = instances.upload(instanceData);
        for (int attribute = 1; attribute <= 4; attribute++) {
            glVertexAttribPointer(attribute, attribute == 4 ? 1 : 4, GL_FLOAT, false, 13 * 4,
                    offset + (attribute - 1) * 4L * 4);
            glEnableVertexAttribArray(attribute);
            org.lwjgl.opengl.GL33.glVertexAttribDivisor(attribute, 1);
        }
        if (gpu != null) gpu.bindPositions();
        if (indirect != 0) {
            org.lwjgl.opengl.GL40.glBindBuffer(org.lwjgl.opengl.GL40.GL_DRAW_INDIRECT_BUFFER, indirect);
            glBufferData(org.lwjgl.opengl.GL40.GL_DRAW_INDIRECT_BUFFER, new int[]{6, visible, 0, 0}, GL_STREAM_DRAW);
            org.lwjgl.opengl.GL40.glDrawArraysIndirect(GL_TRIANGLES, 0L);
            org.lwjgl.opengl.GL40.glBindBuffer(org.lwjgl.opengl.GL40.GL_DRAW_INDIRECT_BUFFER, 0);
        } else {
            org.lwjgl.opengl.GL31.glDrawArraysInstanced(GL_TRIANGLES, 0, 6, visible);
        }
        instances.submitted();
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
        if (this.count >= budget - 64)
            return;
        float[] uv = TextureAtlas.uv(WATER_PARTICLE_TILE);
        int count = onWater ? 3 : 2;
        for (int i = 0; i < count; i++) {
            int p = acquire();
            this.worldLit[p] = true;
            this.skyL[p] = Math.max(0.35f, skyFrac);
            this.blockL[p] = 0f;
            float angle = rnd.nextFloat() * (float) (Math.PI * 2);
            float horiz = (onWater ? 0.9f : 0.6f) + rnd.nextFloat() * 0.6f;
            this.x[p] = x;
            this.y[p] = y;
            this.z[p] = z;
            this.vx[p] = (float) Math.cos(angle) * horiz;
            this.vz[p] = (float) Math.sin(angle) * horiz;
            this.vy[p] = (onWater ? 1.8f : 1.3f) + rnd.nextFloat() * 1.0f;
            this.floorY[p] = y - 0.05f;
            this.life[p] = this.maxLife[p] = 0.16f + rnd.nextFloat() * 0.12f;
            this.size[p] = 0.025f + rnd.nextFloat() * 0.02f;
            this.gravityScale[p] = 1.1f;
            this.cr[p] = 0.78f; this.cg[p] = 0.86f; this.cb[p] = 1f;
            this.baseAlpha[p] = onWater ? 0.75f : 0.55f;
            this.u0[p] = uv[0]; this.v0[p] = uv[1]; this.u1[p] = uv[2]; this.v1[p] = uv[3];

        }
    }

    public void destroy() {
        if (gpu != null) gpu.destroy();
        if (indirect != 0) glDeleteBuffers(indirect);
        instances.destroy();
        MemoryUtil.memFree(instanceData);
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        shader.destroy();
    }
    /** Sparse biome ambience using the existing particle budget. */
    public void emitNature(float x, float y, float z, boolean snow, boolean firefly,
                           float skyFrac) {
        if (this.count >= budget - 96) return;
        int p = acquire();
        this.x[p] = x; this.y[p] = y; this.z[p] = z;
        this.vx[p] = 0.12f + rnd.nextFloat() * 0.15f;
        this.vz[p] = (rnd.nextFloat() - 0.5f) * 0.22f;
        this.vy[p] = firefly ? 0.06f : snow ? -0.35f : -0.22f;
        this.life[p] = this.maxLife[p] = 3f + rnd.nextFloat() * 3f;
        this.size[p] = firefly ? 0.045f : snow ? 0.035f : 0.065f;
        this.gravityScale[p] = 0f;
        this.worldLit[p] = !firefly;
        this.skyL[p] = skyFrac;
        this.baseAlpha[p] = firefly ? 0.85f : 0.7f;
        this.cr[p] = firefly ? 1f : snow ? 0.92f : 0.55f;
        this.cg[p] = firefly ? 0.9f : snow ? 0.96f : 0.75f;
        this.cb[p] = firefly ? 0.3f : snow ? 1f : 0.3f;
        float[] uv = TextureAtlas.uv(PARTICLE_TILE);
        this.u0[p] = uv[0]; this.v0[p] = uv[1]; this.u1[p] = uv[2]; this.v1[p] = uv[3];

    }

    /** Маленькая рыба-силуэт; направление задаётся прочь от недавнего всплеска. */
    public void emitFish(float x, float y, float z, float awayX, float awayZ, float skyFrac) {
        if (this.count >= budget - 96) return;
        float len = (float) Math.sqrt(awayX * awayX + awayZ * awayZ);
        if (len < 0.01f) { awayX = 1f; awayZ = 0f; len = 1f; }
        int p = acquire();
        this.x[p] = x; this.y[p] = y; this.z[p] = z;
        float speed = 0.35f + rnd.nextFloat() * 0.45f;
        this.vx[p] = awayX / len * speed;
        this.vz[p] = awayZ / len * speed;
        this.vy[p] = (rnd.nextFloat() - 0.5f) * 0.08f;
        this.life[p] = this.maxLife[p] = 2.2f + rnd.nextFloat() * 1.8f;
        this.size[p] = 0.07f + rnd.nextFloat() * 0.04f;
        this.gravityScale[p] = 0f;
        this.worldLit[p] = true;
        this.skyL[p] = skyFrac;
        this.baseAlpha[p] = 0.78f;
        this.cr[p] = 0.28f; this.cg[p] = 0.58f; this.cb[p] = 0.72f;
        float[] uv = TextureAtlas.uv(PARTICLE_TILE);
        this.u0[p] = uv[0]; this.v0[p] = uv[1]; this.u1[p] = uv[2]; this.v1[p] = uv[3];

    }
}

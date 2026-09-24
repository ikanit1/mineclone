package com.mineclone.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.Random;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.glDrawArraysInstanced;
import static org.lwjgl.opengl.GL33.glVertexAttribDivisor;

/**
 * Снег и дождь целиком на видеокарте.
 *
 * Прежние осадки были CPU-частицами с общим бюджетом в семьсот штук на все
 * эффекты: метели из них не собрать, а каждая снежинка стоила draw-call.
 * Здесь одна статическая VBO со случайными «семенами», а положение, снос
 * ветром и закрутка считаются в вершинном шейдере от времени. Коробка частиц
 * привязана к миру и сворачивается вокруг камеры: снежинка не едет вместе с
 * игроком, а игрок проходит сквозь снегопад.
 *
 * Плотность задаётся числом отрисованных инстансов — первые N из буфера, —
 * поэтому усиление метели плавное и не пересоздаёт ни одного буфера.
 */
public final class PrecipitationRenderer {

    /** Сколько снежинок в полную силу. */
    public static final int SNOW_MAX = 12000;
    /** Сколько струй дождя в полную силу. */
    public static final int RAIN_MAX = 7000;
    public static final int DUST_MAX = 10000, VEIL_MAX = 900;
    /** Коробка частиц вокруг камеры, блоки. */
    public static final float BOX_H = 40f, BOX_V = 28f;
    /** Текстурный юнит карты крыш — выше атласа и карт теней. */
    public static final int HEIGHT_UNIT = 6;

    private final Shader shader;
    private final int vao, quadVbo, seedVbo;
    private final int heightTex;
    private final FloatBuffer heightBuf =
            MemoryUtil.memAllocFloat(PrecipitationField.SIZE * PrecipitationField.SIZE);
    private final PrecipitationField field = new PrecipitationField();
    private final Matrix4f viewProj = new Matrix4f();
    private final Vector3f cameraRight = new Vector3f();
    private final Vector3f cameraUp = new Vector3f();
    private int uploadedOriginX = Integer.MIN_VALUE, uploadedOriginZ;
    private boolean fieldDirty = true;

    public PrecipitationRenderer() {
        shader = new Shader(Shaders.PRECIP_VERTEX, Shaders.PRECIP_FRAGMENT);
        vao = glGenVertexArrays();
        glBindVertexArray(vao);

        float[] quad = { -0.5f, -0.5f, 0.5f, -0.5f, 0.5f, 0.5f, -0.5f, -0.5f, 0.5f, 0.5f, -0.5f, 0.5f };
        quadVbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, quadVbo);
        FloatBuffer q = MemoryUtil.memAllocFloat(quad.length);
        q.put(quad).flip();
        glBufferData(GL_ARRAY_BUFFER, q, GL_STATIC_DRAW);
        MemoryUtil.memFree(q);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 0, 0);
        glEnableVertexAttribArray(0);

        int n = Math.max(SNOW_MAX, RAIN_MAX);
        FloatBuffer seeds = MemoryUtil.memAllocFloat(n * 4);
        Random rnd = new Random(0x5A0F1A4EL);
        for (int i = 0; i < n; i++)
            seeds.put(rnd.nextFloat()).put(rnd.nextFloat()).put(rnd.nextFloat()).put(rnd.nextFloat());
        seeds.flip();
        seedVbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, seedVbo);
        glBufferData(GL_ARRAY_BUFFER, seeds, GL_STATIC_DRAW);
        MemoryUtil.memFree(seeds);
        glVertexAttribPointer(1, 4, GL_FLOAT, false, 0, 0);
        glEnableVertexAttribArray(1);
        glVertexAttribDivisor(1, 1);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);

        heightTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, heightTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R32F, PrecipitationField.SIZE, PrecipitationField.SIZE, 0,
                GL_RED, GL_FLOAT, (FloatBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    /**
     * Цвет снежинки при освещении кадра.
     *
     * Снег отражает почти весь свет, поэтому хлопья обязаны быть светлее мглы,
     * в которой летят: первая версия брала обычный рассеянный свет, и в метель
     * снежинки читались тёмной грязью на белом фоне. Цвет почти нейтральный —
     * закатный оттенок света ложится на хлопья лишь слегка.
     */
    public static Vector3f flakeColor(Vector3f skyAmbient, Vector3f lightColor, Vector3f haze) {
        Vector3f lit = new Vector3f(skyAmbient).mul(2.2f).add(new Vector3f(lightColor).mul(0.55f));
        float lum = luminance(lit);
        Vector3f c = new Vector3f(lum * 0.97f, lum * 0.99f, lum * 1.05f).lerp(lit, 0.25f);
        float floor = luminance(haze) * 1.3f;
        if (lum > 1e-5f && lum < floor)
            c.mul(floor / lum);
        return c;
    }

    /** Цвет струи дождя: темнее и холоднее снега, почти прозрачная вода. */
    public static Vector3f dropColor(Vector3f flake) {
        return new Vector3f(flake).mul(0.52f, 0.58f, 0.70f);
    }

    private static float luminance(Vector3f c) {
        return 0.2126f * c.x + 0.7152f * c.y + 0.0722f * c.z;
    }

    /** Карта крыш под текущими осадками — ей же пользуются брызги на земле. */
    public PrecipitationField field() {
        return field;
    }

    /** Мир изменился (поставили или сняли блок) — крыши надо пересчитать. */
    public void invalidate() {
        fieldDirty = true;
    }

    /** Пересобирает карту, если игрок ушёл от центра или мир поменялся. */
    public void updateField(com.mineclone.world.World world, Vector3f camPos) {
        int cx = (int) Math.floor(camPos.x), cz = (int) Math.floor(camPos.z);
        if (!fieldDirty && !field.needsRebuild(cx, cz))
            return;
        int cy = (int) Math.floor(camPos.y);
        field.rebuild(world, cx, cz, cy - (int) (BOX_V * 0.5f) - 2, cy + (int) (BOX_V * 0.5f) + 2);
        fieldDirty = false;
        heightBuf.clear();
        heightBuf.put(field.heights()).flip();
        glBindTexture(GL_TEXTURE_2D, heightTex);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, PrecipitationField.SIZE, PrecipitationField.SIZE,
                GL_RED, GL_FLOAT, heightBuf);
        glBindTexture(GL_TEXTURE_2D, 0);
        uploadedOriginX = field.originX();
        uploadedOriginZ = field.originZ();
    }

    /**
     * Рисует снег и дождь. Прозрачный проход: после воды, без записи глубины.
     *
     * @param snowfall сила снегопада 0..1
     * @param rain     сила дождя 0..1
     * @param drift    пройденный воздухом путь — им смещаются частицы
     * @param storm    буря 0..1 — закрутка, скорость и позёмка
     * @param snowColor,rainColor линейный цвет с учётом освещения кадра
     */
    public void render(Matrix4f proj, Matrix4f view, Vector3f camPos, float time,
                       float windX, float windZ, com.mineclone.game.WeatherDrift drift,
                       float snowfall, float rain, float storm,
                       Vector3f snowColor, Vector3f rainColor, float linearOut) {
        render(proj, view, camPos, time, windX, windZ, drift, snowfall, rain, storm, 0,
                snowColor, rainColor, rainColor, linearOut);
    }

    public void render(Matrix4f proj, Matrix4f view, Vector3f camPos, float time,
                       float windX, float windZ, com.mineclone.game.WeatherDrift drift,
                       float snowfall, float rain, float storm, float dust,
                       Vector3f snowColor, Vector3f rainColor, Vector3f dustColor, float linearOut) {
        if ((snowfall < 0.01f && rain < 0.01f && dust < 0.01f) || uploadedOriginX == Integer.MIN_VALUE)
            return;
        viewProj.set(proj).mul(view);
        // Оси камеры из матрицы вида: строки её вращательной части.
        cameraRight.set(view.m00(), view.m10(), view.m20());
        cameraUp.set(view.m01(), view.m11(), view.m21());

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(false);
        glDisable(GL_CULL_FACE);
        shader.bind();
        shader.setMat4("uViewProj", viewProj);
        shader.setVec3("uCamPos", camPos);
        shader.setVec3("uCamRight", cameraRight);
        shader.setVec3("uCamUp", cameraUp);
        shader.setFloat("uTime", time);
        shader.setVec2("uWind", windX, windZ);
        // Положение частицы считается по пройденному пути: ветер пульсирует
        // порывами, и «ветер × время» качало бы весь снегопад разом.
        shader.setVec2("uDrift", drift.x, drift.z);
        shader.setVec2("uDriftSnow", drift.snowX, drift.snowZ);
        shader.setFloat("uFallSnow", drift.snowFall);
        shader.setFloat("uFallRain", drift.rainFall);
        shader.setFloat("uStorm", storm);
        shader.setVec3("uBox", BOX_H, BOX_V, BOX_H);
        shader.setInt("uHeight", HEIGHT_UNIT);
        shader.setVec2("uHeightOrigin", uploadedOriginX, uploadedOriginZ);
        shader.setFloat("uHeightSize", PrecipitationField.SIZE);
        shader.setFloat("uLinearOut", linearOut);
        glActiveTexture(GL_TEXTURE0 + HEIGHT_UNIT);
        glBindTexture(GL_TEXTURE_2D, heightTex);
        glActiveTexture(GL_TEXTURE0);
        glBindVertexArray(vao);
        shader.setFloat("uDust", 0f);

        if (snowfall >= 0.01f) {
            int count = (int) (SNOW_MAX * Math.min(1f, snowfall));
            shader.setFloat("uSnow", 1f);
            shader.setVec3("uColor", snowColor);
            shader.setFloat("uAlpha", 0.9f);
            glDrawArraysInstanced(GL_TRIANGLES, 0, 6, count);
        }
        if (rain >= 0.01f) {
            int count = (int) (RAIN_MAX * Math.min(1f, rain) * (1f + Math.min(1f, storm) * 0.4f));
            shader.setFloat("uSnow", 0f);
            shader.setVec3("uColor", rainColor);
            shader.setFloat("uAlpha", 0.55f + storm * 0.12f);
            glDrawArraysInstanced(GL_TRIANGLES, 0, 6, count);
        }

        if (dust >= 0.01f) {
            shader.setFloat("uSnow", 0f);
            shader.setFloat("uDust", 1f);
            shader.setVec3("uColor", dustColor);
            shader.setFloat("uAlpha", 0.60f);
            glDrawArraysInstanced(GL_TRIANGLES, 0, 6, (int) (DUST_MAX * Math.min(1, dust)));
        }
        // Larger low-opacity wisps add depth between nearby grains/drops and the distant fog.
        float veil = Math.max(dust, rain * storm * 0.70f);
        if (veil >= 0.01f) {
            shader.setFloat("uSnow", 0f);
            shader.setFloat("uDust", 2f);
            shader.setVec3("uColor", dust > 0.01f ? dustColor : rainColor);
            shader.setFloat("uAlpha", dust > 0.01f ? 0.24f : 0.12f);
            glDrawArraysInstanced(GL_TRIANGLES, 0, 6, (int) (VEIL_MAX * Math.min(1, veil)));
        }

        glBindVertexArray(0);
        shader.unbind();
        glDepthMask(true);
        glDisable(GL_BLEND);
        glEnable(GL_CULL_FACE);
    }

    public void destroy() {
        glDeleteBuffers(quadVbo);
        glDeleteBuffers(seedVbo);
        glDeleteVertexArrays(vao);
        glDeleteTextures(heightTex);
        MemoryUtil.memFree(heightBuf);
        shader.destroy();
    }
}

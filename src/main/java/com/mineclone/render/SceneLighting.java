package com.mineclone.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Один кадр освещения: всё, что мировые шейдеры (чанки, вода, мобы, рука)
 * должны знать про солнце, небо, туман и тени.
 *
 * Собирается один раз в {@code Game.render()} и раскладывается по программам
 * через {@link #apply(Shader)}. Держать это в одном месте важно: иначе вода,
 * мобы и рука разъезжаются по свету при первой же правке одной из них.
 */
public final class SceneLighting {

    public final Vector3f camPos = new Vector3f();
    public final Vector3f lightDir = new Vector3f(0f, 1f, 0f);
    public final Vector3f lightColor = new Vector3f(1f);
    public final Vector3f skyLight = new Vector3f(0.4f, 0.45f, 0.55f);
    public final Vector3f groundLight = new Vector3f(0.15f, 0.14f, 0.12f);
    public final Vector3f torchColor = new Vector3f(1.25f, 0.78f, 0.38f);
    public final Vector3f ambientColor = new Vector3f(0.05f, 0.055f, 0.08f);
    public final Vector3f fogColor = new Vector3f(0.55f, 0.75f, 0.95f);
    public final Vector3f fogSunColor = new Vector3f();
    public final Vector3f waterTint = new Vector3f(0.42f, 0.72f, 0.92f);

    /**
     * Источник света в руке игрока. Нулевой цвет означает «источника нет» —
     * шейдер всё равно считает функцию, но она обнуляется на первом же
     * умножении, так что отдельного флага не нужно.
     */
    /**
     * Низовой туман. Нулевая плотность выключает его целиком — отдельного
     * флага не нужно.
     */
    public final Vector3f heightFogColor = new Vector3f(0.6f, 0.65f, 0.72f);
    public float heightFogDensity = 0f;
    public float heightFogTop = 64f;
    public float heightFogDepth = 12f;

    public final Vector3f pointPos = new Vector3f();
    public final Vector3f pointColor = new Vector3f(0f, 0f, 0f);
    public float pointRadius = 1f;
    /** Ближайший цветной эмиттер — дешёвый однопробный voxel GI. */
    public final Vector3f bouncePos = new Vector3f(0f, -1000f, 0f);
    public final Vector3f bounceColor = new Vector3f();
    public float bounceRadius = 1f;

    public float fogStart = 48f;
    public float fogEnd = 96f;
    public float brightness = 1f;
    public float time = 0f;
    /** 1 — писать линейный HDR (дальше пост-обработка), 0 — тонемапить в шейдере. */
    public float linearOut = 1f;

    // ---- тени ----
    public boolean shadows = false;
    public final Matrix4f shadowMat0 = new Matrix4f();
    public final Matrix4f shadowMat1 = new Matrix4f();
    public float shadowTexel = 1f / 2048f;
    public float shadowSplit = SunLight.CASCADE_SPLIT;
    public float shadowFar = SunLight.CASCADE1_RADIUS * 0.85f;
    public float shadowBias0 = 0.04f;
    public float shadowBias1 = 0.12f;
    public float shadowStrength = 0f;
    public int shadowTaps = 1;

    /** Текстурные юниты карт теней. Атлас сидит на 0, скины мобов тоже на 0. */
    public static final int SHADOW_UNIT_0 = 4;
    public static final int SHADOW_UNIT_1 = 5;

    public void apply(Shader s) {
        s.setVec3("uCamPos", camPos);
        s.setVec3("uLightDir", lightDir);
        s.setVec3("uLightColor", lightColor);
        s.setVec3("uSkyLight", skyLight);
        s.setVec3("uGroundLight", groundLight);
        s.setVec3("uTorchColor", torchColor);
        s.setVec3("uAmbientColor", ambientColor);
        s.setVec3("uFogColor", fogColor);
        s.setVec3("uFogSunColor", fogSunColor);
        s.setFloat("uFogStart", fogStart);
        s.setFloat("uFogEnd", fogEnd);
        s.setFloat("uBrightness", brightness);
        s.setFloat("uTime", time);
        s.setFloat("uLinearOut", linearOut);
        s.setVec3("uHeightFogColor", heightFogColor);
        s.setFloat("uHeightFogDensity", heightFogDensity);
        s.setFloat("uHeightFogTop", heightFogTop);
        s.setFloat("uHeightFogDepth", heightFogDepth);
        s.setVec3("uPointPos", pointPos);
        s.setVec3("uPointColor", pointColor);
        s.setFloat("uPointRadius", pointRadius);
        s.setVec3("uBouncePos", bouncePos);
        s.setVec3("uBounceColor", bounceColor);
        s.setFloat("uBounceRadius", bounceRadius);

        s.setInt("uShadow0", SHADOW_UNIT_0);
        s.setInt("uShadow1", SHADOW_UNIT_1);
        s.setMat4("uShadowMat0", shadowMat0);
        s.setMat4("uShadowMat1", shadowMat1);
        s.setFloat("uShadowTexel", shadowTexel);
        s.setFloat("uShadowSplit", shadowSplit);
        s.setFloat("uShadowFar", shadowFar);
        s.setFloat("uShadowBias0", shadowBias0);
        s.setFloat("uShadowBias1", shadowBias1);
        s.setFloat("uShadowStrength", shadows ? shadowStrength : 0f);
        s.setInt("uShadowTaps", shadowTaps);
    }

    /**
     * Свет для вида от первого лица: фиксированный мягкий трёхчетвертной
     * источник, без теней и тумана, яркость целиком задаётся уровнем света в
     * точке игрока. Рука и предмет в ней светятся одинаково именно потому,
     * что берут одну и ту же настройку.
     *
     * @param lightLevel 0..1 — max(небо·день, блочный свет) там, где стоит игрок
     */
    public static SceneLighting firstPerson(float brightness, float lightLevel) {
        SceneLighting l = new SceneLighting();
        float k = 0.18f + 0.82f * clamp01(lightLevel);
        l.camPos.set(0f, 0f, 0f);
        l.lightDir.set(-0.35f, 0.80f, 0.49f).normalize();
        l.lightColor.set(1.15f, 1.10f, 1.00f).mul(k);
        l.skyLight.set(0.32f, 0.35f, 0.43f).mul(k);
        l.groundLight.set(0.15f, 0.14f, 0.12f).mul(k);
        l.torchColor.set(0f, 0f, 0f);
        l.ambientColor.set(0.07f, 0.07f, 0.08f).mul(0.35f + 0.65f * k);
        l.fogStart = 400f;
        l.fogEnd = 500f;
        l.brightness = brightness;
        l.shadows = false;
        return l;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}

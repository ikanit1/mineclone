package com.mineclone.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Математика небесного светила и его каскадов теней. Ни одного GL-вызова —
 * поэтому всё покрывается обычными тестами без окна.
 *
 * Орбита совпадает с {@link SkyRenderer}: солнце ходит в плоскости Y-Z,
 * gameTime = pi/2 — полдень.
 */
public final class SunLight {
    private SunLight() {}

    /**
     * Минимальная высота источника для проекции теней. На точном горизонте
     * тень вытягивается в бесконечность и каскад перестаёт что-либо накрывать,
     * поэтому свет приподнимается — в этот момент тени всё равно уже погашены
     * {@link #shadowStrength}.
     */
    public static final float MIN_ELEVATION = 0.14f;

    /** Дистанция стыка ближнего и дальнего каскадов, в блоках. */
    public static final float CASCADE_SPLIT = 30f;
    /** Радиус ближнего каскада (резкая тень рядом с игроком). */
    public static final float CASCADE0_RADIUS = 40f;
    /** Радиус дальнего каскада. */
    public static final float CASCADE1_RADIUS = 110f;
    /** Как далеко от центра каскада стоит «глаз» света. */
    private static final float LIGHT_DISTANCE = 190f;

    /** Направление НА солнце. */
    public static Vector3f sunDirection(float gameTime) {
        return new Vector3f(0f, (float) Math.sin(gameTime), (float) -Math.cos(gameTime)).normalize();
    }

    /**
     * Направление на доминирующее светило: днём солнце, ночью луна.
     * Высота подрезана снизу — см. {@link #MIN_ELEVATION}.
     */
    public static Vector3f lightDirection(float gameTime) {
        Vector3f d = sunDirection(gameTime);
        if (d.y < 0f)
            d.negate();
        if (d.y < MIN_ELEVATION) {
            // Поднимаем светило, ужимая горизонтальную часть — тогда длина
            // остаётся единичной и высота ровно MIN_ELEVATION (простой
            // normalize() после d.y = MIN съел бы её обратно).
            float h = (float) Math.sqrt(d.x * d.x + d.z * d.z);
            if (h > 1e-5f) {
                float scale = (float) Math.sqrt(1.0 - MIN_ELEVATION * MIN_ELEVATION) / h;
                d.x *= scale;
                d.z *= scale;
            }
            d.y = MIN_ELEVATION;
        }
        return d;
    }

    /**
     * Сила теней 0..1. На горизонте — ноль: там светило меняется с солнца на
     * луну и направление тени скачком разворачивается, этот скачок надо
     * проглотить, пока теней не видно.
     */
    public static float shadowStrength(float gameTime) {
        float elevation = Math.abs((float) Math.sin(gameTime));
        return clamp((elevation - 0.06f) / 0.22f) * 0.88f;
    }

    /**
      * HDR-яркость светила: закат оранжевый, полдень белый, ночь синяя.
      *
      * Тёплый низкий свет держится долго — белым солнце становится только
      * заметно выше 40 градусов. Раньше переход был вдвое быстрее, и закатный
      * кадр выходил с оранжевым небом, но стерильно белой землёй.
      */
    public static Vector3f lightColor(float gameTime) {
        float y = (float) Math.sin(gameTime);
        if (y > 0f) {
            float warmth = smoothstep(clamp(y / 0.70f));
            Vector3f sunset = new Vector3f(1.70f, 0.58f, 0.20f);
            Vector3f noon = new Vector3f(1.26f, 1.20f, 1.06f);
            return sunset.lerp(noon, warmth).mul(0.10f + 0.90f * clamp(y / 0.45f));
        }
        float t = clamp(-y / 0.30f);
        return new Vector3f(0.13f, 0.17f, 0.31f).mul(0.30f + 0.70f * t);
    }

    /** Верхняя полусфера ambient — «свет неба». */
    public static Vector3f skyAmbient(Vector3f skyColor, float daylight) {
        return new Vector3f(skyColor).mul(0.26f + 0.48f * daylight).add(0.012f, 0.016f, 0.030f);
    }

    /** Нижняя полусфера ambient — отражение от земли, всегда теплее и глуше. */
    public static Vector3f groundAmbient(Vector3f skyAmbient) {
        return new Vector3f(skyAmbient).mul(0.42f).add(0.014f, 0.012f, 0.008f);
    }

    /**
     * Матрица light-space для одного каскада.
     *
     * Центр прибивается к сетке текселей: без этого карта «плывёт» при каждом
     * шаге игрока и края теней кипят.
     *
     * @param lightDir направление НА источник (единичное)
     * @param center   центр области, которую накрывает каскад
     * @param radius   половина стороны ортографического бокса, в блоках
     * @param mapSize  разрешение карты теней в текселях
     */
    public static Matrix4f cascadeMatrix(Vector3f lightDir, Vector3f center, float radius, int mapSize) {
        float ux = 0f, uy = 1f, uz = 0f;
        if (Math.abs(lightDir.y) > 0.995f) { uy = 0f; uz = 1f; }
        Matrix4f m = new Matrix4f()
                .ortho(-radius, radius, -radius, radius, 1f, LIGHT_DISTANCE * 2f)
                .lookAt(center.x + lightDir.x * LIGHT_DISTANCE,
                        center.y + lightDir.y * LIGHT_DISTANCE,
                        center.z + lightDir.z * LIGHT_DISTANCE,
                        center.x, center.y, center.z,
                        ux, uy, uz);

        Vector4f p = m.transform(new Vector4f(center.x, center.y, center.z, 1f));
        float texel = 2f / mapSize;
        float ox = Math.round(p.x / texel) * texel - p.x;
        float oy = Math.round(p.y / texel) * texel - p.y;
        return new Matrix4f().translation(ox, oy, 0f).mul(m);
    }

    /** Мировой размер одного текселя каскада — база для normal-bias. */
    public static float texelWorldSize(float radius, int mapSize) {
        return 2f * radius / mapSize;
    }

    private static float clamp(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static float smoothstep(float t) {
        return t * t * (3f - 2f * t);
    }
}

package com.mineclone.render;

import org.joml.Vector3f;

/**
 * Палитра кадра: небо, горизонт, земля под горизонтом, цвет светила,
 * полусферный ambient.
 *
 * <p>Одна функция на игру и фон меню. Раньше формула жила прямо в
 * {@code Game.render}, и живому фону меню пришлось бы переписать её второй
 * раз — а две копии палитры рано или поздно спорят о цвете неба. Вынос бит в
 * бит повторяет прежнюю математику, это закреплено тестом.
 *
 * <p>Векторы пересоздаются на каждый {@link #compute}: потребители кадра
 * вольны держать ссылки, а прежний код и так аллоцировал их каждый кадр.
 */
public final class SkyPalette {

    public Vector3f skySrgb = new Vector3f();
    public Vector3f skyLin = new Vector3f();
    public Vector3f zenith = new Vector3f();
    public Vector3f horizon = new Vector3f();
    public Vector3f ground = new Vector3f();
    public Vector3f sunDir = new Vector3f();
    public Vector3f lightDir = new Vector3f();
    public Vector3f lightCol = new Vector3f();
    public Vector3f skyAmb = new Vector3f();
    public Vector3f groundAmb = new Vector3f();
    public Vector3f sunGlow = new Vector3f();
    /** Множитель света ночью по фазе луны; днём единица. */
    public float moonK = 1f;

    /**
     * @param clouds    облачность 0..1
     * @param storm     сила бури 0..1
     * @param moonlight свет луны в эту фазу ({@code NightSky.moonlight})
     * @param aurora    яркость сияния 0..1
     */
    public void compute(float gameTime, float daylight, float clouds, float storm,
                        float moonlight, float aurora) {
        skySrgb = skyColor(daylight);
        skySrgb.lerp(new Vector3f(0.32f, 0.36f, 0.42f).mul(0.15f + daylight * 0.85f), clouds * 0.62f);
        skyLin = linear(skySrgb);
        zenith = new Vector3f(skyLin).mul(0.70f).add(0.000f, 0.004f, 0.024f);
        horizon = new Vector3f(skyLin).mul(1.32f);
        ground = new Vector3f(skyLin).mul(0.30f).add(0.012f, 0.010f, 0.008f);

        sunDir = SunLight.sunDirection(gameTime);
        lightDir = SunLight.lightDirection(gameTime);
        // Ночью светит луна — и светит по фазе: в новолуние заметно темнее.
        boolean moonUp = Math.sin(gameTime) <= 0.0;
        moonK = moonUp ? moonlight : 1f;
        lightCol = SunLight.lightColor(gameTime).mul((1f - clouds * 0.72f - storm * 0.10f) * moonK);
        // Под тучами темнеет и рассеянный свет, не только солнце: иначе в грозу
        // трава горит тем же дневным зелёным.
        skyAmb = SunLight.skyAmbient(skyLin, daylight)
                .mul(daylight + (1f - daylight) * (0.62f + 0.38f * moonlight))
                .mul(1f - clouds * 0.12f - storm * 0.08f);
        // Сияние чуть подкрашивает снег и землю под собой — иначе оно висит
        // картинкой на небе, не касаясь мира.
        skyAmb.add(0.006f * aurora, 0.034f * aurora, 0.018f * aurora);
        groundAmb = SunLight.groundAmbient(skyAmb);
        sunGlow = new Vector3f(lightCol).mul(daylight > 0.02f ? 0.85f : 0.30f);
    }

    /** Цвет неба по дневному свету: ночь → закатный горизонт → день. */
    public static Vector3f skyColor(float d) {
        float[] night = { 0.02f, 0.03f, 0.08f };
        float[] horizon = { 0.85f, 0.45f, 0.20f };
        float[] day = { 0.55f, 0.75f, 0.95f };
        float[] a, b;
        float t;
        if (d < 0.3f) {
            a = night;
            b = horizon;
            t = d / 0.3f;
        } else {
            a = horizon;
            b = day;
            t = (d - 0.3f) / 0.7f;
        }
        return new Vector3f(a[0] + (b[0] - a[0]) * t,
                a[1] + (b[1] - a[1]) * t,
                a[2] + (b[2] - a[2]) * t);
    }

    public static Vector3f linear(Vector3f srgb) {
        return new Vector3f((float) Math.pow(srgb.x, 2.2), (float) Math.pow(srgb.y, 2.2),
                (float) Math.pow(srgb.z, 2.2));
    }
}

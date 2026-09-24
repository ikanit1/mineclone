package com.mineclone.game;

import com.mineclone.world.Biome;
import com.mineclone.world.NightSky;
import com.mineclone.world.Weather;
import com.mineclone.world.World;
import org.joml.Vector3f;

/**
 * Погода и ночное небо там, где стоит игрок, — на этот кадр.
 *
 * {@link Weather} и {@link NightSky} отвечают на вопрос «какая погода в
 * мире»; этот класс — на вопрос «что видит и слышит игрок»: в пустыне дождя
 * нет, в тундре тот же фронт идёт снегом, в пещере не видно сияния. Всё
 * местное сглаживается по времени: переход из леса в пустыню под ливнем не
 * должен выключать дождь на границе биома, как рубильником.
 *
 * Без GL — Game только раскладывает числа по шейдерам и звукам.
 */
final class Atmosphere {

    /** Постоянная времени местного сглаживания, секунды. */
    private static final float LOCAL_SMOOTH = 2.5f;
    /** Видимость меняется медленнее: туман, который мигает, выглядит багом. */
    private static final float VISIBILITY_SMOOTH = 4f;

    /** Фронт в мире — одинаков для всех точек. */
    Weather.State global = Weather.sample(0L, 0f);
    /** Осадки в точке игрока 0..1: ноль в пустыне. */
    float precipitation;
    /** Доля снега в осадках 0..1. */
    float snow;
    /** Сила бури в точке 0..1. */
    float storm;
    /** Suspended sand in desert and badlands fronts. Smoothed at biome boundaries. */
    float dust;
    /** Облачность 0..1 — общая: небо над пустыней тоже затянуто. */
    float cloudiness;
    /** Множитель дальности тумана 0.08..1. */
    float visibility = 1f;
    /** Ветер, блоков в секунду. */
    float windX, windZ;
    /**
     * Пройденный воздухом путь.
     *
     * <p>Осадки и туман смещаются им, а не произведением «ветер × время»:
     * ветер пульсирует порывами, и такое произведение качало картину целиком.
     */
    final WeatherDrift drift = new WeatherDrift();
    int moonPhase;
    float moonlight = 1f;
    /** Яркость сияния 0..1 в точке игрока. */
    float aurora;
    /** Плотность низового тумана у земли и дневной дымки — сглаженные. */
    float mist, haze;
    private boolean primed;
    /** Погода, заказанная командой, и сколько ещё секунд она держится. */
    private Weather.Kind forced;
    private float forcedLeft;

    /**
     * Заказать погоду на время. Переход всё равно плавный — через то же
     * местное сглаживание, что и у фронтов.
     */
    void force(Weather.Kind kind, float seconds) {
        forced = kind;
        forcedLeft = seconds;
    }

    /**
     * @param clock игровое время в секундах — по нему идут фронты
     */
    void update(float dt, World world, float gameTime, float clock, Vector3f pos) {
        if (forced != null && (forcedLeft -= dt) <= 0f)
            forced = null;
        global = forced != null
                ? new Weather.State(forced, forced.precipitation, forced.cloudiness, forced.wind, forced.storm)
                : Weather.sample(world.seed, clock);
        Biome biome = world.biomes.biomeAt((int) Math.floor(pos.x), (int) Math.floor(pos.z));
        boolean wet = Weather.precipitates(biome);
        boolean snowing = Weather.snowsAt(biome, pos.y);

        float targetPrecip = wet ? global.precipitation() : 0f;
        float targetDust = Weather.dust(biome, global.storm());
        float targetStorm = wet || targetDust > 0 ? global.storm() : global.storm() * 0.35f;
        float targetSnow = snowing ? 1f : 0f;
        float targetVis = Weather.visibility(targetPrecip, targetStorm, snowing, targetDust);
        float targetAurora = NightSky.auroraStrength(world.seed, gameTime, biome, global.cloudiness());

        float daylight = Math.max(0f, (float) Math.sin(gameTime));
        float targetMist = Mist.groundDensity(gameTime, daylight, targetPrecip, biome)
                + targetStorm * 0.018f + targetDust * 0.035f;
        float targetHaze = Mist.haze(targetPrecip, biome) + targetStorm * 0.008f + targetDust * 0.025f;

        float k = primed ? 1f - (float) Math.exp(-dt / LOCAL_SMOOTH) : 1f;
        float kv = primed ? 1f - (float) Math.exp(-dt / VISIBILITY_SMOOTH) : 1f;
        mist += (targetMist - mist) * kv;
        haze += (targetHaze - haze) * kv;
        precipitation += (targetPrecip - precipitation) * k;
        storm += (targetStorm - storm) * k;
        dust += (targetDust - dust) * k;
        snow += (targetSnow - snow) * k;
        visibility += (targetVis - visibility) * kv;
        aurora += (targetAurora - aurora) * k;
        cloudiness += (global.cloudiness() - cloudiness) * k;
        primed = true;

        float[] w = Weather.wind(world.seed, clock);
        // Направление и порывы берём у графика, силу — у текущей погоды:
        // заказанная буря обязана дуть как буря.
        float scheduled = Weather.sample(world.seed, clock).wind();
        float scale = scheduled > 1e-3f ? global.wind() / scheduled : 1f;
        windX = w[0] * scale;
        windZ = w[1] * scale;
        drift.advance(dt, windX, windZ, storm);
        moonPhase = NightSky.moonPhase(gameTime);
        moonlight = NightSky.moonlight(moonPhase);
    }

    /** Сразу к установившимся значениям — после загрузки мира и телепорта. */
    void snap() {
        primed = false;
        drift.reset();
    }

    /** Дождь в точке 0..1. */
    float rain() {
        return precipitation * (1f - snow);
    }

    /** Снегопад в точке 0..1. */
    float snowfall() {
        return precipitation * snow;
    }

    /** Метель 0..1: буря, которая идёт снегом. */
    float blizzard() {
        return storm * snow;
    }

    /** Dry sandstorms and blizzards have wind, but no rain lightning or thunder. */
    float thunderstorm() { return storm * rain(); }

    /** Сила ветра, блоков в секунду. */
    float windSpeed() {
        return (float) Math.sqrt(windX * windX + windZ * windZ);
    }
}

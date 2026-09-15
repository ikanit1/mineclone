package com.mineclone.game;

import com.mineclone.render.Camera;
import com.mineclone.render.Mesh;
import com.mineclone.render.SceneLighting;
import com.mineclone.render.Shader;
import com.mineclone.render.TextureAtlas;
import com.mineclone.save.SaveFormat;
import com.mineclone.world.Chunk;
import com.mineclone.world.ChunkLoader;
import com.mineclone.world.ChunkMesher;
import com.mineclone.world.NightSky;
import com.mineclone.world.Weather;
import com.mineclone.world.World;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL11.*;

/**
 * Живой фон главного меню: свой маленький мир, облёт камерой, свои сутки.
 *
 * <p>Сутки проходят за три минуты — день две, ночь одна: полторы минуты
 * темноты под меню это уже плохо видные кнопки, а не атмосфера. Погода, фаза
 * луны и сияние берутся из тех же {@link Weather} и {@link NightSky}, что в
 * игре. Это дёшево именно потому, что обе — чистые функции от сида и времени:
 * фону не нужен ни игровой мир, ни сохранение, достаточно подать своё время.
 *
 * <p>Игровой мир фон не трогает: загрузка сейва и возврат в меню оставляют его
 * как был. Радиус — три чанка: фон не должен отъедать кадры у меню.
 */
public final class MenuBackground {
    public static final int RADIUS = 3;             // chunks around orbit center
    private static final float ORBIT_RADIUS = 28f;   // world units
    private static final float ORBIT_SPEED  = 0.05f; // radians / sec
    private static final float CAM_OFFSET_Y = 14f;   // above sampled terrain
    private static final float CAM_PITCH    = 0.35f; // rad, looking down

    /** Сколько реальных секунд длится день фона и сколько ночь. */
    static final float DAY_SECONDS = 120f, NIGHT_SECONDS = 60f;
    /** Во сколько раз быстрее реального времени идут погодные фронты фона. */
    static final float WEATHER_SPEED = 2f;
    /** Облачность фона не выше этого: хмурое небо без дождя выглядит пустым. */
    static final float MAX_CLOUDS = 0.8f;
    /** С чего начинается фон: утро, солнце невысоко над горизонтом. */
    static final float START_TIME = 0.55f;

    /** Orbit center in world coords. Placed mid-chunk (0,0) so 3-chunk radius covers it. */
    private static final float CENTER_X = Chunk.SIZE_X * 0.5f;
    private static final float CENTER_Z = Chunk.SIZE_Z * 0.5f;

    private final World world;
    private final ChunkMesher mesher;
    private final ChunkLoader loader;
    private final Map<Long, Mesh> meshes = new HashMap<>();
    private final Map<Long, Mesh> waterMeshes = new HashMap<>();
    private final Camera camera = new Camera();
    private final Matrix4f model = new Matrix4f();
    private float angle = 0f;

    private float gameTime = START_TIME;
    private float clock;
    private float cloudiness, storm, windX, windZ, moonlight = 1f, aurora;
    private int moonPhase;

    public MenuBackground(com.mineclone.save.SaveManager save) {
        this.world = new World(SaveFormat.MENU_SEED);
        this.mesher = new ChunkMesher(world);
        // Sentinel world id so any rogue save-on-modified call cannot collide
        // with the player's "world" directory. The menu never edits blocks,
        // so this directory should never be created in practice.
        this.loader = new ChunkLoader(world, mesher, save, "__menu__");
    }

    /**
     * Время суток фона через dt секунд. Днём (солнце над горизонтом) полоборота
     * идёт {@link #DAY_SECONDS}, ночью — {@link #NIGHT_SECONDS}.
     */
    public static float advance(float gameTime, float dt) {
        double speed = Math.sin(gameTime) > 0.0 ? Math.PI / DAY_SECONDS : Math.PI / NIGHT_SECONDS;
        return (float) (gameTime + dt * speed);
    }

    /** Schedule generation, advance camera, time of day and weather. Call once per frame. */
    public void update(float dt) {
        angle += dt * ORBIT_SPEED;
        clock += dt;
        gameTime = advance(gameTime, dt);

        float weatherClock = clock * WEATHER_SPEED;
        Weather.State w = Weather.sample(SaveFormat.MENU_SEED, weatherClock);
        cloudiness = Math.min(MAX_CLOUDS, w.cloudiness());
        storm = w.storm() * 0.5f;
        float[] wind = Weather.wind(SaveFormat.MENU_SEED, weatherClock);
        windX = wind[0];
        windZ = wind[1];
        moonPhase = NightSky.moonPhase(gameTime);
        moonlight = NightSky.moonlight(moonPhase);
        aurora = NightSky.auroraStrength(SaveFormat.MENU_SEED, gameTime,
                world.biomes.biomeAt((int) CENTER_X, (int) CENTER_Z), cloudiness);

        // Preload 3x3 around the orbit center if missing — synchronously so the
        // very first menu frame already shows terrain instead of empty sky.
        int pcx = (int) Math.floor(CENTER_X / Chunk.SIZE_X);
        int pcz = (int) Math.floor(CENTER_Z / Chunk.SIZE_Z);
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                if (world.getChunkIfExists(pcx + dx, pcz + dz) == null)
                    world.getChunk(pcx + dx, pcz + dz);  // forces synchronous generation

        loader.ensureRadius(pcx, pcz, RADIUS);
        loader.drainLightFlood(2);
        for (ChunkLoader.Ready r : loader.drainReady(3)) {
            Mesh old = meshes.remove(r.key);
            if (old != null) old.destroy();
            Mesh oldW = waterMeshes.remove(r.key);
            if (oldW != null) oldW.destroy();
            if (!r.data[0].isEmpty()) meshes.put(r.key, r.data[0].upload());
            if (!r.data[1].isEmpty()) waterMeshes.put(r.key, r.data[1].upload());
        }

        // Camera: orbit around CENTER_X,CENTER_Z at terrain-top + offset.
        float terrainY = sampleTerrainTop();
        float cx = CENTER_X + (float) Math.cos(angle) * ORBIT_RADIUS;
        float cz = CENTER_Z + (float) Math.sin(angle) * ORBIT_RADIUS;
        camera.position.set(cx, terrainY + CAM_OFFSET_Y, cz);
        camera.yaw   = (float) Math.atan2(CENTER_X - cx, -(CENTER_Z - cz));  // aim at center
        camera.pitch = CAM_PITCH;
    }

    /** Highest solid block at the orbit center column. Falls back to SEA_LEVEL+8 if no chunk. */
    private float sampleTerrainTop() {
        int bx = (int) Math.floor(CENTER_X);
        int bz = (int) Math.floor(CENTER_Z);
        for (int y = Chunk.SIZE_Y - 1; y >= 0; y--) {
            if (world.getBlock(bx, y, bz).solid)
                return y + 1f;
        }
        return World.SEA_LEVEL + 8f;
    }

    /** Позиция орбитальной камеры — нужна шейдеру для бликов и тумана. */
    public Vector3f cameraPosition() {
        return new Vector3f(camera.position);
    }

    public Matrix4f projection(float aspect, int fovDeg) {
        return camera.getProjection(aspect, fovDeg, 0.1f, 600f);
    }

    public Matrix4f view() {
        return camera.getView();
    }

    public float gameTime() { return gameTime; }

    /** Перевести сутки фона — автопилоту, чтобы снять закат и ночь без ожидания. */
    void setGameTime(float t) { gameTime = t; }
    public float daylight() { return Math.max(0f, (float) Math.sin(gameTime)); }
    public float cloudiness() { return cloudiness; }
    public float storm() { return storm; }
    public float moonlight() { return moonlight; }
    public int moonPhase() { return moonPhase; }
    public float aurora() { return aurora; }
    public float windX() { return windX; }
    public float windZ() { return windZ; }

    /**
     * Непрозрачные чанки, затем вода — тем же порядком и теми же шейдерами,
     * что в игре. Ветер у фона свой, а «толкателей» травы нет: юниформы
     * программы общие с игрой, и без явного сброса трава в меню гнулась бы
     * вокруг места, где игрок стоял перед выходом.
     */
    public void renderWorld(Shader chunkShader, Shader waterShader, TextureAtlas atlas,
                            Matrix4f proj, Matrix4f view, SceneLighting lighting) {
        chunkShader.bind();
        chunkShader.setMat4("uProjection", proj);
        chunkShader.setMat4("uView", view);
        chunkShader.setInt("uAtlas", 0);
        lighting.apply(chunkShader);
        applyWind(chunkShader);
        atlas.bind(0);
        for (Map.Entry<Long, Mesh> e : meshes.entrySet()) {
            long k = e.getKey();
            chunkShader.setMat4("uModel", model.translation((int) (k >> 32) * Chunk.SIZE_X, 0,
                    (int) (k & 0xFFFFFFFFL) * Chunk.SIZE_Z));
            e.getValue().render();
        }
        chunkShader.unbind();

        if (waterMeshes.isEmpty())
            return;
        // Вода — дальние первыми, как в игре: смешивание зависит от порядка.
        List<Long> keys = new ArrayList<>(waterMeshes.keySet());
        Vector3f eye = camera.position;
        keys.sort((a, b) -> Float.compare(distSq(b, eye), distSq(a, eye)));
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(false);
        glDisable(GL_CULL_FACE);
        waterShader.bind();
        waterShader.setMat4("uProjection", proj);
        waterShader.setMat4("uView", view);
        waterShader.setInt("uAtlas", 0);
        waterShader.setInt("uScene", 6);
        waterShader.setFloat("uSsrOn", 0f);
        lighting.apply(waterShader);
        waterShader.setVec3("uWaterTint", lighting.waterTint);
        applyWind(waterShader);
        atlas.bind(0);
        for (long k : keys) {
            waterShader.setMat4("uModel", model.translation((int) (k >> 32) * Chunk.SIZE_X, 0,
                    (int) (k & 0xFFFFFFFFL) * Chunk.SIZE_Z));
            waterMeshes.get(k).render();
        }
        waterShader.unbind();
        glDepthMask(true);
        glDisable(GL_BLEND);
        glEnable(GL_CULL_FACE);
    }

    private void applyWind(Shader s) {
        float speed = (float) Math.sqrt(windX * windX + windZ * windZ);
        s.setFloat("uWindSway", Math.min(1.8f, 0.30f + speed * 0.34f));
        if (speed > 1e-3f)
            s.setVec2("uWindDir", windX / speed, windZ / speed);
        else
            s.setVec2("uWindDir", 0.8f, 0.6f);
        s.setVec3("uInteractorPos", 0f, -1000f, 0f);
        s.setFloat("uInteractorRadius", 0f);
        s.setVec3("uMobInteractorPos", 0f, -1000f, 0f);
        s.setFloat("uMobInteractorRadius", 0f);
    }

    private static float distSq(long key, Vector3f eye) {
        float dx = (int) (key >> 32) * Chunk.SIZE_X + Chunk.SIZE_X * 0.5f - eye.x;
        float dz = (int) (key & 0xFFFFFFFFL) * Chunk.SIZE_Z + Chunk.SIZE_Z * 0.5f - eye.z;
        return dx * dx + dz * dz;
    }

    public void destroy() {
        loader.shutdown();
        for (Mesh m : meshes.values()) m.destroy();
        meshes.clear();
        for (Mesh m : waterMeshes.values()) m.destroy();
        waterMeshes.clear();
    }
}

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
import com.mineclone.world.World;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.lwjgl.opengl.GL11.*;

/**
 * Кинематограф главного меню: серия пролётов камеры над отобранными
 * пейзажами мира меню.
 *
 * <p>Пейзажи ищет {@link MenuScout} по сиду — чанки для этого не нужны, так
 * что разведка укладывается в доли секунды прямо в конструкторе, пока игра
 * ещё компилирует шейдеры. Каждый кадр — {@link MenuShot}: траектория,
 * время суток, погода и тон.
 *
 * <p><b>Кадр не выходит на экран, пока не готов.</b> Готов — значит каждый
 * чанк из {@link MenuShot#requiredChunks()} сгенерирован, смешен и выгружен
 * на видеокарту. Пока грузится следующий кадр, идёт текущий; если к своему
 * концу текущий так и не дождался следующего, он идёт обратно по той же
 * траектории — это оставляет камеру внутри уже загруженного коридора, в
 * отличие от «пролететь ещё немного вперёд». Дыре в мире взяться неоткуда.
 *
 * <p>Игровой мир фон не трогает: загрузка сейва и возврат в меню оставляют
 * его как был.
 */
public final class MenuBackground {

    /** За сколько секунд до конца кадра начинаем грузить следующий. */
    private static final float PRELOAD_LEAD = 11f;
    /** Сколько длится кроссфейд между кадрами. */
    public static final float DISSOLVE = 1.1f;
    /** Сколько чанков освобождается за кадр после смены сцены. */
    private static final int RELEASE_PER_FRAME = 6;
    /**
     * Сколько мешей поднимается на видеокарту за кадр. Пока смотреть не на
     * что — торопимся: кадру всё равно нечем заняться. Как только сцена
     * пошла, бюджет падает до двух: загрузка буфера идёт в главном потоке, и
     * предзагрузка следующего кадра не имеет права дёргать текущий.
     */
    private static final int UPLOADS_HURRY = 10, UPLOADS_CALM = 2;

    /** Один кадр в работе: что грузим, где сейчас камера. */
    private static final class Scene {
        final MenuShot shot;
        final int index;
        final long[] keys;
        final byte[] lod;
        final long[] halo;
        final Set<Long> keep;
        float t;
        int dir = 1;
        boolean overtime;

        Scene(MenuShot shot, int index, ChunkLoader loader) {
            this.shot = shot;
            this.index = index;
            Set<Long> required = shot.requiredChunks();
            this.keys = new long[required.size()];
            this.lod = new byte[required.size()];
            MenuShot.Pose mid = shot.pose(0.5f, null);
            int mcx = Math.floorDiv((int) Math.floor(mid.x), Chunk.SIZE_X);
            int mcz = Math.floorDiv((int) Math.floor(mid.z), Chunk.SIZE_Z);
            int i = 0;
            for (long k : required) {
                keys[i] = k;
                // Упрощение назначается один раз, по середине траектории, а
                // не по текущему положению камеры: иначе на ходу менялась бы
                // детализация и чанк пересобирался бы прямо в кадре.
                int d = Math.max(Math.abs(cx(k) - mcx), Math.abs(cz(k) - mcz));
                lod[i] = (byte) loader.lodForDistance(d);
                i++;
            }
            // Кольцо вокруг коридора: меш чанка требует сгенерированных
            // соседей, иначе на краю набора не построится ни один.
            Set<Long> ring = new HashSet<>();
            for (long k : required) {
                addIfNew(ring, required, cx(k) + 1, cz(k));
                addIfNew(ring, required, cx(k) - 1, cz(k));
                addIfNew(ring, required, cx(k), cz(k) + 1);
                addIfNew(ring, required, cx(k), cz(k) - 1);
            }
            this.halo = new long[ring.size()];
            int j = 0;
            for (long k : ring) halo[j++] = k;
            this.keep = new HashSet<>(required);
            this.keep.addAll(ring);
        }

        private static void addIfNew(Set<Long> ring, Set<Long> required, int cx, int cz) {
            long k = World.key(cx, cz);
            if (!required.contains(k))
                ring.add(k);
        }

        float phase() { return shot.duration() <= 0f ? 0f : t / shot.duration(); }
    }

    private final World world;
    private final ChunkMesher mesher;
    private final ChunkLoader loader;
    private final Map<Long, Mesh> meshes = new HashMap<>();
    private final Map<Long, Mesh> waterMeshes = new HashMap<>();
    /** Ключи, меш которых уже приехал на видеокарту (пустой меш — тоже готов). */
    private final Set<Long> uploaded = new HashSet<>();
    private final ArrayDeque<Long> doomed = new ArrayDeque<>();

    private final Camera camera = new Camera();
    private final Matrix4f model = new Matrix4f();
    private final MenuShot.Pose pose = new MenuShot.Pose();
    private final WeatherDrift drift = new WeatherDrift();

    private volatile List<MenuShot> shots;
    private Scene current, next;
    private float clock;
    private float timeShift;     // ход времени внутри кадра
    private float timeOverride = Float.NaN;   // автопилот: снять закат и ночь
    private float fade;          // остаток кроссфейда в секундах
    private int forced = -1;     // снимки: стоять на заказанном кадре
    private float forcedPhase;
    private int moonPhase;
    private float moonlight = 1f;

    public MenuBackground(com.mineclone.save.SaveManager save) {
        this.world = new World(SaveFormat.MENU_SEED);
        this.mesher = new ChunkMesher(world);
        // Sentinel world id so any rogue save-on-modified call cannot collide
        // with the player's "world" directory. The menu never edits blocks,
        // so this directory should never be created in practice.
        this.loader = new ChunkLoader(world, mesher, save, "__menu__");
        // Разведка идёт в своём потоке: она дешёвая, но конструктор игры и
        // так самая занятая точка запуска, а результат нужен только к первому
        // кадру меню.
        Thread t = new Thread(() -> shots = MenuScout.shots(world), "mineclone-menu-scout");
        t.setDaemon(true);
        t.start();
    }

    // ---------------- обновление ----------------

    /** Планирует чанки, двигает камеру, время суток и погоду. Раз в кадр. */
    public void update(float dt) {
        clock += dt;
        if (fade > 0f)
            fade = Math.max(0f, fade - dt);
        List<MenuShot> list = shots;
        if (list == null || list.isEmpty())
            return;
        if (current == null && next == null)
            next = new Scene(list.get(0), 0, loader);

        advance(dt, list);
        stream();
        release();

        if (current == null)
            return;
        MenuShot.Air air = current.shot.air;
        drift.advance(dt, air.windX(), air.windZ(), air.storm());
        float t = gameTime();
        moonPhase = NightSky.moonPhase(t);
        moonlight = NightSky.moonlight(moonPhase);

        current.shot.pose(current.phase(), pose);
        camera.position.set(pose.x, pose.y, pose.z);
        camera.yaw = pose.yaw;
        camera.pitch = pose.pitch;
    }

    /** Двигает текущий кадр и решает, пора ли меняться. */
    private void advance(float dt, List<MenuShot> list) {
        if (forced >= 0) {
            advanceForced(list);
            return;
        }
        if (current == null) {
            if (next != null && ready(next))
                adopt(next, false);
            return;
        }
        float dur = current.shot.duration();
        timeShift += dt * current.shot.air.timeRate();
        current.t += dt * current.dir;
        if (current.t >= dur) {
            // Кадр кончился. Следующий готов — меняемся; не готов — идём той
            // же траекторией обратно: это оставляет камеру внутри уже
            // загруженного коридора, в отличие от «пролететь ещё немного».
            current.t = dur;
            current.dir = -1;
            current.overtime = true;
        } else if (current.t <= 0f) {
            current.t = 0f;
            current.dir = 1;
        }
        int following = (current.index + 1) % list.size();
        if (next == null && list.size() > 1 && current.t >= dur - PRELOAD_LEAD)
            next = new Scene(list.get(following), following, loader);
        if (current.overtime && next != null && ready(next))
            adopt(next, true);
    }

    /** Режим снимков: стоим на заказанном кадре в заказанной точке. */
    private void advanceForced(List<MenuShot> list) {
        int idx = Math.floorMod(forced, list.size());
        if (current == null || current.index != idx) {
            if (next == null || next.index != idx)
                next = new Scene(list.get(idx), idx, loader);
            if (ready(next))
                adopt(next, false);
        }
        if (current != null)
            current.t = forcedPhase * current.shot.duration();
    }

    /**
     * Принять кадр как текущий. Всё, что ему не нужно, уходит в очередь на
     * освобождение — не залпом: уничтожить полторы сотни буферов в одном
     * кадре это рывок на ровном месте, а кроссфейд длится секунду.
     */
    private void adopt(Scene s, boolean dissolve) {
        current = s;
        next = null;
        current.t = 0f;
        current.dir = 1;
        current.overtime = false;
        timeShift = 0f;
        fade = dissolve ? DISSOLVE : 0f;
        drift.reset();
        for (Chunk c : world.getLoadedChunks()) {
            long k = World.key(c.cx, c.cz);
            if (!current.keep.contains(k))
                doomed.add(k);
        }
    }

    private boolean ready(Scene s) {
        return missing(s) == 0;
    }

    private int missing(Scene s) {
        int n = 0;
        for (long k : s.keys)
            if (!uploaded.contains(k))
                n++;
        return n;
    }

    private void stream() {
        streamScene(current);
        streamScene(next);
        boolean hurry = current == null;
        loader.drainLightFlood(hurry ? 6 : 2);
        for (ChunkLoader.Ready r : loader.drainReady(hurry ? UPLOADS_HURRY : UPLOADS_CALM)) {
            Mesh old = meshes.remove(r.key);
            if (old != null) old.destroy();
            Mesh oldW = waterMeshes.remove(r.key);
            if (oldW != null) oldW.destroy();
            if (!r.data[0].isEmpty()) meshes.put(r.key, r.data[0].upload());
            if (!r.data[1].isEmpty()) waterMeshes.put(r.key, r.data[1].upload());
            // Пустой чанк тоже готов: рисовать нечего, ждать нечего.
            uploaded.add(r.key);
        }
    }

    private void streamScene(Scene s) {
        if (s == null)
            return;
        for (int i = 0; i < s.keys.length; i++) {
            long k = s.keys[i];
            if (!uploaded.contains(k))
                loader.ensureChunk(cx(k), cz(k), s.lod[i], true);
        }
        for (long k : s.halo)
            loader.ensureChunk(cx(k), cz(k), -1, false);
    }

    private void release() {
        for (int i = 0; i < RELEASE_PER_FRAME && !doomed.isEmpty(); i++)
            releaseOne();
    }

    private void releaseOne() {
        long k = doomed.poll();
        if (needed(k))
            return;
        Mesh m = meshes.remove(k);
        if (m != null) m.destroy();
        Mesh w = waterMeshes.remove(k);
        if (w != null) w.destroy();
        uploaded.remove(k);
        loader.forget(k);
        Chunk c = world.removeChunk(cx(k), cz(k));
        if (c != null) c.forgetUploadedMesh();
    }

    /**
     * Отпустить всё, что не нужно идущему кадру, — разом и сейчас.
     *
     * <p>Зовётся, когда игра уходит из меню в мир: коридор пролёта вдвое
     * больше прежнего мирка меню, и держать в памяти ещё и предзагруженный
     * следующий кадр всю партию незачем. Разом, а не по нескольку за кадр,
     * потому что дальше меню всё равно не обновляется, а момент перехода и
     * так закрыт экраном загрузки.
     */
    public void trim() {
        next = null;
        for (Chunk c : world.getLoadedChunks()) {
            long k = World.key(c.cx, c.cz);
            if (current == null || !current.keep.contains(k))
                doomed.add(k);
        }
        while (!doomed.isEmpty())
            releaseOne();
    }

    private boolean needed(long key) {
        return (current != null && current.keep.contains(key))
                || (next != null && next.keep.contains(key));
    }

    private static int cx(long key) { return (int) (key >> 32); }
    private static int cz(long key) { return (int) key; }

    // ---------------- что знает о кадре игра ----------------

    /** Есть ли готовый кадр. Пока нет — рисуется только небо. */
    public boolean hasScene() { return current != null; }

    /** Текущий кадр или {@code null}, пока первый ещё грузится. */
    public MenuShot shot() { return current == null ? null : current.shot; }

    /** Найденные пейзажи, или {@code null}, пока разведка не кончилась. */
    public List<MenuShot> shots() { return shots; }

    /** Номер идущего кадра, или −1. */
    public int shotIndex() { return current == null ? -1 : current.index; }

    /**
     * Встать на кадр {@code index} в точке {@code phase} и держать его —
     * снимкам и автопилоту. Ждать по двадцать секунд каждого пролёта, чтобы
     * снять пятый, нельзя. Отрицательный индекс возвращает обычный ход.
     */
    public void previewShot(int index, float phase) {
        forced = index;
        forcedPhase = Math.max(0f, Math.min(1f, phase));
    }

    /**
     * Сколько чанков идущего кадра ещё не приехало на видеокарту.
     *
     * <p>Здесь и живёт вся гарантия «без пропавших чанков»: показанный кадр
     * обязан отдавать ноль в любой момент. Проверяется автопилотом в
     * настоящей игре — офлайновому тесту видно только математику.
     */
    public int missingChunks() {
        return current == null ? 0 : missing(current);
    }

    /** Воздух текущего кадра; до первого кадра — воздух первого в списке. */
    public MenuShot.Air air() {
        if (current != null)
            return current.shot.air;
        List<MenuShot> list = shots;
        return list == null || list.isEmpty() ? FALLBACK_AIR : list.get(0).air;
    }

    private static final MenuShot.Air FALLBACK_AIR = new MenuShot.Air(
            0.55f, 0f, 0.2f, 0f, 0f, 0f, 1f, 0.5f, 0.004f, 0.0012f,
            0.98f, 1.02f, 0f, 40f, 24f, 0f);

    /** Остаток кроссфейда в долях: 1 — только что сменили кадр, 0 — давно. */
    public float dissolve() { return DISSOLVE <= 0f ? 0f : fade / DISSOLVE; }

    /**
     * Стоит ли снимать этот кадр для кроссфейда.
     *
     * <p>Снимок берётся не в момент готовности следующего кадра, а за
     * несколько кадров до неё: подмена происходит в обновлении, а снимок — в
     * отрисовке, и «снять ровно когда готов» означало бы, что при готовности
     * посреди пинг-понга снимать нечего и наплыв вырождается в склейку.
     * За кадр приезжает не больше двух мешей, поэтому дюжина недостающих —
     * это заведомо больше пяти кадров запаса.
     */
    public boolean armDissolve() {
        return fade <= 0f && current != null && next != null && missing(next) <= DISSOLVE_ARM;
    }

    /** Насколько близко к готовности должен быть следующий кадр, чтобы снимать. */
    private static final int DISSOLVE_ARM = 12;

    public World world() { return world; }
    public WeatherDrift drift() { return drift; }

    /** Позиция камеры — нужна шейдеру для бликов и тумана. */
    public Vector3f cameraPosition() {
        return new Vector3f(camera.position);
    }

    public Matrix4f projection(float aspect) {
        return camera.getProjection(aspect, fovDeg(), 0.1f, 600f);
    }

    public float fovDeg() {
        MenuShot s = shot();
        return s == null ? 70f : s.fovDeg();
    }

    public Matrix4f view() {
        return camera.getView();
    }

    /** Время суток кадра: назначенное кадром плюс его медленный ход. */
    public float gameTime() {
        if (!Float.isNaN(timeOverride))
            return timeOverride;
        return air().gameTime() + timeShift;
    }

    /** Перевести сутки фона — автопилоту, чтобы снять закат и ночь без ожидания. */
    void setGameTime(float t) { timeOverride = t; }

    public float daylight() { return Math.max(0f, (float) Math.sin(gameTime())); }
    public float cloudiness() { return air().cloudiness(); }
    public float storm() { return air().storm(); }
    public float rain() { return air().rain(); }
    public float snow() { return air().snow(); }
    public float moonlight() { return moonlight; }
    public int moonPhase() { return moonPhase; }
    public float aurora() { return air().aurora(); }
    public float windX() { return air().windX(); }
    public float windZ() { return air().windZ(); }
    public float clock() { return clock; }

    // ---------------- отрисовка ----------------

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
            chunkShader.setMat4("uModel", model.translation(cx(k) * Chunk.SIZE_X, 0,
                    cz(k) * Chunk.SIZE_Z));
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
            waterShader.setMat4("uModel", model.translation(cx(k) * Chunk.SIZE_X, 0,
                    cz(k) * Chunk.SIZE_Z));
            waterMeshes.get(k).render();
        }
        waterShader.unbind();
        glDepthMask(true);
        glDisable(GL_BLEND);
        glEnable(GL_CULL_FACE);
    }

    /**
     * Непрозрачные чанки в карту теней. Матрицы каскада ставит вызывающий —
     * здесь только модель и геометрия, как в теневом проходе игры.
     *
     * @param frustum ортографический фрустум каскада, или {@code null}
     */
    public void renderDepth(Shader shadowShader, org.joml.FrustumIntersection frustum) {
        for (Map.Entry<Long, Mesh> e : meshes.entrySet()) {
            long k = e.getKey();
            float wx = cx(k) * Chunk.SIZE_X, wz = cz(k) * Chunk.SIZE_Z;
            if (frustum != null && !frustum.testAab(wx, 0, wz,
                    wx + Chunk.SIZE_X, Chunk.SIZE_Y, wz + Chunk.SIZE_Z))
                continue;
            shadowShader.setMat4("uModel", model.translation(wx, 0, wz));
            e.getValue().render();
        }
    }

    /** Направление взгляда камеры — карте теней, чтобы поставить каскады. */
    public Vector3f cameraForward() {
        return camera.forward();
    }

    private void applyWind(Shader s) {
        float wx = windX(), wz = windZ();
        float speed = (float) Math.sqrt(wx * wx + wz * wz);
        s.setFloat("uWindSway", Math.min(1.8f, 0.30f + speed * 0.34f));
        if (speed > 1e-3f)
            s.setVec2("uWindDir", wx / speed, wz / speed);
        else
            s.setVec2("uWindDir", 0.8f, 0.6f);
        s.setVec3("uInteractorPos", 0f, -1000f, 0f);
        s.setFloat("uInteractorRadius", 0f);
        s.setVec3("uMobInteractorPos", 0f, -1000f, 0f);
        s.setFloat("uMobInteractorRadius", 0f);
    }

    private static float distSq(long key, Vector3f eye) {
        float dx = cx(key) * Chunk.SIZE_X + Chunk.SIZE_X * 0.5f - eye.x;
        float dz = cz(key) * Chunk.SIZE_Z + Chunk.SIZE_Z * 0.5f - eye.z;
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

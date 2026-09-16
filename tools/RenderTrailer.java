import com.mineclone.render.*;
import com.mineclone.world.*;
import org.joml.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

/**
 * Кинематографичный пролёт по миру: кадры трейлера тем же конвейером, что в
 * игре, но с прописанной камерой, временем суток и погодой.
 *
 * Отличие от соседних превью — они снимают по одному кадру на состояние, а
 * здесь состояние **едет**: камера движется, солнце всходит, дождь набирает
 * силу. Поэтому сцена описана парой «начало → конец», а кадр получается
 * интерполяцией. Съёмка живой игры дала бы дрожащую камеру и случайный
 * кадр — трейлеру нужен дубль, который можно повторить.
 *
 * Запуск из корня репозитория:
 *   java -cp "out;libs/*" tools/RenderTrailer.java [--fast]
 * Результат — PNG в out-test/trailer/, дальше их собирает
 * tools/make_trailer.py.
 *
 * {@code --fast} снимает в 960x540 и вдвое реже: для проверки монтажа, когда
 * важна последовательность, а не резкость.
 */
public class RenderTrailer {

    private static int W = 1920, H = 1080;
    private static int FPS = 30;
    private static final int RADIUS = 5;
    private static final long SEED = 1337L;

    /** Одна сцена трейлера: всё, что едет от начала к концу. */
    private record Scene(String name, float seconds,
                         Vector3f eyeA, Vector3f eyeB,
                         Vector3f aimA, Vector3f aimB,
                         float timeA, float timeB,
                         float rainA, float rainB,
                         float snowA, float snowB,
                         float stormA, float stormB,
                         float cloudsA, float cloudsB,
                         float auroraA, float auroraB,
                         float visA, float visB,
                         float dofA, float dofB, float focusA, float focusB,
                         boolean shadows, boolean torch) {}

    public static void main(String[] args) throws Exception {
        boolean fast = args.length > 0 && args[0].equals("--fast");
        if (fast) { W = 960; H = 540; FPS = 15; }

        if (!glfwInit()) throw new IllegalStateException("GLFW failed");
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_SAMPLES, 4);
        long window = glfwCreateWindow(W, H, "Trailer", 0, 0);
        if (window == 0) throw new IllegalStateException("No GL window");
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);

        TextureAtlas atlas = new TextureAtlas(TextureAtlas.DEFAULT_PATH, false);
        Shader chunkShader = new Shader(Shaders.CHUNK_VERTEX, Shaders.CHUNK_FRAGMENT);
        Shader waterShader = new Shader(Shaders.CHUNK_VERTEX, Shaders.WATER_FRAGMENT);
        Shader shadowShader = new Shader(Shaders.SHADOW_VERTEX, Shaders.SHADOW_FRAGMENT);
        SkyRenderer sky = new SkyRenderer();
        PrecipitationRenderer precip = new PrecipitationRenderer();
        ShadowMap shadows = new ShadowMap(2048);
        PostProcess post = new PostProcess(W, H);
        System.out.println("HDR " + post.isReady() + ", shadows " + shadows.isReady()
                + ", " + W + "x" + H + " @" + FPS);

        World world = new World(SEED);
        ChunkMesher mesher = new ChunkMesher(world);
        for (int cx = -RADIUS - 1; cx <= RADIUS + 1; cx++)
            for (int cz = -RADIUS - 1; cz <= RADIUS + 1; cz++)
                world.getChunk(cx, cz);

        Map<Long, Mesh> opaque = new HashMap<>();
        Map<Long, Mesh> water = new HashMap<>();
        for (int cx = -RADIUS; cx <= RADIUS; cx++)
            for (int cz = -RADIUS; cz <= RADIUS; cz++) {
                MeshData[] md = mesher.buildData(world.getChunk(cx, cz));
                if (!md[0].isEmpty()) opaque.put(World.key(cx, cz), md[0].upload());
                if (!md[1].isEmpty()) water.put(World.key(cx, cz), md[1].upload());
            }

        Vector3f[] cave = findCave(world);
        Vector3f vista = findVista(world);
        System.out.printf("точка облёта: %.1f %.1f %.1f%n", vista.x, vista.y, vista.z);

        List<Scene> scenes = script(vista, cave);
        Path out = Path.of("out-test/trailer");
        if (Files.isDirectory(out))
            try (var walk = Files.list(out)) { for (Path p : walk.toList()) Files.deleteIfExists(p); }
        Files.createDirectories(out);

        Matrix4f proj = new Matrix4f().perspective((float) java.lang.Math.toRadians(66f),
                (float) W / H, 0.1f, 700f);

        int frame = 0;
        long started = System.nanoTime();
        for (Scene s : scenes) {
            int count = java.lang.Math.max(1, java.lang.Math.round(s.seconds() * FPS));
            for (int i = 0; i < count; i++) {
                float raw = count == 1 ? 0f : i / (float) (count - 1);
                // Камера едет с плавным входом и выходом, а погода и время —
                // линейно: рывок камеры заметен, рывок света нет.
                float k = smooth(raw);
                drawFrame(s, k, raw, proj, world, opaque, water, atlas, chunkShader, waterShader,
                        shadowShader, sky, precip, shadows, post, frame == 0);
                BufferedImage img = grab();
                ImageIO.write(img, "png", out.resolve(String.format("f%05d.png", frame)).toFile());
                frame++;
            }
            int err = glGetError();
            if (err != GL_NO_ERROR) throw new IllegalStateException("OpenGL error " + err + " in " + s.name());
            System.out.printf("%-10s %3d кадров, всего %d%n", s.name(), count, frame);
        }
        System.out.printf("OK — %d кадров (%.1f c) за %.1f c в %s%n",
                frame, frame / (float) FPS, (System.nanoTime() - started) / 1e9, out);

        precip.destroy();
        shadows.destroy();
        shadowShader.destroy();
        post.destroy();
        sky.destroy();
        waterShader.destroy();
        chunkShader.destroy();
        atlas.destroy();
        glfwDestroyWindow(window);
        glfwTerminate();
    }

    /**
     * Раскадровка.
     *
     * Порядок не случаен: рассвет открывает, пещера даёт контраст темнотой,
     * гроза — движением, ночь — цветом, а боке закрывает, потому что после
     * него уже ничего не покажешь.
     */
    /**
     * Раскадровка.
     *
     * Порядок не случаен: рассвет открывает, пещера даёт контраст темнотой,
     * гроза — движением, ночь — цветом, а боке закрывает, потому что после
     * него уже ничего не покажешь.
     *
     * Все наземные сцены — облёт одной и той же точки на разных углах: так
     * зритель узнаёт место и видит, что меняется именно свет и погода, а не
     * локация. Прямой пролёт вдаль вместо этого уводил камеру в пустое море.
     */
    private static List<Scene> script(Vector3f aim, Vector3f[] cave) {
        Vector3f hi = new Vector3f(aim).add(0f, 5f, 0f);

        Vector3f caveEye = cave != null ? cave[0] : new Vector3f(aim);
        Vector3f caveFwd = cave != null ? cave[1] : new Vector3f(0, 0, -1);
        Vector3f caveAim = new Vector3f(caveEye).add(new Vector3f(caveFwd).mul(14f));
        Vector3f caveEnd = new Vector3f(caveEye).add(new Vector3f(caveFwd).mul(7f));

        List<Scene> s = new ArrayList<>();
        // 1. Рассвет: камера идёт по дуге, солнце выходит, тени втягиваются.
        s.add(new Scene("dawn", 6.5f, orbit(aim, 0.35f, 30f, 11f), orbit(aim, 0.95f, 24f, 8f),
                aim, aim, 0.06f, 0.42f,
                0, 0, 0, 0, 0, 0, 0.15f, 0.10f, 0, 0, 1f, 1f, 0, 0, 10, 10, true, false));
        // 2. День: облёт продолжается, свет встаёт в зенит.
        s.add(new Scene("day", 5.5f, orbit(aim, 0.95f, 24f, 8f), orbit(aim, 1.75f, 20f, 13f),
                aim, hi, 1.05f, 1.55f,
                0, 0, 0, 0, 0, 0, 0.10f, 0.22f, 0, 0, 1f, 1f, 0, 0, 10, 10, true, false));
        // 3. Пещера: единственная сцена без солнца — работает факел.
        s.add(new Scene("cave", 6f, caveEye, caveEnd, caveAim, caveAim, 1.5f, 1.5f,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1f, 1f, 0, 0, 10, 10, false, true));
        // 4. Гроза: небо затягивает и начинается ливень.
        s.add(new Scene("storm", 6.5f, orbit(aim, 2.4f, 22f, 12f), orbit(aim, 3.1f, 17f, 9f),
                hi, aim, 2.35f, 2.75f,
                0f, 1f, 0, 0, 0f, 1f, 0.25f, 1f, 0, 0, 1f, 0.42f, 0, 0, 10, 10, true, false));
        // 5. Метель: тот же кадр, но зима и белая мгла.
        s.add(new Scene("snow", 6f, orbit(aim, 3.1f, 17f, 9f), orbit(aim, 3.8f, 21f, 11f),
                aim, aim, 2.9f, 3.15f,
                0, 0, 0.6f, 1f, 0.3f, 0.6f, 0.95f, 0.85f, 0, 0, 0.82f, 0.66f, 0, 0, 10, 10, true, false));
        // 6. Ночь: луна, звёзды и сияние над северным горизонтом.
        s.add(new Scene("night", 6.5f, orbit(aim, 3.8f, 21f, 11f), orbit(aim, 4.5f, 26f, 18f),
                aim, new Vector3f(aim).add(-22f, 9f, 0f), 4.35f, 4.75f,
                0, 0, 0, 0, 0, 0, 0.25f, 0.05f, 0.15f, 1f, 1f, 1f, 0, 0, 10, 10, true, false));
        // 7. Боке: фоторежим, фокус уезжает от переднего плана вглубь.
        s.add(new Scene("bokeh", 5f, orbit(aim, 5.1f, 9f, 3.4f), orbit(aim, 5.5f, 8f, 3.0f),
                aim, aim, 0.62f, 0.80f,
                0, 0, 0, 0, 0, 0, 0.12f, 0.12f, 0, 0, 1f, 1f, 6.5f, 6.5f, 5f, 34f, true, false));
        return s;
    }

    /** Точка на окружности вокруг цели: угол в радианах, радиус и высота. */
    private static Vector3f orbit(Vector3f aim, float angle, float radius, float height) {
        return new Vector3f(aim.x + (float) java.lang.Math.cos(angle) * radius,
                aim.y + height,
                aim.z + (float) java.lang.Math.sin(angle) * radius);
    }

    // ---------------------------------------------------------------- кадр --

    private static void drawFrame(Scene s, float k, float raw, Matrix4f proj, World world,
            Map<Long, Mesh> opaque, Map<Long, Mesh> water, TextureAtlas atlas,
            Shader chunkShader, Shader waterShader, Shader shadowShader, SkyRenderer sky,
            PrecipitationRenderer precip, ShadowMap shadows, PostProcess post, boolean first) {

        Vector3f eye = new Vector3f(s.eyeA()).lerp(s.eyeB(), k);
        // Камера целится в точку, а не «куда-то»: при движении цель остаётся
        // в кадре сама, и композиция не разваливается на длинном проезде.
        Vector3f aim = new Vector3f(s.aimA()).lerp(s.aimB(), k);
        Vector3f fwd = new Vector3f(aim).sub(eye).normalize();
        float gameTime = mix(s.timeA(), s.timeB(), raw);
        float rain = mix(s.rainA(), s.rainB(), raw);
        float snow = mix(s.snowA(), s.snowB(), raw);
        float storm = mix(s.stormA(), s.stormB(), raw);
        float clouds = mix(s.cloudsA(), s.cloudsB(), raw);
        float aurora = mix(s.auroraA(), s.auroraB(), raw);
        float visibility = mix(s.visA(), s.visB(), raw);
        float windX = 0.65f * (0.4f + storm), windZ = -0.35f * (0.4f + storm);

        float daylight = java.lang.Math.max(0f, (float) java.lang.Math.sin(gameTime));
        int moonPhase = NightSky.moonPhase(gameTime);
        boolean moonUp = java.lang.Math.sin(gameTime) <= 0.0;
        float moonK = moonUp ? NightSky.moonlight(moonPhase) : 1f;

        Matrix4f view = new Matrix4f().lookAt(eye, aim, new Vector3f(0, 1, 0));

        Vector3f skySrgb = skyColor(daylight);
        skySrgb.lerp(new Vector3f(0.32f, 0.36f, 0.42f).mul(0.15f + daylight * 0.85f), clouds * 0.62f);
        Vector3f skyLin = linear(skySrgb);
        Vector3f lightCol = SunLight.lightColor(gameTime)
                .mul((1f - clouds * 0.72f - storm * 0.10f) * moonK);
        Vector3f skyAmb = SunLight.skyAmbient(skyLin, daylight)
                .mul(daylight + (1f - daylight) * (0.62f + 0.38f * NightSky.moonlight(moonPhase)))
                .mul(1f - clouds * 0.12f - storm * 0.08f);
        skyAmb.add(0.006f * aurora, 0.034f * aurora, 0.018f * aurora);

        Vector3f horizon = new Vector3f(skyLin).mul(1.32f);
        Vector3f hazeCol = linear(new Vector3f(0.40f, 0.44f, 0.50f))
                .lerp(linear(new Vector3f(0.78f, 0.82f, 0.88f)), snow > 0 ? 1f : 0f)
                .mul(0.06f + 0.94f * java.lang.Math.max(daylight, 0.05f) * moonK);
        float hazeMix = java.lang.Math.min(0.92f, (1f - visibility) * 1.15f);
        Vector3f fogCol = new Vector3f(horizon).lerp(hazeCol, java.lang.Math.min(1f, hazeMix * 1.1f));

        SceneLighting l = new SceneLighting();
        l.camPos.set(eye);
        l.lightDir.set(SunLight.lightDirection(gameTime));
        l.lightColor.set(lightCol);
        l.skyLight.set(skyAmb);
        l.groundLight.set(SunLight.groundAmbient(skyAmb));
        l.torchColor.set(1.55f, 0.88f, 0.42f);
        l.ambientColor.set(0.030f, 0.034f, 0.052f).mul(0.65f + 0.35f * daylight);
        l.fogColor.set(fogCol);
        l.fogSunColor.set(lightCol).mul(0.22f);
        float fogEnd = java.lang.Math.max(18f, RADIUS * Chunk.SIZE_X * 1.15f * visibility);
        l.fogEnd = fogEnd;
        l.fogStart = fogEnd * (0.10f + 0.42f * visibility);
        l.brightness = 1f;
        l.time = 12f + raw * 4f;
        l.linearOut = 1f;
        l.waterTint.set(0.34f, 0.66f, 0.92f);
        l.shadows = false;
        if (s.torch()) {
            // Факел в руке: тот же точечный источник, что в игре.
            l.pointPos.set(eye).add(fwd.x * 0.4f, -0.25f, fwd.z * 0.4f);
            l.pointRadius = BlockType.TORCH.emittedLight;
            float[] tc = BlockType.TORCH.particleColor;
            l.pointColor.set(tc[0], tc[1], tc[2]).mul(2.4f);
        }
        if (s.shadows() && shadows.isReady()) {
            l.shadowTaps = 2;
            l.shadowStrength = SunLight.shadowStrength(gameTime);
            l.shadows = l.shadowStrength > 0.002f;
            l.shadowTexel = 1f / shadows.getSize();
            l.shadowBias0 = SunLight.texelWorldSize(SunLight.CASCADE0_RADIUS, shadows.getSize()) * 2.2f;
            l.shadowBias1 = SunLight.texelWorldSize(SunLight.CASCADE1_RADIUS, shadows.getSize()) * 2.2f;
            if (l.shadows) {
                shadows.update(eye, fwd, l.lightDir);
                shadowPass(shadows, shadowShader, atlas, opaque);
                l.shadowMat0.set(shadows.matrix(0));
                l.shadowMat1.set(shadows.matrix(1));
            }
        }

        post.begin(fogCol.x, fogCol.y, fogCol.z);
        shadows.bind();
        SkyRenderer.Dome dome = new SkyRenderer.Dome();
        dome.zenith.set(skyLin).mul(0.70f).add(0f, 0.004f, 0.024f);
        dome.horizon.set(horizon);
        dome.ground.set(skyLin).mul(0.30f).add(0.012f, 0.010f, 0.008f);
        dome.sunGlow.set(lightCol).mul(daylight > 0.02f ? 0.85f : 0.30f);
        dome.lightDir.set(l.lightDir);
        dome.cloudiness = clouds;
        dome.aurora = aurora;
        dome.time = 40f + raw * 8f;
        dome.haze.set(fogCol);
        dome.hazeMix = hazeMix;
        dome.linearOut = 1f;
        sky.renderDome(new Matrix4f(proj).mul(view).invert(), dome);
        glDepthMask(false);
        sky.render(proj, view, eye, gameTime, daylight, 30f + raw * 10f, 1f, moonPhase, clouds, hazeMix);
        glDepthMask(true);

        float speed = (float) java.lang.Math.hypot(windX, windZ);
        drawChunks(chunkShader, l, atlas, proj, view, opaque, false,
                java.lang.Math.min(1.8f, 0.30f + speed * 0.34f),
                windX / java.lang.Math.max(speed, 1e-3f), windZ / java.lang.Math.max(speed, 1e-3f));
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(false);
        glDisable(GL_CULL_FACE);
        drawChunks(waterShader, l, atlas, proj, view, water, true, 0f, 1f, 0f);
        glDepthMask(true);
        glDisable(GL_BLEND);
        glEnable(GL_CULL_FACE);

        if (snow > 0.01f || rain > 0.01f) {
            precip.updateField(world, eye);
            Vector3f flake = PrecipitationRenderer.flakeColor(skyAmb, lightCol, fogCol);
            Vector3f drop = PrecipitationRenderer.dropColor(flake);
            precip.render(proj, view, eye, 57.3f + raw * 20f, windX, windZ, snow, rain, storm,
                    flake, drop, 1f);
        }

        PostProcess.Settings ps = new PostProcess.Settings();
        ps.night = 1f - java.lang.Math.min(1f, daylight * 3.2f);
        ps.bloomStrength = 0.58f;
        ps.rayStrength = 0.85f * daylight * (1f - clouds * 0.8f);
        ps.fogTime = 21f + raw * 6f;
        ps.fogDensity = 0.0015f + 0.0022f * (1f - visibility);
        ps.fogTop = World.SEA_LEVEL + 15f;
        ps.fogDepth = 13f;
        ps.fogMaxDist = 72f;
        ps.fogWindX = windX;
        ps.fogWindZ = windZ;
        ps.fogLight.set(lightCol).mul(0.7f);
        ps.fogAmbient.set(skyAmb).mul(0.6f);
        ps.camPos.set(eye);
        ps.lightDir.set(l.lightDir);
        ps.invViewProj.set(proj).mul(view).invert();
        ps.fogShadowTex = l.shadows ? shadows.texture(1) : 0;
        ps.shadowMat.set(shadows.matrix(1));
        ps.dofStrength = mix(s.dofA(), s.dofB(), raw);
        ps.dofFocus = mix(s.focusA(), s.focusB(), raw);
        ps.dofRange = 2.0f;
        post.resolve();
        post.render(ps, ps.rayStrength > 0f ? sunUv(proj, view, eye, SunLight.sunDirection(gameTime)) : null);
    }

    // ------------------------------------------------------------- точки ----

    /**
     * Точка, вокруг которой стоит облетать.
     *
     * «Куда-нибудь» не годится: на плоской равнине не видно ни теней, ни
     * рельефа, а посреди океана в кадре одна вода. Кандидат оценивается по
     * трём вещам разом — есть ли рядом деревья, есть ли вода в обзоре и
     * насколько неровный вокруг рельеф. Только их сумма даёт берег с лесом
     * на холме, то есть кадр, в котором видно и свет, и мир.
     *
     * Держимся в пределах ±45 блоков: облёт уходит на тридцать, а прогружено
     * только пять чанков от центра — за краем начинается пустота.
     */
    private static Vector3f findVista(World world) {
        Vector3f best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int wx = -45; wx <= 45; wx += 3)
            for (int wz = -45; wz <= 45; wz += 3) {
                int y = surfaceOf(world, wx, wz);
                if (y < World.SEA_LEVEL + 2 || y > World.SEA_LEVEL + 16)
                    continue;
                // Кольцо по радиусу облёта: камера весь план смотрит поверх
                // него, и если там открытое море, в кадре будет пустота.
                // Без этой проверки точка уезжала на мыс, стоило поменять
                // что-нибудь в генерации деревьев.
                int landRing = 0;
                for (int a = 0; a < 12; a++) {
                    double ang = a * java.lang.Math.PI / 6.0;
                    int rx = wx + (int) (java.lang.Math.cos(ang) * 24);
                    int rz = wz + (int) (java.lang.Math.sin(ang) * 24);
                    if (surfaceOf(world, rx, rz) > World.SEA_LEVEL + 1)
                        landRing++;
                }
                if (landRing < 8)
                    continue;
                int trees = 0, water = 0, relief = 0;
                for (int dx = -14; dx <= 14; dx += 7)
                    for (int dz = -14; dz <= 14; dz += 7) {
                        int h = surfaceOf(world, wx + dx, wz + dz);
                        if (h < 0) continue;
                        relief += java.lang.Math.min(8, java.lang.Math.abs(h - y));
                        BlockType top = world.getBlock(wx + dx, h, wz + dz);
                        if (top == BlockType.LEAVES || top == BlockType.WOOD) trees++;
                    }
                for (int d = 8; d <= 32; d += 8) {
                    if (world.getBlock(wx + d, World.SEA_LEVEL, wz) == BlockType.WATER) water++;
                    if (world.getBlock(wx - d, World.SEA_LEVEL, wz) == BlockType.WATER) water++;
                    if (world.getBlock(wx, World.SEA_LEVEL, wz + d) == BlockType.WATER) water++;
                    if (world.getBlock(wx, World.SEA_LEVEL, wz - d) == BlockType.WATER) water++;
                }
                int score = trees * 6 + java.lang.Math.min(water, 6) * 5 + relief + landRing * 3;
                if (score > bestScore) {
                    bestScore = score;
                    // Целимся выше земли: камера смотрит на кроны и склон, а
                    // не себе под ноги.
                    best = new Vector3f(wx + 0.5f, y + 3.5f, wz + 0.5f);
                }
            }
        return best != null ? best : new Vector3f(8.5f, World.SEA_LEVEL + 6f, 8.5f);
    }

    /** Точка внутри пещеры и направление вдоль самого длинного хода. */
    private static Vector3f[] findCave(World world) {
        int[][] dirs = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
        Vector3f[] best = null;
        int bestRun = 0;
        for (int wx = -60; wx < 60; wx += 2)
            for (int wz = -60; wz < 60; wz += 2)
                for (int y = 12; y < World.SEA_LEVEL - 4; y += 2) {
                    if (world.getBlock(wx, y, wz) != BlockType.AIR) continue;
                    if (!world.getBlock(wx, y - 1, wz).solid) continue;
                    if (world.getBlock(wx, y + 1, wz) != BlockType.AIR) continue;
                    for (int[] d : dirs) {
                        int run = 0;
                        while (run < 22 && world.getBlock(wx + d[0] * (run + 1), y,
                                wz + d[1] * (run + 1)) == BlockType.AIR) run++;
                        if (run > bestRun) {
                            bestRun = run;
                            best = new Vector3f[] {
                                new Vector3f(wx + 0.5f, y + 0.9f, wz + 0.5f),
                                new Vector3f(d[0], -0.04f, d[1]).normalize() };
                        }
                    }
                }
        return bestRun >= 8 ? best : null;
    }

    // ------------------------------------------------------------ утилиты ---

    private static float mix(float a, float b, float t) { return a + (b - a) * t; }

    /** Плавный вход и выход: камера не должна трогаться и вставать рывком. */
    private static float smooth(float t) {
        float k = t < 0f ? 0f : (t > 1f ? 1f : t);
        return k * k * (3f - 2f * k);
    }

    private static float[] sunUv(Matrix4f proj, Matrix4f view, Vector3f eye, Vector3f sunDir) {
        Vector4f clip = new Matrix4f(proj).mul(view)
                .transform(new Vector4f(eye.x + sunDir.x * 400f, eye.y + sunDir.y * 400f,
                        eye.z + sunDir.z * 400f, 1f));
        if (clip.w <= 0f) return null;
        float u = (clip.x / clip.w) * 0.5f + 0.5f, v = (clip.y / clip.w) * 0.5f + 0.5f;
        if (u < -0.4f || u > 1.4f || v < -0.4f || v > 1.4f) return null;
        return new float[] { u, v };
    }

    private static void shadowPass(ShadowMap shadows, Shader shadowShader,
                                   TextureAtlas atlas, Map<Long, Mesh> meshes) {
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(2.2f, 4.0f);
        glDisable(GL_CULL_FACE);
        atlas.bind(0);
        Matrix4f model = new Matrix4f();
        for (int c = 0; c < ShadowMap.CASCADES; c++) {
            shadows.begin(c);
            shadowShader.bind();
            shadowShader.setInt("uAtlas", 0);
            shadowShader.setMat4("uLightSpace", shadows.matrix(c));
            shadowShader.setFloat("uWindSway", 0f);
            for (Map.Entry<Long, Mesh> e : meshes.entrySet()) {
                int cx = (int) (e.getKey() >> 32), cz = (int) (e.getKey() & 0xFFFFFFFFL);
                shadowShader.setMat4("uModel", model.translation(cx * Chunk.SIZE_X, 0, cz * Chunk.SIZE_Z));
                e.getValue().render();
            }
            shadowShader.unbind();
        }
        shadows.end(W, H);
        glPolygonOffset(0f, 0f);
        glDisable(GL_POLYGON_OFFSET_FILL);
        glEnable(GL_CULL_FACE);
    }

    private static void drawChunks(Shader shader, SceneLighting lighting, TextureAtlas atlas,
                                   Matrix4f proj, Matrix4f view, Map<Long, Mesh> meshes,
                                   boolean isWater, float sway, float dirX, float dirZ) {
        shader.bind();
        shader.setMat4("uProjection", proj);
        shader.setMat4("uView", view);
        shader.setInt("uAtlas", 0);
        lighting.apply(shader);
        shader.setFloat("uWindSway", sway);
        shader.setVec2("uWindDir", dirX, dirZ);
        if (isWater) shader.setVec3("uWaterTint", lighting.waterTint);
        atlas.bind(0);
        Matrix4f model = new Matrix4f();
        for (Map.Entry<Long, Mesh> e : meshes.entrySet()) {
            int cx = (int) (e.getKey() >> 32), cz = (int) (e.getKey() & 0xFFFFFFFFL);
            shader.setMat4("uModel", model.translation(cx * Chunk.SIZE_X, 0, cz * Chunk.SIZE_Z));
            e.getValue().render();
        }
        shader.unbind();
    }

    private static int surfaceOf(World world, int wx, int wz) {
        for (int y = Chunk.SIZE_Y - 1; y > 0; y--) {
            BlockType b = world.getBlock(wx, y, wz);
            if (b != BlockType.AIR && b != BlockType.WATER && b != BlockType.WATER_FLOW)
                return y;
        }
        return -1;
    }

    private static Vector3f skyColor(float d) {
        Vector3f night = new Vector3f(0.05f, 0.06f, 0.11f);
        Vector3f day = new Vector3f(0.46f, 0.64f, 0.92f);
        float k = java.lang.Math.min(1f, d * 1.5f);
        return new Vector3f(night).lerp(day, k);
    }

    private static Vector3f linear(Vector3f c) {
        return new Vector3f((float) java.lang.Math.pow(c.x, 2.2), (float) java.lang.Math.pow(c.y, 2.2),
                (float) java.lang.Math.pow(c.z, 2.2));
    }

    private static BufferedImage grab() {
        var pixels = BufferUtils.createByteBuffer(W * H * 4);
        glReadPixels(0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                int p = (y * W + x) * 4;
                img.setRGB(x, H - y - 1, (pixels.get(p) & 255) << 16
                        | (pixels.get(p + 1) & 255) << 8 | (pixels.get(p + 2) & 255));
            }
        return img;
    }
}

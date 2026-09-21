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
 * Офлайновые снимки шейдерного конвейера: настоящий сгенерированный кусок мира,
 * каскадные тени, HDR-пост — всё то же, что в игре, но без окна и без ввода.
 *
 * Запуск из корня репозитория:
 *   java -cp "out;libs/*" tools/RenderShaderPreview.java
 * Результат — PNG в out-test/previews/shader-*.png.
 *
 * Тест здесь не «красиво ли», а «не пусто и не сломалось»: кадры не должны
 * быть однотонными, между временами суток обязаны отличаться, и GL не должен
 * отдавать ошибку.
 */
public class RenderShaderPreview {

    private static final int W = 1280, H = 720;
    private static final int RADIUS = 5;      // чанков вокруг центра
    private static final long SEED = 1337L;

    public static void main(String[] args) throws Exception {
        if (!glfwInit()) throw new IllegalStateException("GLFW failed");
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_SAMPLES, 4);
        long window = glfwCreateWindow(W, H, "Shader preview", 0, 0);
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
        PlayerRenderer playerRenderer = new PlayerRenderer();
        ShadowMap shadows = new ShadowMap(2048);
        PostProcess post = new PostProcess(W, H);
        System.out.println("shadow FBO ok: " + shadows.isReady() + ", HDR FBO ok: " + post.isReady());

        World world = new World(SEED);
        ChunkMesher mesher = new ChunkMesher(world);
        for (int cx = -RADIUS - 1; cx <= RADIUS + 1; cx++)
            for (int cz = -RADIUS - 1; cz <= RADIUS + 1; cz++)
                world.getChunk(cx, cz);
        // Костры и факелы ставятся до мешинга: иначе их нет в геометрии,
        // и проверять кросс-модель с излучаемым светом нечем.
        int fireZ = 14;
        for (int i = 0; i < 5; i++) {
            int fx = 2 + i * 3;
            int fy = surfaceOf(world, fx, fireZ);
            if (fy <= 0) continue;
            world.setBlock(fx, fy + 1, fireZ, i % 2 == 0 ? BlockType.FIRE : BlockType.TORCH);
        }

        // Лесенка снежных слоёв 0..7: единственный способ проверить, что
        // высота из meta действительно попадает в меш.
        int snowZ = fireZ + 3;
        for (int i = 0; i < 8; i++) {
            int sx = 2 + i * 2;
            int sy = surfaceOf(world, sx, snowZ);
            if (sy <= 0) continue;
            world.setBlock(sx, sy + 1, snowZ, BlockType.SNOW_LAYER);
            world.setSnowLevel(sx, sy + 1, snowZ, i);
        }

        // Снежное поле под следы. Площадка выравнивается руками: на рельефе
        // дорожка распадается на отдельные отпечатки по разным уровням, и
        // проверять по ней нечего. Здесь важно, что декаль ложится на верх
        // снежного слоя, а не тонет в блоке под ним.
        int tracksZ = snowZ + 4;
        int tracksY = surfaceOf(world, 6, tracksZ + 3);
        for (int tx = 0; tx < 14; tx++)
            for (int tz = tracksZ; tz < tracksZ + 8; tz++) {
                for (int y = tracksY + 1; y < tracksY + 8; y++)
                    world.setBlock(tx, y, tz, BlockType.AIR);
                for (int y = tracksY; y > tracksY - 3; y--)
                    world.setBlock(tx, y, tz, BlockType.SNOWY_GRASS);
                world.setBlock(tx, tracksY + 1, tz, BlockType.SNOW_LAYER);
                world.setSnowLevel(tx, tracksY + 1, tz, 5);
            }

        Map<Long, Mesh> opaque = new HashMap<>();
        Map<Long, Mesh> water = new HashMap<>();
        for (int cx = -RADIUS; cx <= RADIUS; cx++)
            for (int cz = -RADIUS; cz <= RADIUS; cz++) {
                MeshData[] md = mesher.buildData(world.getChunk(cx, cz));
                if (!md[0].isEmpty()) opaque.put(World.key(cx, cz), md[0].upload());
                if (!md[1].isEmpty()) water.put(World.key(cx, cz), md[1].upload());
            }

        // Камера чуть выше поверхности в центре, смотрит вдоль −Z и вниз.
        int surface = surfaceAt(world, 8, 8);
        Vector3f camPos = new Vector3f(8f, surface + 6f, 26f);
        Vector3f camTarget = new Vector3f(8f, surface + 1.5f, -18f);
        Matrix4f proj = new Matrix4f().perspective((float) java.lang.Math.toRadians(70f),
                (float) W / H, 0.1f, 600f);
        Matrix4f baseView = new Matrix4f().lookAt(camPos, camTarget, new Vector3f(0, 1, 0));
        Vector3f baseFwd = new Vector3f(camTarget).sub(camPos).normalize();

        // Отдельный ракурс поперёк орбиты светила: солнце ходит в плоскости
        // Y-Z, поэтому взгляд вдоль X — единственный, где длинные тени видно
        // целиком, а не в торец.
        Vector3f sideTarget = new Vector3f(camPos.x - 40f, surface, camPos.z - 4f);
        Matrix4f sideView = new Matrix4f().lookAt(camPos, sideTarget, new Vector3f(0, 1, 0));
        Vector3f sideFwd = new Vector3f(sideTarget).sub(camPos).normalize();

        Files.createDirectories(Path.of("out-test/previews"));
        // Утро с длинными тенями, полдень, закат, ночь, и вид поперёк солнца.
        // Точка внутри пещеры: нужна отдельным кадром, иначе новый подземный
        // свет вообще нечем проверить.
        Vector3f[] structurePose = findStructure(world);
        Vector3f[] riverPose = findRiver(world);
        Vector3f[] cavePose = findCave(world);
        Vector3f caveCam = cavePose == null ? null : cavePose[0];
        int fireSurface = surfaceOf(world, 8, fireZ);
        Vector3f fireEye = new Vector3f(8.5f, fireSurface + 2.6f, fireZ + 7f);
        Vector3f fireTarget = new Vector3f(8.5f, fireSurface + 1.2f, fireZ);
        int snowSurface = surfaceOf(world, 8, snowZ);
        Vector3f snowEye = new Vector3f(8.5f, snowSurface + 2.2f, snowZ + 6f);
        Vector3f snowTarget = new Vector3f(8.5f, snowSurface + 1.1f, snowZ);

        // Дорожка следов наискось через снежное поле: две колеи, затухающие
        // к хвосту — ровно то, что видно в игре за уходящим игроком.
        DecalRenderer decals = new DecalRenderer();
        int steps = 13;
        float trackTop = tracksY + 1f + 6f / 8f;      // верх слоя снега уровня 5
        float ax = 1.2f, az = tracksZ + 6.4f;
        float bx = 12.2f, bz = tracksZ + 1.4f;
        float yaw = (float) java.lang.Math.atan2(-(bx - ax), -(bz - az));
        for (int k = 0; k < steps; k++) {
            float t = k / (float) (steps - 1);
            float fx = ax + (bx - ax) * t;
            float fz = az + (bz - az) * t;
            float side = (k % 2 == 0 ? 1f : -1f) * 0.2f;
            // Хвост дорожки старее головы: у дальних следов меньше остатка
            // жизни, и затухание видно прямо на снимке.
            float life = 3f + t * 23f;
            decals.add(fx + (float) java.lang.Math.cos(yaw) * side, trackTop,
                    fz - (float) java.lang.Math.sin(yaw) * side,
                    yaw, 0.70f, life, 0.85f, 75);
        }
        Vector3f tracksEye = new Vector3f(6.5f, tracksY + 5.5f, tracksZ + 11.5f);
        Vector3f tracksTarget = new Vector3f(6.5f, tracksY + 1.5f, tracksZ + 3f);
        // Вид от третьего лица: камера за спиной модели, стоящей на поверхности.
        int heroSurface = surfaceOf(world, 8, 4);
        Vector3f heroPos = new Vector3f(8.5f, heroSurface + 1f, 4.5f);
        Vector3f heroEye = new Vector3f(heroPos.x - 2.1f, heroPos.y + 2.4f, heroPos.z + 3.2f);
        float[] times = { 0.55f, 1.5708f, 2.75f, 4.3f, 0.45f, 1.5708f, 4.3f, 1.2f, 1.1f, 1.1f, 1.0f,
                0.20f, 1.15f, 1.35f, 1.0f };
        String[] names = { "morning", "noon", "sunset", "night", "shadows", "cave", "fire", "snow",
                "player", "hurt", "structure", "mist", "tracks", "river", "photo" };
        BufferedImage previous = null;
        for (int i = 0; i < times.length; i++) {
            boolean side = names[i].equals("shadows");
            boolean cave = names[i].equals("cave");
            boolean fireShot = names[i].equals("fire");
            boolean snowShot = names[i].equals("snow");
            boolean tracksShot = names[i].equals("tracks");
            boolean riverShot = names[i].equals("river") && riverPose != null;
            // Фоторежим: та же сцена, что у кадра «player», но с глубиной
            // резкости — единственный способ увидеть её, не запуская игру.
            // Фоторежим снимается с того же ракурса, что кадр «player»:
            // модель в фокусе, а мир за ней уходит в размытие.
            boolean photoShot = names[i].equals("photo");
            boolean heroShot = photoShot
                    || names[i].equals("player") || names[i].equals("hurt");
            boolean hurtShot = names[i].equals("hurt");
            boolean structShot = names[i].equals("structure") && structurePose != null;
            // Рассветный туман смотрим с того же ракурса, что и тени: там
            // виден длинный кусок рельефа, по которому он и стелется.
            boolean mistShot = names[i].equals("mist");
            if (mistShot)
                side = true;
            Matrix4f view = side ? sideView : baseView;
            Vector3f camFwd = side ? sideFwd : baseFwd;
            Vector3f eye = camPos;
            if (cave && caveCam != null) {
                eye = caveCam;
                camFwd = cavePose[1];
                Vector3f t = new Vector3f(caveCam).add(camFwd);
                view = new Matrix4f().lookAt(caveCam, t, new Vector3f(0, 1, 0));
            }
            if (fireShot) {
                eye = fireEye;
                view = new Matrix4f().lookAt(fireEye, fireTarget, new Vector3f(0, 1, 0));
                camFwd = new Vector3f(fireTarget).sub(fireEye).normalize();
            }
            if (snowShot) {
                eye = snowEye;
                view = new Matrix4f().lookAt(snowEye, snowTarget, new Vector3f(0, 1, 0));
                camFwd = new Vector3f(snowTarget).sub(snowEye).normalize();
            }
            if (tracksShot) {
                eye = tracksEye;
                view = new Matrix4f().lookAt(tracksEye, tracksTarget, new Vector3f(0, 1, 0));
                camFwd = new Vector3f(tracksTarget).sub(tracksEye).normalize();
            }
            if (riverShot) {
                eye = riverPose[0];
                view = new Matrix4f().lookAt(eye, riverPose[1], new Vector3f(0, 1, 0));
                camFwd = new Vector3f(riverPose[1]).sub(eye).normalize();
            }
            if (structShot) {
                eye = structurePose[0];
                view = new Matrix4f().lookAt(eye, structurePose[1], new Vector3f(0, 1, 0));
                camFwd = new Vector3f(structurePose[1]).sub(eye).normalize();
            }
            if (heroShot) {
                eye = heroEye;
                Vector3f t = new Vector3f(heroPos).add(0f, 1.1f, 0f);
                view = new Matrix4f().lookAt(heroEye, t, new Vector3f(0, 1, 0));
                camFwd = new Vector3f(t).sub(heroEye).normalize();
            }
            float gameTime = times[i];
            float daylight = java.lang.Math.max(0f, (float) java.lang.Math.sin(gameTime));

            SceneLighting lighting = buildLighting(gameTime, daylight, eye, shadows);
            if (cave && caveCam != null) {
                // Факел в руке: тот же точечный источник, что в игре.
                lighting.pointPos.set(caveCam).add(camFwd.x * 0.4f, -0.25f, camFwd.z * 0.4f);
                lighting.pointRadius = BlockType.TORCH.emittedLight;
                float[] tc = BlockType.TORCH.particleColor;
                lighting.pointColor.set(tc[0], tc[1], tc[2]).mul(2.4f);
                lighting.shadows = false;
            }
            Vector3f lightDir = SunLight.lightDirection(gameTime);
            if (lighting.shadows) {
                shadows.update(eye, camFwd, lightDir);
                lighting.shadowMat0.set(shadows.matrix(0));
                lighting.shadowMat1.set(shadows.matrix(1));
                shadowPass(shadows, shadowShader, atlas, opaque);
            }

            Vector3f skyLin = linear(skyColor(daylight));
            post.begin(skyLin.x, skyLin.y, skyLin.z);
            shadows.bind();

            sky.renderDome(new Matrix4f(proj).mul(view).invert(),
                    new Vector3f(skyLin).mul(0.70f).add(0f, 0.004f, 0.024f),
                    new Vector3f(skyLin).mul(1.32f),
                    new Vector3f(skyLin).mul(0.30f).add(0.012f, 0.010f, 0.008f),
                    new Vector3f(lighting.lightColor).mul(0.85f), lightDir, 0f, 1f);
            glDepthMask(false);
            sky.render(proj, view, eye, gameTime, daylight, gameTime * 8f, 1f);
            glDepthMask(true);

            if (heroShot) {
                // Ходячая поза и замах: статичная модель не показала бы,
                // что анимация вообще работает.
                // Корпус и голова врозь: голова отвёрнута на предел, чтобы
                // кадр показывал именно раздельный поворот.
                playerRenderer.render(proj, view, heroPos, 2.5f,
                        2.5f + com.mineclone.render.BodyRotation.MAX_OFFSET, 0.15f,
                        0.62f, 1f, 0.45f, lighting, 1f, 0f);
            }
            drawChunks(chunkShader, lighting, atlas, proj, view, opaque, false);
            if (tracksShot)
                decals.render(proj, view, atlas.getTextureId(), true);
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            glDepthMask(false);
            glDisable(GL_CULL_FACE);
            drawChunks(waterShader, lighting, atlas, proj, view, water, true);
            glDepthMask(true);
            glDisable(GL_BLEND);
            glEnable(GL_CULL_FACE);

            PostProcess.Settings s = new PostProcess.Settings();
            s.night = 1f - java.lang.Math.min(1f, daylight * 3.2f);
            if (hurtShot) s.damage = 0.85f;
            if (photoShot) {
                s.dofStrength = 7f;
                s.dofFocus = heroEye.distance(heroPos);
                s.dofRange = 1.6f;
                s.near = 0.1f;
                s.far = 600f;
            }
            s.rayStrength = 0.75f * daylight;
            post.resolve();
            post.render(s, cave ? null : sunUv(proj, view, eye, SunLight.sunDirection(gameTime)));

            BufferedImage img = grab();
            check(img, previous, names[i]);
            previous = img;
            ImageIO.write(img, "png", Path.of("out-test/previews/shader-" + names[i] + ".png").toFile());
            int err = glGetError();
            if (err != GL_NO_ERROR) throw new IllegalStateException("OpenGL error " + err);
            System.out.println("rendered " + names[i]);
        }

        post.destroy();
        shadows.destroy();
        sky.destroy();
        playerRenderer.destroy();
        chunkShader.destroy();
        waterShader.destroy();
        shadowShader.destroy();
        atlas.destroy();
        glfwDestroyWindow(window);
        glfwTerminate();
        System.out.println("OK — " + names.length + " frames in out-test/previews/");
    }

    // ------------------------------------------------------------------

    private static SceneLighting buildLighting(float gameTime, float daylight,
                                               Vector3f camPos, ShadowMap shadows) {
        SceneLighting l = new SceneLighting();
        Vector3f skyLin = linear(skyColor(daylight));
        l.camPos.set(camPos);
        l.lightDir.set(SunLight.lightDirection(gameTime));
        l.lightColor.set(SunLight.lightColor(gameTime));
        l.skyLight.set(SunLight.skyAmbient(skyLin, daylight));
        l.groundLight.set(SunLight.groundAmbient(l.skyLight));
        l.torchColor.set(1.55f, 0.88f, 0.42f);
        l.ambientColor.set(0.030f, 0.034f, 0.052f).mul(0.65f + 0.35f * daylight);
        l.fogColor.set(new Vector3f(skyLin).mul(1.32f));
        l.fogSunColor.set(l.lightColor).mul(0.22f);
        l.fogStart = RADIUS * Chunk.SIZE_X * 0.55f;
        l.fogEnd = RADIUS * Chunk.SIZE_X * 1.05f;
        l.brightness = 1f;
        l.time = gameTime * 8f;
        l.linearOut = 1f;
        l.waterTint.set(0.34f, 0.66f, 0.92f);
        // Низовой туман по тому же правилу, что в игре: копится ночью и на
        // рассвете, днём его нет.
        float night = java.lang.Math.max(0f, java.lang.Math.min(1f, (0.55f - daylight) / 0.45f));
        l.heightFogDensity = 0.030f * night * night;
        l.heightFogTop = World.SEA_LEVEL + 15f;
        l.heightFogDepth = 13f;
        l.heightFogColor.set(l.fogColor).mul(1.15f).add(0.010f, 0.012f, 0.018f);
        l.shadowTaps = 2;
        l.shadowStrength = SunLight.shadowStrength(gameTime);
        l.shadows = shadows.isReady() && l.shadowStrength > 0.002f;
        l.shadowTexel = 1f / shadows.getSize();
        l.shadowBias0 = SunLight.texelWorldSize(SunLight.CASCADE0_RADIUS, shadows.getSize()) * 2.2f;
        l.shadowBias1 = SunLight.texelWorldSize(SunLight.CASCADE1_RADIUS, shadows.getSize()) * 2.2f;
        return l;
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
            for (Map.Entry<Long, Mesh> e : meshes.entrySet()) {
                int cx = (int) (e.getKey() >> 32), cz = (int) (e.getKey() & 0xFFFFFFFFL);
                shadowShader.setMat4("uModel",
                        model.translation(cx * Chunk.SIZE_X, 0, cz * Chunk.SIZE_Z));
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
                                   boolean isWater) {
        shader.bind();
        shader.setMat4("uProjection", proj);
        shader.setMat4("uView", view);
        shader.setInt("uAtlas", 0);
        lighting.apply(shader);
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

    private static float[] sunUv(Matrix4f proj, Matrix4f view, Vector3f camPos, Vector3f sunDir) {
        if (sunDir.y <= 0.02f) return null;
        Vector3f p = new Vector3f(camPos).add(new Vector3f(sunDir).mul(200f));
        Vector4f clip = new Matrix4f(proj).mul(view).transform(new Vector4f(p.x, p.y, p.z, 1f));
        if (clip.w <= 1e-4f) return null;
        float nx = clip.x / clip.w, ny = clip.y / clip.w;
        if (nx < -1.7f || nx > 1.7f || ny < -1.7f || ny > 1.7f) return null;
        return new float[] { nx * 0.5f + 0.5f, ny * 0.5f + 0.5f };
    }

    /**
     * Ближайшая постройка и точка съёмки перед ней.
     *
     * @return {позиция камеры, точка взгляда} или null, если построек нет
     */
    /**
     * Точка над руслом реки.
     *
     * Искать «где вода» бесполезно — так камера встаёт посреди океана. Берём
     * саму маску русла ({@code world.rivers}) и требуем, чтобы поперёк неё в
     * десяти блоках стояла суша заметно выше воды: это и отличает реку от
     * залива.
     *
     * @return {позиция камеры, точка взгляда} или null
     */
    private static Vector3f[] findRiver(World world) {
        for (int wx = -70; wx < 70; wx++)
            for (int wz = -70; wz < 70; wz++) {
                if (world.rivers.riverStrength(wx, wz) < 0.85f)
                    continue;
                if (world.getBlock(wx, World.SEA_LEVEL, wz) != BlockType.WATER)
                    continue;
                // Берега поперёк русла — по обеим сторонам и вдоль обеих осей.
                boolean banksX = surfaceOf(world, wx + 10, wz) > World.SEA_LEVEL + 2
                        && surfaceOf(world, wx - 10, wz) > World.SEA_LEVEL + 2;
                boolean banksZ = surfaceOf(world, wx, wz + 10) > World.SEA_LEVEL + 2
                        && surfaceOf(world, wx, wz - 10) > World.SEA_LEVEL + 2;
                if (!banksX && !banksZ)
                    continue;
                Vector3f target = new Vector3f(wx + 0.5f, World.SEA_LEVEL + 0.5f, wz + 0.5f);
                // Высоко и наискось: с уровня воды русло закрывают кроны,
                // и в кадр попадает лес, а не река.
                Vector3f eye = new Vector3f(target).add(-10f, 30f, 30f);
                return new Vector3f[] { eye, target };
            }
        return null;
    }

    private static Vector3f[] findStructure(World world) {
        for (int wx = -70; wx < 70; wx++)
            for (int wz = -70; wz < 70; wz++)
                for (int y = World.SEA_LEVEL + 2; y < 100; y++) {
                    BlockType b = world.getBlock(wx, y, wz);
                    if (b != BlockType.PLANKS && b != BlockType.COBBLE)
                        continue;
                    // Отходим по диагонали и поднимаемся, чтобы постройка
                    // попала в кадр целиком, а не одной стеной.
                    Vector3f target = new Vector3f(wx + 0.5f, y + 1.5f, wz + 0.5f);
                    Vector3f eye = new Vector3f(target).add(-7.5f, 5.5f, 7.5f);
                    return new Vector3f[] { eye, target };
                }
        return null;
    }

    /**
     * Точка внутри пещеры и направление вдоль самого длинного хода из неё.
     *
     * Просто «воздух с полом» не годится: камера встаёт вплотную к стене и в
     * кадре оказывается один блок во весь экран. Поэтому меряем длину
     * свободного пробега по четырём сторонам и берём место, где есть куда
     * смотреть.
     *
     * @return {позиция, направление взгляда} или null, если пещер не нашлось
     */
    private static Vector3f[] findCave(World world) {
        int[][] dirs = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
        Vector3f[] best = null;
        int bestRun = 0;
        for (int wx = -60; wx < 60; wx++)
            for (int wz = -60; wz < 60; wz++)
                for (int y = 12; y < 44; y++) {
                    if (world.getBlock(wx, y, wz) != BlockType.AIR) continue;
                    if (world.getBlock(wx, y + 1, wz) != BlockType.AIR) continue;
                    if (!world.getBlock(wx, y - 1, wz).solid) continue;
                    if (world.getSkyLight(wx, y, wz) != 0) continue;
                    for (int[] d : dirs) {
                        int run = 0;
                        while (run < 24
                                && world.getBlock(wx + d[0] * (run + 1), y, wz + d[1] * (run + 1)) == BlockType.AIR)
                            run++;
                        if (run > bestRun) {
                            bestRun = run;
                            best = new Vector3f[] {
                                new Vector3f(wx + 0.5f, y + 0.7f, wz + 0.5f),
                                new Vector3f(d[0], -0.12f, d[1]).normalize()
                            };
                        }
                    }
                    if (bestRun >= 14) return best;
                }
        return best;
    }

    /** Верхний непрозрачный блок колонки в мировых координатах. */
    private static int surfaceOf(World world, int wx, int wz) {
        for (int y = Chunk.SIZE_Y - 2; y > 0; y--) {
            BlockType b = world.getBlock(wx, y, wz);
            if (b != BlockType.AIR && b != BlockType.WATER)
                return y;
        }
        return -1;
    }

    private static int surfaceAt(World world, int x, int z) {
        for (int y = Chunk.SIZE_Y - 1; y > 0; y--)
            if (world.getBlock(x, y, z) != BlockType.AIR) return y;
        return World.SEA_LEVEL;
    }

    private static Vector3f skyColor(float d) {
        float[] night = { 0.02f, 0.03f, 0.08f };
        float[] horizon = { 0.85f, 0.45f, 0.20f };
        float[] day = { 0.55f, 0.75f, 0.95f };
        float[] a, b;
        float t;
        if (d < 0.3f) { a = night; b = horizon; t = d / 0.3f; }
        else { a = horizon; b = day; t = (d - 0.3f) / 0.7f; }
        return new Vector3f(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t,
                a[2] + (b[2] - a[2]) * t);
    }

    private static Vector3f linear(Vector3f c) {
        return new Vector3f((float) java.lang.Math.pow(c.x, 2.2), (float) java.lang.Math.pow(c.y, 2.2),
                (float) java.lang.Math.pow(c.z, 2.2));
    }

    private static BufferedImage grab() {
        var pixels = BufferUtils.createByteBuffer(W * H * 4);
        glReadPixels(0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                int p = (y * W + x) * 4;
                image.setRGB(x, H - y - 1, 0xff000000 | (pixels.get(p) & 255) << 16
                        | (pixels.get(p + 1) & 255) << 8 | (pixels.get(p + 2) & 255));
            }
        return image;
    }

    /** Кадр не должен быть однотонным и обязан отличаться от предыдущего. */
    private static void check(BufferedImage img, BufferedImage prev, String name) {
        Set<Integer> colors = new HashSet<>();
        long changed = 0;
        for (int y = 0; y < H; y += 3)
            for (int x = 0; x < W; x += 3) {
                colors.add(img.getRGB(x, y));
                if (prev != null && img.getRGB(x, y) != prev.getRGB(x, y)) changed++;
            }
        if (colors.size() < 500)
            throw new IllegalStateException("Frame " + name + " is nearly flat: "
                    + colors.size() + " distinct colours");
        if (prev != null && changed < 1000)
            throw new IllegalStateException("Frame " + name + " barely differs from the previous one");
    }
}

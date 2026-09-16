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
 * Офлайновые снимки погоды и ночного неба: снегопад, метель, ливень, северное
 * сияние и фазы луны — тем же конвейером, что в игре, но с заданной погодой.
 *
 * Запуск из корня репозитория:
 *   java -cp "out;libs/*" tools/RenderAtmospherePreview.java
 * Результат — PNG в out-test/previews/atmo-*.png.
 *
 * Как и соседний RenderShaderPreview, проверяет «не пусто и не сломалось»:
 * кадр не однотонный, отличается от предыдущего, GL без ошибок.
 */
public class RenderAtmospherePreview {

    private static final int W = 1280, H = 720;
    private static final int RADIUS = 4;
    private static final long SEED = 1337L;

    /** Сцена кадра: время, погода, небо, куда смотрит камера. */
    record Shot(String name, float gameTime, float snow, float rain, float storm, float clouds,
                float visibility, float aurora, int moonPhase, float lookUp, float windX, float windZ,
                Extra x) {
        Shot(String name, float gameTime, float snow, float rain, float storm, float clouds,
             float visibility, float aurora, int moonPhase, float lookUp, float windX, float windZ) {
            this(name, gameTime, snow, rain, storm, clouds, visibility, aurora, moonPhase, lookUp,
                    windX, windZ, Extra.NONE);
        }
    }

    /**
     * Туман, иней, тени и рука от первого лица — то, чего нет в погодных кадрах.
     * {@code juice} — сцена «сочности»: неоновая рамка, обломки, искры удара и
     * предметы на земле.
     */
    record Extra(float mist, float haze, float frost, boolean shadows, boolean hand,
                 float inspect, float swing, float condition, boolean juice) {
        static final Extra NONE = new Extra(0f, 0f, 0f, false, false, 0f, 0f, 1f);

        Extra(float mist, float haze, float frost, boolean shadows, boolean hand,
              float inspect, float swing, float condition) {
            this(mist, haze, frost, shadows, hand, inspect, swing, condition, false);
        }
    }

    public static void main(String[] args) throws Exception {
        if (!glfwInit()) throw new IllegalStateException("GLFW failed");
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        long window = glfwCreateWindow(W, H, "Atmosphere preview", 0, 0);
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
        HeldItemRenderer hand = new HeldItemRenderer();
        BlockOutline outline = new BlockOutline();
        DebrisRenderer debrisRenderer = new DebrisRenderer();
        ItemRenderer itemRenderer = new ItemRenderer();
        ParticleSystem particles = new ParticleSystem();
        ShadowMap shadows = new ShadowMap(2048);
        PostProcess post = new PostProcess(W, H);
        System.out.println("HDR FBO ok: " + post.isReady());

        World world = new World(SEED);
        ChunkMesher mesher = new ChunkMesher(world);
        for (int cx = -RADIUS - 1; cx <= RADIUS + 1; cx++)
            for (int cz = -RADIUS - 1; cz <= RADIUS + 1; cz++)
                world.getChunk(cx, cz);
        // Камера на суше в западной части куска (у центра сида течёт река).
        int surface = surfaceOf(world, -12, 2);
        Vector3f weatherEye = new Vector3f(-11.5f, surface + 2.8f, 2.5f);
        // Навес над частью кадра: снег под ним идти не должен — это и есть
        // проверка карты крыш.
        int roofY = surface + 4;
        for (int x = -20; x < -13; x++)
            for (int z = -6; z < 1; z++)
                world.setBlock(x, roofY, z, BlockType.PLANKS);
        // Сцена «сочности» — на своей ровной площадке в стороне от погодной
        // камеры: рамка на блоке, обломки только что выбитого, искры удара и
        // россыпь предметов на траве. Площадка ровняется, иначе на холмах
        // предметы разбегаются по уступам и в кадре их не разглядеть.
        int gy = java.lang.Math.max(World.SEA_LEVEL + 1, surfaceOf(world, 22, 20));
        for (int x = 15; x <= 30; x++)
            for (int z = 12; z <= 27; z++)
                for (int y = gy - 3; y <= gy + 10; y++)
                    world.setBlock(x, y, z, y < gy ? BlockType.DIRT : y == gy ? BlockType.GRASS : BlockType.AIR);
        world.setBlock(23, gy + 1, 18, BlockType.COBBLE);
        world.setBlock(19, gy + 1, 19, BlockType.TORCH);
        Vector3f juiceEye = new Vector3f(22.5f, gy + 1 + 1.62f, 23.2f);
        List<com.mineclone.world.entity.ItemEntity> items = new ArrayList<>();
        Object[][] drops = {
                { new ItemStack(BlockType.COBBLE, 24), 21.4f, 20.6f, 0.4f },
                { ItemStack.of("iron_pickaxe"), 23.3f, 20.9f, 1.9f },
                { ItemStack.of("beef", 2), 22.3f, 21.5f, 3.1f },
                { new ItemStack(BlockType.WOOD, 1), 24.1f, 19.8f, 0.9f },
                { new ItemStack(BlockType.TORCH, 3), 20.6f, 21.4f, 2.4f },
        };
        for (Object[] d : drops) {
            var e = new com.mineclone.world.entity.ItemEntity((ItemStack) d[0], (Float) d[1], gy + 1.001f,
                    (Float) d[2], 0f, (Float) d[3]);
            e.age = 1.3f + (Float) d[3];
            e.onGround = true;
            items.add(e);
        }

        Map<Long, Mesh> opaque = new HashMap<>();
        Map<Long, Mesh> water = new HashMap<>();
        for (int cx = -RADIUS; cx <= RADIUS; cx++)
            for (int cz = -RADIUS; cz <= RADIUS; cz++) {
                MeshData[] md = mesher.buildData(world.getChunk(cx, cz));
                if (!md[0].isEmpty()) opaque.put(World.key(cx, cz), md[0].upload());
                if (!md[1].isEmpty()) water.put(World.key(cx, cz), md[1].upload());
            }

        Matrix4f proj = new Matrix4f().perspective((float) java.lang.Math.toRadians(70f),
                (float) W / H, 0.1f, 600f);

        Shot[] shots = {
            new Shot("snowfall", 1.2f, 0.85f, 0f, 0.2f, 0.9f, 0.55f, 0f, 0, -0.10f, 1.4f, 0.6f),
            new Shot("blizzard", 2.6f, 1f, 0f, 1f, 1f, 0.22f, 0f, 0, -0.05f, 4.2f, 1.2f),
            new Shot("rainstorm", 1.3f, 0f, 1f, 1f, 1f, 0.42f, 0f, 0, -0.08f, 1.5f, 3.0f),
            new Shot("aurora", 4.75f, 0f, 0f, 0f, 0f, 1f, 1f, 0, 0.45f, 0.3f, 0.2f),
            new Shot("moon-full", 3.75f, 0f, 0f, 0f, 0f, 1f, 0f, 0, 0.62f, 0f, 0f),
            new Shot("moon-quarter", 3.75f, 0f, 0f, 0f, 0f, 1f, 0f, 2, 0.62f, 0f, 0f),
            new Shot("moon-new", 3.75f, 0f, 0f, 0f, 0f, 1f, 0f, 4, 0.62f, 0f, 0f),
            new Shot("mist-dawn", 0.22f, 0f, 0f, 0f, 0.1f, 1f, 0f, 0, -0.12f, 0.6f, 0.2f,
                    new Extra(0.045f, 0.0015f, 0f, true, false, 0f, 0f, 1f)),
            new Shot("light-shafts", 0.55f, 0f, 0f, 0f, 0f, 1f, 0f, 0, 0.18f, 0.6f, 0.2f,
                    new Extra(0f, 0.0035f, 0f, true, false, 0f, 0f, 1f)),
            new Shot("frost", 1.2f, 0.4f, 0f, 0.1f, 0.6f, 0.75f, 0f, 0, -0.08f, 1.2f, 0.4f,
                    new Extra(0f, 0.002f, 1f, false, false, 0f, 0f, 1f)),
            new Shot("inspect", 1.2f, 0f, 0f, 0f, 0f, 1f, 0f, 0, -0.05f, 0.5f, 0.2f,
                    new Extra(0f, 0.002f, 0f, true, true, 1f, 0f, 0.18f)),
            new Shot("swing-trail", 1.2f, 0f, 0f, 0f, 0f, 1f, 0f, 0, -0.05f, 0.5f, 0.2f,
                    new Extra(0f, 0.002f, 0f, true, true, 0f, 0.62f, 0.9f)),
            new Shot("juice", 1.2f, 0f, 0f, 0f, 0f, 1f, 0f, 0, -0.62f, 0.5f, 0.2f,
                    new Extra(0f, 0.0015f, 0f, true, false, 0f, 0f, 1f, true)),
            new Shot("juice-night", 4.4f, 0f, 0f, 0f, 0f, 1f, 0f, 0, -0.62f, 0.5f, 0.2f,
                    new Extra(0f, 0.0015f, 0f, false, false, 0f, 0f, 1f, true)),
        };

        Files.createDirectories(Path.of("out-test/previews"));
        BufferedImage previous = null;
        for (Shot s : shots) {
            float daylight = java.lang.Math.max(0f, (float) java.lang.Math.sin(s.gameTime));
            // Сияние висит над северным горизонтом, а север этого мира — −X.
            Vector3f fwd = s.name.equals("aurora")
                    ? new Vector3f(-1f, s.lookUp, 0.3f).normalize()
                    : new Vector3f(0f, s.lookUp, -1f).normalize();
            Vector3f eye = s.x.juice() ? juiceEye : weatherEye;
            Matrix4f view = new Matrix4f().lookAt(eye, new Vector3f(eye).add(fwd), new Vector3f(0, 1, 0));
            boolean moonUp = java.lang.Math.sin(s.gameTime) <= 0.0;
            float moonK = moonUp ? NightSky.moonlight(s.moonPhase) : 1f;

            Vector3f skySrgb = skyColor(daylight);
            skySrgb.lerp(new Vector3f(0.32f, 0.36f, 0.42f).mul(0.15f + daylight * 0.85f), s.clouds * 0.62f);
            Vector3f skyLin = linear(skySrgb);
            Vector3f lightCol = SunLight.lightColor(s.gameTime)
                    .mul((1f - s.clouds * 0.72f - s.storm * 0.10f) * moonK);
            Vector3f skyAmb = SunLight.skyAmbient(skyLin, daylight)
                    .mul(daylight + (1f - daylight) * (0.62f + 0.38f * NightSky.moonlight(s.moonPhase)))
                    .mul(1f - s.clouds * 0.12f - s.storm * 0.08f);
            skyAmb.add(0.006f * s.aurora, 0.034f * s.aurora, 0.018f * s.aurora);

            Vector3f horizon = new Vector3f(skyLin).mul(1.32f);
            Vector3f hazeCol = linear(new Vector3f(0.40f, 0.44f, 0.50f))
                    .lerp(linear(new Vector3f(0.78f, 0.82f, 0.88f)), s.snow > 0 ? 1f : 0f)
                    .mul(0.06f + 0.94f * java.lang.Math.max(daylight, 0.05f) * moonK);
            float hazeMix = java.lang.Math.min(0.92f, (1f - s.visibility) * 1.15f);
            Vector3f fogCol = new Vector3f(horizon).lerp(hazeCol, java.lang.Math.min(1f, hazeMix * 1.1f));

            SceneLighting l = new SceneLighting();
            l.camPos.set(eye);
            l.lightDir.set(SunLight.lightDirection(s.gameTime));
            l.lightColor.set(lightCol);
            l.skyLight.set(skyAmb);
            l.groundLight.set(SunLight.groundAmbient(skyAmb));
            l.torchColor.set(1.55f, 0.88f, 0.42f);
            l.ambientColor.set(0.030f, 0.034f, 0.052f).mul(0.65f + 0.35f * daylight);
            l.fogColor.set(fogCol);
            l.fogSunColor.set(lightCol).mul(0.22f);
            float fogEnd = java.lang.Math.max(18f, RADIUS * Chunk.SIZE_X * 1.05f * s.visibility);
            l.fogEnd = fogEnd;
            l.fogStart = fogEnd * (0.10f + 0.42f * s.visibility);
            l.brightness = 1f;
            l.time = 12f;
            l.linearOut = 1f;
            l.waterTint.set(0.34f, 0.66f, 0.92f);
            l.shadows = false;
            if (s.x.shadows() && shadows.isReady()) {
                l.shadowTaps = 2;
                l.shadowStrength = SunLight.shadowStrength(s.gameTime);
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
            dome.cloudiness = s.clouds;
            dome.aurora = s.aurora;
            dome.time = 40f;
            dome.haze.set(fogCol);
            dome.hazeMix = hazeMix;
            dome.linearOut = 1f;
            sky.renderDome(new Matrix4f(proj).mul(view).invert(), dome);
            glDepthMask(false);
            sky.render(proj, view, eye, s.gameTime, daylight, 30f, 1f, s.moonPhase, s.clouds, hazeMix);
            glDepthMask(true);

            float speed = (float) java.lang.Math.hypot(s.windX, s.windZ);
            drawChunks(chunkShader, l, atlas, proj, view, opaque, false,
                    java.lang.Math.min(1.8f, 0.30f + speed * 0.34f), s.windX / java.lang.Math.max(speed, 1e-3f),
                    s.windZ / java.lang.Math.max(speed, 1e-3f));
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            glDepthMask(false);
            glDisable(GL_CULL_FACE);
            drawChunks(waterShader, l, atlas, proj, view, water, true, 0f, 1f, 0f);
            glDepthMask(true);
            glDisable(GL_BLEND);
            glEnable(GL_CULL_FACE);

            if (s.x.juice()) {
                // Обломки и искры каждый кадр заводятся заново: так снимок не
                // зависит от того, какие кадры шли перед ним.
                Debris debris = new Debris(11L);
                debris.spawn(21, gy + 1, 18, BlockType.STONE, daylight > 0f ? 1f : 0.2f, 0f);
                for (int i = 0; i < 7; i++)
                    debris.update(world, 1f / 60f);
                debrisRenderer.render(proj, view, debris, atlas, l, daylight);
                itemRenderer.render(proj, view, items, world, atlas, l, daylight);
                outline.renderBox(proj, view, new float[] { 23f, gy + 1f, 18f, 24f, gy + 2f, 19f }, eye,
                        1f, OutlineAnimator.pulse(0.4f), 1f);
                particles.emitHitImpact(24.6f, gy + 1.9f, 17.2f, -0.3f, 0f, -0.95f, true,
                        new float[] { 0.35f, 0.55f, 0.25f }, 1f, 0f);
                particles.update(0.035f);
                Vector3f right = new Vector3f(fwd).cross(0f, 1f, 0f).normalize();
                Vector3f up = new Vector3f(right).cross(fwd).normalize();
                particles.render(proj, view, right, up, atlas, daylight, 0.04f + 0.18f * daylight, 1f, 1f);
                particles.update(10f);   // гасим до следующего кадра
            }

            if (s.snow > 0f || s.rain > 0f) {
                precip.updateField(world, eye);
                Vector3f flake = PrecipitationRenderer.flakeColor(skyAmb, lightCol, fogCol);
                Vector3f drop = PrecipitationRenderer.dropColor(flake);
                precip.render(proj, view, eye, 57.3f, s.windX, s.windZ, s.snow, s.rain, s.storm,
                        flake, drop, 1f);
            }

            PostProcess.Settings ps = new PostProcess.Settings();
            ps.night = 1f - java.lang.Math.min(1f, daylight * 3.2f);
            ps.bloomStrength = 0.55f;
            ps.rayStrength = 0f;
            ps.frost = s.x.frost();
            ps.fogTime = 21f;
            ps.fogDensity = s.x.mist();
            ps.fogHaze = s.x.haze();
            ps.fogTop = World.SEA_LEVEL + 15f;
            ps.fogDepth = 13f;
            ps.fogMaxDist = 64f;
            ps.fogWindX = s.windX;
            ps.fogWindZ = s.windZ;
            ps.fogLight.set(lightCol).mul(0.7f);
            ps.fogAmbient.set(skyAmb).mul(0.6f);
            ps.camPos.set(eye);
            ps.lightDir.set(l.lightDir);
            ps.invViewProj.set(proj).mul(view).invert();
            ps.fogShadowTex = l.shadows ? shadows.texture(1) : 0;
            ps.shadowMat.set(shadows.matrix(1));
            if (s.x.hand()) {
                ps.dofStrength = s.x.inspect() * 4.5f;
                ps.dofFocus = 0.3f;
                ps.dofRange = 0.25f;
                post.resolveDepth();
                ItemStack inHand = ItemStack.of("iron_pickaxe");
                inHand.setDamage((int) ((1f - s.x.condition()) * inHand.item.durability));
                hand.render(atlas, inHand, (float) W / H, 70f, 1f, s.x.swing(),
                        0f, false, false, daylight, 1f, 1f, 0f, 1f, s.x.inspect(), 0.9f);
                if (ps.dofStrength > 0f)
                    post.resolveHandDepth();
                post.resolveColor();
            } else {
                post.resolve();
            }
            post.render(ps, null);

            BufferedImage img = grab();
            check(img, previous, s.name);
            previous = img;
            ImageIO.write(img, "png", Path.of("out-test/previews/atmo-" + s.name + ".png").toFile());
            int err = glGetError();
            if (err != GL_NO_ERROR) throw new IllegalStateException("OpenGL error " + err + " in " + s.name);
            System.out.println("rendered " + s.name);
        }

        precip.destroy();
        outline.destroy();
        debrisRenderer.destroy();
        itemRenderer.destroy();
        particles.destroy();
        hand.destroy();
        shadows.destroy();
        shadowShader.destroy();
        post.destroy();
        sky.destroy();
        chunkShader.destroy();
        waterShader.destroy();
        atlas.destroy();
        glfwDestroyWindow(window);
        glfwTerminate();
        System.out.println("OK - " + shots.length + " frames in out-test/previews/");
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
        for (int y = Chunk.SIZE_Y - 2; y > 0; y--) {
            BlockType b = world.getBlock(wx, y, wz);
            if (b != BlockType.AIR && b != BlockType.WATER)
                return y;
        }
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

    private static void check(BufferedImage img, BufferedImage prev, String name) {
        Set<Integer> colors = new HashSet<>();
        long changed = 0;
        for (int y = 0; y < H; y += 3)
            for (int x = 0; x < W; x += 3) {
                colors.add(img.getRGB(x, y));
                if (prev != null && img.getRGB(x, y) != prev.getRGB(x, y)) changed++;
            }
        if (colors.size() < 60)
            throw new IllegalStateException("Frame " + name + " is nearly flat: " + colors.size() + " colours");
        if (prev != null && changed < 500)
            throw new IllegalStateException("Frame " + name + " barely differs from the previous one");
    }
}

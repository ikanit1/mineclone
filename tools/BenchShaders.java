import com.mineclone.render.*;
import com.mineclone.world.*;
import org.joml.*;
import org.lwjgl.opengl.GL;

import java.util.*;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

/**
 * Сколько миллисекунд стоит кадр на каждом уровне качества.
 *
 * Меряется ровно то, что делает игра: проход теней, сцена в HDR-буфер,
 * bloom, god rays, композит. Окно скрытое, после каждого кадра glFinish,
 * поэтому число — это честное время GPU, а не глубина очереди команд.
 *
 * Запуск из корня репозитория:
 *   java -cp "out;libs/*" tools/BenchShaders.java
 */
public class BenchShaders {

    private static final int RADIUS = 6;      // как renderRadius по умолчанию
    private static final long SEED = 1337L;
    private static final int WARMUP = 40;
    private static final int FRAMES = 160;

    /**
     * Один замеряемый режим. lazy=false заставляет обе карты теней
     * перестраиваться каждый кадр — так конвейер работал до ленивых каскадов.
     */
    private record Config(String name, int shadowSize, int taps, float bloom, float rays,
                          boolean lazy) {}

    public static void main(String[] args) throws Exception {
        int[][] resolutions = { { 1280, 720 }, { 1920, 1080 } };
        List<Config> configs = List.of(
                new Config("post only (no shadows/bloom)", 0, 1, 0f, 0f, true),
                new Config("bloom only", 0, 1, 0.45f, 0f, true),
                new Config("Fancy 1024 PCF3  eager", 1024, 1, 0.45f, 0.7f, false),
                new Config("Fancy 1024 PCF3  lazy", 1024, 1, 0.45f, 0.7f, true),
                new Config("Ultra 2048 PCF5  eager", 2048, 2, 0.60f, 0.7f, false),
                new Config("Ultra 2048 PCF5  lazy", 2048, 2, 0.60f, 0.7f, true),
                new Config("Ultra 3072 PCF5  eager", 3072, 2, 0.60f, 0.7f, false));

        if (!glfwInit()) throw new IllegalStateException("GLFW failed");
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_SAMPLES, 4);
        long window = glfwCreateWindow(1920, 1080, "bench", 0, 0);
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

        World world = new World(SEED);
        ChunkMesher mesher = new ChunkMesher(world);
        for (int cx = -RADIUS - 1; cx <= RADIUS + 1; cx++)
            for (int cz = -RADIUS - 1; cz <= RADIUS + 1; cz++)
                world.getChunk(cx, cz);
        Map<Long, Mesh> opaque = new HashMap<>();
        Map<Long, Mesh> water = new HashMap<>();
        long tris = 0;
        for (int cx = -RADIUS; cx <= RADIUS; cx++)
            for (int cz = -RADIUS; cz <= RADIUS; cz++) {
                MeshData[] md = mesher.buildData(world.getChunk(cx, cz));
                if (!md[0].isEmpty()) { Mesh m = md[0].upload(); opaque.put(World.key(cx, cz), m); tris += m.getIndexCount() / 3; }
                if (!md[1].isEmpty()) { Mesh m = md[1].upload(); water.put(World.key(cx, cz), m); tris += m.getIndexCount() / 3; }
            }
        System.out.println("world: " + opaque.size() + " opaque chunks, " + tris + " triangles");

        int surface = surfaceAt(world, 8, 8);
        Vector3f camPos = new Vector3f(8f, surface + 6f, 26f);

        for (int[] res : resolutions) {
            int w = res[0], h = res[1];
            PostProcess post = new PostProcess(w, h);
            System.out.println();
            System.out.println("=== " + w + "x" + h + " ===");
            for (Config cfg : configs) {
                ShadowMap shadows = cfg.shadowSize() > 0 ? new ShadowMap(cfg.shadowSize()) : null;
                // Первый прогон выбрасываем целиком: драйвер на нём компилирует
                // варианты пайплайна и раскладывает свежие текстуры, и это
                // прилетает в замер десятками миллисекунд.
                measure(w, h, cfg, shadows, post, atlas, chunkShader, waterShader,
                        shadowShader, sky, camPos, surface, opaque, water);
                double ms = measure(w, h, cfg, shadows, post, atlas, chunkShader, waterShader,
                        shadowShader, sky, camPos, surface, opaque, water);
                System.out.printf("  %-28s %6.2f ms  (%5.0f fps)%n", cfg.name(), ms, 1000.0 / ms);
                if (shadows != null) shadows.destroy();
            }
            post.destroy();
        }

        sky.destroy();
        chunkShader.destroy();
        waterShader.destroy();
        shadowShader.destroy();
        atlas.destroy();
        glfwDestroyWindow(window);
        glfwTerminate();
    }

    private static double measure(int W, int H, Config cfg, ShadowMap shadows, PostProcess post,
                                  TextureAtlas atlas, Shader chunkShader, Shader waterShader,
                                  Shader shadowShader, SkyRenderer sky, Vector3f camPos,
                                  int surface, Map<Long, Mesh> opaque, Map<Long, Mesh> water) {
        Matrix4f proj = new Matrix4f().perspective((float) java.lang.Math.toRadians(70f),
                (float) W / H, 0.1f, 600f);
        long start = 0;
        for (int frame = 0; frame < WARMUP + FRAMES; frame++) {
            if (frame == WARMUP) { glFinish(); start = System.nanoTime(); }
            // Камера медленно крутится: статичный кадр не показал бы стоимость
            // перестройки каскадов и переупорядочивания вызовов.
            float a = frame * 0.01f;
            Vector3f target = new Vector3f(camPos.x + (float) java.lang.Math.sin(a) * 40f,
                    surface + 1.5f, camPos.z - 40f);
            Matrix4f view = new Matrix4f().lookAt(camPos, target, new Vector3f(0, 1, 0));
            Vector3f fwd = new Vector3f(target).sub(camPos).normalize();
            float gameTime = 0.9f;
            float daylight = (float) java.lang.Math.sin(gameTime);

            SceneLighting l = lighting(gameTime, daylight, camPos, shadows, cfg);
            Vector3f lightDir = SunLight.lightDirection(gameTime);
            if (l.shadows) {
                if (!cfg.lazy())
                    shadows.invalidate();
                shadows.update(camPos, fwd, lightDir);
                shadowPass(W, H, shadows, shadowShader, atlas, opaque);
                l.shadowMat0.set(shadows.matrix(0));
                l.shadowMat1.set(shadows.matrix(1));
                shadows.endFrame();
            }

            Vector3f skyLin = new Vector3f(0.26f, 0.53f, 0.89f);
            post.begin(skyLin.x, skyLin.y, skyLin.z);
            if (shadows != null) shadows.bind();
            sky.renderDome(new Matrix4f(proj).mul(view).invert(),
                    new Vector3f(skyLin).mul(0.7f), new Vector3f(skyLin).mul(1.32f),
                    new Vector3f(skyLin).mul(0.3f), new Vector3f(l.lightColor).mul(0.85f),
                    lightDir, 0f, 1f);
            glDepthMask(false);
            sky.render(proj, view, camPos, gameTime, daylight, frame * 0.05f, 1f);
            glDepthMask(true);

            draw(chunkShader, l, atlas, proj, view, opaque, false);
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            glDepthMask(false);
            glDisable(GL_CULL_FACE);
            draw(waterShader, l, atlas, proj, view, water, true);
            glDepthMask(true);
            glDisable(GL_BLEND);
            glEnable(GL_CULL_FACE);

            PostProcess.Settings s = new PostProcess.Settings();
            s.bloomStrength = cfg.bloom();
            s.rayStrength = cfg.rays();
            post.resolve();
            post.render(s, cfg.rays() > 0f ? new float[] { 0.5f, 0.85f } : null);
        }
        glFinish();
        return (System.nanoTime() - start) / 1e6 / FRAMES;
    }

    private static SceneLighting lighting(float gameTime, float daylight, Vector3f camPos,
                                          ShadowMap shadows, Config cfg) {
        SceneLighting l = new SceneLighting();
        Vector3f skyLin = new Vector3f(0.26f, 0.53f, 0.89f);
        l.camPos.set(camPos);
        l.lightDir.set(SunLight.lightDirection(gameTime));
        l.lightColor.set(SunLight.lightColor(gameTime));
        l.skyLight.set(SunLight.skyAmbient(skyLin, daylight));
        l.groundLight.set(SunLight.groundAmbient(l.skyLight));
        l.fogColor.set(new Vector3f(skyLin).mul(1.32f));
        l.fogStart = RADIUS * Chunk.SIZE_X * 0.55f;
        l.fogEnd = RADIUS * Chunk.SIZE_X * 1.05f;
        l.linearOut = 1f;
        l.shadowTaps = cfg.taps();
        l.shadowStrength = SunLight.shadowStrength(gameTime);
        l.shadows = shadows != null && shadows.isReady();
        if (shadows != null) {
            l.shadowTexel = 1f / shadows.getSize();
            l.shadowBias0 = SunLight.texelWorldSize(SunLight.CASCADE0_RADIUS, shadows.getSize()) * 2.2f;
            l.shadowBias1 = SunLight.texelWorldSize(SunLight.CASCADE1_RADIUS, shadows.getSize()) * 2.2f;
        }
        return l;
    }

    private static final FrustumIntersection FRUSTUM = new FrustumIntersection();

    private static void shadowPass(int W, int H, ShadowMap shadows, Shader shadowShader,
                                   TextureAtlas atlas, Map<Long, Mesh> meshes) {
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(2.2f, 4.0f);
        glDisable(GL_CULL_FACE);
        atlas.bind(0);
        Matrix4f model = new Matrix4f();
        for (int c = 0; c < ShadowMap.CASCADES; c++) {
            if (!shadows.needsRebuild(c))
                continue;
            shadows.begin(c);
            FRUSTUM.set(shadows.matrix(c));
            shadowShader.bind();
            shadowShader.setInt("uAtlas", 0);
            shadowShader.setMat4("uLightSpace", shadows.matrix(c));
            for (Map.Entry<Long, Mesh> e : meshes.entrySet()) {
                int cx = (int) (e.getKey() >> 32), cz = (int) (e.getKey() & 0xFFFFFFFFL);
                float wx = cx * Chunk.SIZE_X, wz = cz * Chunk.SIZE_Z;
                if (!FRUSTUM.testAab(wx, 0, wz, wx + Chunk.SIZE_X, Chunk.SIZE_Y, wz + Chunk.SIZE_Z))
                    continue;
                shadowShader.setMat4("uModel", model.translation(wx, 0, wz));
                e.getValue().render();
            }
            shadowShader.unbind();
        }
        shadows.end(W, H);
        glPolygonOffset(0f, 0f);
        glDisable(GL_POLYGON_OFFSET_FILL);
        glEnable(GL_CULL_FACE);
    }

    private static void draw(Shader shader, SceneLighting l, TextureAtlas atlas,
                             Matrix4f proj, Matrix4f view, Map<Long, Mesh> meshes, boolean isWater) {
        shader.bind();
        shader.setMat4("uProjection", proj);
        shader.setMat4("uView", view);
        shader.setInt("uAtlas", 0);
        l.apply(shader);
        if (isWater) shader.setVec3("uWaterTint", l.waterTint);
        atlas.bind(0);
        FRUSTUM.set(new Matrix4f(proj).mul(view));
        Matrix4f model = new Matrix4f();
        for (Map.Entry<Long, Mesh> e : meshes.entrySet()) {
            int cx = (int) (e.getKey() >> 32), cz = (int) (e.getKey() & 0xFFFFFFFFL);
            float wx = cx * Chunk.SIZE_X, wz = cz * Chunk.SIZE_Z;
            if (!FRUSTUM.testAab(wx, 0, wz, wx + Chunk.SIZE_X, Chunk.SIZE_Y, wz + Chunk.SIZE_Z))
                continue;
            shader.setMat4("uModel", model.translation(wx, 0, wz));
            e.getValue().render();
        }
        shader.unbind();
    }

    private static int surfaceAt(World world, int x, int z) {
        for (int y = Chunk.SIZE_Y - 1; y > 0; y--)
            if (world.getBlock(x, y, z) != BlockType.AIR) return y;
        return World.SEA_LEVEL;
    }
}

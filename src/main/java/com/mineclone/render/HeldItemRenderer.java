package com.mineclone.render;

import com.mineclone.world.BlockType;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.glDeleteBuffers;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30.glDeleteVertexArrays;

/**
 * Вид от первого лица: рука и то, что в ней.
 *
 * Рука — обычный текстурированный бокс из {@link PlayerSkin}, рисуемый тем же
 * кубом и шейдером, что и части тела мобов ({@link MobRenderer}). Она видна
 * ВСЕГДА: и с пустой ладонью, и когда игрок держит блок — предмет висит перед
 * кулаком, а не сам по себе.
 *
 * Позы вынесены в статические {@link #armPose} и {@link #itemPose}: это чистая
 * матричная математика без GL, её можно считать в оффлайне и смотреть, что
 * получилось, не запуская игру.
 */
public final class HeldItemRenderer {

    // Габариты руки в единицах вида. Рука — вытянутый бокс, +Y локально
    // указывает на плечо, −Y на кулак (как на тайлах скина).
    private static final float ARM_W = 0.22f;
    private static final float ARM_L = 0.72f;

    private final Shader blockShader;
    private final Shader armShader;
    private final Map<BlockType, Mesh> blockMeshes = new EnumMap<>(BlockType.class);
    private final int armVao, armVbo;
    private final int armTexture;

    public HeldItemRenderer() {
        this.blockShader = new Shader(Shaders.CHUNK_VERTEX, Shaders.CHUNK_FRAGMENT);
        this.armShader = new Shader(Shaders.MOB_VERTEX, Shaders.MOB_FRAGMENT);
        int[] ids = MobRenderer.createCubeVao();
        this.armVao = ids[0];
        this.armVbo = ids[1];
        this.armTexture = MobRenderer.uploadTexture(PlayerSkin.load());
    }

    // -------------------------------------------------------------------------
    //  Позы — чистая математика, без GL
    // -------------------------------------------------------------------------

    /**
     * Матрица руки. Локальный бокс развёрнут rotateZ(180°): на тайле скина
     * плечо сверху, а на экране кисть должна смотреть вверх-влево, к прицелу.
     *
     * @param holding true — в руке предмет: кисть уходит ниже и правее, чтобы
     *                блок сел перед ней, а не внутрь неё.
     */
    public static Matrix4f armPose(float equipProgress, float swingProgress,
                                   float walkDistance, boolean viewBobbing, boolean holding) {
        float arc = swingArc(swingProgress);
        float yaw = swingYaw(swingProgress);
        float eq = equipEase(equipProgress);
        float drop = (1f - eq) * 0.75f;
        float tilt = (1f - eq) * (float) Math.toRadians(55f);
        float bobX = bobX(walkDistance, viewBobbing);
        float bobY = bobY(walkDistance, viewBobbing);

        if (holding) {
            // Кулак под предметом: почти вертикально, чуть завален внутрь.
            return new Matrix4f()
                    .translate(0.74f + bobX + arc * 0.04f,
                               -0.80f - drop + bobY + arc * 0.10f,
                               -0.80f - arc * 0.06f)
                    .rotateZ((float) Math.PI + (float) Math.toRadians(30f + arc * 6f))
                    .rotateY((float) Math.toRadians(-14f + yaw * 6f))
                    .rotateX((float) Math.toRadians(10f + arc * 10f) - tilt * 0.6f)
                    .scale(ARM_W, ARM_L, ARM_W);
        }
        // Пустая рука: предплечье наискось из правого нижнего угла, замах
        // выносит кулак к центру экрана.
        return new Matrix4f()
                .translate(0.68f + bobX - arc * 0.24f,
                           -0.64f - drop + bobY + arc * 0.12f,
                           -0.95f - arc * 0.16f)
                .rotateZ((float) Math.PI + (float) Math.toRadians(18f + arc * 14f))
                .rotateY((float) Math.toRadians(-15f + yaw * 12f))
                .rotateX((float) Math.toRadians(-15f + arc * 20f) - tilt)
                .scale(ARM_W, ARM_L, ARM_W);
    }

    /**
     * Матрица предмета. Блок повёрнут на 45° по рысканью и наклонён вперёд —
     * так видно верх и две боковые грани сразу, иначе куб читается плоским
     * квадратом.
     */
    public static Matrix4f itemPose(float equipProgress, float swingProgress,
                                    float walkDistance, boolean viewBobbing) {
        float arc = swingArc(swingProgress);
        float yaw = swingYaw(swingProgress);
        float eq = equipEase(equipProgress);
        float drop = (1f - eq) * 0.62f;
        float tilt = (1f - eq) * (float) Math.toRadians(55f);

        return new Matrix4f()
                .translate(0.27f + bobX(walkDistance, viewBobbing) + arc * 0.08f,
                           -0.27f - drop + bobY(walkDistance, viewBobbing) - arc * 0.10f,
                           -0.76f - arc * 0.10f)
                .rotateY((float) Math.toRadians(48f + yaw * 14f))
                .rotateX((float) Math.toRadians(-14f - arc * 14f) + tilt * 0.6f)
                .rotateZ((float) Math.toRadians(6f - arc * 12f))
                .scale(0.34f);
    }

    /** Проекция вида от первого лица — отдельная от мировой, с узким near. */
    public static Matrix4f projection(float aspect, float fovDegrees) {
        return new Matrix4f().perspective(
                (float) Math.toRadians(70f),
                aspect, 0.05f, 20f);
    }

    /**
     * Дуга замаха: короткий занос, резкий удар, мягкий возврат. Степень 1.5
     * даёт пик острее чистого синуса — как в MC.
     */
    private static float swingArc(float swingProgress) {
        float s = 1f - clamp01(swingProgress);
        return (float) Math.sin(Math.sqrt(s) * Math.PI);
    }

    /** Горизонтальная составляющая замаха: кисть уходит влево и возвращается. */
    private static float swingYaw(float swingProgress) {
        float s = 1f - clamp01(swingProgress);
        return (float) -Math.sin(s * Math.PI * 2.0);
    }

    /** Кубический ease-out доставания предмета: быстро вверх, мягкая посадка. */
    private static float equipEase(float equipProgress) {
        float e = 1f - clamp01(equipProgress);
        return 1f - e * e * e;
    }

    // Фигура Лиссажу для покачивания при ходьбе: по X один период, по Y два —
    // именно удвоение по вертикали даёт «восьмёрку», а не круг.
    private static float bobX(float walkDistance, boolean on) {
        return on ? (float) Math.cos(walkDistance * Math.PI) * 0.022f : 0f;
    }

    private static float bobY(float walkDistance, boolean on) {
        return on ? (float) Math.sin(walkDistance * Math.PI * 2f) * 0.016f : 0f;
    }

    // -------------------------------------------------------------------------
    //  Отрисовка
    // -------------------------------------------------------------------------

    public void render(TextureAtlas atlas, BlockType held, float aspect,
            float fovDegrees, float equipProgress, float swingProgress,
            float walkDistance, boolean underwater, boolean viewBobbing,
            float daylight, float brightness, float skyFrac, float blockFrac) {

        // Единый источник правды по свету — повторяет шейдер чанков:
        // effectiveLight = max(sky·daylight, blockLight).
        float ambient = 0.04f + 0.18f * daylight;
        float effectiveLight = Math.max(skyFrac * daylight, blockFrac);
        float armLight = (float) Math.pow(Math.max(ambient, effectiveLight), 0.75) * brightness;

        Matrix4f projection = projection(aspect, fovDegrees);
        Matrix4f view = new Matrix4f().translate(
                -0.48f * Math.max(0f, 1f - aspect / (16f / 9f)), 0f, 0f);
        boolean holding = held != null && held != BlockType.AIR;

        // Собственный чистый z-буфер на первый план: без него задние грани
        // бокса руки перекрывают передние (обход вершин куба не гарантирован,
        // поэтому на куллинг здесь полагаться нельзя), а предмет не может
        // корректно закрыть кулак.
        glDepthMask(true);            // без записи в глубину glClear ничего не очистит
        glClear(GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST);
        glDisable(GL_BLEND);
        glDisable(GL_CULL_FACE);

        // --- рука: видна всегда, в том числе с предметом ---------------------
        armShader.bind();
        armShader.setMat4("uProjection", projection);
        armShader.setMat4("uView", view);
        armShader.setMat4("uModel", armPose(equipProgress, swingProgress,
                walkDistance, viewBobbing, holding));
        armShader.setInt("uSkin", 0);
        armShader.setVec2("uTileSize", MobRenderer.TILE_U, MobRenderer.TILE_V);
        armShader.setVec2("uUvFront", MobRenderer.uvX(MobSkins.T_ACCENT),
                MobRenderer.uvY(MobSkins.T_ACCENT));
        armShader.setVec2("uUvSide", MobRenderer.uvX(MobSkins.T_LIMB),
                MobRenderer.uvY(MobSkins.T_LIMB));
        armShader.setVec2("uUvTop", MobRenderer.uvX(MobSkins.T_BODY_TOP),
                MobRenderer.uvY(MobSkins.T_BODY_TOP));
        armShader.setFloat("uLight", Math.min(1f, armLight));
        // Под водой рука уходит в холодный синий — как и всё остальное.
        armShader.setVec3("uTint", underwater ? UNDERWATER_TINT : NO_TINT);
        armShader.setVec3("uFogColor", new Vector3f(0f, 0f, 0f));
        armShader.setFloat("uFogStart", 100f);
        armShader.setFloat("uFogEnd", 120f);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, armTexture);
        glBindVertexArray(armVao);
        glDrawArrays(GL_TRIANGLES, 0, MobRenderer.VERTEX_COUNT);
        glBindVertexArray(0);
        armShader.unbind();

        // --- предмет ---------------------------------------------------------
        if (holding) {
            Mesh mesh = blockMeshes.computeIfAbsent(held, HeldItemRenderer::createItemMesh);
            // Факел светит сам — не даём ему потемнеть в руке.
            float itemLight = held == BlockType.TORCH
                    ? Math.max(effectiveLight, 0.75f) : effectiveLight;
            blockShader.bind();
            blockShader.setMat4("uProjection", projection);
            blockShader.setMat4("uView", view);
            blockShader.setInt("uAtlas", 0);
            blockShader.setVec3("uFogColor", new Vector3f(0, 0, 0));
            blockShader.setFloat("uFogStart", 100f);
            blockShader.setFloat("uFogEnd", 120f);
            blockShader.setFloat("uAmbient", ambient);
            blockShader.setFloat("uDaylight", itemLight);
            blockShader.setFloat("uBrightness", brightness);
            blockShader.setFloat("uTime", 0f);
            atlas.bind(0);
            blockShader.setMat4("uModel",
                    itemPose(equipProgress, swingProgress, walkDistance, viewBobbing));
            mesh.render();
            blockShader.unbind();
        }

        glEnable(GL_CULL_FACE);
    }

    private static final Vector3f NO_TINT = new Vector3f(1f, 1f, 1f);
    private static final Vector3f UNDERWATER_TINT = new Vector3f(0.62f, 0.78f, 1.0f);

    /** Dispatches to the correct mesh builder for each block type. */
    private static Mesh createItemMesh(BlockType block) {
        if (block == BlockType.TORCH) return createTorchMesh();
        return createBlockMesh(block);
    }

    /** Cross (+) mesh matching ChunkMesher.emitCross but in local [-0.5,+0.5] space. */
    private static Mesh createTorchMesh() {
        List<Float> pos = new ArrayList<>();
        List<Float> uvs = new ArrayList<>();
        List<Float> light = new ArrayList<>();
        List<Float> blockLight = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
        float[] uv = TextureAtlas.uv(BlockType.TORCH.sideTile);
        float u0 = uv[0], v0 = uv[1], u1 = uv[2], v1 = uv[3];
        float h = 10f / 16f; // same proportion as world torch
        // planes centred at origin: x/z in [-0.5, 0.5], y in [-h/2, h/2]
        float y0 = -h * 0.5f, y1 = h * 0.5f;
        float[][][] planes = {
            { {-0.5f, y0, 0f}, { 0.5f, y0, 0f}, { 0.5f, y1, 0f}, {-0.5f, y1, 0f} }, // A front
            { { 0.5f, y0, 0f}, {-0.5f, y0, 0f}, {-0.5f, y1, 0f}, { 0.5f, y1, 0f} }, // A back
            { { 0f, y0, -0.5f}, { 0f, y0,  0.5f}, { 0f, y1,  0.5f}, { 0f, y1, -0.5f} }, // B front
            { { 0f, y0,  0.5f}, { 0f, y0, -0.5f}, { 0f, y1, -0.5f}, { 0f, y1,  0.5f} }, // B back
        };
        float[][] uvQ = { {u0, v1}, {u1, v1}, {u1, v0}, {u0, v0} };
        for (float[][] quad : planes) {
            int base = pos.size() / 3;
            for (int i = 0; i < 4; i++) {
                pos.add(quad[i][0]); pos.add(quad[i][1]); pos.add(quad[i][2]);
                uvs.add(uvQ[i][0]); uvs.add(uvQ[i][1]);
                light.add(1.0f); blockLight.add(0f);
            }
            indices.add(base); indices.add(base + 1); indices.add(base + 2);
            indices.add(base); indices.add(base + 2); indices.add(base + 3);
        }
        return new Mesh(toFloatArray(pos), toFloatArray(uvs), toFloatArray(light),
                toFloatArray(blockLight), toIntArray(indices));
    }

    private static Mesh createBlockMesh(BlockType block) {
        List<Float> pos = new ArrayList<>();
        List<Float> uvs = new ArrayList<>();
        List<Float> light = new ArrayList<>();
        List<Float> blockLight = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
        emitBox(pos, uvs, light, blockLight, indices,
                block.sideTile, block.topTile, block.bottomTile);
        return new Mesh(toFloatArray(pos), toFloatArray(uvs), toFloatArray(light),
                toFloatArray(blockLight), toIntArray(indices));
    }

    private static void emitBox(List<Float> pos, List<Float> uvs,
            List<Float> light, List<Float> blockLight, List<Integer> indices,
            int sideTile, int topTile, int bottomTile) {
        float a = -0.5f, b = 0.5f;
        float[][][] faces = {
                { { a, a, b }, { b, a, b }, { b, b, b }, { a, b, b } },
                { { b, a, a }, { a, a, a }, { a, b, a }, { b, b, a } },
                { { b, a, b }, { b, a, a }, { b, b, a }, { b, b, b } },
                { { a, a, a }, { a, a, b }, { a, b, b }, { a, b, a } },
                { { a, b, b }, { b, b, b }, { b, b, a }, { a, b, a } },
                { { a, a, a }, { b, a, a }, { b, a, b }, { a, a, b } }
        };
        int[] tiles = { sideTile, sideTile, sideTile, sideTile, topTile, bottomTile };
        float[] faceLight = { 0.88f, 0.88f, 0.80f, 0.80f, 1.0f, 0.65f };
        for (int f = 0; f < faces.length; f++) {
            float[] uv = TextureAtlas.uv(Math.max(0, tiles[f]));
            float[][] uvQ = {
                    { uv[0], uv[3] }, { uv[2], uv[3] }, { uv[2], uv[1] }, { uv[0], uv[1] }
            };
            int base = pos.size() / 3;
            for (int i = 0; i < 4; i++) {
                pos.add(faces[f][i][0]);
                pos.add(faces[f][i][1]);
                pos.add(faces[f][i][2]);
                uvs.add(uvQ[i][0]);
                uvs.add(uvQ[i][1]);
                light.add(faceLight[f]);
                blockLight.add(0f);
            }
            indices.add(base);
            indices.add(base + 1);
            indices.add(base + 2);
            indices.add(base);
            indices.add(base + 2);
            indices.add(base + 3);
        }
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static float[] toFloatArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++)
            arr[i] = list.get(i);
        return arr;
    }

    private static int[] toIntArray(List<Integer> list) {
        int[] arr = new int[list.size()];
        for (int i = 0; i < list.size(); i++)
            arr[i] = list.get(i);
        return arr;
    }

    public void destroy() {
        for (Mesh mesh : blockMeshes.values())
            mesh.destroy();
        blockMeshes.clear();
        glDeleteVertexArrays(armVao);
        glDeleteBuffers(armVbo);
        glDeleteTextures(armTexture);
        blockShader.destroy();
        armShader.destroy();
    }
}

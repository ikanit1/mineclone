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
    private static final float EMPTY_ARM_W = 0.282f;

    private final Shader blockShader;
    private final Shader armShader;
    private final Map<BlockType, Mesh> blockMeshes = new EnumMap<>(BlockType.class);
    // Ключ — сам предмет, а не его тайл: два предмета с одной иконкой
    // остаются двумя предметами, и делить меш между ними незачем.
    private final Map<com.mineclone.item.Item, Mesh> toolMeshes = new java.util.HashMap<>();
    private final Map<com.mineclone.item.Item, Mesh> materialMeshes = new java.util.HashMap<>();
    private final int armVao, armVbo;
    private final int armTexture;

    private final Shader crackShader;
    private final Shader trailShader;
    private final int trailVao, trailVbo;
    private final java.nio.FloatBuffer trailBuf =
            org.lwjgl.system.MemoryUtil.memAllocFloat(TRAIL_SAMPLES * 2 * 4);

    public HeldItemRenderer() {
        this.blockShader = new Shader(Shaders.CHUNK_VERTEX, Shaders.CHUNK_FRAGMENT);
        this.armShader = new Shader(Shaders.MOB_VERTEX, Shaders.MOB_FRAGMENT);
        this.crackShader = new Shader(Shaders.CHUNK_VERTEX, Shaders.ITEM_CRACK_FRAGMENT);
        this.trailShader = new Shader(Shaders.TRAIL_VERTEX, Shaders.TRAIL_FRAGMENT);
        int[] ids = MobRenderer.createCubeVao();
        this.armVao = ids[0];
        this.armVbo = ids[1];
        this.armTexture = MobRenderer.uploadTexture(PlayerSkin.load());

        trailVao = org.lwjgl.opengl.GL30.glGenVertexArrays();
        trailVbo = org.lwjgl.opengl.GL15.glGenBuffers();
        org.lwjgl.opengl.GL30.glBindVertexArray(trailVao);
        glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, trailVbo);
        org.lwjgl.opengl.GL15.glBufferData(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER,
                (long) TRAIL_SAMPLES * 2 * 4 * Float.BYTES, org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW);
        org.lwjgl.opengl.GL20.glVertexAttribPointer(0, 3, GL_FLOAT, false, 4 * Float.BYTES, 0L);
        org.lwjgl.opengl.GL20.glEnableVertexAttribArray(0);
        org.lwjgl.opengl.GL20.glVertexAttribPointer(1, 1, GL_FLOAT, false, 4 * Float.BYTES, 3L * Float.BYTES);
        org.lwjgl.opengl.GL20.glEnableVertexAttribArray(1);
        glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, 0);
        org.lwjgl.opengl.GL30.glBindVertexArray(0);
    }

    private static void glBindBuffer(int target, int buffer) {
        org.lwjgl.opengl.GL15.glBindBuffer(target, buffer);
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
        return armPose(equipProgress, swingProgress, walkDistance, viewBobbing, holding, 0f);
    }

    /**
     * @param inspect 0..1 — насколько игрок сейчас разглядывает предмет: кисть
     *                уходит ниже, уступая центр кадра самому предмету
     */
    public static Matrix4f armPose(float equipProgress, float swingProgress,
                                   float walkDistance, boolean viewBobbing, boolean holding,
                                   float inspect) {
        float arc = swingArc(swingProgress);
        float yaw = swingYaw(swingProgress);
        float eq = equipEase(equipProgress);
        float ins = smooth01(inspect);
        float drop = (1f - eq) * 0.75f + ins * 0.34f;
        float tilt = (1f - eq) * (float) Math.toRadians(55f);
        float bobX = bobX(walkDistance, viewBobbing) - ins * 0.10f;
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
        // Пустая рука: две широкие грани предплечья сходятся у кисти.
        // Торец отвёрнут от глаз, плечо уходит за нижний край кадра.
        // Поза подобрана по силуэту при отдельном FOV руки 70 градусов.
        return new Matrix4f()
                .translate(0.710f + bobX - arc * 0.24f,
                           -0.680f - drop + bobY + arc * 0.12f,
                           -0.95f - arc * 0.16f)
                .rotateZ((float) Math.PI + (float) Math.toRadians(-34f + arc * 14f))
                .rotateY((float) Math.toRadians(4.4f + yaw * 12f))
                .rotateX((float) Math.toRadians(52.7f + arc * 20f) - tilt)
                .scale(EMPTY_ARM_W, ARM_L, EMPTY_ARM_W);
    }

    /**
     * Матрица предмета. Блок повёрнут на 45° по рысканью и наклонён вперёд —
     * так видно верх и две боковые грани сразу, иначе куб читается плоским
     * квадратом.
     */
    public static Matrix4f itemPose(float equipProgress, float swingProgress,
                                    float walkDistance, boolean viewBobbing) {
        return itemPose(equipProgress, swingProgress, walkDistance, viewBobbing, 0f, 0f);
    }

    /**
     * @param inspect 0..1 — переход в позу осмотра: предмет выезжает к центру
     *                кадра, ближе к глазам, и медленно вращается
     * @param spin    угол вращения при осмотре, радианы
     */
    public static Matrix4f itemPose(float equipProgress, float swingProgress,
                                    float walkDistance, boolean viewBobbing,
                                    float inspect, float spin) {
        float arc = swingArc(swingProgress) * (1f - inspect);
        float yaw = swingYaw(swingProgress) * (1f - inspect);
        float eq = equipEase(equipProgress);
        float drop = (1f - eq) * 0.62f;
        float tilt = (1f - eq) * (float) Math.toRadians(55f);
        float k = smooth01(inspect);

        return new Matrix4f()
                .translate(lerp(0.27f + bobX(walkDistance, viewBobbing) + arc * 0.08f, 0.10f, k),
                           lerp(-0.27f - drop + bobY(walkDistance, viewBobbing) - arc * 0.10f, -0.13f, k),
                           lerp(-0.76f - arc * 0.10f, -0.60f, k))
                .rotateY(lerp((float) Math.toRadians(48f + yaw * 14f), spin, k))
                .rotateX(lerp((float) Math.toRadians(-14f - arc * 14f) + tilt * 0.6f,
                        (float) Math.toRadians(-24f), k))
                .rotateZ(lerp((float) Math.toRadians(6f - arc * 12f), 0f, k))
                .scale(lerp(0.34f, 0.30f, k));
    }

    /**
     * Матрица инструмента.
     *
     * Своя, а не общая с блоком: блок — это куб вокруг центра, а инструмент —
     * плоский спрайт, который держат за рукоять. Общая поза вешала его в
     * воздухе рядом с кулаком. На спрайте рукоять внизу справа, а головка
     * сверху слева, поэтому доворачивать его почти не нужно — нужно посадить
     * правый нижний угол в кулак.
     */
    public static Matrix4f toolPose(float equipProgress, float swingProgress,
                                    float walkDistance, boolean viewBobbing) {
        return toolPose(equipProgress, swingProgress, walkDistance, viewBobbing, 0f, 0f);
    }

    public static Matrix4f toolPose(float equipProgress, float swingProgress,
                                    float walkDistance, boolean viewBobbing,
                                    float inspect, float spin) {
        float arc = swingArc(swingProgress) * (1f - inspect);
        float yaw = swingYaw(swingProgress) * (1f - inspect);
        float eq = equipEase(equipProgress);
        float drop = (1f - eq) * 0.62f;
        float tilt = (1f - eq) * (float) Math.toRadians(55f);
        float k = smooth01(inspect);

        // Смещения замаха повторяют руку знак в знак: разойдись они — кирка
        // на пике удара отрывается от кулака и летит отдельно. Сам удар
        // читается доворотом, а не расхождением.
        return new Matrix4f()
                .translate(lerp(0.33f + bobX(walkDistance, viewBobbing) + arc * 0.04f, 0.07f, k),
                           lerp(-0.26f - drop + bobY(walkDistance, viewBobbing) + arc * 0.10f, -0.10f, k),
                           lerp(-0.74f - arc * 0.06f, -0.62f, k))
                .rotateY(lerp((float) Math.toRadians(14f + yaw * 12f), spin, k))
                .rotateZ(lerp((float) Math.toRadians(-8f - arc * 26f), (float) Math.toRadians(-38f), k))
                .rotateX(lerp((float) Math.toRadians(-6f - arc * 18f) + tilt * 0.6f,
                        (float) Math.toRadians(-8f), k))
                .scale(lerp(0.42f, 0.52f, k));
    }

    // -------------------------------------------------------------------------
    //  След взмаха — тоже чистая математика
    // -------------------------------------------------------------------------

    /** Сколько отсчётов в следе: по два на отсчёт — головка и середина рукояти. */
    public static final int TRAIL_SAMPLES = 14;
    /** Насколько назад по фазе замаха тянется след. */
    public static final float TRAIL_SPAN = 0.42f;
    /** Головка и середина инструмента в локальных координатах спрайта. */
    private static final float[] TOOL_HEAD = { -0.58f, 0.58f };
    private static final float[] TOOL_MID = { -0.05f, 0.05f };

    /**
     * Лента следа за головкой инструмента, в координатах вида от первого лица.
     *
     * Никакой истории кадров: поза — аналитическая функция фазы замаха,
     * поэтому «где головка была мгновение назад» просто считается по более
     * ранней фазе. След от этого не рвётся при просадке FPS и не зависит от
     * того, сколько кадров успело пройти.
     *
     * @return {x, y, z, alpha} × (TRAIL_SAMPLES × 2), вершины полосы по порядку;
     *         пустой массив — следа нет (замах кончился или не начинался)
     */
    public static float[] trailStrip(float equipProgress, float swingProgress,
                                     float walkDistance, boolean viewBobbing) {
        if (swingProgress <= 0.02f || swingProgress >= 0.995f)
            return new float[0];
        float[] out = new float[TRAIL_SAMPLES * 2 * 4];
        org.joml.Vector4f v = new org.joml.Vector4f();
        for (int i = 0; i < TRAIL_SAMPLES; i++) {
            float t = i / (float) (TRAIL_SAMPLES - 1);          // 0 — сейчас, 1 — хвост
            float phase = Math.min(1f, swingProgress + t * TRAIL_SPAN);
            Matrix4f pose = toolPose(equipProgress, phase, walkDistance, viewBobbing);
            // Яркость по скорости головки: медленный возврат следа не оставляет.
            float speed = Math.abs(swingArc(phase) - swingArc(Math.min(1f, phase + 0.04f))) / 0.04f;
            float a = (1f - t) * Math.min(1f, speed * 0.35f) * 0.85f;
            pose.transform(v.set(TOOL_HEAD[0], TOOL_HEAD[1], 0f, 1f));
            int o = i * 8;
            out[o] = v.x; out[o + 1] = v.y; out[o + 2] = v.z; out[o + 3] = a;
            pose.transform(v.set(TOOL_MID[0], TOOL_MID[1], 0f, 1f));
            out[o + 4] = v.x; out[o + 5] = v.y; out[o + 6] = v.z; out[o + 7] = 0f;
        }
        return out;
    }

    /**
     * Стадия трещин 0..9 для остатка прочности, или −1 — инструмент ещё цел.
     * Трещины появляются, когда износ перевалил за треть: новый инструмент
     * с первой же царапины выглядел бы старым.
     */
    public static int crackStage(float condition) {
        if (condition >= 0.66f)
            return -1;
        float wear = (0.66f - Math.max(0f, condition)) / 0.66f;
        return Math.min(9, (int) (wear * 10f));
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

    public void render(TextureAtlas atlas, com.mineclone.world.ItemStack held,
            float aspect, float fovDegrees, float equipProgress, float swingProgress,
            float walkDistance, boolean underwater, boolean viewBobbing,
            float daylight, float brightness, float skyFrac, float blockFrac,
            float linearOut) {
        render(atlas, held, aspect, fovDegrees, equipProgress, swingProgress,
                walkDistance, underwater, viewBobbing, daylight, brightness, skyFrac, blockFrac,
                linearOut, 0f, 0f);
    }

    /**
     * @param inspect 0..1 — поза осмотра предмета
     * @param spin    угол вращения предмета при осмотре, радианы
     */
    public void render(TextureAtlas atlas, com.mineclone.world.ItemStack held,
            float aspect, float fovDegrees, float equipProgress, float swingProgress,
            float walkDistance, boolean underwater, boolean viewBobbing,
            float daylight, float brightness, float skyFrac, float blockFrac,
            float linearOut, float inspect, float spin) {
        BlockType heldBlock = held == null ? null : held.block();
        com.mineclone.item.Item heldTool = held != null && held.tool() != null ? held.item : null;

        // Единый источник правды по свету — уровень освещённости там, где стоит
        // игрок. Рука и предмет берут одну и ту же настройку, поэтому не могут
        // разъехаться по яркости.
        float effectiveLight = Math.max(skyFrac * daylight, blockFrac);
        float handLevel = Math.max(0.10f, effectiveLight);
        SceneLighting handLight = SceneLighting.firstPerson(brightness, handLevel);
        handLight.linearOut = linearOut;

        Matrix4f projection = projection(aspect, fovDegrees);
        Matrix4f view = new Matrix4f().translate(
                -0.48f * Math.max(0f, 1f - aspect / (16f / 9f)), 0f, 0f);
        boolean holding = held != null && held.item != null
                && (heldBlock != null && heldBlock != BlockType.AIR || heldTool != null || held.iconTile() >= 0);

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
                walkDistance, viewBobbing, holding, inspect));
        armShader.setInt("uSkin", 0);
        // Четыре широких пикселя поперёк грани и двенадцать вдоль руки.
        armShader.setVec2("uSkinGrid", MobSkins.COLS * 4f, MobSkins.ROWS * 12f);
        armShader.setVec2("uTileSize", MobRenderer.TILE_U, MobRenderer.TILE_V);
        armShader.setVec2("uUvFront", MobRenderer.uvX(MobSkins.T_ACCENT),
                MobRenderer.uvY(MobSkins.T_ACCENT));
        armShader.setVec2("uUvSide", MobRenderer.uvX(MobSkins.T_LIMB),
                MobRenderer.uvY(MobSkins.T_LIMB));
        armShader.setVec2("uUvTop", MobRenderer.uvX(MobSkins.T_BODY_TOP),
                MobRenderer.uvY(MobSkins.T_BODY_TOP));
        handLight.apply(armShader);
        // Яркость уже сидит в цветах handLight — видимость здесь единичная.
        armShader.setFloat("uSkyVis", 1f);
        armShader.setFloat("uBlockVis", 0f);
        // Под водой рука уходит в холодный синий — как и всё остальное.
        armShader.setVec3("uTint", underwater ? UNDERWATER_TINT : SKIN_TINT);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, armTexture);
        glBindVertexArray(armVao);
        glDrawArrays(GL_TRIANGLES, 0, MobRenderer.VERTEX_COUNT);
        glBindVertexArray(0);
        armShader.unbind();

        // --- предмет ---------------------------------------------------------
        if (holding) {
            Mesh mesh = heldTool != null
                    ? toolMeshes.computeIfAbsent(held.item, HeldItemRenderer::createToolMesh)
                    : heldBlock != null
                        ? blockMeshes.computeIfAbsent(heldBlock, HeldItemRenderer::createItemMesh)
                        : materialMeshes.computeIfAbsent(held.item, HeldItemRenderer::createMaterialMesh);
            // Факел светит сам — не даём ему потемнеть в руке.
            SceneLighting itemLight = heldBlock == BlockType.TORCH && heldTool == null
                    ? SceneLighting.firstPerson(brightness, Math.max(handLevel, 0.85f))
                    : handLight;
            itemLight.linearOut = linearOut;
            blockShader.bind();
            blockShader.setMat4("uProjection", projection);
            blockShader.setMat4("uView", view);
            blockShader.setInt("uAtlas", 0);
            itemLight.apply(blockShader);
            blockShader.setFloat("uTime", 0f);
            blockShader.setFloat("uWindSway", 0f);
            atlas.bind(0);
            Matrix4f pose = heldTool != null
                    ? toolPose(equipProgress, swingProgress, walkDistance, viewBobbing, inspect, spin)
                    : itemPose(equipProgress, swingProgress, walkDistance, viewBobbing, inspect, spin);
            blockShader.setMat4("uModel", pose);
            mesh.render();
            blockShader.unbind();

            if (heldTool != null) {
                int stage = crackStage(held.condition());
                if (stage >= 0)
                    renderCracks(atlas, mesh, projection, view, pose, held.iconTile(), stage, linearOut);
                if (inspect < 0.05f)
                    renderTrail(projection, view, equipProgress, swingProgress, walkDistance,
                            viewBobbing, handLevel, linearOut);
            }
        }

        glEnable(GL_CULL_FACE);
    }

    /**
     * Трещины износа поверх инструмента: второй проход той же геометрии с
     * маской по альфе спрайта. Трещина берётся из тайлов разрушения блока —
     * ровно тот же язык «оно скоро сломается», что у копаемого блока.
     */
    private void renderCracks(TextureAtlas atlas, Mesh mesh, Matrix4f projection, Matrix4f view,
                              Matrix4f pose, int toolTile, int stage, float linearOut) {
        float[] tuv = TextureAtlas.uv(toolTile);
        float[] cuv = TextureAtlas.uv(TextureAtlas.CRACK_TILE_0 + stage);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthFunc(GL_LEQUAL);
        glDepthMask(false);
        crackShader.bind();
        crackShader.setMat4("uProjection", projection);
        crackShader.setMat4("uView", view);
        crackShader.setMat4("uModel", pose);
        crackShader.setInt("uAtlas", 0);
        crackShader.setFloat("uWindSway", 0f);
        crackShader.setVec2("uToolUv0", tuv[0], tuv[1]);
        crackShader.setVec2("uCrackUv0", cuv[0], cuv[1]);
        crackShader.setVec2("uSpan", tuv[2] - tuv[0], tuv[3] - tuv[1]);
        crackShader.setFloat("uLinearOut", linearOut);
        // Чем глубже износ, тем заметнее трещина: на первых стадиях это
        // царапины, на последних — раскол через весь инструмент.
        crackShader.setFloat("uAlpha", 0.55f + 0.045f * stage);
        crackShader.setFloat("uWear", (stage + 1) / 10f);
        atlas.bind(0);
        mesh.render();
        crackShader.unbind();
        glDepthMask(true);
        glDepthFunc(GL_LESS);
        glDisable(GL_BLEND);
    }

    /** Полупрозрачная лента за головкой инструмента во время замаха. */
    private void renderTrail(Matrix4f projection, Matrix4f view, float equipProgress,
                             float swingProgress, float walkDistance, boolean viewBobbing,
                             float light, float linearOut) {
        float[] strip = trailStrip(equipProgress, swingProgress, walkDistance, viewBobbing);
        if (strip.length == 0)
            return;
        trailBuf.clear();
        trailBuf.put(strip).flip();
        glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, trailVbo);
        org.lwjgl.opengl.GL15.glBufferSubData(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, 0L, trailBuf);
        glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, 0);

        glEnable(GL_BLEND);
        // Сложение, а не смешивание: след светится поверх того, что за ним,
        // а не закрашивает мир мутной плёнкой.
        glBlendFunc(GL_SRC_ALPHA, GL_ONE);
        glDepthMask(false);
        trailShader.bind();
        trailShader.setMat4("uProjection", projection);
        trailShader.setMat4("uView", view);
        float k = 0.35f + 0.65f * light;
        trailShader.setVec3("uColor", 0.95f * k, 0.97f * k, 1.0f * k);
        trailShader.setFloat("uLinearOut", linearOut);
        glBindVertexArray(trailVao);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, strip.length / 4);
        glBindVertexArray(0);
        trailShader.unbind();
        glDepthMask(true);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDisable(GL_BLEND);
    }

    private static final Vector3f SKIN_TINT = new Vector3f(0.22f, 0.23f, 0.30f);
    private static final Vector3f UNDERWATER_TINT = new Vector3f(SKIN_TINT).mul(0.62f, 0.78f, 1.0f);

    /** Объёмная модель инструмента: отдельная рукоять и рабочая часть. */
    private static Mesh createToolMesh(com.mineclone.item.Item tool) {
        List<Float> pos = new ArrayList<>();
        List<Float> uvs = new ArrayList<>();
        List<Float> light = new ArrayList<>();
        List<Float> blockLight = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
        com.mineclone.item.ToolClass kind = tool.tool.toolClass();
        int materialTile = tool.id.path().startsWith("gold_") ? 120 : tool.id.path().startsWith("copper_") ? 121 : 122;
        emitSolidBox(pos, uvs, light, blockLight, indices, materialTile, 0f, -0.18f, 0f, .10f, .72f, .10f);
        if (kind == com.mineclone.item.ToolClass.PICKAXE)
            emitSolidBox(pos, uvs, light, blockLight, indices, materialTile, 0f, .27f, 0f, .82f, .12f, .12f);
        else if (kind == com.mineclone.item.ToolClass.AXE)
            emitSolidBox(pos, uvs, light, blockLight, indices, materialTile, .25f, .28f, 0f, .38f, .42f, .14f);
        else if (kind == com.mineclone.item.ToolClass.SHOVEL)
            emitSolidBox(pos, uvs, light, blockLight, indices, materialTile, 0f, .28f, 0f, .38f, .24f, .18f);
        return new Mesh(toFloatArray(pos), toFloatArray(uvs), toFloatArray(light),
                toFloatArray(blockLight), toIntArray(indices));
    }

    private static Mesh createMaterialMesh(com.mineclone.item.Item item) {
        List<Float> p = new ArrayList<>(), u = new ArrayList<>(), l = new ArrayList<>(), bl = new ArrayList<>();
        List<Integer> i = new ArrayList<>();
        emitSolidBox(p, u, l, bl, i, 122, 0f, -0.18f, 0f, .10f, .82f, .10f);
        return new Mesh(toFloatArray(p), toFloatArray(u), toFloatArray(l), toFloatArray(bl), toIntArray(i));
    }

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

    /** Append a textured cuboid in local first-person coordinates. */
    private static void emitBox(List<Float> pos, List<Float> uvs, List<Float> light,
            List<Float> blockLight, List<Integer> indices, int tile,
            float cx, float cy, float cz, float sx, float sy, float sz) {
        float a = -.5f, b = .5f;
        float[][][] faces = {
                {{a,b},{b,b},{b,a},{a,a}}, {{b,b},{a,b},{a,a},{b,a}},
                {{b,b},{b,b},{b,a},{b,a}}, {{a,b},{a,b},{a,a},{a,a}},
                {{a,b},{b,b},{b,b},{a,b}}, {{a,a},{b,a},{b,a},{a,a}}
        };
        // Explicit vertices avoid allocations and keep UV orientation consistent with block meshes.
        float[][][] v = {
            {{-1,-1,1},{1,-1,1},{1,1,1},{-1,1,1}}, {{1,-1,-1},{-1,-1,-1},{-1,1,-1},{1,1,-1}},
            {{1,-1,1},{1,-1,-1},{1,1,-1},{1,1,1}}, {{-1,-1,-1},{-1,-1,1},{-1,1,1},{-1,1,-1}},
            {{-1,1,1},{1,1,1},{1,1,-1},{-1,1,-1}}, {{-1,-1,-1},{1,-1,-1},{1,-1,1},{-1,-1,1}}
        };
        float[] uv = TextureAtlas.uv(tile);
        for (float[][] face : v) {
            int base = pos.size() / 3;
            for (int q = 0; q < 4; q++) {
                pos.add(cx + face[q][0] * sx * .5f); pos.add(cy + face[q][1] * sy * .5f); pos.add(cz + face[q][2] * sz * .5f);
                float uu = (q == 0 || q == 3) ? uv[0] : uv[2], vv = (q < 2) ? uv[3] : uv[1];
                uvs.add(uu); uvs.add(vv); light.add(1f); blockLight.add(0f);
            }
            indices.add(base); indices.add(base+1); indices.add(base+2); indices.add(base); indices.add(base+2); indices.add(base+3);
        }
    }

    /** Same cuboid, but samples the material center so a 2D icon cannot turn into a floating card. */
    private static void emitSolidBox(List<Float> pos, List<Float> uvs, List<Float> light,
            List<Float> blockLight, List<Integer> indices, int tile,
            float cx, float cy, float cz, float sx, float sy, float sz) {
        float[] uv = TextureAtlas.uv(tile);
        float u = (uv[0] + uv[2]) * .5f, v = (uv[1] + uv[3]) * .5f;
        float[][][] faces = {
            {{-1,-1,1},{1,-1,1},{1,1,1},{-1,1,1}}, {{1,-1,-1},{-1,-1,-1},{-1,1,-1},{1,1,-1}},
            {{1,-1,1},{1,-1,-1},{1,1,-1},{1,1,1}}, {{-1,-1,-1},{-1,-1,1},{-1,1,1},{-1,1,-1}},
            {{-1,1,1},{1,1,1},{1,1,-1},{-1,1,-1}}, {{-1,-1,-1},{1,-1,-1},{1,-1,1},{-1,-1,1}}
        };
        for (float[][] face : faces) {
            int base = pos.size() / 3;
            for (float[] q : face) { pos.add(cx + q[0]*sx*.5f); pos.add(cy + q[1]*sy*.5f); pos.add(cz + q[2]*sz*.5f); uvs.add(u); uvs.add(v); light.add(1f); blockLight.add(0f); }
            indices.add(base); indices.add(base+1); indices.add(base+2); indices.add(base); indices.add(base+2); indices.add(base+3);
        }
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static float smooth01(float v) {
        float k = clamp01(v);
        return k * k * (3f - 2f * k);
    }

    private static float lerp(float a, float b, float k) {
        return a + (b - a) * k;
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
        for (Mesh m : toolMeshes.values())
            m.destroy();
        toolMeshes.clear();
        for (Mesh m : materialMeshes.values()) m.destroy();
        materialMeshes.clear();
        blockShader.destroy();
        armShader.destroy();
        crackShader.destroy();
        trailShader.destroy();
        glDeleteVertexArrays(trailVao);
        glDeleteBuffers(trailVbo);
        org.lwjgl.system.MemoryUtil.memFree(trailBuf);
    }
}

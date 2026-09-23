package com.mineclone.render;

import com.mineclone.world.Chunk;
import com.mineclone.world.World;
import com.mineclone.world.entity.Mob;
import com.mineclone.world.entity.MobType;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryUtil;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Рендер мобов коробочными моделями.
 *
 * Одна статическая VBO единичного куба (−0.5..0.5) на всё; каждая часть тела —
 * отдельный draw с своей model-матрицей и тремя UV-базами (front/side/top).
 * Текстура — одна на MobType, отдельный GL-объект (не блочный атлас).
 *
 * Куллинг на время прохода выключен: боксы замкнутые и непрозрачные, глубина
 * сама всё решает, зато порядок обхода вершин не может ничего сломать.
 */
public class MobRenderer {
    /** Как анимируется часть тела. */
    private enum Anim { NONE, HEAD, LEG_A, LEG_B, ARM, WING, TAIL }

    /**
     * Часть тела: пивот (точка вращения, в локальных координатах моба),
     * смещение центра бокса от пивота, размеры бокса, три тайла скина.
     */
    private record Part(float pivX, float pivY, float pivZ,
                        float offX, float offY, float offZ,
                        float sx, float sy, float sz,
                        int front, int side, int top,
                        Anim anim) {}

    private static final Vector3f NO_TINT = new Vector3f(1f, 1f, 1f);
    private static final Vector3f HURT_TINT = new Vector3f(1f, 0.45f, 0.45f);

    static final float TILE_U = (MobSkins.TILE - 1f) / MobSkins.WIDTH;
    static final float TILE_V = (MobSkins.TILE - 1f) / MobSkins.HEIGHT;

    // pos(3) + faceId(1) + cornerUv(2) = 6 float на вершину, 36 вершин.
    // package-private: HeldItemRenderer рисует руку от первого лица тем же
    // кубом, шейдером и раскладкой скина, что и части тела моба — иначе
    // рука и мобы разъезжаются по стилю при первой же правке одного из них.
    static final float[] CUBE = {
        // front (−Z), faceId 0
         0.5f,-0.5f,-0.5f, 0f, 0f,1f,  -0.5f,-0.5f,-0.5f, 0f, 1f,1f,  -0.5f, 0.5f,-0.5f, 0f, 1f,0f,
         0.5f,-0.5f,-0.5f, 0f, 0f,1f,  -0.5f, 0.5f,-0.5f, 0f, 1f,0f,   0.5f, 0.5f,-0.5f, 0f, 0f,0f,
        // back (+Z), faceId 1
        -0.5f,-0.5f, 0.5f, 1f, 0f,1f,   0.5f,-0.5f, 0.5f, 1f, 1f,1f,   0.5f, 0.5f, 0.5f, 1f, 1f,0f,
        -0.5f,-0.5f, 0.5f, 1f, 0f,1f,   0.5f, 0.5f, 0.5f, 1f, 1f,0f,  -0.5f, 0.5f, 0.5f, 1f, 0f,0f,
        // left (−X), faceId 1
        -0.5f,-0.5f,-0.5f, 1f, 0f,1f,  -0.5f,-0.5f, 0.5f, 1f, 1f,1f,  -0.5f, 0.5f, 0.5f, 1f, 1f,0f,
        -0.5f,-0.5f,-0.5f, 1f, 0f,1f,  -0.5f, 0.5f, 0.5f, 1f, 1f,0f,  -0.5f, 0.5f,-0.5f, 1f, 0f,0f,
        // right (+X), faceId 1
         0.5f,-0.5f, 0.5f, 1f, 0f,1f,   0.5f,-0.5f,-0.5f, 1f, 1f,1f,   0.5f, 0.5f,-0.5f, 1f, 1f,0f,
         0.5f,-0.5f, 0.5f, 1f, 0f,1f,   0.5f, 0.5f,-0.5f, 1f, 1f,0f,   0.5f, 0.5f, 0.5f, 1f, 0f,0f,
        // top (+Y), faceId 2
        -0.5f, 0.5f,-0.5f, 2f, 0f,0f,  -0.5f, 0.5f, 0.5f, 2f, 0f,1f,   0.5f, 0.5f, 0.5f, 2f, 1f,1f,
        -0.5f, 0.5f,-0.5f, 2f, 0f,0f,   0.5f, 0.5f, 0.5f, 2f, 1f,1f,   0.5f, 0.5f,-0.5f, 2f, 1f,0f,
        // bottom (−Y), faceId 2
        -0.5f,-0.5f, 0.5f, 2f, 0f,0f,  -0.5f,-0.5f,-0.5f, 2f, 0f,1f,   0.5f,-0.5f,-0.5f, 2f, 1f,1f,
        -0.5f,-0.5f, 0.5f, 2f, 0f,0f,   0.5f,-0.5f,-0.5f, 2f, 1f,1f,   0.5f,-0.5f, 0.5f, 2f, 1f,0f,
    };
    static final int VERTEX_COUNT = 36;
    static final int STRIDE = 6 * Float.BYTES;

    private final int vao, vbo;
    private final org.joml.FrustumIntersection visibility = new org.joml.FrustumIntersection();
    private final Matrix4f visibilityMatrix = new Matrix4f();
    private final Shader shader;
    private final Map<MobType, Integer> textures = new EnumMap<>(MobType.class);
    private final Map<MobType, Part[]> models = new EnumMap<>(MobType.class);
    private final Matrix4f[] pose = new Matrix4f[16];

    public MobRenderer() {
        shader = new Shader(Shaders.MOB_VERTEX, Shaders.MOB_FRAGMENT);
        int[] ids = createCubeVao();
        vao = ids[0];
        vbo = ids[1];

        for (MobType t : MobType.values()) {
            textures.put(t, uploadTexture(MobSkins.load(t)));
            models.put(t, buildModel(t));
        }
    }

    /**
     * VAO единичного куба со схемой атрибутов MOB_VERTEX: позиция, faceId,
     * угловые UV. Возвращает {vao, vbo}.
     */
    static int[] createCubeVao() {
        int vao = glGenVertexArrays();
        int vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        FloatBuffer fb = MemoryUtil.memAllocFloat(CUBE.length);
        fb.put(CUBE).flip();
        glBufferData(GL_ARRAY_BUFFER, fb, GL_STATIC_DRAW);
        MemoryUtil.memFree(fb);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, STRIDE, 0);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 1, GL_FLOAT, false, STRIDE, 3 * Float.BYTES);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(2, 2, GL_FLOAT, false, STRIDE, 4 * Float.BYTES);
        glEnableVertexAttribArray(2);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
        return new int[] { vao, vbo };
    }

    // -------------------------------------------------------------------------
    //  Таблицы частей тела. Числа в блоках, подбирались под AABB из MobType —
    //  трогать можно свободно, на физику они не влияют.
    // -------------------------------------------------------------------------

    private static Part[] buildModel(MobType type) {
        // The generated profile tile contains an eye. Only the front may carry
        // facial features; use the clean crown material on sides and rear.
        int hf = MobSkins.T_HEAD_FRONT, hs = MobSkins.T_HEAD_TOP, ht = MobSkins.T_HEAD_TOP;
        int bs = MobSkins.T_BODY_SIDE, bt = MobSkins.T_BODY_TOP;
        int lm = MobSkins.T_LIMB, ac = MobSkins.T_ACCENT;
        return switch (type) {
            case COW -> new Part[] {
                new Part(0f, 1.0625f, 0f, 0f, 0f, 0f, 0.75f, 0.625f, 1.125f, bs, bs, bt, Anim.NONE),
                new Part(0f, 1.15f, -0.50f, 0f, 0f, -0.26f, 0.5f, 0.5f, 0.4f, hf, hs, ht, Anim.HEAD),
                new Part(-0.22f, 0.75f, -0.38f, 0f, -0.375f, 0f, 0.25f, 0.75f, 0.25f, lm, lm, lm, Anim.LEG_A),
                new Part( 0.22f, 0.75f, -0.38f, 0f, -0.375f, 0f, 0.25f, 0.75f, 0.25f, lm, lm, lm, Anim.LEG_B),
                new Part(-0.22f, 0.75f,  0.38f, 0f, -0.375f, 0f, 0.25f, 0.75f, 0.25f, lm, lm, lm, Anim.LEG_B),
                new Part( 0.22f, 0.75f,  0.38f, 0f, -0.375f, 0f, 0.25f, 0.75f, 0.25f, lm, lm, lm, Anim.LEG_A),
            };
            case PIG -> new Part[] {
                new Part(0f, 0.625f, 0f, 0f, 0f, 0f, 0.625f, 0.5f, 1.0f, bs, bs, bt, Anim.NONE),
                new Part(0f, 0.625f, -0.40f, 0f, 0f, -0.25f, 0.5f, 0.5f, 0.5f, hf, hs, ht, Anim.HEAD),
                new Part(-0.2f, 0.375f, -0.35f, 0f, -0.1875f, 0f, 0.25f, 0.375f, 0.25f, lm, lm, lm, Anim.LEG_A),
                new Part( 0.2f, 0.375f, -0.35f, 0f, -0.1875f, 0f, 0.25f, 0.375f, 0.25f, lm, lm, lm, Anim.LEG_B),
                new Part(-0.2f, 0.375f,  0.35f, 0f, -0.1875f, 0f, 0.25f, 0.375f, 0.25f, lm, lm, lm, Anim.LEG_B),
                new Part( 0.2f, 0.375f,  0.35f, 0f, -0.1875f, 0f, 0.25f, 0.375f, 0.25f, lm, lm, lm, Anim.LEG_A),
            };
            case SHEEP -> new Part[] {
                new Part(0f, 0.925f, 0f, 0f, 0f, 0f, 0.7f, 0.65f, 1.05f, bs, bs, bt, Anim.NONE),
                new Part(0f, 1.0f, -0.50f, 0f, 0f, -0.22f, 0.45f, 0.45f, 0.4f, hf, hs, ht, Anim.HEAD),
                new Part(-0.2f, 0.6f, -0.35f, 0f, -0.3f, 0f, 0.25f, 0.6f, 0.25f, lm, lm, lm, Anim.LEG_A),
                new Part( 0.2f, 0.6f, -0.35f, 0f, -0.3f, 0f, 0.25f, 0.6f, 0.25f, lm, lm, lm, Anim.LEG_B),
                new Part(-0.2f, 0.6f,  0.35f, 0f, -0.3f, 0f, 0.25f, 0.6f, 0.25f, lm, lm, lm, Anim.LEG_B),
                new Part( 0.2f, 0.6f,  0.35f, 0f, -0.3f, 0f, 0.25f, 0.6f, 0.25f, lm, lm, lm, Anim.LEG_A),
            };
            case CHICKEN -> new Part[] {
                new Part(0f, 0.425f, 0f, 0f, 0f, 0f, 0.3f, 0.35f, 0.4f, bs, bs, bt, Anim.NONE),
                new Part(0f, 0.50f, -0.10f, 0f, 0.10f, -0.05f, 0.25f, 0.25f, 0.2f, hf, hs, ht, Anim.HEAD),
                new Part(-0.08f, 0.25f, 0f, 0f, -0.125f, 0f, 0.08f, 0.25f, 0.08f, lm, lm, lm, Anim.LEG_A),
                new Part( 0.08f, 0.25f, 0f, 0f, -0.125f, 0f, 0.08f, 0.25f, 0.08f, lm, lm, lm, Anim.LEG_B),
                new Part(-0.16f, 0.55f, 0f, 0f, -0.125f, 0f, 0.06f, 0.25f, 0.3f, ac, ac, ac, Anim.WING),
                new Part( 0.16f, 0.55f, 0f, 0f, -0.125f, 0f, 0.06f, 0.25f, 0.3f, ac, ac, ac, Anim.WING),
            };
            case ZOMBIE -> new Part[] {
                new Part(0f, 1.025f, 0f, 0f, 0f, 0f, 0.5f, 0.65f, 0.25f, bs, bs, bt, Anim.NONE),
                new Part(0f, 1.35f, 0f, 0f, 0.225f, 0f, 0.45f, 0.45f, 0.45f, hf, hs, ht, Anim.HEAD),
                new Part(-0.13f, 0.7f, 0f, 0f, -0.35f, 0f, 0.25f, 0.7f, 0.25f, lm, lm, lm, Anim.LEG_A),
                new Part( 0.13f, 0.7f, 0f, 0f, -0.35f, 0f, 0.25f, 0.7f, 0.25f, lm, lm, lm, Anim.LEG_B),
                new Part(-0.36f, 1.3f, 0f, 0f, -0.3f, 0f, 0.22f, 0.6f, 0.22f, ac, ac, ac, Anim.ARM),
                new Part( 0.36f, 1.3f, 0f, 0f, -0.3f, 0f, 0.22f, 0.6f, 0.22f, ac, ac, ac, Anim.ARM),
            };
            // Скелет: те же пропорции, что у зомби, но тоньше — кость, а не
            // мясо. Руки опущены, а не вытянуты: он стреляет, а не тянется.
            case SKELETON -> new Part[] {
                new Part(0f, 1.025f, 0f, 0f, 0f, 0f, 0.42f, 0.65f, 0.20f, bs, bs, bt, Anim.NONE),
                new Part(0f, 1.35f, 0f, 0f, 0.225f, 0f, 0.44f, 0.44f, 0.44f, hf, hs, ht, Anim.HEAD),
                new Part(-0.11f, 0.7f, 0f, 0f, -0.35f, 0f, 0.16f, 0.7f, 0.16f, lm, lm, lm, Anim.LEG_A),
                new Part( 0.11f, 0.7f, 0f, 0f, -0.35f, 0f, 0.16f, 0.7f, 0.16f, lm, lm, lm, Anim.LEG_B),
                new Part(-0.30f, 1.3f, 0f, 0f, -0.3f, 0f, 0.15f, 0.6f, 0.15f, ac, ac, ac, Anim.ARM),
                new Part( 0.30f, 1.3f, 0f, 0f, -0.3f, 0f, 0.15f, 0.6f, 0.15f, ac, ac, ac, Anim.ARM),
            };
            // Паук: широкое низкое брюшко, голова впереди и восемь ног по
            // сторонам. Ноги шагают вразнобой парами — иначе он семенит.
            case SPIDER -> new Part[] {
                new Part(0f, 0.42f, 0.22f, 0f, 0f, 0f, 0.62f, 0.42f, 0.62f, bs, bs, bt, Anim.NONE),
                new Part(0f, 0.44f, -0.34f, 0f, 0f, -0.16f, 0.42f, 0.36f, 0.34f, hf, hs, ht, Anim.HEAD),
                // Ноги вертикальные, как у всех: Anim.LEG вращает их вокруг
                // сустава наверху, и горизонтальная «лапа вбок» отрывалась бы
                // от него на первом же шаге. Паучьим силуэт делает не наклон
                // ног, а их разнос вширь при низком теле.
                new Part(-0.46f, 0.26f, -0.18f, 0f, -0.13f, 0f, 0.10f, 0.26f, 0.10f, lm, lm, lm, Anim.LEG_A),
                new Part( 0.46f, 0.26f, -0.18f, 0f, -0.13f, 0f, 0.10f, 0.26f, 0.10f, lm, lm, lm, Anim.LEG_B),
                new Part(-0.50f, 0.26f,  0.06f, 0f, -0.13f, 0f, 0.10f, 0.26f, 0.10f, lm, lm, lm, Anim.LEG_B),
                new Part( 0.50f, 0.26f,  0.06f, 0f, -0.13f, 0f, 0.10f, 0.26f, 0.10f, lm, lm, lm, Anim.LEG_A),
                new Part(-0.50f, 0.26f,  0.30f, 0f, -0.13f, 0f, 0.10f, 0.26f, 0.10f, ac, ac, ac, Anim.LEG_A),
                new Part( 0.50f, 0.26f,  0.30f, 0f, -0.13f, 0f, 0.10f, 0.26f, 0.10f, ac, ac, ac, Anim.LEG_B),
                new Part(-0.46f, 0.26f,  0.52f, 0f, -0.13f, 0f, 0.10f, 0.26f, 0.10f, ac, ac, ac, Anim.LEG_B),
                new Part( 0.46f, 0.26f,  0.52f, 0f, -0.13f, 0f, 0.10f, 0.26f, 0.10f, ac, ac, ac, Anim.LEG_A),
            };
            // Крипер: столб на четырёх коротких ногах, без рук. Голова
            // крупная — на ней всё узнавание вида.
            case CREEPER -> new Part[] {
                new Part(0f, 0.95f, 0f, 0f, 0f, 0f, 0.42f, 0.72f, 0.26f, bs, bs, bt, Anim.NONE),
                new Part(0f, 1.38f, 0f, 0f, 0.21f, 0f, 0.48f, 0.48f, 0.48f, hf, hs, ht, Anim.HEAD),
                new Part(-0.13f, 0.30f, -0.14f, 0f, -0.15f, 0f, 0.18f, 0.30f, 0.18f, lm, lm, lm, Anim.LEG_A),
                new Part( 0.13f, 0.30f, -0.14f, 0f, -0.15f, 0f, 0.18f, 0.30f, 0.18f, lm, lm, lm, Anim.LEG_B),
                new Part(-0.13f, 0.30f,  0.14f, 0f, -0.15f, 0f, 0.18f, 0.30f, 0.18f, lm, lm, lm, Anim.LEG_B),
                new Part( 0.13f, 0.30f,  0.14f, 0f, -0.15f, 0f, 0.18f, 0.30f, 0.18f, lm, lm, lm, Anim.LEG_A),
            };
            // Кролик: пригнутое тело, длинные уши, мощные задние лапы.
            case RABBIT -> new Part[] {
                new Part(0f, 0.24f, 0.04f, 0f, 0f, 0f, 0.30f, 0.26f, 0.42f, bs, bs, bt, Anim.NONE),
                new Part(0f, 0.30f, -0.14f, 0f, 0.06f, -0.12f, 0.24f, 0.22f, 0.22f, hf, hs, ht, Anim.HEAD),
                new Part(0f, 0.30f, -0.14f, -0.06f, 0.26f, -0.08f, 0.06f, 0.22f, 0.04f, ac, ac, lm, Anim.HEAD),
                new Part(0f, 0.30f, -0.14f, 0.06f, 0.26f, -0.08f, 0.06f, 0.22f, 0.04f, ac, ac, lm, Anim.HEAD),
                new Part(-0.08f, 0.14f, -0.12f, 0f, -0.07f, 0f, 0.07f, 0.14f, 0.07f, lm, lm, lm, Anim.LEG_A),
                new Part( 0.08f, 0.14f, -0.12f, 0f, -0.07f, 0f, 0.07f, 0.14f, 0.07f, lm, lm, lm, Anim.LEG_B),
                new Part(-0.11f, 0.14f, 0.14f, 0f, -0.07f, 0f, 0.09f, 0.14f, 0.16f, lm, lm, lm, Anim.LEG_B),
                new Part( 0.11f, 0.14f, 0.14f, 0f, -0.07f, 0f, 0.09f, 0.14f, 0.16f, lm, lm, lm, Anim.LEG_A),
                new Part(0f, 0.30f, 0.27f, 0f, 0f, 0.03f, 0.10f, 0.10f, 0.08f, MobSkins.T_SPARE, MobSkins.T_SPARE, MobSkins.T_SPARE, Anim.NONE),
            };
            // Волк: длинное тело, морда вперёд, уши торчком, хвост на отлёте.
            case WOLF -> new Part[] {
                new Part(0f, 0.55f, 0.05f, 0f, 0f, 0f, 0.36f, 0.34f, 0.80f, bs, bs, bt, Anim.NONE),
                new Part(0f, 0.62f, -0.28f, 0f, 0.04f, -0.20f, 0.34f, 0.30f, 0.28f, hf, hs, ht, Anim.HEAD),
                new Part(0f, 0.62f, -0.28f, 0f, -0.02f, -0.41f, 0.16f, 0.13f, 0.16f, ac, ac, ac, Anim.HEAD),
                new Part(0f, 0.62f, -0.28f, -0.10f, 0.23f, -0.14f, 0.08f, 0.10f, 0.05f, ac, ac, ac, Anim.HEAD),
                new Part(0f, 0.62f, -0.28f, 0.10f, 0.23f, -0.14f, 0.08f, 0.10f, 0.05f, ac, ac, ac, Anim.HEAD),
                new Part(-0.12f, 0.40f, -0.26f, 0f, -0.20f, 0f, 0.12f, 0.40f, 0.12f, lm, lm, lm, Anim.LEG_A),
                new Part( 0.12f, 0.40f, -0.26f, 0f, -0.20f, 0f, 0.12f, 0.40f, 0.12f, lm, lm, lm, Anim.LEG_B),
                new Part(-0.12f, 0.40f,  0.32f, 0f, -0.20f, 0f, 0.12f, 0.40f, 0.12f, lm, lm, lm, Anim.LEG_B),
                new Part( 0.12f, 0.40f,  0.32f, 0f, -0.20f, 0f, 0.12f, 0.40f, 0.12f, lm, lm, lm, Anim.LEG_A),
                new Part(0f, 0.62f, 0.44f, 0f, -0.06f, 0.14f, 0.10f, 0.10f, 0.32f, ac, ac, ac, Anim.TAIL),
            };
            // Птица: круглое тело, клюв, крылья по бокам и хвост лопаткой.
            case BIRD -> new Part[] {
                new Part(0f, 0.17f, 0f, 0f, 0f, 0f, 0.18f, 0.17f, 0.28f, bs, bs, bt, Anim.NONE),
                new Part(0f, 0.22f, -0.10f, 0f, 0.08f, -0.03f, 0.14f, 0.14f, 0.14f, hf, hs, ht, Anim.HEAD),
                new Part(0f, 0.22f, -0.10f, 0f, 0.06f, -0.12f, 0.05f, 0.05f, 0.08f, MobSkins.T_SPARE, MobSkins.T_SPARE, MobSkins.T_SPARE, Anim.HEAD),
                // Крылья длиннее, чем нужно сложенным: в полёте короткая
                // пластинка читалась как крыша на спине, а не как взмах.
                new Part(-0.10f, 0.24f, 0f, 0f, -0.085f, 0.01f, 0.03f, 0.17f, 0.22f, ac, ac, ac, Anim.WING),
                new Part( 0.10f, 0.24f, 0f, 0f, -0.085f, 0.01f, 0.03f, 0.17f, 0.22f, ac, ac, ac, Anim.WING),
                new Part(0f, 0.18f, 0.15f, 0f, 0f, 0.08f, 0.12f, 0.03f, 0.16f, ac, ac, bt, Anim.TAIL),
                new Part(-0.04f, 0.09f, 0f, 0f, -0.045f, 0f, 0.03f, 0.09f, 0.03f, lm, lm, lm, Anim.LEG_A),
                new Part( 0.04f, 0.09f, 0f, 0f, -0.045f, 0f, 0.03f, 0.09f, 0.03f, lm, lm, lm, Anim.LEG_B),
            };
        };
    }

    // -------------------------------------------------------------------------

    public void render(Matrix4f proj, Matrix4f view, List<Mob> mobs, World world,
                       float daylight, SceneLighting lighting) {
        if (mobs.isEmpty())
            return;
        visibility.set(visibilityMatrix.set(proj).mul(view));
        glDisable(GL_CULL_FACE);
        shader.bind();
        shader.setMat4("uProjection", proj);
        shader.setMat4("uView", view);
        shader.setInt("uSkin", 0);
        lighting.apply(shader);
        shader.setVec2("uTileSize", TILE_U, TILE_V);
        glBindVertexArray(vao);

        for (Mob m : mobs) {
            if (!visibility.testSphere(m.position.x, m.position.y + m.type.height * 0.5f, m.position.z,
                    m.type.height + m.type.width)) continue;
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, textures.get(m.type));
            shader.setFloat("uSkyVis", skyVisAt(world, m) * daylight);
            shader.setFloat("uBlockVis", blockVisAt(world, m));
            shader.setVec3("uTint", tintOf(m));
            shader.setVec3("uGlow", glowOf(m));

            Part[] parts = models.get(m.type);
            preparePose(m, parts, pose);
            for (int i = 0; i < parts.length; i++) {
                Part p = parts[i];
                Matrix4f model = pose[i];
                shader.setMat4("uModel", model);
                shader.setVec2("uUvFront", uvX(p.front()), uvY(p.front()));
                shader.setVec2("uUvSide", uvX(p.side()), uvY(p.side()));
                shader.setVec2("uUvTop", uvX(p.top()), uvY(p.top()));
                glDrawArrays(GL_TRIANGLES, 0, VERTEX_COUNT);
            }
        }

        glBindVertexArray(0);
        shader.unbind();
        glEnable(GL_CULL_FACE);
    }

    /**
     * Те же кубы в карту теней. Позы берутся из {@link #partMatrix}, поэтому
     * тень не может разъехаться с моделью при правке анимации.
     */
    public void renderShadow(Shader shadowShader, Matrix4f lightSpace, List<Mob> mobs) {
        if (mobs.isEmpty())
            return;
        shadowShader.bind();
        shadowShader.setMat4("uLightSpace", lightSpace);
        shadowShader.setInt("uAtlas", 0);
        shadowShader.setVec2("uTileSize", TILE_U, TILE_V);
        glBindVertexArray(vao);
        for (Mob m : mobs) {
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, textures.get(m.type));
            Part[] parts = models.get(m.type);
            preparePose(m, parts, pose);
            for (int i = 0; i < parts.length; i++) {
                Part p = parts[i];
                Matrix4f model = pose[i];
                shadowShader.setMat4("uModel", model);
                shadowShader.setVec2("uUvFront", uvX(p.front()), uvY(p.front()));
                shadowShader.setVec2("uUvSide", uvX(p.side()), uvY(p.side()));
                shadowShader.setVec2("uUvTop", uvX(p.top()), uvY(p.top()));
                glDrawArrays(GL_TRIANGLES, 0, VERTEX_COUNT);
            }
        }
        glBindVertexArray(0);
        shadowShader.unbind();
    }

    private final Vector3f tint = new Vector3f();
    private final Vector3f glow = new Vector3f();
    private static final Vector3f NO_GLOW = new Vector3f();

    /**
     * Оттенок моба: вспышка урона, а у взбешённого — пульсация. Пульс, а не
     * ровный цвет: ровный читался бы как другой вид моба.
     */
    private Vector3f tintOf(Mob m) {
        if (m.hurtFlash > 0f)
            return HURT_TINT;
        if (m.elite == com.mineclone.world.entity.MobTactics.Elite.FROST)
            return tint.set(0.72f, 0.90f, 1.18f);
        if (m.elite == com.mineclone.world.entity.MobTactics.Elite.VENOMOUS)
            return tint.set(0.76f, 1.10f, 0.70f);
        if (m.elite == com.mineclone.world.entity.MobTactics.Elite.BURNING)
            return tint.set(1.18f, 0.82f, 0.62f);
        if (m.enraged && !m.dead) {
            float k = ragePulse(m);
            return tint.set(1f, 1f - 0.22f * k, 1f - 0.26f * k);
        }
        return NO_TINT;
    }

    /**
     * Красное свечение ярости. Одним множителем цвета его не сделать: зелёная
     * кожа зомби почти без красного канала, и умноженная на красный она не
     * краснеет, а темнеет в оливковый.
     */
    private Vector3f glowOf(Mob m) {
        if (m.dead || m.hurtFlash > 0f)
            return NO_GLOW;
        if (m.elite != com.mineclone.world.entity.MobTactics.Elite.NONE) {
            float pulse = 0.55f + 0.25f * (float) Math.sin(m.animationTime * 5f);
            return switch (m.elite) {
                case BURNING -> glow.set(0.32f, 0.08f, 0.01f).mul(pulse);
                case VENOMOUS -> glow.set(0.04f, 0.28f, 0.02f).mul(pulse);
                case FROST -> glow.set(0.04f, 0.18f, 0.34f).mul(pulse);
                default -> NO_GLOW;
            };
        }
        if (!m.enraged)
            return NO_GLOW;
        float k = ragePulse(m);
        return glow.set(0.15f * k, 0.008f * k, 0.003f * k);
    }

    private static float ragePulse(Mob m) {
        return 0.5f + 0.5f * (float) Math.sin(m.animationTime * 9f);
    }

    /** Actual render matrices, also usable by headless joint/contact checks. */
    public static Matrix4f[] pose(Mob m) {
        Part[] parts = buildModel(m.type);
        Matrix4f[] result = new Matrix4f[parts.length];
        preparePose(m, parts, result);
        return result;
    }

    private static void preparePose(Mob m, Part[] parts, Matrix4f[] result) {
        float minY = Float.POSITIVE_INFINITY;
        for (int i = 0; i < parts.length; i++) {
            if (result[i] == null) result[i] = new Matrix4f();
            partMatrix(m, parts[i], result[i]);
            Matrix4f a = result[i];
            // Exact lower support of the transformed cube, without temporary vectors.
            minY = Math.min(minY, a.m31() - (Math.abs(a.m01()) + Math.abs(a.m11())
                    + Math.abs(a.m21())) * 0.5f);
        }
        // Keep the entire corpse above the support plane as it rolls. Live grounded
        // feet also stay in contact; airborne poses retain their physical height.
        if ((m.dead || m.onGround) && !m.inWater) {
            float lift = m.position.y - minY;
            for (int i = 0; i < parts.length; i++)
                result[i].m31(result[i].m31() + lift);
        }
    }

    /** Shared by the colour and shadow passes. Every appendage rotates at its socket. */
    private static void partMatrix(Mob m, Part p, Matrix4f out) {
        out.identity().translate(m.position);
        if (m.dead) out.rotate(m.topple, m.deathAxisX, 0f, m.deathAxisZ);
        out.rotateY(m.yaw);
        float hip = m.type.height * 0.5f;
        out.translate(0, hip, 0).rotateX(MobAnimation.bodyPitch(m))
                .rotateZ(MobAnimation.bodyRoll(m)).translate(0, -hip, 0);
        out.translate(p.pivX(), p.pivY(), p.pivZ());
        switch (p.anim()) {
            case LEG_A, LEG_B -> {
                boolean right = p.pivX() > 0;
                boolean front = p.pivZ() <= 0;
                out.rotateX(MobAnimation.leg(m, front, right));
                out.scale(1, MobAnimation.legLength(m, front, right, p.sy()), 1);
            }
            case TAIL -> out.rotateY(MobAnimation.tail(m));
            case HEAD -> out.rotateY(MobAnimation.headYaw(m)).rotateX(MobAnimation.headPitch(m));
            case ARM -> out.rotateX(MobAnimation.arm(m, p.pivX() > 0));
            case WING -> out.rotateZ(Math.signum(p.pivX()) * MobAnimation.wing(m));
            case NONE -> {
                // Breathing expands the chest; it must not levitate the feet.
                out.scale(1 + MobAnimation.breathe(m), 1 + MobAnimation.breathe(m), 1);
            }
        }
        out.translate(p.offX(), p.offY(), p.offZ()).scale(p.sx(), p.sy(), p.sz());
    }

    private static float skyVisAt(World world, Mob m) {
        int bx = (int) Math.floor(m.position.x);
        int by = (int) Math.floor(m.position.y + m.type.height * 0.5f);
        int bz = (int) Math.floor(m.position.z);
        return world.getSkyLight(bx, by, bz) / (float) Chunk.MAX_LIGHT;
    }

    private static float blockVisAt(World world, Mob m) {
        int bx = (int) Math.floor(m.position.x);
        int by = (int) Math.floor(m.position.y + m.type.height * 0.5f);
        int bz = (int) Math.floor(m.position.z);
        return world.getBlockLightWorld(bx, by, bz) / (float) Chunk.MAX_LIGHT;
    }

    static float uvX(int tile) {
        return ((tile % MobSkins.COLS) * MobSkins.TILE + 0.5f) / MobSkins.WIDTH;
    }

    static float uvY(int tile) {
        return ((tile / MobSkins.COLS) * MobSkins.TILE + 0.5f) / MobSkins.HEIGHT;
    }

    static int uploadTexture(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                buf.put((byte) ((argb >> 16) & 0xFF));
                buf.put((byte) ((argb >> 8) & 0xFF));
                buf.put((byte) (argb & 0xFF));
                buf.put((byte) ((argb >>> 24) & 0xFF));
            }
        buf.flip();
        int id = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, id);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        return id;
    }

    public void destroy() {
        for (int id : textures.values())
            glDeleteTextures(id);
        textures.clear();
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        shader.destroy();
    }
}

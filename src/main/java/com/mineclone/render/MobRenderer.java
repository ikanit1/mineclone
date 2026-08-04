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
    private enum Anim { NONE, LEG_A, LEG_B, ARM, WING }

    /**
     * Часть тела: пивот (точка вращения, в локальных координатах моба),
     * смещение центра бокса от пивота, размеры бокса, три тайла скина.
     */
    private record Part(float pivX, float pivY, float pivZ,
                        float offX, float offY, float offZ,
                        float sx, float sy, float sz,
                        int front, int side, int top,
                        Anim anim) {}

    private static final float LEG_SWING = 0.6f;      // ±34°
    private static final float PHASE_PER_METRE = 6f;
    private static final Vector3f NO_TINT = new Vector3f(1f, 1f, 1f);
    private static final Vector3f HURT_TINT = new Vector3f(1f, 0.45f, 0.45f);

    private static final float TILE_U = (MobSkins.TILE - 1f) / MobSkins.WIDTH;
    private static final float TILE_V = (MobSkins.TILE - 1f) / MobSkins.HEIGHT;

    // pos(3) + faceId(1) + cornerUv(2) = 6 float на вершину, 36 вершин.
    private static final float[] CUBE = {
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
    private static final int VERTEX_COUNT = 36;
    private static final int STRIDE = 6 * Float.BYTES;

    private final int vao, vbo;
    private final Shader shader;
    private final Map<MobType, Integer> textures = new EnumMap<>(MobType.class);
    private final Map<MobType, Part[]> models = new EnumMap<>(MobType.class);
    private final Matrix4f model = new Matrix4f();

    public MobRenderer() {
        shader = new Shader(Shaders.MOB_VERTEX, Shaders.MOB_FRAGMENT);
        vao = glGenVertexArrays();
        vbo = glGenBuffers();
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

        for (MobType t : MobType.values()) {
            textures.put(t, uploadTexture(MobSkins.load(t)));
            models.put(t, buildModel(t));
        }
    }

    // -------------------------------------------------------------------------
    //  Таблицы частей тела. Числа в блоках, подбирались под AABB из MobType —
    //  трогать можно свободно, на физику они не влияют.
    // -------------------------------------------------------------------------

    private static Part[] buildModel(MobType type) {
        int hf = MobSkins.T_HEAD_FRONT, hs = MobSkins.T_HEAD_SIDE, ht = MobSkins.T_HEAD_TOP;
        int bs = MobSkins.T_BODY_SIDE, bt = MobSkins.T_BODY_TOP;
        int lm = MobSkins.T_LIMB, ac = MobSkins.T_ACCENT;
        return switch (type) {
            case COW -> new Part[] {
                new Part(0f, 1.0625f, 0f, 0f, 0f, 0f, 0.75f, 0.625f, 1.125f, bs, bs, bt, Anim.NONE),
                new Part(0f, 1.15f, -0.76f, 0f, 0f, 0f, 0.5f, 0.5f, 0.4f, hf, hs, ht, Anim.NONE),
                new Part(-0.22f, 0.75f, -0.38f, 0f, -0.375f, 0f, 0.25f, 0.75f, 0.25f, lm, lm, lm, Anim.LEG_A),
                new Part( 0.22f, 0.75f, -0.38f, 0f, -0.375f, 0f, 0.25f, 0.75f, 0.25f, lm, lm, lm, Anim.LEG_B),
                new Part(-0.22f, 0.75f,  0.38f, 0f, -0.375f, 0f, 0.25f, 0.75f, 0.25f, lm, lm, lm, Anim.LEG_B),
                new Part( 0.22f, 0.75f,  0.38f, 0f, -0.375f, 0f, 0.25f, 0.75f, 0.25f, lm, lm, lm, Anim.LEG_A),
            };
            case PIG -> new Part[] {
                new Part(0f, 0.625f, 0f, 0f, 0f, 0f, 0.625f, 0.5f, 1.0f, bs, bs, bt, Anim.NONE),
                new Part(0f, 0.625f, -0.65f, 0f, 0f, 0f, 0.5f, 0.5f, 0.5f, hf, hs, ht, Anim.NONE),
                new Part(-0.2f, 0.375f, -0.35f, 0f, -0.1875f, 0f, 0.25f, 0.375f, 0.25f, lm, lm, lm, Anim.LEG_A),
                new Part( 0.2f, 0.375f, -0.35f, 0f, -0.1875f, 0f, 0.25f, 0.375f, 0.25f, lm, lm, lm, Anim.LEG_B),
                new Part(-0.2f, 0.375f,  0.35f, 0f, -0.1875f, 0f, 0.25f, 0.375f, 0.25f, lm, lm, lm, Anim.LEG_B),
                new Part( 0.2f, 0.375f,  0.35f, 0f, -0.1875f, 0f, 0.25f, 0.375f, 0.25f, lm, lm, lm, Anim.LEG_A),
            };
            case SHEEP -> new Part[] {
                new Part(0f, 0.925f, 0f, 0f, 0f, 0f, 0.7f, 0.65f, 1.05f, bs, bs, bt, Anim.NONE),
                new Part(0f, 1.0f, -0.72f, 0f, 0f, 0f, 0.45f, 0.45f, 0.4f, hf, hs, ht, Anim.NONE),
                new Part(-0.2f, 0.6f, -0.35f, 0f, -0.3f, 0f, 0.25f, 0.6f, 0.25f, lm, lm, lm, Anim.LEG_A),
                new Part( 0.2f, 0.6f, -0.35f, 0f, -0.3f, 0f, 0.25f, 0.6f, 0.25f, lm, lm, lm, Anim.LEG_B),
                new Part(-0.2f, 0.6f,  0.35f, 0f, -0.3f, 0f, 0.25f, 0.6f, 0.25f, lm, lm, lm, Anim.LEG_B),
                new Part( 0.2f, 0.6f,  0.35f, 0f, -0.3f, 0f, 0.25f, 0.6f, 0.25f, lm, lm, lm, Anim.LEG_A),
            };
            case CHICKEN -> new Part[] {
                new Part(0f, 0.425f, 0f, 0f, 0f, 0f, 0.3f, 0.35f, 0.4f, bs, bs, bt, Anim.NONE),
                new Part(0f, 0.6f, -0.15f, 0f, 0f, 0f, 0.25f, 0.25f, 0.2f, hf, hs, ht, Anim.NONE),
                new Part(-0.08f, 0.25f, 0f, 0f, -0.125f, 0f, 0.08f, 0.25f, 0.08f, lm, lm, lm, Anim.LEG_A),
                new Part( 0.08f, 0.25f, 0f, 0f, -0.125f, 0f, 0.08f, 0.25f, 0.08f, lm, lm, lm, Anim.LEG_B),
                new Part(-0.16f, 0.55f, 0f, 0f, -0.125f, 0f, 0.06f, 0.25f, 0.3f, ac, ac, ac, Anim.WING),
                new Part( 0.16f, 0.55f, 0f, 0f, -0.125f, 0f, 0.06f, 0.25f, 0.3f, ac, ac, ac, Anim.WING),
            };
            case ZOMBIE -> new Part[] {
                new Part(0f, 1.025f, 0f, 0f, 0f, 0f, 0.5f, 0.65f, 0.25f, bs, bs, bt, Anim.NONE),
                new Part(0f, 1.575f, 0f, 0f, 0f, 0f, 0.45f, 0.45f, 0.45f, hf, hs, ht, Anim.NONE),
                new Part(-0.13f, 0.7f, 0f, 0f, -0.35f, 0f, 0.25f, 0.7f, 0.25f, lm, lm, lm, Anim.LEG_A),
                new Part( 0.13f, 0.7f, 0f, 0f, -0.35f, 0f, 0.25f, 0.7f, 0.25f, lm, lm, lm, Anim.LEG_B),
                new Part(-0.36f, 1.3f, 0f, 0f, -0.3f, 0f, 0.22f, 0.6f, 0.22f, ac, ac, ac, Anim.ARM),
                new Part( 0.36f, 1.3f, 0f, 0f, -0.3f, 0f, 0.22f, 0.6f, 0.22f, ac, ac, ac, Anim.ARM),
            };
        };
    }

    // -------------------------------------------------------------------------

    public void render(Matrix4f proj, Matrix4f view, List<Mob> mobs, World world,
                       float daylight, float ambient, float brightness,
                       Vector3f fogColor, float fogStart, float fogEnd) {
        if (mobs.isEmpty())
            return;
        glDisable(GL_CULL_FACE);
        shader.bind();
        shader.setMat4("uProjection", proj);
        shader.setMat4("uView", view);
        shader.setInt("uSkin", 0);
        shader.setVec3("uFogColor", fogColor);
        shader.setFloat("uFogStart", fogStart);
        shader.setFloat("uFogEnd", fogEnd);
        shader.setVec2("uTileSize", TILE_U, TILE_V);
        glBindVertexArray(vao);

        for (Mob m : mobs) {
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, textures.get(m.type));
            shader.setFloat("uLight", lightAt(world, m, daylight, ambient, brightness));
            shader.setVec3("uTint", m.hurtFlash > 0f ? HURT_TINT : NO_TINT);

            float phase = m.walkedDistance * PHASE_PER_METRE;
            for (Part p : models.get(m.type)) {
                model.identity()
                     .translate(m.position.x, m.position.y, m.position.z)
                     .rotateY(m.yaw)
                     .translate(p.pivX(), p.pivY(), p.pivZ());
                switch (p.anim()) {
                    case LEG_A -> model.rotateX((float) Math.sin(phase) * LEG_SWING);
                    case LEG_B -> model.rotateX((float) -Math.sin(phase) * LEG_SWING);
                    // Руки зомби вытянуты вперёд (в −Z) + лёгкое покачивание.
                    // В момент удара замахивается: руки поднимаются и опускаются
                    // за ATTACK_SWING_TIME — иначе атака визуально не читается.
                    case ARM -> {
                        float arm = 1.5708f + (float) Math.sin(phase * 0.5f) * 0.1f;
                        if (m.attackSwing > 0f)
                            arm -= 1.2f * (float) Math.sin(
                                    Math.PI * (m.attackSwing / Mob.ATTACK_SWING_TIME));
                        model.rotateX(arm);
                    }
                    case WING -> model.rotateZ((float) Math.sin(phase * 2f) * 0.35f);
                    case NONE -> { }
                }
                model.translate(p.offX(), p.offY(), p.offZ()).scale(p.sx(), p.sy(), p.sz());
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

    /** Тот же тон-курве, что у чанков — моб темнеет ночью и в тени. */
    private static float lightAt(World world, Mob m, float daylight, float ambient,
                                 float brightness) {
        int bx = (int) Math.floor(m.position.x);
        int by = (int) Math.floor(m.position.y + m.type.height * 0.5f);
        int bz = (int) Math.floor(m.position.z);
        float skyF = world.getSkyLight(bx, by, bz) / (float) Chunk.MAX_LIGHT;
        float blkF = world.getBlockLightWorld(bx, by, bz) / (float) Chunk.MAX_LIGHT;
        float combined = Math.max(skyF * daylight, blkF);
        return Math.min(1f, (float) Math.pow(Math.max(ambient, combined), 0.75) * brightness);
    }

    private static float uvX(int tile) {
        return ((tile % MobSkins.COLS) * MobSkins.TILE + 0.5f) / MobSkins.WIDTH;
    }

    private static float uvY(int tile) {
        return ((tile / MobSkins.COLS) * MobSkins.TILE + 0.5f) / MobSkins.HEIGHT;
    }

    private static int uploadTexture(BufferedImage img) {
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

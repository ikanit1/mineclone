package com.mineclone.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.glDeleteBuffers;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30.glDeleteVertexArrays;

/**
 * Модель игрока для вида от третьего лица.
 *
 * Тот же единичный куб, тот же шейдер и та же раскладка скина, что у мобов
 * ({@link MobRenderer}) — иначе игрок и мобы разъедутся по стилю при первой
 * же правке одного из них. Текстура берётся из {@link PlayerSkin}, которая
 * уже раскладывает восемь тайлов ровно под эту схему.
 *
 * Габариты подогнаны под AABB игрока (0.6 × 1.8): голова, корпус, две руки,
 * две ноги. Анимация — чистые синусы от пройденного пути, как у мобов.
 */
public final class PlayerRenderer {

    /**
     * Часть тела: пивот (точка вращения в локальных координатах),
     * смещение центра бокса от пивота, размеры, тайлы скина и вид анимации.
     */
    private record Part(float pivX, float pivY, float pivZ,
                        float offX, float offY, float offZ,
                        float sx, float sy, float sz,
                        int front, int side, int top,
                        int anim) {}

    private static final int A_NONE = 0;
    private static final int A_HEAD = 1;
    private static final int A_LEG_A = 2;
    private static final int A_LEG_B = 3;
    private static final int A_ARM_A = 4;
    private static final int A_ARM_B = 5;

    private static final Part[] BODY = {
            // Корпус: рубаха со всех сторон. Верх НЕ берём с T_BODY_TOP —
            // в скине игрока этот тайл занят торцом кулака.
            new Part(0f, 1.05f, 0f, 0f, 0f, 0f, 0.5f, 0.7f, 0.26f,
                    MobSkins.T_BODY_SIDE, MobSkins.T_BODY_SIDE, MobSkins.T_BODY_SIDE, A_NONE),
            // голова: лицо спереди, профиль по бокам, волосы сверху
            new Part(0f, 1.62f, 0f, 0f, 0f, 0f, 0.5f, 0.5f, 0.5f,
                    MobSkins.T_HEAD_FRONT, MobSkins.T_HEAD_SIDE, MobSkins.T_HEAD_TOP, A_HEAD),
            // Ноги — запасной тайл: в нём лежат штаны. Через T_LIMB ноги
            // выходили телесными, как вторая пара рук.
            new Part(-0.13f, 0.7f, 0f, 0f, -0.35f, 0f, 0.24f, 0.7f, 0.24f,
                    MobSkins.T_SPARE, MobSkins.T_SPARE, MobSkins.T_SPARE, A_LEG_A),
            new Part(0.13f, 0.7f, 0f, 0f, -0.35f, 0f, 0.24f, 0.7f, 0.24f,
                    MobSkins.T_SPARE, MobSkins.T_SPARE, MobSkins.T_SPARE, A_LEG_B),
            // Руки: рукав и предплечье по длинным граням, кулак на торцах.
            new Part(-0.37f, 1.38f, 0f, 0f, -0.32f, 0f, 0.22f, 0.64f, 0.22f,
                    MobSkins.T_ACCENT, MobSkins.T_LIMB, MobSkins.T_BODY_TOP, A_ARM_A),
            new Part(0.37f, 1.38f, 0f, 0f, -0.32f, 0f, 0.22f, 0.64f, 0.22f,
                    MobSkins.T_ACCENT, MobSkins.T_LIMB, MobSkins.T_BODY_TOP, A_ARM_B),
    };

    private final int vao, vbo;
    private final int texture;
    private final Shader shader;
    private final Matrix4f model = new Matrix4f();

    public PlayerRenderer() {
        shader = new Shader(Shaders.MOB_VERTEX, Shaders.MOB_FRAGMENT);
        int[] ids = MobRenderer.createCubeVao();
        vao = ids[0];
        vbo = ids[1];
        texture = MobRenderer.uploadTexture(PlayerSkin.load());
    }

    /**
     * @param yaw          курс игрока, радианы (как у камеры)
     * @param pitch        наклон взгляда; поворачивает только голову
     * @param walkDistance монотонный пройденный путь — им заведены синусы шага
     * @param walkAmount   0..1, размах шага; на месте гаснет в ноль
     * @param swing        0..1, замах рукой
     */
    public void render(Matrix4f proj, Matrix4f view, Vector3f position,
                       float yaw, float pitch, float walkDistance, float walkAmount, float swing,
                       SceneLighting lighting, float skyVis, float blockVis) {
        glDisable(GL_CULL_FACE);
        shader.bind();
        shader.setMat4("uProjection", proj);
        shader.setMat4("uView", view);
        shader.setInt("uSkin", 0);
        lighting.apply(shader);
        shader.setVec2("uTileSize", MobRenderer.TILE_U, MobRenderer.TILE_V);
        shader.setFloat("uSkyVis", skyVis);
        shader.setFloat("uBlockVis", blockVis);
        shader.setVec3("uTint", NO_TINT);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texture);
        glBindVertexArray(vao);

        for (Part p : BODY) {
            partMatrix(position, yaw, pitch, walkDistance, walkAmount, swing, p, model);
            shader.setMat4("uModel", model);
            shader.setVec2("uUvFront", MobRenderer.uvX(p.front()), MobRenderer.uvY(p.front()));
            shader.setVec2("uUvSide", MobRenderer.uvX(p.side()), MobRenderer.uvY(p.side()));
            shader.setVec2("uUvTop", MobRenderer.uvX(p.top()), MobRenderer.uvY(p.top()));
            glDrawArrays(GL_TRIANGLES, 0, MobRenderer.VERTEX_COUNT);
        }

        glBindVertexArray(0);
        shader.unbind();
        glEnable(GL_CULL_FACE);
    }

    /** Те же части в карту теней: игрок обязан отбрасывать тень наравне с мобами. */
    public void renderShadow(Shader shadowShader, Matrix4f lightSpace, Vector3f position,
                            float yaw, float pitch, float walkDistance, float walkAmount,
                            float swing) {
        shadowShader.bind();
        shadowShader.setMat4("uLightSpace", lightSpace);
        shadowShader.setInt("uAtlas", 0);
        shadowShader.setVec2("uTileSize", MobRenderer.TILE_U, MobRenderer.TILE_V);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texture);
        glBindVertexArray(vao);
        for (Part p : BODY) {
            partMatrix(position, yaw, pitch, walkDistance, walkAmount, swing, p, model);
            shadowShader.setMat4("uModel", model);
            shadowShader.setVec2("uUvFront", MobRenderer.uvX(p.front()), MobRenderer.uvY(p.front()));
            shadowShader.setVec2("uUvSide", MobRenderer.uvX(p.side()), MobRenderer.uvY(p.side()));
            shadowShader.setVec2("uUvTop", MobRenderer.uvX(p.top()), MobRenderer.uvY(p.top()));
            glDrawArrays(GL_TRIANGLES, 0, MobRenderer.VERTEX_COUNT);
        }
        glBindVertexArray(0);
        shadowShader.unbind();
    }

    /**
     * Матрица одной части тела. Вынесена отдельно и статична, чтобы её могли
     * звать и проход цвета, и проход теней — тогда тень не может разъехаться
     * с моделью.
     */
    private static void partMatrix(Vector3f position, float yaw, float pitch,
                           float walkDistance, float walkAmount, float swing,
                           Part p, Matrix4f out) {
        // Камера смотрит по -Z при yaw = 0, а модель развёрнута лицом в -Z,
        // поэтому курс уходит в поворот как есть.
        out.identity().translate(position.x, position.y, position.z).rotateY(yaw);
        out.translate(p.pivX(), p.pivY(), p.pivZ());
        float step = swingOf(walkDistance, walkAmount);
        switch (p.anim()) {
            case A_HEAD -> out.rotateX(pitch);
            case A_LEG_A -> out.rotateX(step);
            case A_LEG_B -> out.rotateX(-step);
            // Руки ходят в противофазе с ногами; замах добавляется поверх.
            case A_ARM_A -> out.rotateX(-step * 0.75f - swing * 1.3f);
            case A_ARM_B -> out.rotateX(step * 0.75f);
            default -> { }
        }
        out.translate(p.offX(), p.offY(), p.offZ()).scale(p.sx(), p.sy(), p.sz());
    }

    /**
     * Размах шага от пройденного пути, умноженный на амплитуду.
     * Амплитуда обязана гаснуть на месте: walkedDistance монотонен, и без неё
     * остановившийся игрок застывает с раскинутыми ногами.
     */
    public static float swingOf(float walkDistance, float walkAmount) {
        return (float) Math.sin(walkDistance * 6.0) * 0.62f * clamp01(walkAmount);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static final Vector3f NO_TINT = new Vector3f(1f, 1f, 1f);

    public void destroy() {
        glDeleteTextures(texture);
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        shader.destroy();
    }
}

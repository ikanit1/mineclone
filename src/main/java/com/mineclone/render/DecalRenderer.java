package com.mineclone.render;

import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Следы на земле: горизонтальные спрайты, лежащие на поверхности и медленно
 * тающие.
 *
 * Шейдер берётся частиц ({@code PARTICLE_*}): там квад строится из
 * произвольных базисных векторов, а не всегда лицом к камере, — значит
 * горизонтальный квад это тот же квад с другим базисом. Заводить ради этого
 * ещё одну программу было бы копией с одной изменённой строкой.
 *
 * Глубина читается, но не пишется: след лежит поверх блока и не должен
 * заслонять частицы и воду, нарисованные после него.
 */
public class DecalRenderer {

    /** Потолок следов. Старые вытесняются новыми — цепочка, а не куча. */
    private static final int MAX = 160;
    /** На сколько след приподнят над гранью, чтобы не мерцать z-fighting'ом. */
    private static final float LIFT = 0.021f;
    /** Последняя доля жизни, на которой след тает. */
    private static final float FADE_TAIL = 0.45f;

    private static final class D {
        float x, y, z;
        float cos, sin;          // поворот следа вокруг вертикали
        float size;
        float life, maxLife;
        float alpha;
        float u0, v0, u1, v1;
    }

    private final List<D> decals = new ArrayList<>();
    private final int vao, vbo;
    private final Shader shader;

    public DecalRenderer() {
        shader = new Shader(Shaders.PARTICLE_VERTEX, Shaders.PARTICLE_FRAGMENT);
        float[] quad = {
            -0.5f, -0.5f,  0.5f, -0.5f,  0.5f, 0.5f,
            -0.5f, -0.5f,  0.5f,  0.5f, -0.5f, 0.5f
        };
        vao = glGenVertexArrays();
        vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, quad, GL_STATIC_DRAW);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 2 * Float.BYTES, 0L);
        glEnableVertexAttribArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    /**
     * Кладёт след на поверхность.
     *
     * @param topY   уровень грани, на которую ложится след
     * @param yaw    курс в конвенции камеры: носок спрайта смотрит туда же,
     *               куда направление {@code (-sin yaw, -cos yaw)}
     * @param life   сколько секунд след держится
     */
    public void add(float x, float topY, float z, float yaw, float size, float life,
                    float alpha, int tile) {
        D d = decals.size() >= MAX ? decals.remove(0) : new D();
        d.x = x;
        d.y = topY + LIFT;
        d.z = z;
        d.cos = (float) Math.cos(yaw);
        d.sin = (float) Math.sin(yaw);
        d.size = size;
        d.life = life;
        d.maxLife = life;
        d.alpha = alpha;
        float[] uv = TextureAtlas.uv(tile);
        d.u0 = uv[0];
        d.v0 = uv[1];
        d.u1 = uv[2];
        d.v1 = uv[3];
        decals.add(d);
    }

    public void update(float dt) {
        decals.removeIf(d -> (d.life -= dt) <= 0f);
    }

    public void clear() {
        decals.clear();
    }

    public int count() {
        return decals.size();
    }

    /** Доля непрозрачности следа по остатку жизни: ровно, потом хвост в ноль. */
    public static float fade(float life, float maxLife) {
        if (maxLife <= 0f)
            return 0f;
        float left = Math.max(0f, Math.min(1f, life / maxLife));
        return left >= FADE_TAIL ? 1f : left / FADE_TAIL;
    }

    public void render(Matrix4f projection, Matrix4f view, int atlasTexture, boolean linearOut) {
        if (decals.isEmpty())
            return;
        shader.bind();
        shader.setMat4("uProjection", projection);
        shader.setMat4("uView", view);
        shader.setInt("uAtlas", 0);
        shader.setFloat("uLinearOut", linearOut ? 1f : 0f);
        shader.setFloat("uEmissive", 0f);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, atlasTexture);

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(false);
        // Отсечение задних граней выключено: горизонтальный квад повёрнут
        // рысканьем следа, и половина направлений отбраковывалась бы как
        // «изнанка». Частицам это не мешает — они всегда лицом к камере.
        glDisable(GL_CULL_FACE);
        glBindVertexArray(vao);

        for (D d : decals) {
            shader.setVec3("uCenter", d.x, d.y, d.z);
            // Базис лежит в плоскости XZ: квад ложится на грань, а не встаёт
            // лицом к камере, как частица. uUp смотрит в низ спрайта (v растёт
            // вниз по картинке), а носок нарисован сверху — поэтому носок
            // оказывается ровно по курсу.
            shader.setVec3("uRight", d.cos, 0f, -d.sin);
            shader.setVec3("uUp", d.sin, 0f, d.cos);
            shader.setFloat("uSize", d.size);
            shader.setVec2("uUv0", d.u0, d.v0);
            shader.setVec2("uUv1", d.u1, d.v1);
            shader.setVec4("uColor", 1f, 1f, 1f, d.alpha * fade(d.life, d.maxLife));
            glDrawArrays(GL_TRIANGLES, 0, 6);
        }

        glBindVertexArray(0);
        glEnable(GL_CULL_FACE);
        glDepthMask(true);
        glDisable(GL_BLEND);
        shader.unbind();
    }

    public void destroy() {
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        shader.destroy();
    }
}

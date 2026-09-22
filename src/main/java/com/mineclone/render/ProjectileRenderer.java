package com.mineclone.render;

import com.mineclone.world.entity.Projectile;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.glDeleteBuffers;
import static org.lwjgl.opengl.GL30.*;

/**
 * Рисует летящие и воткнувшиеся снаряды тем же кубом и шейдером, что мобов и
 * обломки: свет, тени и туман у стрелы ровно те же, что у мира вокруг.
 *
 * Стрела — вытянутый вдоль полёта брусок, а не плоский спрайт лицом к камере:
 * в блочном мире плоский квад выдаёт себя сразу, а летящая стрела ещё и
 * обязана показывать, куда она летит.
 */
public final class ProjectileRenderer {

    /** Длина и толщина стрелы в блоках. */
    private static final float LENGTH = 0.85f, THICK = 0.09f;
    private static final Vector3f NO_TINT = new Vector3f(1f, 1f, 1f);

    private final Shader shader;
    private final int vao, vbo;
    private final Matrix4f model = new Matrix4f();
    private final Vector3f up = new Vector3f();
    private final Vector3f right = new Vector3f();

    public ProjectileRenderer() {
        shader = new Shader(Shaders.MOB_VERTEX, Shaders.MOB_FRAGMENT);
        int[] ids = MobRenderer.createCubeVao();
        vao = ids[0];
        vbo = ids[1];
    }

    public void render(Matrix4f proj, Matrix4f view, List<Projectile> shots,
                       com.mineclone.world.World world, TextureAtlas atlas,
                       SceneLighting lighting, float daylight) {
        if (shots.isEmpty())
            return;
        glDisable(GL_CULL_FACE);
        shader.bind();
        shader.setMat4("uProjection", proj);
        shader.setMat4("uView", view);
        shader.setInt("uSkin", 0);
        lighting.apply(shader);
        float tile = 1f / TextureAtlas.TILES_PER_ROW;
        shader.setVec2("uTileSize", tile, tile);
        shader.setVec3("uTint", NO_TINT);
        atlas.bind(0);
        glBindVertexArray(vao);
        int arrowTile = TextureAtlas.tileIndex("arrow");
        float[] uv = TextureAtlas.uv(arrowTile < 0 ? 0 : arrowTile);
        shader.setVec2("uUvFront", uv[0], uv[1]);
        shader.setVec2("uUvSide", uv[0], uv[1]);
        shader.setVec2("uUvTop", uv[0], uv[1]);
        for (Projectile p : shots) {
            if (p.dead)
                continue;
            orient(p.heading);
            model.identity()
                    .translate(p.position.x, p.position.y, p.position.z)
                    .set3x3(new Matrix4f().set(
                            right.x, right.y, right.z, 0f,
                            up.x, up.y, up.z, 0f,
                            p.heading.x, p.heading.y, p.heading.z, 0f,
                            0f, 0f, 0f, 1f))
                    .scale(THICK, THICK, LENGTH);
            shader.setMat4("uModel", model);
            float sky = world == null ? 1f
                    : world.getSkyLight((int) Math.floor(p.position.x),
                            (int) Math.floor(p.position.y), (int) Math.floor(p.position.z)) / 15f;
            float block = world == null ? 0f
                    : world.getBlockLightWorld((int) Math.floor(p.position.x),
                            (int) Math.floor(p.position.y), (int) Math.floor(p.position.z)) / 15f;
            shader.setFloat("uSkyVis", sky * daylight);
            shader.setFloat("uBlockVis", block);
            glDrawArrays(GL_TRIANGLES, 0, MobRenderer.VERTEX_COUNT);
        }
        glBindVertexArray(0);
        shader.unbind();
        glEnable(GL_CULL_FACE);
    }

    /**
     * Базис вокруг направления полёта.
     *
     * Опорная ось берётся не «всегда вверх»: у стрелы, летящей строго вниз,
     * вертикаль совпала бы с курсом, и векторное произведение выродилось бы в
     * ноль — брусок схлопнулся бы в точку.
     */
    private void orient(Vector3f heading) {
        Vector3f reference = Math.abs(heading.y) > 0.95f
                ? new Vector3f(1f, 0f, 0f)
                : new Vector3f(0f, 1f, 0f);
        right.set(reference).cross(heading);
        if (right.lengthSquared() < 1e-8f)
            right.set(1f, 0f, 0f);
        right.normalize();
        up.set(heading).cross(right).normalize();
    }

    public void destroy() {
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        shader.destroy();
    }
}

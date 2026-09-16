package com.mineclone.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.glDeleteBuffers;
import static org.lwjgl.opengl.GL30.*;

/**
 * Рисует {@link Debris} тем же кубом и шейдером, что мобов: освещение, тени и
 * туман у обломка ровно те же, что у мира вокруг. Текстура — блочный атлас,
 * но на грань ложится не весь тайл, а случайный кусочек размером в четверть
 * тайла: обломок камня — это кусок камня, а не уменьшенный камень.
 */
public final class DebrisRenderer {

    /** Какая доля тайла видна на грани обломка. */
    private static final float PATCH = 0.28f;
    private static final Vector3f NO_TINT = new Vector3f(1f, 1f, 1f);
    private static final Vector3f ICE_TINT = new Vector3f(0.92f, 1.0f, 1.12f);

    private final Shader shader;
    private final int vao, vbo;
    private final Matrix4f model = new Matrix4f();

    public DebrisRenderer() {
        shader = new Shader(Shaders.MOB_VERTEX, Shaders.MOB_FRAGMENT);
        int[] ids = MobRenderer.createCubeVao();
        vao = ids[0];
        vbo = ids[1];
    }

    public void render(Matrix4f proj, Matrix4f view, Debris debris, TextureAtlas atlas,
                       SceneLighting lighting, float daylight) {
        if (debris.pieces().isEmpty())
            return;
        glDisable(GL_CULL_FACE);
        shader.bind();
        shader.setMat4("uProjection", proj);
        shader.setMat4("uView", view);
        shader.setInt("uSkin", 0);
        lighting.apply(shader);
        float tile = 1f / TextureAtlas.TILES_PER_ROW;
        float span = tile * PATCH;
        shader.setVec2("uTileSize", span, span);
        atlas.bind(0);
        glBindVertexArray(vao);
        for (Debris.Piece p : debris.pieces()) {
            float s = p.size * p.scale();
            if (s <= 0.002f)
                continue;
            model.identity().translate(p.x, p.y, p.z)
                    .rotateXYZ(p.rotX, p.rotY, p.rotZ)
                    .scale(s, s * p.flatten, s);
            shader.setMat4("uModel", model);
            float[] side = TextureAtlas.uv(p.sideTile);
            float[] top = TextureAtlas.uv(p.topTile);
            float free = (side[2] - side[0]) - span;
            float su = side[0] + p.texU * Math.max(0f, free), sv = side[1] + p.texV * Math.max(0f, free);
            float tu = top[0] + p.texV * Math.max(0f, free), tv = top[1] + p.texU * Math.max(0f, free);
            shader.setVec2("uUvFront", su, sv);
            shader.setVec2("uUvSide", su, sv);
            shader.setVec2("uUvTop", tu, tv);
            shader.setFloat("uSkyVis", p.skyLight * daylight);
            shader.setFloat("uBlockVis", p.blockLight);
            shader.setVec3("uTint", p.glassy ? ICE_TINT : NO_TINT);
            glDrawArrays(GL_TRIANGLES, 0, MobRenderer.VERTEX_COUNT);
        }
        glBindVertexArray(0);
        shader.unbind();
        glEnable(GL_CULL_FACE);
    }

    public void destroy() {
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        shader.destroy();
    }
}

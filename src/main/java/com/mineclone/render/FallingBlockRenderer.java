package com.mineclone.render;

import com.mineclone.world.Chunk;
import com.mineclone.world.World;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import static org.lwjgl.opengl.GL33.*;

/** Full-size unrotated block cubes while gravity owns them instead of the chunk mesh. */
public final class FallingBlockRenderer {
    private final Shader shader = new Shader(Shaders.MOB_VERTEX, Shaders.MOB_FRAGMENT);
    private final Matrix4f model = new Matrix4f();
    private final Vector3f white = new Vector3f(1);
    private final int vao, vbo;

    public FallingBlockRenderer() {
        int[] ids = MobRenderer.createCubeVao(); vao = ids[0]; vbo = ids[1];
    }

    public void render(Matrix4f projection, Matrix4f view, World world, TextureAtlas atlas,
                       SceneLighting lighting, float daylight) {
        if (world.falling.active().isEmpty()) return;
        shader.bind();
        shader.setMat4("uProjection", projection); shader.setMat4("uView", view);
        shader.setInt("uSkin", 0); shader.setVec2("uSkinGrid", 0, 0);
        shader.setVec3("uTint", white); lighting.apply(shader);
        float span = (TextureAtlas.TILE - 1f) / TextureAtlas.ATLAS_SIZE;
        shader.setVec2("uTileSize", span, span);
        atlas.bind(0); glBindVertexArray(vao); glDisable(GL_CULL_FACE);
        for (var f : world.falling.active()) {
            int y = Math.max(0, (int) f.y);
            shader.setFloat("uSkyVis", world.getSkyLight(f.x, y, f.z) / (float) Chunk.MAX_LIGHT * daylight);
            shader.setFloat("uBlockVis", world.getBlockLightWorld(f.x, y, f.z) / (float) Chunk.MAX_LIGHT);
            float[] uv = TextureAtlas.uv(f.block.sideTile);
            shader.setVec2("uUvFront", uv[0], uv[1]); shader.setVec2("uUvSide", uv[0], uv[1]);
            shader.setVec2("uUvTop", uv[0], uv[1]);
            shader.setMat4("uModel", model.translation(f.x + 0.5f, f.y + 0.5f, f.z + 0.5f));
            glDrawArrays(GL_TRIANGLES, 0, MobRenderer.VERTEX_COUNT);
        }
        glBindVertexArray(0); glEnable(GL_CULL_FACE); shader.unbind();
    }

    public void destroy() { glDeleteBuffers(vbo); glDeleteVertexArrays(vao); shader.destroy(); }
}

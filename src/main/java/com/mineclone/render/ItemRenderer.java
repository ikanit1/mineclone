package com.mineclone.render;

import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.ItemStack;
import com.mineclone.world.World;
import com.mineclone.world.entity.ItemEntity;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.glDeleteBuffers;
import static org.lwjgl.opengl.GL30.*;

/**
 * Предметы на земле. Блок — маленький кубик с гранями из атласа, инструмент и
 * еда — тонкая пластинка со своим спрайтом. Всё тем же кубом и шейдером, что
 * мобы: свет, тени и туман у предмета ровно те же, что у мира вокруг.
 *
 * Стопка рисуется двумя-тремя копиями вразбежку: одна иконка на 64 булыжника
 * не отличала бы стопку от одного блока.
 */
public final class ItemRenderer {

    private static final float BLOCK_SIZE = 0.25f;
    private static final float FLAT_SIZE = 0.42f, FLAT_DEPTH = 0.02f;
    /**
     * У пластинки рисуются только лицевая и обратная грани — первые двенадцать
     * вершин куба. Торцы получили бы весь спрайт, сжатый в полоску, и у факела
     * или кирки по бокам торчали бы пунктирные штрихи.
     */
    private static final int FLAT_VERTICES = 12;
    private static final Vector3f NO_TINT = new Vector3f(1f, 1f, 1f);

    private final org.joml.FrustumIntersection visibility = new org.joml.FrustumIntersection();
    private final Matrix4f visibilityMatrix = new Matrix4f();
    private final Shader shader;
    private final int vao, vbo;
    private final Matrix4f model = new Matrix4f();

    public ItemRenderer() {
        shader = new Shader(Shaders.MOB_VERTEX, Shaders.MOB_FRAGMENT);
        int[] ids = MobRenderer.createCubeVao();
        vao = ids[0];
        vbo = ids[1];
    }

    public void render(Matrix4f proj, Matrix4f view, List<ItemEntity> items, World world,
                       TextureAtlas atlas, SceneLighting lighting, float daylight) {
        if (items.isEmpty())
            return;
        visibility.set(visibilityMatrix.set(proj).mul(view));
        glDisable(GL_CULL_FACE);
        shader.bind();
        shader.setMat4("uProjection", proj);
        shader.setMat4("uView", view);
        shader.setInt("uSkin", 0);
        shader.setVec3("uTint", NO_TINT);
        lighting.apply(shader);
        atlas.bind(0);
        glBindVertexArray(vao);
        float tileSpan = (TextureAtlas.TILE - 1f) / TextureAtlas.ATLAS_SIZE;
        shader.setVec2("uTileSize", tileSpan, tileSpan);
        for (ItemEntity e : items) {
            if (!visibility.testSphere(e.position.x, e.position.y + 0.4f, e.position.z, 0.9f)) continue;
            if (e.stack.count <= 0)
                continue;
            int bx = (int) Math.floor(e.position.x), by = (int) Math.floor(e.position.y + 0.2f),
                bz = (int) Math.floor(e.position.z);
            shader.setFloat("uSkyVis", world.getSkyLight(bx, by, bz) / (float) Chunk.MAX_LIGHT * daylight);
            shader.setFloat("uBlockVis", world.getBlockLightWorld(bx, by, bz) / (float) Chunk.MAX_LIGHT);
            boolean cube = isCube(e.stack);
            int copies = e.stack.count >= 16 ? 3 : e.stack.count >= 2 ? 2 : 1;
            setTiles(e.stack, cube);
            for (int k = 0; k < copies; k++) {
                // Копии разнесены по кругу и по высоте — стопка, а не мерцание.
                float ox = k == 0 ? 0f : (float) Math.cos(k * 2.1f + e.phase) * 0.07f;
                float oz = k == 0 ? 0f : (float) Math.sin(k * 2.1f + e.phase) * 0.07f;
                float oy = k * 0.05f;
                model.identity().translate(e.position.x + ox, e.position.y + e.bob() + oy, e.position.z + oz)
                        .rotateY(e.spin() + k * 0.6f)
                        .rotateZ(e.waveTilt());
                if (cube)
                    model.translate(0f, BLOCK_SIZE * 0.5f, 0f).scale(BLOCK_SIZE);
                else
                    model.translate(0f, FLAT_SIZE * 0.5f, 0f).scale(FLAT_SIZE, FLAT_SIZE, FLAT_DEPTH);
                shader.setMat4("uModel", model);
                glDrawArrays(GL_TRIANGLES, 0, cube ? MobRenderer.VERTEX_COUNT : FLAT_VERTICES);
            }
        }
        glBindVertexArray(0);
        shader.unbind();
        glEnable(GL_CULL_FACE);
    }

    private void setTiles(ItemStack s, boolean cube) {
        if (cube) {
            float[] side = TextureAtlas.uv(s.block().sideTile);
            float[] top = TextureAtlas.uv(s.block().topTile);
            shader.setVec2("uUvFront", side[0], side[1]);
            shader.setVec2("uUvSide", side[0], side[1]);
            shader.setVec2("uUvTop", top[0], top[1]);
        } else {
            float[] uv = TextureAtlas.uv(s.iconTile());
            shader.setVec2("uUvFront", uv[0], uv[1]);
            shader.setVec2("uUvSide", uv[0], uv[1]);
            shader.setVec2("uUvTop", uv[0], uv[1]);
        }
    }

    /** Кубиком рисуются настоящие блоки; факел, огонь и слои — плоско. */
    private static boolean isCube(ItemStack s) {
        BlockType b = s.block();
        return b != null && !b.isCross() && !b.isLayered()
                && b != BlockType.WATER && b != BlockType.WATER_FLOW && b != BlockType.LAVA;
    }

    public void destroy() {
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        shader.destroy();
    }
}

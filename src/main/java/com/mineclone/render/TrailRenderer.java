package com.mineclone.render;

import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.World;
import java.nio.FloatBuffer;
import java.util.Map;
import org.joml.Matrix4f;
import org.joml.Vector3fc;
import org.lwjgl.BufferUtils;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.*;
import static org.lwjgl.opengl.GL33.*;

/** One instanced draw of persistent, pixel-edged soil wear above exposed grass. */
public final class TrailRenderer {
    private static final int RADIUS = 28;
    private static final int MAX_VISIBLE = 4096;
    private static final int INSTANCE_FLOATS = 10;
    private static final float LIFT = 0.016f;
    private static final float[] DIRT_UV = TextureAtlas.uv(BlockType.DIRT.topTile);
    private final FloatBuffer instances = BufferUtils.createFloatBuffer(MAX_VISIBLE * INSTANCE_FLOATS);
    private final int vao, quadBuffer, instanceBuffer;
    private final Shader shader = new Shader(Shaders.FOOTPATH_VERTEX, Shaders.FOOTPATH_FRAGMENT);

    public TrailRenderer() {
        float[] quad = {
                -0.5f, -0.5f,  0.5f, -0.5f,  0.5f, 0.5f,
                -0.5f, -0.5f,  0.5f,  0.5f, -0.5f, 0.5f
        };
        vao = glGenVertexArrays();
        quadBuffer = glGenBuffers();
        instanceBuffer = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, quadBuffer);
        glBufferData(GL_ARRAY_BUFFER, quad, GL_STATIC_DRAW);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 2 * Float.BYTES, 0L);
        glEnableVertexAttribArray(0);

        glBindBuffer(GL_ARRAY_BUFFER, instanceBuffer);
        glBufferData(GL_ARRAY_BUFFER, (long) MAX_VISIBLE * INSTANCE_FLOATS * Float.BYTES, GL_STREAM_DRAW);
        int stride = INSTANCE_FLOATS * Float.BYTES;
        glVertexAttribPointer(1, 4, GL_FLOAT, false, stride, 0L);
        glVertexAttribPointer(2, 4, GL_FLOAT, false, stride, 4L * Float.BYTES);
        glVertexAttribPointer(3, 2, GL_FLOAT, false, stride, 8L * Float.BYTES);
        for (int i = 1; i <= 3; i++) {
            glEnableVertexAttribArray(i);
            glVertexAttribDivisor(i, 1);
        }
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    public int render(Matrix4f projection, Matrix4f view, World world, Vector3fc camera,
                      TextureAtlas atlas, SceneLighting lighting) {
        instances.clear();
        int count = gather(world, camera);
        if (count == 0) return 0;
        instances.flip();
        glBindBuffer(GL_ARRAY_BUFFER, instanceBuffer);
        glBufferSubData(GL_ARRAY_BUFFER, 0, instances);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        shader.bind();
        shader.setMat4("uProjection", projection);
        shader.setMat4("uView", view);
        shader.setInt("uAtlas", 0);
        shader.setVec2("uDirtUv0", DIRT_UV[0], DIRT_UV[1]);
        shader.setVec2("uDirtUv1", DIRT_UV[2], DIRT_UV[3]);
        lighting.apply(shader);
        atlas.bind(0);
        glDepthMask(false);
        glDisable(GL_CULL_FACE);
        glBindVertexArray(vao);
        glDrawArraysInstanced(GL_TRIANGLES, 0, 6, count);
        glBindVertexArray(0);
        glEnable(GL_CULL_FACE);
        glDepthMask(true);
        shader.unbind();
        return count;
    }

    private int gather(World world, Vector3fc camera) {
        int minCx = Math.floorDiv((int) Math.floor(camera.x()) - RADIUS, Chunk.SIZE_X);
        int maxCx = Math.floorDiv((int) Math.floor(camera.x()) + RADIUS, Chunk.SIZE_X);
        int minCz = Math.floorDiv((int) Math.floor(camera.z()) - RADIUS, Chunk.SIZE_Z);
        int maxCz = Math.floorDiv((int) Math.floor(camera.z()) + RADIUS, Chunk.SIZE_Z);
        int count = 0;
        for (int cx = minCx; cx <= maxCx; cx++) for (int cz = minCz; cz <= maxCz; cz++) {
            Chunk chunk = world.getChunkIfExists(cx, cz);
            if (chunk == null) continue;
            for (Map.Entry<Integer, Byte> entry : chunk.trailVisuals().entrySet()) {
                if (count >= MAX_VISIBLE) return count;
                int index = entry.getKey();
                int lx = index % Chunk.SIZE_X;
                int y = index / (Chunk.SIZE_X * Chunk.SIZE_Z);
                int lz = index / Chunk.SIZE_X % Chunk.SIZE_Z;
                int x = cx * Chunk.SIZE_X + lx, z = cz * Chunk.SIZE_Z + lz;
                float dx = x + 0.5f - camera.x(), dz = z + 0.5f - camera.z();
                if (dx * dx + dz * dz > RADIUS * RADIUS || Math.abs(y + 1f - camera.y()) > RADIUS)
                    continue;
                BlockType ground = chunk.get(lx, y, lz);
                if (ground != BlockType.GRASS && ground != BlockType.SNOWY_GRASS) continue;
                BlockType above = world.getBlock(x, y + 1, z);
                if (above == BlockType.SNOW_LAYER || above == BlockType.WATER || above == BlockType.WATER_FLOW
                        || (above.solid && !above.isCross())) continue;
                instances.put(x + 0.5f).put(y + 1f + LIFT).put(z + 0.5f).put(entry.getValue());
                instances.put(stageAt(world, x - 1, y, z)).put(stageAt(world, x + 1, y, z))
                         .put(stageAt(world, x, y, z - 1)).put(stageAt(world, x, y, z + 1));
                instances.put(world.getSkyLight(x, y + 1, z) / (float) Chunk.MAX_LIGHT)
                         .put(world.getBlockLightWorld(x, y + 1, z) / (float) Chunk.MAX_LIGHT);
                count++;
            }
        }
        return count;
    }

    private static float stageAt(World world, int x, int y, int z) {
        BlockType block = world.getBlock(x, y, z);
        return block == BlockType.GRASS || block == BlockType.SNOWY_GRASS
                ? Math.max(0, Math.min(5, world.getBlockMeta(x, y, z))) : 0;
    }

    public void destroy() {
        glDeleteBuffers(instanceBuffer);
        glDeleteBuffers(quadBuffer);
        glDeleteVertexArrays(vao);
        shader.destroy();
    }
}

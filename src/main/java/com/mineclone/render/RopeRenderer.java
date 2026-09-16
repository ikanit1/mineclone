package com.mineclone.render;

import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.RopeSimulation;
import com.mineclone.world.World;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/** Verlet-отрисовка вертикальных связок ROPE/CHAIN возле игрока. */
public final class RopeRenderer {
    private static final int RADIUS = 13, MAX_LENGTH = 12, MAX_VERTS = 24 * (MAX_LENGTH * 4 + 2);
    private static final float DISCOVERY_INTERVAL = 0.25f;
    private static final Vector4f CHAIN_COLOR = new Vector4f(0.55f, 0.62f, 0.70f, 0.92f);
    private static final Vector4f ROPE_COLOR = new Vector4f(0.48f, 0.30f, 0.13f, 0.94f);
    private static final class Strand {
        final RopeSimulation sim;
        final int length;
        final BlockType type;
        Strand(RopeSimulation sim, int length, BlockType type) { this.sim = sim; this.length = length; this.type = type; }
    }

    private final Map<Long, Strand> strands = new HashMap<>();
    private final Set<Long> seen = new HashSet<>();
    private final int vao = glGenVertexArrays();
    private final int vbo = glGenBuffers();
    private final Shader shader = new Shader(Shaders.LINE_VERTEX, Shaders.LINE_FRAGMENT);
    private final FloatBuffer upload = MemoryUtil.memAllocFloat(MAX_VERTS * 3);
    private final Matrix4f identity = new Matrix4f();
    private float discoveryTimer;

    public RopeRenderer() {
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) MAX_VERTS * 3 * Float.BYTES, GL_DYNAMIC_DRAW);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0);
        glEnableVertexAttribArray(0);
        glBindVertexArray(0);
    }

    public void render(World world, Vector3f player, Matrix4f projection, Matrix4f view,
                       float dt, float windX, float windZ, float linearOut) {
        if (world == null) return;
        discoveryTimer -= dt;
        if (discoveryTimer <= 0f) {
            discoveryTimer = DISCOVERY_INTERVAL;
            discover(world, player);
        }
        int rendered = 0;
        shader.bind();
        shader.setMat4("uProjection", projection);
        shader.setMat4("uView", view);
        shader.setMat4("uModel", identity);
        shader.setFloat("uLinearOut", linearOut);
        shader.setFloat("uEmissive", 0.08f);
        glBindVertexArray(vao);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        for (Strand strand : strands.values()) {
            if (rendered >= 24) break;
            RopeSimulation.Node[] nodes = strand.sim.nodes();
            for (int i = 1; i < nodes.length; i++) {
                Vector3f n = nodes[i].position;
                float dx = n.x - player.x, dz = n.z - player.z;
                float ds = dx * dx + dz * dz;
                if (ds < 1.2f * 1.2f && Math.abs(n.y - (player.y + 0.8f)) < 1.4f) {
                    float inv = 1f / Math.max(0.1f, (float) Math.sqrt(ds));
                    strand.sim.impulse(i, dx * inv * 0.018f, 0f, dz * inv * 0.018f);
                }
            }
            strand.sim.step(dt, windX * 0.18f, windZ * 0.18f);
            upload.clear();
            for (int i = 0; i < nodes.length - 1; i++) {
                Vector3f a = nodes[i].position, b = nodes[i + 1].position;
                upload.put(a.x).put(a.y).put(a.z).put(b.x).put(b.y).put(b.z);
            }
            upload.flip();
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferSubData(GL_ARRAY_BUFFER, 0, upload);
            shader.setVec4("uColor", strand.type == BlockType.CHAIN ? CHAIN_COLOR : ROPE_COLOR);
            glLineWidth(strand.type == BlockType.CHAIN ? 2.4f : 3f);
            glDrawArrays(GL_LINES, 0, (nodes.length - 1) * 2);
            rendered++;
        }
        glDisable(GL_BLEND);
        glBindVertexArray(0);
        shader.unbind();
    }

    /** World discovery is much more expensive than advancing known Verlet strands. */
    private void discover(World world, Vector3f player) {
        seen.clear();
        int px = (int) Math.floor(player.x), py = (int) Math.floor(player.y), pz = (int) Math.floor(player.z);
        int found = 0;
        for (int x = px - RADIUS; x <= px + RADIUS && found < 24; x++)
            for (int z = pz - RADIUS; z <= pz + RADIUS && found < 24; z++)
                for (int y = Math.max(1, py - 8); y <= Math.min(Chunk.SIZE_Y - 2, py + 10) && found < 24; y++) {
                    BlockType type = world.getBlock(x, y, z);
                    if (!rope(type) || world.getBlock(x, y + 1, z) == type) continue;
                    int length = 1;
                    while (length < MAX_LENGTH && world.getBlock(x, y - length, z) == type) length++;
                    long key = key(x, y, z);
                    seen.add(key);
                    Strand strand = strands.get(key);
                    if (strand == null || strand.length != length || strand.type != type) {
                        Vector3f top = new Vector3f(x + 0.5f, y + 1f, z + 0.5f);
                        Vector3f bottom = new Vector3f(x + 0.5f, y + 1f - length, z + 0.5f);
                        boolean pinned = world.getBlock(x, y - length, z).solid;
                        strands.put(key, new Strand(new RopeSimulation(top, bottom,
                                Math.max(2, length * 3), pinned), length, type));
                    }
                    found++;
                }
        strands.keySet().removeIf(k -> !seen.contains(k));
    }

    private static boolean rope(BlockType b) { return b == BlockType.ROPE || b == BlockType.CHAIN; }
    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 34) ^ ((long) (z & 0x3FFFFFF) << 8) ^ (y & 255L);
    }

    public void clear() { strands.clear(); discoveryTimer = 0f; }
    public void destroy() {
        MemoryUtil.memFree(upload);
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        shader.destroy();
    }
}

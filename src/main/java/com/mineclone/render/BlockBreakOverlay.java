package com.mineclone.render;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

public class BlockBreakOverlay {
    // 6 faces × 2 triangles × 3 verts = 36 verts; 5 floats each (x,y,z,u,v)
    private static final int VERTEX_COUNT = 36;
    private static final int STRIDE = 5 * Float.BYTES; // 20 bytes

    private final int vao, vbo;
    private final Shader shader;

    public BlockBreakOverlay() {
        shader = new Shader(Shaders.CRACK_VERTEX, Shaders.CRACK_FRAGMENT);
        vao = glGenVertexArrays();
        vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) VERTEX_COUNT * STRIDE, GL_DYNAMIC_DRAW);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, STRIDE, 0);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, STRIDE, 3 * Float.BYTES);
        glEnableVertexAttribArray(1);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    /** Render crack overlay for block at (bx,by,bz), stage 0..9. */
    public void render(Matrix4f proj, Matrix4f view, int bx, int by, int bz,
                       int stage, TextureAtlas atlas, float linearOut) {
        float[] uv = TextureAtlas.uv(TextureAtlas.CRACK_TILE_0 + stage);
        float u0 = uv[0], v0 = uv[1], u1 = uv[2], v1 = uv[3];
        float x = bx, y = by, z = bz;

        float[] verts = {
            // Top (+Y)
            x,   y+1, z,   u0,v0,   x+1, y+1, z,   u1,v0,   x+1, y+1, z+1, u1,v1,
            x,   y+1, z,   u0,v0,   x+1, y+1, z+1, u1,v1,   x,   y+1, z+1, u0,v1,
            // Bottom (-Y)
            x,   y, z+1, u0,v0,   x+1, y, z+1, u1,v0,   x+1, y, z,   u1,v1,
            x,   y, z+1, u0,v0,   x+1, y, z,   u1,v1,   x,   y, z,   u0,v1,
            // North (-Z)
            x+1, y,   z, u0,v0,   x,   y,   z, u1,v0,   x,   y+1, z, u1,v1,
            x+1, y,   z, u0,v0,   x,   y+1, z, u1,v1,   x+1, y+1, z, u0,v1,
            // South (+Z)
            x,   y,   z+1, u0,v0,   x+1, y,   z+1, u1,v0,   x+1, y+1, z+1, u1,v1,
            x,   y,   z+1, u0,v0,   x+1, y+1, z+1, u1,v1,   x,   y+1, z+1, u0,v1,
            // East (+X)
            x+1, y,   z,   u0,v0,   x+1, y,   z+1, u1,v0,   x+1, y+1, z+1, u1,v1,
            x+1, y,   z,   u0,v0,   x+1, y+1, z+1, u1,v1,   x+1, y+1, z,   u0,v1,
            // West (-X)
            x,   y,   z+1, u1,v0,   x,   y,   z,   u0,v0,   x,   y+1, z,   u0,v1,
            x,   y,   z+1, u1,v0,   x,   y+1, z,   u0,v1,   x,   y+1, z+1, u1,v1,
        };

        FloatBuffer fb = MemoryUtil.memAllocFloat(verts.length);
        try {
            fb.put(verts).flip();
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferSubData(GL_ARRAY_BUFFER, 0, fb);
            glBindBuffer(GL_ARRAY_BUFFER, 0);
        } finally {
            MemoryUtil.memFree(fb);
        }

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(-1f, -1f);
        glDisable(GL_CULL_FACE);

        shader.bind();
        shader.setMat4("uProjection", proj);
        shader.setMat4("uView", view);
        shader.setInt("uAtlas", 0);
        shader.setFloat("uLinearOut", linearOut);
        atlas.bind(0);
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, VERTEX_COUNT);
        glBindVertexArray(0);
        shader.unbind();

        glEnable(GL_CULL_FACE);
        glPolygonOffset(0f, 0f);
        glDisable(GL_POLYGON_OFFSET_FILL);
        glDisable(GL_BLEND);
    }

    public void destroy() {
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        shader.destroy();
    }
}

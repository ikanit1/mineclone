package com.mineclone.render;

import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

public class Mesh {
    private final int vao, vboPos, vboUv, vboLight, ebo;
    private final int indexCount;

    public Mesh(float[] positions, float[] uvs, float[] light, int[] indices) {
        indexCount = indices.length;
        vao = glGenVertexArrays();
        glBindVertexArray(vao);

        vboPos = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vboPos);
        FloatBuffer pb = MemoryUtil.memAllocFloat(positions.length);
        pb.put(positions).flip();
        glBufferData(GL_ARRAY_BUFFER, pb, GL_STATIC_DRAW);
        MemoryUtil.memFree(pb);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0);
        glEnableVertexAttribArray(0);

        vboUv = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vboUv);
        FloatBuffer ub = MemoryUtil.memAllocFloat(uvs.length);
        ub.put(uvs).flip();
        glBufferData(GL_ARRAY_BUFFER, ub, GL_STATIC_DRAW);
        MemoryUtil.memFree(ub);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 0, 0);
        glEnableVertexAttribArray(1);

        vboLight = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vboLight);
        FloatBuffer lb = MemoryUtil.memAllocFloat(light.length);
        lb.put(light).flip();
        glBufferData(GL_ARRAY_BUFFER, lb, GL_STATIC_DRAW);
        MemoryUtil.memFree(lb);
        glVertexAttribPointer(2, 1, GL_FLOAT, false, 0, 0);
        glEnableVertexAttribArray(2);

        ebo = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        IntBuffer ib = MemoryUtil.memAllocInt(indices.length);
        ib.put(indices).flip();
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, ib, GL_STATIC_DRAW);
        MemoryUtil.memFree(ib);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    public void render() {
        glBindVertexArray(vao);
        glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0);
        glBindVertexArray(0);
    }

    public void destroy() {
        glDisableVertexAttribArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glDeleteBuffers(vboPos);
        glDeleteBuffers(vboUv);
        glDeleteBuffers(vboLight);
        glDeleteBuffers(ebo);
        glBindVertexArray(0);
        glDeleteVertexArrays(vao);
    }

    public int getIndexCount() { return indexCount; }
}

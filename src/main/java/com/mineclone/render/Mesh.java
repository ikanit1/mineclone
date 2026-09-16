package com.mineclone.render;

import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

public class Mesh {
    private final int vao, vboPos, vboUv, vboLight, vboBlockLight, ebo, vboRepeat;
    private final int indexCount;

    public Mesh(float[] positions, float[] uvs, float[] light, float[] blockLight, int[] indices) {
        this(positions, uvs, light, blockLight, indices, null);
    }

    public Mesh(float[] positions, float[] uvs, float[] light, float[] blockLight, int[] indices, float[] repeat) {
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

        vboBlockLight = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vboBlockLight);
        FloatBuffer blb = MemoryUtil.memAllocFloat(blockLight.length);
        blb.put(blockLight).flip();
        glBufferData(GL_ARRAY_BUFFER, blb, GL_STATIC_DRAW);
        MemoryUtil.memFree(blb);
        glVertexAttribPointer(3, 1, GL_FLOAT, false, 0, 0);
        glEnableVertexAttribArray(3);

        vboRepeat = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vboRepeat);
        glBufferData(GL_ARRAY_BUFFER, repeat == null ? new float[positions.length] : repeat, GL_STATIC_DRAW);
        glVertexAttribPointer(4, 3, GL_FLOAT, false, 0, 0);
        glEnableVertexAttribArray(4);

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
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glDeleteBuffers(vboPos);
        glDeleteBuffers(vboUv);
        glDeleteBuffers(vboLight);
        glDeleteBuffers(vboBlockLight);
        glDeleteBuffers(ebo);
        glDeleteBuffers(vboRepeat);
        glBindVertexArray(0);
        glDeleteVertexArrays(vao);
    }

    public int getIndexCount() { return indexCount; }
}

package com.mineclone.render;

import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Чанк на видеокарте: одна чересстрочная вершинная буферная область и индексы.
 *
 * <p>Раньше атрибутов было пять и каждый жил в своём буфере: семь объектов GL
 * на чанк, шесть {@code glBufferData} и шесть временных нативных буферов. Плюс
 * атрибут {@code repeat} выделял массив нулей размером с позиции, даже когда
 * повторов не было, — по мегабайту мусора и мегабайту видеопамяти на чанк.
 * Загрузка меша идёт в главном потоке, и каждый такой вызов — поход в
 * менеджер памяти драйвера; на стриминге это и складывалось в рывки.
 *
 * <p>Теперь на вершину десять {@code float} подряд (позиция, UV, свет,
 * блочный свет, повтор) в одном буфере: два объекта GL вместо семи, один
 * подъём данных вместо шести и вдвое меньше потоков вершинной выборки на
 * стороне видеокарты.
 */
public class Mesh {
    /** float на вершину: 3 позиции + 2 UV + 1 свет + 1 блочный свет + 3 повтора. */
    private static final int STRIDE_FLOATS = 10;
    private static final int STRIDE_BYTES = STRIDE_FLOATS * Float.BYTES;

    private final int vao, vbo, ebo;
    private final int indexCount;

    public Mesh(float[] positions, float[] uvs, float[] light, float[] blockLight, int[] indices) {
        this(positions, uvs, light, blockLight, indices, null);
    }

    public Mesh(float[] positions, float[] uvs, float[] light, float[] blockLight, int[] indices, float[] repeat) {
        indexCount = indices.length;
        int vertices = positions.length / 3;
        vao = glGenVertexArrays();
        glBindVertexArray(vao);

        vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        FloatBuffer data = MemoryUtil.memAllocFloat(vertices * STRIDE_FLOATS);
        for (int v = 0; v < vertices; v++) {
            int p = v * 3, t = v * 2;
            data.put(positions[p]).put(positions[p + 1]).put(positions[p + 2]);
            data.put(uvs[t]).put(uvs[t + 1]);
            data.put(light[v]);
            data.put(blockLight[v]);
            if (repeat == null)
                data.put(0f).put(0f).put(0f);
            else
                data.put(repeat[p]).put(repeat[p + 1]).put(repeat[p + 2]);
        }
        data.flip();
        glBufferData(GL_ARRAY_BUFFER, data, GL_STATIC_DRAW);
        MemoryUtil.memFree(data);

        glVertexAttribPointer(0, 3, GL_FLOAT, false, STRIDE_BYTES, 0L);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, STRIDE_BYTES, 3L * Float.BYTES);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(2, 1, GL_FLOAT, false, STRIDE_BYTES, 5L * Float.BYTES);
        glEnableVertexAttribArray(2);
        glVertexAttribPointer(3, 1, GL_FLOAT, false, STRIDE_BYTES, 6L * Float.BYTES);
        glEnableVertexAttribArray(3);
        glVertexAttribPointer(4, 3, GL_FLOAT, false, STRIDE_BYTES, 7L * Float.BYTES);
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
        glDeleteBuffers(vbo);
        glDeleteBuffers(ebo);
        glBindVertexArray(0);
        glDeleteVertexArrays(vao);
    }

    public int getIndexCount() { return indexCount; }
}

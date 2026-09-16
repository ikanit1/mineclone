package com.mineclone.render;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import org.lwjgl.opengl.GL;
import static org.lwjgl.opengl.GL44.*;

/** Triple-buffered persistent writes; busy slots fall back to orphaning without waiting. */
public final class StreamingBuffer {
    private final int persistent, fallback = glGenBuffers(), bytes;
    private final ByteBuffer mapped;
    private final long[] fences = new long[3];
    private int cursor, active = -1;

    public StreamingBuffer(int capacityBytes) {
        bytes = capacityBytes;
        if (!Boolean.getBoolean("mineclone.noPersistent")
                && (GL.getCapabilities().OpenGL44 || GL.getCapabilities().GL_ARB_buffer_storage)) {
            persistent = glGenBuffers();
            glBindBuffer(GL_ARRAY_BUFFER, persistent);
            int flags = GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT;
            glBufferStorage(GL_ARRAY_BUFFER, (long) bytes * 3, flags);
            mapped = glMapBufferRange(GL_ARRAY_BUFFER, 0, (long) bytes * 3, flags);
            if (mapped == null) throw new IllegalStateException("Persistent buffer mapping failed");
        } else {
            persistent = 0;
            mapped = null;
        }
    }

    /** Binds the buffer for attribute setup and returns the byte offset of this frame. */
    public long upload(FloatBuffer data) {
        if (data.remaining() * 4 > bytes) throw new IllegalArgumentException("Streaming capacity exceeded");
        active = -1;
        int slot = cursor++ % 3;
        if (mapped != null) {
            int status = fences[slot] == 0 ? GL_ALREADY_SIGNALED : glClientWaitSync(fences[slot], 0, 0);
            if (status == GL_ALREADY_SIGNALED || status == GL_CONDITION_SATISFIED) {
                if (fences[slot] != 0) glDeleteSync(fences[slot]);
                fences[slot] = 0;
                active = slot;
                int offset = bytes * slot;
                mapped.position(offset);
                mapped.slice().order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().put(data);
                glBindBuffer(GL_ARRAY_BUFFER, persistent);
                return offset;
            }
        }
        glBindBuffer(GL_ARRAY_BUFFER, fallback);
        glBufferData(GL_ARRAY_BUFFER, bytes, GL_STREAM_DRAW);
        glBufferSubData(GL_ARRAY_BUFFER, 0, data);
        return 0;
    }

    public void submitted() {
        if (active >= 0) fences[active] = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        active = -1;
    }

    public void destroy() {
        for (long fence : fences) if (fence != 0) glDeleteSync(fence);
        if (persistent != 0) {
            glBindBuffer(GL_ARRAY_BUFFER, persistent);
            glUnmapBuffer(GL_ARRAY_BUFFER);
            glDeleteBuffers(persistent);
        }
        glDeleteBuffers(fallback);
    }
}

package com.mineclone.render;

import java.nio.FloatBuffer;
import org.lwjgl.system.MemoryStack;
import static org.lwjgl.opengl.GL43.*;

/** GPU-resident position/velocity state. CPU owns slot lifetime, never reads positions back. */
final class GpuParticlePhysics {
    private final int buffer, program;
    private static final int STRIDE = 48;
    GpuParticlePhysics(int capacity) {
        int shader = glCreateShader(GL_COMPUTE_SHADER);
        glShaderSource(shader, """
            #version 430 core
            layout(local_size_x=64) in;
            struct Particle { vec4 positionSize; vec4 velocityGravity; vec4 params; };
            layout(std430, binding=0) buffer State { Particle p[]; };
            uniform int uCount;
            uniform float uDt;
            uniform vec2 uWind;
            void main() {
                uint i = gl_GlobalInvocationID.x;
                if (i >= uint(uCount)) return;
                if (p[i].positionSize.y <= p[i].params.w) { p[i].positionSize.w = 0; return; }
                vec3 v = p[i].velocityGravity.xyz;
                v.y -= 14.0 * p[i].velocityGravity.w * uDt;
                if (p[i].params.z > 0) v.xz += (uWind * p[i].params.z - v.xz) * min(1.0, uDt * 1.5);
                p[i].positionSize.xyz += v * uDt;
                p[i].positionSize.w = max(0.01, p[i].positionSize.w + p[i].params.y * uDt);
                v.xz *= max(0.0, 1.0 - 2.0 * uDt);
                p[i].velocityGravity.xyz = v;
                if (p[i].positionSize.y <= p[i].params.w) p[i].positionSize.w = 0;
            }
            """);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == 0) throw new IllegalStateException(glGetShaderInfoLog(shader));
        program = glCreateProgram();
        glAttachShader(program, shader); glLinkProgram(program); glDeleteShader(shader);
        if (glGetProgrami(program, GL_LINK_STATUS) == 0) throw new IllegalStateException(glGetProgramInfoLog(program));
        buffer = glGenBuffers();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer);
        glBufferData(GL_SHADER_STORAGE_BUFFER, (long)capacity * STRIDE, GL_DYNAMIC_DRAW);
    }
    void spawn(int i, float x, float y, float z, float size, float vx, float vy, float vz,
               float gravity, float growth, float wind, float floor) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer values = stack.floats(x,y,z,size,vx,vy,vz,gravity,0,growth,wind,floor);
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer);
            glBufferSubData(GL_SHADER_STORAGE_BUFFER, (long)i * STRIDE, values);
        }
    }
    void move(int from, int to) {
        if (from == to) return;
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
        glBindBuffer(GL_COPY_READ_BUFFER, buffer); glBindBuffer(GL_COPY_WRITE_BUFFER, buffer);
        glCopyBufferSubData(GL_COPY_READ_BUFFER, GL_COPY_WRITE_BUFFER, (long)from * STRIDE, (long)to * STRIDE, STRIDE);
    }
    void update(int count, float dt, float windX, float windZ) {
        if (count == 0) return;
        int previous = glGetInteger(GL_CURRENT_PROGRAM);
        glUseProgram(program);
        glUniform1i(glGetUniformLocation(program, "uCount"), count);
        glUniform1f(glGetUniformLocation(program, "uDt"), dt);
        glUniform2f(glGetUniformLocation(program, "uWind"), windX, windZ);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, buffer);
        glDispatchCompute((count + 63) / 64, 1, 1);
        glMemoryBarrier(GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT | GL_BUFFER_UPDATE_BARRIER_BIT);
        glUseProgram(previous);
    }
    void bindPositions() {
        glBindBuffer(GL_ARRAY_BUFFER, buffer);
        glVertexAttribPointer(1, 4, GL_FLOAT, false, STRIDE, 0);
    }
    void destroy() { glDeleteBuffers(buffer); glDeleteProgram(program); }
}

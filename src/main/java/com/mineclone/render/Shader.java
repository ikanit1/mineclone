package com.mineclone.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.glBindFragDataLocation;

public class Shader {
    private final int program;
    private final Map<String, Integer> uniforms = new HashMap<>();

    public Shader(String vertexSrc, String fragmentSrc) {
        int vs = compile(GL_VERTEX_SHADER, vertexSrc);
        int fs = compile(GL_FRAGMENT_SHADER, fragmentSrc);
        program = glCreateProgram();
        glAttachShader(program, vs);
        glAttachShader(program, fs);
        glLinkProgram(program);
        if (glGetProgrami(program, GL_LINK_STATUS) == 0) {
            throw new RuntimeException("Shader link error: " + glGetProgramInfoLog(program));
        }
        glDetachShader(program, vs);
        glDetachShader(program, fs);
        glDeleteShader(vs);
        glDeleteShader(fs);
    }

    private int compile(int type, String src) {
        int s = glCreateShader(type);
        glShaderSource(s, src);
        glCompileShader(s);
        if (glGetShaderi(s, GL_COMPILE_STATUS) == 0) {
            throw new RuntimeException("Shader compile error: " + glGetShaderInfoLog(s));
        }
        return s;
    }

    public void bind()   { glUseProgram(program); }
    public void unbind() { glUseProgram(0); }
    public void destroy(){ glDeleteProgram(program); }

    private int loc(String name) {
        return uniforms.computeIfAbsent(name, n -> glGetUniformLocation(program, n));
    }

    public void setInt(String name, int v) { glUniform1i(loc(name), v); }
    public void setFloat(String name, float v) { glUniform1f(loc(name), v); }
    public void setVec2(String name, float x, float y) { glUniform2f(loc(name), x, y); }
    public void setVec4(String name, float[] rgba) { glUniform4f(loc(name), rgba[0], rgba[1], rgba[2], rgba[3]); }
    public void setVec3(String name, Vector3f v) { glUniform3f(loc(name), v.x, v.y, v.z); }
    public void setVec4(String name, Vector4f v) { glUniform4f(loc(name), v.x, v.y, v.z, v.w); }
    public void setVec4(String name, float x, float y, float z, float w) { glUniform4f(loc(name), x, y, z, w); }
    public void setMat4(String name, Matrix4f m) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer fb = stack.mallocFloat(16);
            m.get(fb);
            glUniformMatrix4fv(loc(name), false, fb);
        }
    }
}

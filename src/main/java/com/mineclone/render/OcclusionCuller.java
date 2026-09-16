package com.mineclone.render;

import org.joml.Matrix4f;
import static org.lwjgl.opengl.GL33.*;

/** Current-frame conservative box queries; NO_WAIT never stalls the CPU or uses stale visibility. */
public final class OcclusionCuller {
    private final Shader shader = new Shader("""
        #version 330 core
        layout(location=0) in vec3 p;
        uniform mat4 uMvp;
        void main() { gl_Position = uMvp * vec4(p, 1); }
        """, """
        #version 330 core
        out vec4 color;
        void main() { color = vec4(0); }
        """);
    private final int vao = glGenVertexArrays(), vbo = glGenBuffers(), ebo = glGenBuffers();
    private final java.util.ArrayList<Integer> queries = new java.util.ArrayList<>();
    private final Matrix4f vp = new Matrix4f(), mvp = new Matrix4f();
    private int cursor;
    public OcclusionCuller() {
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, new float[]{0,0,0, 1,0,0, 1,1,0, 0,1,0, 0,0,1, 1,0,1, 1,1,1, 0,1,1}, GL_STATIC_DRAW);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0); glEnableVertexAttribArray(0);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, new int[]{0,1,2,0,2,3,4,7,6,4,6,5,0,4,5,0,5,1,3,2,6,3,6,7,0,3,7,0,7,4,1,5,6,1,6,2}, GL_STATIC_DRAW);
        glBindVertexArray(0);
    }
    public void begin(Matrix4f projection, Matrix4f view) { vp.set(projection).mul(view); cursor = 0; }
    public void test(float x, float y, float z, float width, float height, float depth) {
        if (cursor == queries.size()) queries.add(glGenQueries());
        int query = queries.get(cursor++);
        shader.bind();
        shader.setMat4("uMvp", mvp.set(vp).translate(x, y, z).scale(width, height, depth));
        glColorMask(false, false, false, false); glDepthMask(false); glDisable(GL_CULL_FACE);
        glBindVertexArray(vao);
        glBeginQuery(GL_ANY_SAMPLES_PASSED, query);
        glDrawElements(GL_TRIANGLES, 36, GL_UNSIGNED_INT, 0L);
        glEndQuery(GL_ANY_SAMPLES_PASSED);
        glColorMask(true, true, true, true); glDepthMask(true); glEnable(GL_CULL_FACE);
        glBeginConditionalRender(query, GL_QUERY_NO_WAIT);
    }
    public void endTest() { glEndConditionalRender(); }
    public void destroy() {
        for (int q : queries) glDeleteQueries(q);
        glDeleteVertexArrays(vao); glDeleteBuffers(vbo); glDeleteBuffers(ebo); shader.destroy();
    }
}

package com.mineclone.render;

import org.joml.Matrix4f;

import static org.lwjgl.opengl.GL33.*;

/**
 * Аппаратная окклюзия по результатам ПРОШЛОГО кадра.
 *
 * <p>Раньше здесь стоял условный рендер по запросу, выпущенному строкой выше:
 * с {@code GL_QUERY_NO_WAIT} ответ к этому моменту заведомо не готов, драйвер
 * честно рисует всё, и от всей затеи оставалась только цена. А цена была
 * заметная: на каждый чанк — своя привязка шейдера, четыре смены масок, смена
 * отсечения, запрос, отрисовка коробки и пара условного рендера. На
 * ста семидесяти чанках это больше двух тысяч лишних вызовов GL за кадр.
 *
 * <p>Теперь порядок обратный и без единой синхронизации: чанки рисуются по
 * видимости, снятой в прошлом кадре, а коробки ставятся в очередь и
 * выпускаются одной пачкой в конце — одна настройка состояния на всю пачку.
 * Результаты забираются в начале следующего кадра и только если они уже
 * готовы ({@code GL_QUERY_RESULT_AVAILABLE}), поэтому CPU не ждёт GPU никогда.
 *
 * <p>Чанк, признанный невидимым, продолжает проверяться каждый кадр — коробка
 * стоит двенадцать треугольников, а без проверки он не вернулся бы на экран.
 * Ближние к камере чанки не проверяются вовсе: их коробка пересекает ближнюю
 * плоскость, и ответ запроса там бессмыслен.
 */
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

    /** Запросы и ключи чанков, которым они принадлежат, в порядке выпуска. */
    private int[] queries = new int[256];
    private long[] owners = new long[256];
    private int issued;
    /** Сколько запросов реально выпущено в прошлом кадре — столько и читаем. */
    private int pending;

    /** Ключи чанков, признанных закрытыми. Всё, чего здесь нет, видимо. */
    private final java.util.HashSet<Long> hidden = new java.util.HashSet<>();

    /** Коробки, накопленные за кадр: x, y, z, w, h, d по шесть float на штуку. */
    private float[] boxes = new float[6 * 256];
    private long[] boxOwners = new long[256];
    private int boxCount;

    private final Matrix4f vp = new Matrix4f(), mvp = new Matrix4f();
    private boolean enabled = true;

    public OcclusionCuller() {
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, new float[]{0,0,0, 1,0,0, 1,1,0, 0,1,0, 0,0,1, 1,0,1, 1,1,1, 0,1,1}, GL_STATIC_DRAW);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0); glEnableVertexAttribArray(0);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, new int[]{0,1,2,0,2,3,4,7,6,4,6,5,0,4,5,0,5,1,3,2,6,3,6,7,0,3,7,0,7,4,1,5,6,1,6,2}, GL_STATIC_DRAW);
        glBindVertexArray(0);
    }

    /** Выключенная окклюзия не выпускает запросов и всё считает видимым. */
    public void setEnabled(boolean on) {
        if (enabled == on)
            return;
        enabled = on;
        if (!on) {
            hidden.clear();
            pending = 0;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Начало кадра: забирает готовые ответы прошлого кадра и чистит очередь.
     * Неготовый ответ просто пропускается — чанк остаётся с прежней оценкой.
     */
    public void begin(Matrix4f projection, Matrix4f view) {
        vp.set(projection).mul(view);
        for (int i = 0; i < pending; i++) {
            int q = queries[i];
            if (glGetQueryObjecti(q, GL_QUERY_RESULT_AVAILABLE) == GL_FALSE)
                continue;
            if (glGetQueryObjecti(q, GL_QUERY_RESULT) == GL_FALSE)
                hidden.add(owners[i]);
            else
                hidden.remove(owners[i]);
        }
        pending = 0;
        issued = 0;
        boxCount = 0;
    }

    /** Был ли чанк закрыт в прошлом кадре. Незнакомый чанк считается видимым. */
    public boolean hidden(long key) {
        return enabled && hidden.contains(key);
    }

    /** Ставит коробку чанка в очередь проверки; сама проверка — в {@link #flush}. */
    public void enqueue(long key, float x, float y, float z, float width, float height, float depth) {
        if (!enabled)
            return;
        if (boxCount == boxOwners.length) {
            boxOwners = java.util.Arrays.copyOf(boxOwners, boxCount * 2);
            boxes = java.util.Arrays.copyOf(boxes, boxes.length * 2);
        }
        boxOwners[boxCount] = key;
        int at = boxCount * 6;
        boxes[at] = x; boxes[at + 1] = y; boxes[at + 2] = z;
        boxes[at + 3] = width; boxes[at + 4] = height; boxes[at + 5] = depth;
        boxCount++;
    }

    /**
     * Выпускает все накопленные запросы одной пачкой. Зовётся ПОСЛЕ отрисовки
     * непрозрачной геометрии: коробка проверяется по уже заполненному
     * z-буферу, иначе закрытым оказалось бы всё подряд.
     */
    public void flush() {
        if (!enabled || boxCount == 0)
            return;
        shader.bind();
        glColorMask(false, false, false, false);
        glDepthMask(false);
        glDisable(GL_CULL_FACE);
        glBindVertexArray(vao);
        for (int i = 0; i < boxCount; i++) {
            if (issued == queries.length) {
                queries = java.util.Arrays.copyOf(queries, issued * 2);
                owners = java.util.Arrays.copyOf(owners, issued * 2);
            }
            if (queries[issued] == 0)
                queries[issued] = glGenQueries();
            int at = i * 6;
            shader.setMat4("uMvp", mvp.set(vp)
                    .translate(boxes[at], boxes[at + 1], boxes[at + 2])
                    .scale(boxes[at + 3], boxes[at + 4], boxes[at + 5]));
            owners[issued] = boxOwners[i];
            glBeginQuery(GL_ANY_SAMPLES_PASSED, queries[issued]);
            glDrawElements(GL_TRIANGLES, 36, GL_UNSIGNED_INT, 0L);
            glEndQuery(GL_ANY_SAMPLES_PASSED);
            issued++;
        }
        glBindVertexArray(0);
        glColorMask(true, true, true, true);
        glDepthMask(true);
        glEnable(GL_CULL_FACE);
        shader.unbind();
        pending = issued;
        boxCount = 0;
    }

    /** Чанк выгружен — забываем его оценку, иначе множество растёт вечно. */
    public void forget(long key) {
        hidden.remove(key);
    }

    public void destroy() {
        for (int q : queries) if (q != 0) glDeleteQueries(q);
        glDeleteVertexArrays(vao); glDeleteBuffers(vbo); glDeleteBuffers(ebo); shader.destroy();
    }
}

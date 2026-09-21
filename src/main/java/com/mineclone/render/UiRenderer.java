package com.mineclone.render;

import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Пакетный рисовальщик интерфейса в экранных пикселях (начало — левый верх).
 *
 * <p>Раньше каждый квадрат был отдельным draw call'ом. На хотбаре это незаметно,
 * но окно инвентаря — это сотни подложек, рамок, иконок и цифр, и полтысячи
 * вызовов драйвера на кадр стоят дороже, чем всё, что они рисуют.
 *
 * <p>Здесь вид квадрата (заливка, атлас, стекло, шрифт, чужая текстура)
 * приезжает атрибутом вершины, а не юниформой, поэтому смена вида пакет не
 * разрывает: весь слой интерфейса уходит одним вызовом. Разрывает пакет только
 * смена «чужой» текстуры — её номер в шейдер не передать.
 */
public class UiRenderer {

    // Режимы фрагментного шейдера; совпадают с UI_BATCH_FRAGMENT.
    static final float MODE_FILL = 0f;
    static final float MODE_ATLAS = 1f;
    static final float MODE_GLASS = 2f;
    static final float MODE_FONT_A = 3f;
    static final float MODE_FONT_B = 4f;
    static final float MODE_TEXTURE = 5f;

    /** x, y, u, v, r, g, b, a, режим. */
    private static final int FLOATS_PER_VERTEX = 9;
    private static final int VERTICES_PER_QUAD = 4;
    private static final int INDICES_PER_QUAD = 6;
    private static final int INITIAL_QUADS = 8192;

    private final Shader shader;
    private final int vao, vbo, ibo;

    private FloatBuffer verts;
    private int quadCapacity = INITIAL_QUADS;
    private int quadCount;

    private int screenW, screenH;
    private boolean open;

    /** Атлас блоков — самая частая текстура интерфейса, ей выделен свой блок. */
    private int atlasTex;
    /** Размытый кадр под стеклом; 0 — стекла нет, панели остаются плоскими. */
    private int backdropTex;
    private int fontATex, fontBTex;
    private Font fontA, fontB;
    /** Текстура, которой сейчас рисуются квадраты режима 5; 0 — никакая. */
    private int otherTex;
    /**
     * Есть ли в пакете хоть один квадрат чужой текстурой.
     *
     * <p>Без этого флага номер задерживался бы в блоке 4 и после того, как
     * текстуру удалили — превью миров живут ровно до закрытия экрана, — а
     * рисование с удалённой текстурой драйвер считает ошибкой.
     */
    private boolean usesOther;

    // Действующее преобразование: p' = p * scale + (offsetX, offsetY).
    private float scale = 1f, offsetX, offsetY;
    private final float[] stack = new float[3 * 8];
    private int stackDepth;

    private static int drawCalls;
    private static int lastFrameDrawCalls;
    private final int[] viewport = new int[4];

    public UiRenderer() {
        shader = new Shader(Shaders.UI_BATCH_VERTEX, Shaders.UI_BATCH_FRAGMENT);
        verts = MemoryUtil.memAllocFloat(quadCapacity * VERTICES_PER_QUAD * FLOATS_PER_VERTEX);

        vao = glGenVertexArrays();
        vbo = glGenBuffers();
        ibo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        allocateVertexBuffer();
        int stride = FLOATS_PER_VERTEX * Float.BYTES;
        glVertexAttribPointer(0, 2, GL_FLOAT, false, stride, 0L);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 2L * Float.BYTES);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(2, 4, GL_FLOAT, false, stride, 4L * Float.BYTES);
        glEnableVertexAttribArray(2);
        glVertexAttribPointer(3, 1, GL_FLOAT, false, stride, 8L * Float.BYTES);
        glEnableVertexAttribArray(3);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo);
        uploadIndices();
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    private void allocateVertexBuffer() {
        glBufferData(GL_ARRAY_BUFFER,
                (long) quadCapacity * VERTICES_PER_QUAD * FLOATS_PER_VERTEX * Float.BYTES,
                GL_DYNAMIC_DRAW);
    }

    /**
     * Индексы одинаковы для всех квадратов и не меняются никогда: два
     * треугольника на четыре вершины. Загружаются один раз при росте буфера.
     */
    private void uploadIndices() {
        IntBuffer idx = MemoryUtil.memAllocInt(quadCapacity * INDICES_PER_QUAD);
        for (int q = 0; q < quadCapacity; q++) {
            int v = q * VERTICES_PER_QUAD;
            idx.put(v).put(v + 1).put(v + 2);
            idx.put(v).put(v + 2).put(v + 3);
        }
        idx.flip();
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, idx, GL_STATIC_DRAW);
        MemoryUtil.memFree(idx);
    }

    // ------------------------------------------------------------ настройка

    /** Атлас блоков: квадраты с ним рисуются без разрыва пакета. */
    public void setAtlas(int texture) {
        this.atlasTex = texture;
    }

    /** Два кегля шрифта, которые печатаются прямо в пакет. */
    public void registerFonts(Font a, Font b) {
        this.fontA = a;
        this.fontB = b;
        this.fontATex = a == null ? 0 : a.getTexture();
        this.fontBTex = b == null ? 0 : b.getTexture();
    }

    /** Размытый кадр для стеклянных панелей этого кадра; 0 — без стекла. */
    public void setBackdrop(int texture) {
        this.backdropTex = texture;
    }

    public boolean hasBackdrop() {
        return backdropTex != 0;
    }

    /** Сколько вызовов интерфейс сделал с последнего сброса. */
    public static int drawCalls() {
        return drawCalls;
    }

    /** Итог прошлого кадра: свой оверлей в счётчик текущего попасть не успеет. */
    public static int lastFrameDrawCalls() {
        return lastFrameDrawCalls;
    }

    public static void resetDrawCalls() {
        lastFrameDrawCalls = drawCalls;
        drawCalls = 0;
    }

    static void countDrawCall() {
        drawCalls++;
    }

    public int screenW() {
        return screenW;
    }

    public int screenH() {
        return screenH;
    }

    public boolean isOpen() {
        return open;
    }

    // ------------------------------------------------------------- пакет

    /**
     * Открывает пакет. Повторный вызов с тем же размером — продолжение того
     * же пакета: слои интерфейса рисуются подряд и не обязаны знать друг о
     * друге, но и разрывать пакет между ними незачем.
     */
    public void begin(int screenW, int screenH) {
        if (open && screenW == this.screenW && screenH == this.screenH)
            return;
        if (open)
            flush();
        this.screenW = screenW;
        this.screenH = screenH;
        if (!open) {
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_CULL_FACE);
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            open = true;
        }
    }

    /** Отправляет накопленное и закрывает пакет, возвращая состояние GL. */
    public void end() {
        if (!open)
            return;
        flush();
        open = false;
        otherTex = 0;
        scale = 1f;
        offsetX = 0f;
        offsetY = 0f;
        stackDepth = 0;
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
    }

    /** Отправляет накопленное, не закрывая пакет. */
    public void flush() {
        if (quadCount == 0)
            return;
        verts.position(0);
        verts.limit(quadCount * VERTICES_PER_QUAD * FLOATS_PER_VERTEX);

        shader.bind();
        shader.setVec2("uScreenSize", screenW, screenH);
        // Координаты интерфейса виртуальные (с учётом масштаба GUI), а
        // gl_FragCoord — в настоящих пикселях: для выборки стекла нужен
        // настоящий размер кадра.
        glGetIntegerv(GL_VIEWPORT, viewport);
        shader.setVec2("uFbSize", viewport[2], viewport[3]);
        shader.setInt("uAtlas", 0);
        shader.setInt("uBackdrop", 1);
        shader.setInt("uFontA", 2);
        shader.setInt("uFontB", 3);
        shader.setInt("uTex", 4);
        bind(GL_TEXTURE0, atlasTex);
        bind(GL_TEXTURE1, backdropTex);
        bind(GL_TEXTURE2, fontATex);
        bind(GL_TEXTURE3, fontBTex);
        bind(GL_TEXTURE4, usesOther ? otherTex : 0);
        glActiveTexture(GL_TEXTURE0);

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0L, verts);
        glDrawElements(GL_TRIANGLES, quadCount * INDICES_PER_QUAD, GL_UNSIGNED_INT, 0L);
        drawCalls++;
        glBindVertexArray(0);
        shader.unbind();

        verts.clear();
        quadCount = 0;
        usesOther = false;
    }

    private static void bind(int unit, int texture) {
        glActiveTexture(unit);
        glBindTexture(GL_TEXTURE_2D, texture);
    }

    // --------------------------------------------------------- квадраты

    /** Solid colored rectangle. */
    public void quad(float x, float y, float w, float h, float r, float g, float b, float a) {
        rect(x, y, w, h, 0, 0, 0, 0, r, g, b, a, MODE_FILL);
    }

    /**
     * Матовое стекло: размытый мир под прямоугольником. Поверх него вызывающий
     * рисует свою тонированную подложку — стекло без тона выглядело бы просто
     * дырой с размытием.
     */
    public void glass(float x, float y, float w, float h) {
        glass(x, y, w, h, 1f);
    }

    /** Стекло с прозрачностью — для экранов меню, которые проявляются фейдом. */
    public void glass(float x, float y, float w, float h, float alpha) {
        if (backdropTex == 0 || alpha <= 0f)
            return;
        rect(x, y, w, h, 0, 0, 0, 0, 1f, 1f, 1f, alpha, MODE_GLASS);
    }

    /** Textured rectangle sampled from {@code texId}, tinted by (r,g,b,a). */
    public void texQuad(float x, float y, float w, float h, int texId,
                        float u0, float v0, float u1, float v1,
                        float r, float g, float b, float a) {
        rect(x, y, w, h, u0, v0, u1, v1, r, g, b, a, textureMode(texId));
    }

    /** Заливка по произвольным углам — тень под изометрической иконкой. */
    public void quad4(float[] xy, float r, float g, float b, float a) {
        corners(xy, 0, 0, 0, 0, r, g, b, a, MODE_FILL);
    }

    /**
     * Текстурированный четырёхугольник по произвольным углам.
     *
     * Нужен изометрическим иконкам блоков: грань куба на экране — это
     * параллелограмм, а осепараллельным прямоугольником его не нарисовать.
     * Углы перечисляются по кругу, начиная с левого верхнего.
     */
    public void texQuad4(float[] xy, int texId,
                         float u0, float v0, float u1, float v1,
                         float r, float g, float b, float a) {
        corners(xy, u0, v0, u1, v1, r, g, b, a, textureMode(texId));
    }

    /**
     * Буквы строки прямо в пакет.
     *
     * <p>Текст перестал быть отдельным рисовальщиком: счётчик стопки в слоте и
     * сам слот — это один и тот же слой интерфейса, и разрывать из-за букв
     * пакет означало бы платить за каждую надпись отдельным вызовом.
     */
    public void glyphs(Font font, String s, float x, float y,
                       float r, float g, float b, float a) {
        if (font == null || s == null || s.isEmpty() || a <= 0f)
            return;
        float mode = font == fontA ? MODE_FONT_A
                : font == fontB ? MODE_FONT_B
                : textureMode(font.getTexture());
        font.glyphs(s, x, y, (x0, y0, x1, y1, s0, t0, s1, t1) ->
                rect(x0, y0, x1 - x0, y1 - y0, s0, t0, s1, t1, r, g, b, a, mode));
    }

    /**
     * Чужая текстура живёт в своём блоке, и её смена — единственное, что
     * разрывает пакет: номер текстуры в вершину не положить.
     */
    private float textureMode(int texId) {
        if (texId == atlasTex)
            return MODE_ATLAS;
        if (texId != otherTex) {
            flush();
            otherTex = texId;
        }
        usesOther = true;
        return MODE_TEXTURE;
    }

    // ------------------------------------------------------- преобразование

    /**
     * Масштаб вокруг точки и сдвиг для всего, что рисуется дальше.
     *
     * <p>Нужен окнам: они появляются, чуть подрастая из 0.96, и делать это
     * каждой отдельной координатой означало бы протащить масштаб через каждый
     * вызов раскладки.
     */
    public void pushTransform(float scale, float pivotX, float pivotY, float dx, float dy) {
        if (stackDepth < stack.length / 3) {
            stack[stackDepth * 3] = this.scale;
            stack[stackDepth * 3 + 1] = this.offsetX;
            stack[stackDepth * 3 + 2] = this.offsetY;
            stackDepth++;
        }
        float localOffsetX = pivotX * (1f - scale) + dx;
        float localOffsetY = pivotY * (1f - scale) + dy;
        this.offsetX = localOffsetX * this.scale + this.offsetX;
        this.offsetY = localOffsetY * this.scale + this.offsetY;
        this.scale *= scale;
    }

    public void popTransform() {
        if (stackDepth <= 0) {
            scale = 1f;
            offsetX = 0f;
            offsetY = 0f;
            return;
        }
        stackDepth--;
        scale = stack[stackDepth * 3];
        offsetX = stack[stackDepth * 3 + 1];
        offsetY = stack[stackDepth * 3 + 2];
    }

    /**
     * Куда уезжает точка при масштабировании вокруг опоры со сдвигом.
     *
     * <p>Статическая и без GL — именно её проверяет тест: пружина окна лежит в
     * этих двух строчках, и ошибка здесь видна только на глаз.
     */
    public static float[] transformPoint(float x, float y, float scale,
            float pivotX, float pivotY, float dx, float dy) {
        return new float[] {
                pivotX + (x - pivotX) * scale + dx,
                pivotY + (y - pivotY) * scale + dy };
    }

    // ------------------------------------------------------------ вершины

    private void rect(float x, float y, float w, float h,
                      float u0, float v0, float u1, float v1,
                      float r, float g, float b, float a, float mode) {
        float x0 = x, y0 = y, x1 = x + w, y1 = y + h;
        reserve();
        vertex(x0, y0, u0, v0, r, g, b, a, mode);
        vertex(x1, y0, u1, v0, r, g, b, a, mode);
        vertex(x1, y1, u1, v1, r, g, b, a, mode);
        vertex(x0, y1, u0, v1, r, g, b, a, mode);
        quadCount++;
    }

    private void corners(float[] xy, float u0, float v0, float u1, float v1,
                         float r, float g, float b, float a, float mode) {
        float[] u = { u0, u1, u1, u0 };
        float[] v = { v0, v0, v1, v1 };
        reserve();
        for (int i = 0; i < 4; i++)
            vertex(xy[i * 2], xy[i * 2 + 1], u[i], v[i], r, g, b, a, mode);
        quadCount++;
    }

    private void vertex(float x, float y, float u, float v,
                        float r, float g, float b, float a, float mode) {
        verts.put(x * scale + offsetX).put(y * scale + offsetY);
        verts.put(u).put(v);
        verts.put(r).put(g).put(b).put(a);
        verts.put(mode);
    }

    /** Буфер кончился — растим вдвое, а не роняем кадр. */
    private void reserve() {
        if (quadCount < quadCapacity)
            return;
        quadCapacity *= 2;
        FloatBuffer bigger =
                MemoryUtil.memAllocFloat(quadCapacity * VERTICES_PER_QUAD * FLOATS_PER_VERTEX);
        verts.flip();
        bigger.put(verts);
        MemoryUtil.memFree(verts);
        verts = bigger;
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        allocateVertexBuffer();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo);
        uploadIndices();
        glBindVertexArray(0);
    }

    public void destroy() {
        glDeleteBuffers(vbo);
        glDeleteBuffers(ibo);
        glDeleteVertexArrays(vao);
        shader.destroy();
        MemoryUtil.memFree(verts);
    }
}

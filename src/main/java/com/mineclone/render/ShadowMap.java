package com.mineclone.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL14.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Каскадная карта теней от солнца/луны: два ортографических среза вокруг
 * игрока — ближний резкий и дальний широкий.
 *
 * Тексты глубины сравниваются железом (sampler2DShadow + GL_LINEAR), поэтому
 * каждый отсчёт в шейдере это уже 2x2 PCF; сверху шейдер кладёт ещё 3x3 или
 * 5x5. За границей карты выставлен border 1.0 — «всё освещено», иначе мир за
 * пределами каскада уходил бы в чёрное.
 *
 * Каскады перестраиваются ЛЕНИВО. Мир статичен, а солнце за кадр проходит
 * 8e-5 радиана, поэтому гнать всю геометрию в обе карты каждый кадр — чистая
 * трата: замер показал, что проход теней упирается не в разрешение карты, а в
 * число draw-call'ов. Дальний каскад (радиус 110, накрывает почти все
 * загруженные чанки) обновляется раз в {@code PERIOD[1]} кадров, ближний —
 * каждый кадр. Резкий поворот камеры, прыжок времени командой и перестройка
 * мешей ломают лень немедленно — иначе после разворота дальние тени
 * подгружались бы на глазах.
 *
 * Шейдер обязан сэмплить матрицей, которой карта РЕАЛЬНО отрисована
 * ({@link #matrix}), а не свежевычисленной — иначе тени поедут.
 */
public final class ShadowMap {

    public static final int CASCADES = 2;

    /** Через сколько кадров каскад обязан перестроиться, даже если ничего не менялось. */
    private static final int[] PERIOD = { 2, 6 };
    /** Насколько центр каскада может уехать (в долях радиуса) до принудительной перестройки. */
    private static final float DRIFT_LIMIT = 0.12f;
    /** Косинус угла поворота светила, после которого карта считается протухшей. */
    private static final float LIGHT_LIMIT = 0.9995f;

    private final int size;
    private final int[] fbo = new int[CASCADES];
    private final int[] tex = new int[CASCADES];
    private final float[] radii = { SunLight.CASCADE0_RADIUS, SunLight.CASCADE1_RADIUS };

    /** Матрица, которой карта реально отрисована — ею же шейдер и сэмплит. */
    private final Matrix4f[] matrices = { new Matrix4f(), new Matrix4f() };
    /** Матрица, посчитанная под текущий кадр; станет matrices[c] при перестройке. */
    private final Matrix4f[] pending = { new Matrix4f(), new Matrix4f() };
    private final Vector3f[] renderedCenter = { new Vector3f(), new Vector3f() };
    private final Vector3f[] pendingCenter = { new Vector3f(), new Vector3f() };
    private final Vector3f[] renderedLight = { new Vector3f(), new Vector3f() };
    private final Vector3f lightDir = new Vector3f(0f, 1f, 0f);
    private final int[] age = { Integer.MAX_VALUE / 2, Integer.MAX_VALUE / 2 };

    private boolean ready;

    public ShadowMap(int size) {
        this.size = size;
        boolean ok = true;
        for (int i = 0; i < CASCADES; i++) {
            tex[i] = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, tex[i]);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, size, size, 0,
                    GL_DEPTH_COMPONENT, GL_FLOAT, (java.nio.ByteBuffer) null);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_BORDER);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_BORDER);
            glTexParameterfv(GL_TEXTURE_2D, GL_TEXTURE_BORDER_COLOR,
                    new float[] { 1f, 1f, 1f, 1f });
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_R_TO_TEXTURE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL);

            fbo[i] = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, fbo[i]);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, tex[i], 0);
            glDrawBuffer(GL_NONE);
            glReadBuffer(GL_NONE);
            ok &= glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
        }
        ready = ok;
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glBindTexture(GL_TEXTURE_2D, 0);
        if (!ready)
            System.err.println("ShadowMap: FBO incomplete, shadows disabled");
    }

    public boolean isReady() { return ready; }

    public int getSize() { return size; }

    /** Матрица, которой карта реально отрисована. Именно ею сэмплит шейдер. */
    public Matrix4f matrix(int cascade) { return matrices[cascade]; }

    public float radius(int cascade) { return radii[cascade]; }

    /** Текстура каскада — её читает объёмный туман в посте. */
    public int texture(int cascade) { return tex[cascade]; }

    /**
     * Пересчитывает матрицы-кандидаты под текущее положение камеры.
     * Центр каждого каскада уезжает вперёд по взгляду — так половина карты не
     * тратится на то, что за спиной. Ничего не рисует.
     */
    public void update(Vector3f camPos, Vector3f camForward, Vector3f lightDir) {
        this.lightDir.set(lightDir);
        for (int i = 0; i < CASCADES; i++) {
            float r = radii[i];
            Vector3f center = new Vector3f(camForward.x, 0f, camForward.z);
            if (center.lengthSquared() < 1e-6f)
                center.set(0f, 0f, 1f);
            center.normalize().mul(r * 0.45f).add(camPos);
            pendingCenter[i].set(center);
            pending[i] = SunLight.cascadeMatrix(lightDir, center, r, size);
        }
    }

    /**
     * Надо ли перерисовать каскад в этом кадре: вышел срок, центр уехал
     * (резкий поворот камеры), или светило дёрнули командой времени.
     */
    public boolean needsRebuild(int cascade) {
        return isStale(age[cascade], PERIOD[cascade],
                pendingCenter[cascade].distance(renderedCenter[cascade]),
                radii[cascade],
                renderedLight[cascade].dot(lightDir));
    }

    /**
     * Чистое правило протухания каскада — без GL, чтобы его можно было
     * проверить тестом. Три независимые причины перерисовать:
     *
     * <ul>
     *   <li>вышел срок ({@code age >= period}) — страховка на всё, что мы не
     *       отследили явно;
     *   <li>центр каскада уехал дальше {@code DRIFT_LIMIT} радиуса — это
     *       резкий поворот камеры: при радиусе 110 разворот на 180° уносит
     *       центр почти на 100 блоков, и без этой проверки дальние тени
     *       подгружались бы на глазах;
     *   <li>светило повернулось сильнее {@code LIGHT_LIMIT} — прыжок времени
     *       командой (за обычный кадр солнце проходит 8e-5 радиана и сюда
     *       не попадает).
     * </ul>
     *
     * @param lightDot косинус между направлением света при отрисовке и текущим
     */
    public static boolean isStale(int age, int period, float centerDrift, float radius,
                                  float lightDot) {
        return age >= period
                || centerDrift > radius * DRIFT_LIMIT
                || lightDot < LIGHT_LIMIT;
    }

    /** Меши перестроились — обе карты протухли. */
    public void invalidate() {
        age[0] = age[1] = Integer.MAX_VALUE / 2;
    }

    /** Конец кадра: каскады стареют. */
    public void endFrame() {
        for (int i = 0; i < CASCADES; i++)
            if (age[i] < Integer.MAX_VALUE / 2)
                age[i]++;
    }

    /** Делает каскад целью рендера, чистит глубину и фиксирует его матрицу. */
    public void begin(int cascade) {
        matrices[cascade].set(pending[cascade]);
        renderedCenter[cascade].set(pendingCenter[cascade]);
        renderedLight[cascade].set(lightDir);
        age[cascade] = 0;
        glBindFramebuffer(GL_FRAMEBUFFER, fbo[cascade]);
        glViewport(0, 0, size, size);
        glClear(GL_DEPTH_BUFFER_BIT);
    }

    public void end(int screenWidth, int screenHeight) {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, screenWidth, screenHeight);
    }

    /** Вешает обе карты на юниты, которых ждёт {@link SceneLighting}. */
    public void bind() {
        glActiveTexture(GL_TEXTURE0 + SceneLighting.SHADOW_UNIT_0);
        glBindTexture(GL_TEXTURE_2D, tex[0]);
        glActiveTexture(GL_TEXTURE0 + SceneLighting.SHADOW_UNIT_1);
        glBindTexture(GL_TEXTURE_2D, tex[1]);
        glActiveTexture(GL_TEXTURE0);
    }

    public void destroy() {
        for (int i = 0; i < CASCADES; i++) {
            glDeleteFramebuffers(fbo[i]);
            glDeleteTextures(tex[i]);
        }
    }
}

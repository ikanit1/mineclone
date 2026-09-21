package com.mineclone.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * HDR-конвейер кадра.
 *
 * Сцена рисуется не в экран, а в multisample-буфер RGBA16F: значения света там
 * спокойно уходят далеко за 1.0 (солнце, блики, факелы), поэтому bloom и
 * god rays есть из чего строить, а тонемап ACES в композите даёт мягкие
 * пересветы вместо выжженных белых пятен.
 *
 * Порядок: {@link #begin} → мир → {@link #resolveDepth} → рука →
 * {@link #resolveHandDepth} (только если нужна маска резкости) →
 * {@link #resolveColor} → {@link #render}.
 *
 * Глубина мира резольвится ДО руки: рука от первого лица чистит z-буфер под
 * себя, и после неё в буфере остаётся только она. Глубина резкости и объёмный
 * туман читают глубину мира, поэтому снимать её надо раньше.
 *
 * Если драйвер по какой-то причине не собрал FBO, {@link #isReady()} врёт false
 * и вызывающий код рисует напрямую в экран (шейдеры тогда тонемапят сами).
 */
public final class PostProcess {

    /** Половинное разрешение для bloom, лучей и тумана — глазу хватает, GPU легче. */
    private static final int DOWNSCALE = 2;

    private final Shader brightShader;
    private final Shader blurShader;
    private final Shader rayShader;
    private final Shader fogShader;
    private final Shader compositeShader;
    private final int emptyVao;
    private final int blackTex;
    private final int clearFogTex;
    private final int farDepthTex;
    private final int farShadowTex;

    private int width, height;
    /**
     * Размер экрана. Сцена может рисоваться меньше его ({@link #resize}), и
     * тогда композит растягивает её на окно — интерфейс при этом остаётся
     * чётким, потому что рисуется после и в полном разрешении.
     */
    private int outWidth, outHeight;
    private int samples;
    private int wantSamples;

    private int msFbo, msColor, msDepth;
    /** Разрешённая (не multisample) глубина мира — её читают туман и глубина резкости. */
    private int depthFbo, depthTex;
    /** Глубина после руки: где рука — там резко, даже если мир размыт. */
    private int handFbo, handTex;
    private boolean handResolved;
    private int sceneFbo, sceneTex;
    private int brightFbo, brightTex;
    private final int[] blurFbo = new int[2];
    private final int[] blurTex = new int[2];
    private int rayFbo, rayTex;
    private int fogFbo, fogTex;
    private int frame;

    private boolean ready;

    public PostProcess(int width, int height) {
        brightShader = new Shader(Shaders.POST_VERTEX, Shaders.POST_BRIGHT);
        blurShader = new Shader(Shaders.POST_VERTEX, Shaders.POST_BLUR);
        rayShader = new Shader(Shaders.POST_VERTEX, Shaders.POST_GODRAY);
        fogShader = new Shader(Shaders.POST_VERTEX, Shaders.POST_FOG);
        compositeShader = new Shader(Shaders.POST_VERTEX, Shaders.POST_COMPOSITE);
        emptyVao = glGenVertexArrays();

        blackTex = constantTex(0f, 0f, 0f, 1f);
        // «Тумана нет»: ноль рассеяния и полное пропускание.
        clearFogTex = constantTex(0f, 0f, 0f, 1f);
        farDepthTex = farDepth(false);
        // Для sampler2DShadow нужна текстура с режимом сравнения, иначе выборка
        // не определена — даже если ветка шейдера её не делает.
        farShadowTex = farDepth(true);

        int max = glGetInteger(GL_MAX_SAMPLES);
        wantSamples = 4;
        samples = Math.max(1, Math.min(wantSamples, max));
        resize(width, height, width, height);
    }

    /**
     * Сколько выборок у multisample-цели: 0 или 1 — сглаживания нет.
     *
     * <p>Перестройка целей дорогая (шесть текстур и два renderbuffer'а), но
     * случается она ровно по щелчку в настройках, а не в кадре.
     */
    public void setSamples(int n) {
        int max = glGetInteger(GL_MAX_SAMPLES);
        int want = Math.max(1, Math.min(n <= 1 ? 1 : n, max));
        if (want == samples)
            return;
        wantSamples = want;
        samples = want;
        int w = width, h = height, ow = outWidth, oh = outHeight;
        width = height = 0;                 // заставить resize пересобрать цели
        resize(w, h, ow, oh);
    }

    public int getSamples() {
        return samples;
    }

    /** Глубина 1×1 «всё далеко». */
    private static int farDepth(boolean compare) {
        int t = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, t);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, 1, 1, 0, GL_DEPTH_COMPONENT, GL_FLOAT,
                new float[] { 1f });
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        if (compare) {
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL);
        }
        glBindTexture(GL_TEXTURE_2D, 0);
        return t;
    }

    private static int constantTex(float r, float g, float b, float a) {
        int t = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, t);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, 1, 1, 0, GL_RGBA, GL_FLOAT, new float[] { r, g, b, a });
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_2D, 0);
        return t;
    }

    public boolean isReady() { return ready; }

    public void resize(int w, int h) {
        resize(w, h, w, h);
    }

    /**
     * @param w  ширина, в которой рисуется сцена
     * @param ow ширина окна, в которую композит её растягивает
     */
    public void resize(int w, int h, int ow, int oh) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        outWidth = Math.max(1, ow);
        outHeight = Math.max(1, oh);
        if (w == width && h == height && ready)
            return;
        destroyTargets();
        width = w;
        height = h;
        int bw = Math.max(1, w / DOWNSCALE), bh = Math.max(1, h / DOWNSCALE);

        msFbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, msFbo);
        msColor = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, msColor);
        glRenderbufferStorageMultisample(GL_RENDERBUFFER, samples, GL_RGBA16F, w, h);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, msColor);
        msDepth = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, msDepth);
        glRenderbufferStorageMultisample(GL_RENDERBUFFER, samples, GL_DEPTH_COMPONENT24, w, h);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, msDepth);
        boolean ok = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;

        // Отдельные цели под глубину: сэмплить multisample-renderbuffer нельзя,
        // а туман и глубина резкости обязаны читать глубину попиксельно.
        int[] d = depthTarget(w, h);
        depthFbo = d[0];
        depthTex = d[1];
        ok &= d[2] == 1;
        int[] hd = depthTarget(w, h);
        handFbo = hd[0];
        handTex = hd[1];
        ok &= hd[2] == 1;

        int[] scene = colorTarget(w, h);
        sceneFbo = scene[0];
        sceneTex = scene[1];
        ok &= scene[2] == 1;

        int[] bright = colorTarget(bw, bh);
        brightFbo = bright[0];
        brightTex = bright[1];
        ok &= bright[2] == 1;

        for (int i = 0; i < 2; i++) {
            int[] t = colorTarget(bw, bh);
            blurFbo[i] = t[0];
            blurTex[i] = t[1];
            ok &= t[2] == 1;
        }

        int[] ray = colorTarget(bw, bh);
        rayFbo = ray[0];
        rayTex = ray[1];
        ok &= ray[2] == 1;

        int[] fog = colorTarget(bw, bh);
        fogFbo = fog[0];
        fogTex = fog[1];
        ok &= fog[2] == 1;

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glBindRenderbuffer(GL_RENDERBUFFER, 0);
        ready = ok;
        if (!ok)
            System.err.println("PostProcess: HDR framebuffer incomplete, falling back to direct render");
    }

    /** @return {fbo, texture, 1 если собралось} */
    private int[] colorTarget(int w, int h) {
        int fb = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fb);
        int tx = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tx);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, w, h, 0, GL_RGBA, GL_FLOAT, (java.nio.ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tx, 0);
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        return new int[] { fb, tx, status == GL_FRAMEBUFFER_COMPLETE ? 1 : 0 };
    }

    private int[] depthTarget(int w, int h) {
        int fb = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fb);
        int tx = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tx);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, w, h, 0,
                GL_DEPTH_COMPONENT, GL_FLOAT, (java.nio.ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, tx, 0);
        glDrawBuffers(GL_NONE);
        glReadBuffer(GL_NONE);
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        return new int[] { fb, tx, status == GL_FRAMEBUFFER_COMPLETE ? 1 : 0 };
    }

    /** Переключает рендер на HDR-буфер и чистит его. */
    public void begin(float clearR, float clearG, float clearB) {
        glBindFramebuffer(GL_FRAMEBUFFER, msFbo);
        glViewport(0, 0, width, height);
        glClearColor(clearR, clearG, clearB, 1f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        handResolved = false;
    }

    /**
     * Снимает глубину мира. Звать до руки от первого лица — она чистит z-буфер.
     * Цель рендера после вызова снова multisample-буфер.
     */
    public void resolveDepth() {
        blitDepth(depthFbo);
    }

    /**
     * Снимает глубину после руки — маску «здесь рука, её не размывать». Нужна
     * только глубине резкости, поэтому без неё проход не делается.
     */
    public void resolveHandDepth() {
        blitDepth(handFbo);
        handResolved = true;
    }

    private void blitDepth(int target) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, msFbo);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, target);
        // Глубина блитится только GL_NEAREST и только в цель того же формата —
        // иначе драйвер молча ничего не делает.
        glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
                GL_DEPTH_BUFFER_BIT, GL_NEAREST);
        glBindFramebuffer(GL_FRAMEBUFFER, msFbo);
    }

    /** Схлопывает MSAA-цвет в обычную текстуру, из которой дальше читает пост. */
    public void resolveColor() {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, msFbo);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, sceneFbo);
        glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
                GL_COLOR_BUFFER_BIT, GL_NEAREST);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    /** Снимок непрозрачной сцены для экранных отражений, затем возврат в HDR-цель. */
    public int captureSceneColor() {
        resolveColor();
        glBindFramebuffer(GL_FRAMEBUFFER, msFbo);
        glViewport(0, 0, width, height);
        return sceneTex;
    }

    /** Цвет и глубина разом — когда руки в кадре нет (превью, меню). */
    public void resolve() {
        resolveDepth();
        resolveColor();
    }

    /**
     * Свёртывает туман, bloom, лучи и тонемап в экран.
     *
     * @param sunUv экранная позиция солнца в 0..1, или null — лучей нет
     */
    public void render(Settings s, float[] sunUv) {
        int bw = Math.max(1, width / DOWNSCALE), bh = Math.max(1, height / DOWNSCALE);
        frame++;
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_BLEND);
        glDisable(GL_CULL_FACE);
        glBindVertexArray(emptyVao);

        int fogSource = clearFogTex;
        if (s.fogDensity > 0f || s.fogHaze > 0f) {
            glBindFramebuffer(GL_FRAMEBUFFER, fogFbo);
            glViewport(0, 0, bw, bh);
            fogShader.bind();
            fogShader.setInt("uDepth", 0);
            fogShader.setInt("uShadow", 1);
            fogShader.setMat4("uInvViewProj", s.invViewProj);
            fogShader.setMat4("uShadowMat", s.shadowMat);
            fogShader.setVec3("uCamPos", s.camPos);
            fogShader.setVec3("uLightDir", s.lightDir);
            fogShader.setVec3("uLightColor", s.fogLight);
            fogShader.setVec3("uAmbient", s.fogAmbient);
            fogShader.setFloat("uDensity", s.fogDensity);
            fogShader.setFloat("uHaze", s.fogHaze);
            fogShader.setFloat("uTop", s.fogTop);
            fogShader.setFloat("uDepthRange", s.fogDepth);
            fogShader.setFloat("uMaxDist", s.fogMaxDist);
            fogShader.setVec2("uDrift", s.fogDriftX, s.fogDriftZ);
            fogShader.setFloat("uShadowOn", s.fogShadowTex != 0 ? 1f : 0f);
            // Шум сдвигается от кадра к кадру: зерно марша не стоит на месте
            // сеткой, а мелко кипит, и глаз его усредняет.
            fogShader.setVec2("uNoiseOffset", (frame % 64) * 7.0f, (frame % 64) * 3.0f);
            bindTex(0, depthTex);
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D, s.fogShadowTex != 0 ? s.fogShadowTex : farShadowTex);
            drawQuad();
            fogSource = fogTex;
        }

        boolean wantBright = s.bloomStrength > 0f || (sunUv != null && s.rayStrength > 0f);
        if (wantBright) {
            glBindFramebuffer(GL_FRAMEBUFFER, brightFbo);
            glViewport(0, 0, bw, bh);
            brightShader.bind();
            brightShader.setInt("uScene", 0);
            brightShader.setFloat("uThreshold", s.bloomThreshold);
            bindTex(0, sceneTex);
            drawQuad();
        }

        int bloomSource = blackTex;
        if (s.bloomStrength > 0f) {
            // Две итерации разделимого гаусса с растущим шагом: дёшево, но
            // хвост засветки получается широким и мягким.
            blurShader.bind();
            blurShader.setInt("uTex", 0);
            int src = brightTex;
            for (int pass = 0; pass < 2; pass++) {
                float scale = pass == 0 ? 1f : 2.2f;
                glBindFramebuffer(GL_FRAMEBUFFER, blurFbo[0]);
                glViewport(0, 0, bw, bh);
                blurShader.setVec2("uDir", scale / bw, 0f);
                bindTex(0, src);
                drawQuad();

                glBindFramebuffer(GL_FRAMEBUFFER, blurFbo[1]);
                blurShader.setVec2("uDir", 0f, scale / bh);
                bindTex(0, blurTex[0]);
                drawQuad();
                src = blurTex[1];
            }
            bloomSource = blurTex[1];
        }

        int raySource = blackTex;
        if (sunUv != null && s.rayStrength > 0f) {
            glBindFramebuffer(GL_FRAMEBUFFER, rayFbo);
            glViewport(0, 0, bw, bh);
            rayShader.bind();
            rayShader.setInt("uTex", 0);
            rayShader.setVec2("uSunUv", sunUv[0], sunUv[1]);
            rayShader.setFloat("uDensity", s.rayDensity);
            rayShader.setFloat("uDecay", s.rayDecay);
            bindTex(0, brightTex);
            drawQuad();
            raySource = rayTex;
        }

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        // Композит — единственный проход, который пишет в окно, поэтому
        // масштаб рендера виден только здесь: сцена меньше, вывод во весь экран.
        glViewport(0, 0, outWidth, outHeight);
        compositeShader.bind();
        compositeShader.setInt("uScene", 0);
        compositeShader.setInt("uBloom", 1);
        compositeShader.setInt("uRays", 2);
        compositeShader.setInt("uDepth", 3);
        compositeShader.setInt("uFog", 4);
        compositeShader.setInt("uHandDepth", 5);
        compositeShader.setFloat("uBloomStrength", s.bloomStrength);
        compositeShader.setFloat("uRayStrength", sunUv == null ? 0f : s.rayStrength);
        compositeShader.setVec3("uRayColor", s.rayColor);
        compositeShader.setFloat("uExposure", s.exposure);
        compositeShader.setFloat("uVignette", s.vignette);
        compositeShader.setFloat("uUnderwater", s.underwater);
        compositeShader.setVec3("uUnderwaterTint", s.underwaterTint);
        compositeShader.setFloat("uNight", s.night);
        compositeShader.setFloat("uSaturation", s.saturation);
        compositeShader.setFloat("uDamage", s.damage);
        compositeShader.setVec3("uDamageColor", s.damageColor);
        compositeShader.setFloat("uDofStrength", s.dofStrength);
        compositeShader.setFloat("uDofFocus", s.dofFocus);
        compositeShader.setFloat("uDofRange", Math.max(0.1f, s.dofRange));
        compositeShader.setFloat("uHandMask", handResolved && s.dofStrength > 0f ? 1f : 0f);
        compositeShader.setFloat("uFogOn", fogSource == fogTex ? 1f : 0f);
        compositeShader.setFloat("uFrost", s.frost);
        compositeShader.setFloat("uFrostTime", s.fogTime);
        compositeShader.setFloat("uPoison", s.poison);
        compositeShader.setFloat("uStun", s.stun);
        compositeShader.setVec2("uNearFar", s.near, s.far);
        compositeShader.setVec2("uTexel", 1f / width, 1f / height);
        bindTex(0, sceneTex);
        bindTex(1, bloomSource);
        bindTex(2, raySource);
        bindTex(3, depthTex);
        bindTex(4, fogSource);
        bindTex(5, handResolved ? handTex : farDepthTex);
        drawQuad();
        compositeShader.unbind();

        glBindVertexArray(0);
        glActiveTexture(GL_TEXTURE0);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
    }

    private void drawQuad() {
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }

    private static void bindTex(int unit, int tex) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D, tex);
    }

    private void destroyTargets() {
        if (msFbo != 0) {
            glDeleteFramebuffers(msFbo);
            glDeleteRenderbuffers(msColor);
            glDeleteRenderbuffers(msDepth);
            glDeleteFramebuffers(depthFbo);
            glDeleteTextures(depthTex);
            glDeleteFramebuffers(handFbo);
            glDeleteTextures(handTex);
        }
        if (sceneFbo != 0) { glDeleteFramebuffers(sceneFbo); glDeleteTextures(sceneTex); }
        if (brightFbo != 0) { glDeleteFramebuffers(brightFbo); glDeleteTextures(brightTex); }
        for (int i = 0; i < 2; i++)
            if (blurFbo[i] != 0) { glDeleteFramebuffers(blurFbo[i]); glDeleteTextures(blurTex[i]); }
        if (rayFbo != 0) { glDeleteFramebuffers(rayFbo); glDeleteTextures(rayTex); }
        if (fogFbo != 0) { glDeleteFramebuffers(fogFbo); glDeleteTextures(fogTex); }
        msFbo = sceneFbo = brightFbo = rayFbo = fogFbo = 0;
    }

    public void destroy() {
        destroyTargets();
        glDeleteTextures(blackTex);
        glDeleteTextures(clearFogTex);
        glDeleteTextures(farDepthTex);
        glDeleteTextures(farShadowTex);
        glDeleteVertexArrays(emptyVao);
        brightShader.destroy();
        blurShader.destroy();
        rayShader.destroy();
        fogShader.destroy();
        compositeShader.destroy();
    }

    /** Настройки композита на кадр. */
    public static final class Settings {
        public float bloomStrength = 0.55f;
        public float bloomThreshold = 1.0f;
        public float rayStrength = 0.55f;
        public float rayDensity = 0.65f;
        public float rayDecay = 0.94f;
        public final Vector3f rayColor = new Vector3f(1.0f, 0.86f, 0.62f);
        public float exposure = 0.95f;
        public float vignette = 0.30f;
        public float underwater = 0f;
        public final Vector3f underwaterTint = new Vector3f(0.28f, 0.62f, 1.0f);
        public float night = 0f;
        public float saturation = 1.02f;
        /** 0..1 — сила красной вспышки по краям кадра. */
        public float damage = 0f;
        public final Vector3f damageColor = new Vector3f(0.62f, 0.02f, 0.02f);
        /**
         * Радиус размытия вне фокуса, в пикселях. Ноль — глубины резкости
         * нет вообще, и композит не делает ни одной лишней выборки.
         */
        public float dofStrength = 0f;
        /** Дистанция фокуса и полуширина резкой зоны, блоки. */
        public float dofFocus = 8f;
        public float dofRange = 2.5f;
        /** Плоскости отсечения камеры — без них глубину не разлинеаризовать. */
        public float near = 0.1f;
        public float far = 600f;

        // ---- объёмный туман ----
        /** Плотность мглы у земли; ноль вместе с {@link #fogHaze} выключает проход. */
        public float fogDensity = 0f;
        /** Равномерная дымка на любой высоте — из неё днём растут столбы света. */
        public float fogHaze = 0f;
        public float fogTop = 64f;
        public float fogDepth = 12f;
        public float fogMaxDist = 96f;
        public float fogTime = 0f;
        /** Пройденный воздухом путь, а не ветер: см. {@code WeatherDrift}. */
        public float fogDriftX, fogDriftZ;
        public final Vector3f fogLight = new Vector3f();
        public final Vector3f fogAmbient = new Vector3f();
        public final Vector3f camPos = new Vector3f();
        public final Vector3f lightDir = new Vector3f(0f, 1f, 0f);
        public final Matrix4f invViewProj = new Matrix4f();
        public final Matrix4f shadowMat = new Matrix4f();
        /** Текстура дальнего каскада теней; 0 — теней в тумане нет. */
        public int fogShadowTex = 0;

        /** 0..1 — иней по краям кадра. */
        public float frost = 0f;
        /** 0..1 — яд и оглушение поверх кадра. */
        public float poison = 0f;
        public float stun = 0f;
    }
}

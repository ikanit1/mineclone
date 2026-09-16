package com.mineclone.render;

import com.mineclone.core.AppPaths;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Random;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Renders the visible sky layers: stars, sun, moon, and drifting clouds.
 * Sprites are loaded from assets/textures/*.png.
 * If the files are absent they are generated procedurally and saved.
 * The sun orbits in the Y-Z plane: gameTime=π/2 → sun directly overhead (noon).
 */
public class SkyRenderer {

    private static final String TEX_DIR   = "assets/textures";
    private static final int    SPRITE_SZ = 64;
    private static final int    SKY_SZ    = 256;
    private static final float  DIST      = 480f;
    private static final float  SUN_SIZE  = 44f;
    private static final float  MOON_SIZE = 30f;
    private static final float  STAR_SIZE = 980f;
    private static final float  CLOUD_SIZE = 1150f;
    private static final float  CLOUD_HEIGHT = 118f;
    // Interleaved VBO: [x,y,z,u,v] per vertex × 4 vertices
    private static final int    STRIDE    = 5 * Float.BYTES;

    private final Shader shader;
    private final Shader domeShader;
    private final int    domeVao;
    private final int    vao, vbo, ebo;
    private final int    sunTex, moonTex, starsTex, cloudsTex;
    /** Во сколько раз спрайт ярче 1.0 в HDR — задаётся перед каждой группой. */
    private float emissive = 1f;
    private float linearOut = 1f;
    /** Фаза луны для текущего спрайта; отрицательная — рисуется не луна. */
    private float phase = -1f;
    /** Мгла кадра 0..1. */
    private float haze;

    public SkyRenderer() {
        shader  = new Shader(Shaders.SKY_VERTEX, Shaders.SKY_FRAGMENT);
        domeShader = new Shader(Shaders.SKYDOME_VERTEX, Shaders.SKYDOME_FRAGMENT);
        domeVao = glGenVertexArrays();
        sunTex  = loadOrGenerate("sun-blocky.png",  SkyRenderer::drawSun);
        moonTex = loadOrGenerate("moon-blocky.png", SkyRenderer::drawMoon);
        starsTex = loadOrGenerate("stars-blocky.png", SkyRenderer::drawStars, true);
        cloudsTex = loadOrGenerate("clouds-blocky.png", SkyRenderer::drawClouds, true);

        int[] indices = {0, 1, 2, 0, 2, 3};

        vao = glGenVertexArrays();
        vbo = glGenBuffers();
        ebo = glGenBuffers();

        glBindVertexArray(vao);

        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) 4 * STRIDE, GL_DYNAMIC_DRAW);
        // attrib 0: position (xyz)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, STRIDE, 0);
        glEnableVertexAttribArray(0);
        // attrib 1: UV
        glVertexAttribPointer(1, 2, GL_FLOAT, false, STRIDE, 3L * Float.BYTES);
        glEnableVertexAttribArray(1);

        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        IntBuffer ib = MemoryUtil.memAllocInt(indices.length);
        ib.put(indices).flip();
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, ib, GL_STATIC_DRAW);
        MemoryUtil.memFree(ib);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    /** Всё, что нужно куполу на кадр. */
    public static final class Dome {
        public final Vector3f zenith = new Vector3f();
        public final Vector3f horizon = new Vector3f();
        public final Vector3f ground = new Vector3f();
        public final Vector3f sunGlow = new Vector3f();
        public final Vector3f lightDir = new Vector3f(0f, 1f, 0f);
        /** Облачность 0..1: гасит гало вокруг светила. */
        public float cloudiness;
        /** Яркость северного сияния 0..1; ноль — шейдер его не считает. */
        public float aurora;
        public float time;
        public float linearOut = 1f;
        /** Мгла ливня и метели: цвет и насколько она закрывает небо. */
        public final Vector3f haze = new Vector3f();
        public float hazeMix;
    }

    /**
     * Градиентный купол на весь экран: зенит, горизонт, земля под горизонтом и
     * гало вокруг светила. Рисуется первым, до всей геометрии — именно он, а не
     * glClearColor, задаёт цвет неба, поэтому дальний туман сходится с небом
     * без шва.
     */
    public void renderDome(Matrix4f invViewProj, Vector3f zenith, Vector3f horizon,
                           Vector3f ground, Vector3f sunGlow, Vector3f lightDir,
                           float weather, float linearOut) {
        Dome d = new Dome();
        d.zenith.set(zenith);
        d.horizon.set(horizon);
        d.ground.set(ground);
        d.sunGlow.set(sunGlow);
        d.lightDir.set(lightDir);
        d.cloudiness = weather;
        d.linearOut = linearOut;
        renderDome(invViewProj, d);
    }

    public void renderDome(Matrix4f invViewProj, Dome d) {
        glDisable(GL_DEPTH_TEST);
        glDepthMask(false);
        glDisable(GL_CULL_FACE);
        domeShader.bind();
        domeShader.setMat4("uInvViewProj", invViewProj);
        domeShader.setVec3("uZenith", d.zenith);
        domeShader.setVec3("uHorizon", d.horizon);
        domeShader.setVec3("uGround", d.ground);
        domeShader.setVec3("uSunGlow", d.sunGlow);
        domeShader.setVec3("uLightDir", d.lightDir);
        domeShader.setFloat("uWeather", d.cloudiness);
        domeShader.setFloat("uAurora", d.aurora);
        domeShader.setFloat("uTime", d.time);
        domeShader.setVec3("uHaze", d.haze);
        domeShader.setFloat("uHazeMix", d.hazeMix);
        domeShader.setFloat("uLinearOut", d.linearOut);
        glBindVertexArray(domeVao);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glBindVertexArray(0);
        domeShader.unbind();
        glDepthMask(true);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
    }

    public void render(Matrix4f proj, Matrix4f view, Vector3f playerPos, float gameTime,
                       float daylight, float totalTime, float linearOut) {
        render(proj, view, playerPos, gameTime, daylight, totalTime, linearOut, 0, 0f, 0f);
    }

    /**
     * @param moonPhase  фаза луны 0..7 (0 — полнолуние)
     * @param cloudiness облачность 0..1: гасит звёзды и светила, густит облака
     * @param haze       мгла ливня и метели 0..1: в ней тонет всё небо, облака тоже
     */
    public void render(Matrix4f proj, Matrix4f view, Vector3f playerPos, float gameTime,
                       float daylight, float totalTime, float linearOut,
                       int moonPhase, float cloudiness, float haze) {
        this.linearOut = linearOut;
        this.haze = Math.max(0f, Math.min(1f, haze));
        float clear = (1f - Math.min(1f, cloudiness)) * (1f - this.haze);
        float sy = (float) Math.sin(gameTime);
        float sz = (float) -Math.cos(gameTime);
        Vector3f sunDir  = new Vector3f(0, sy, sz).normalize();
        Vector3f moonDir = new Vector3f(sunDir).negate();
        // Right axis is always world-X (sun orbit is in the Y-Z plane)
        Vector3f right = new Vector3f(1, 0, 0);
        float night = clamp((0.48f - daylight) / 0.48f);

        shader.bind();
        shader.setMat4("uProjection", proj);
        shader.setMat4("uView", view);
        shader.setInt("uTex", 0);

        glDisable(GL_CULL_FACE);
        glDisable(GL_DEPTH_TEST);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        // Сквозь сплошные тучи звёзд не видно; в ясную ночь они и есть небо.
        if (night * clear > 0.01f) {
            emissive = 1.8f;
            renderStars(playerPos, gameTime, totalTime, night * clear);
        }

        // Sun — visible above horizon, warm tint fades near horizon
        if (sunDir.y > -0.25f) {
            float t = Math.min(1f, (sunDir.y + 0.25f) / 0.35f) * (0.15f + 0.85f * clear);
            float r = 1.0f, g = 0.92f + 0.08f * t, b = 0.70f + 0.30f * t;
            // Солнце в HDR сильно за 1.0: из этого потом растут bloom и лучи.
            emissive = 9f;
            Vector3f sunUp = new Vector3f(sunDir).cross(right).normalize();
            renderDisc(playerPos, sunDir, right, sunUp, SUN_SIZE, sunTex,
                       new Vector4f(r, g, b, t));
        }

        // Moon — opposite side, cool pale white
        if (moonDir.y > -0.25f) {
            float t = Math.min(1f, (moonDir.y + 0.25f) / 0.35f) * 0.95f * (0.1f + 0.9f * clear);
            emissive = 3f;
            phase = Math.floorMod(moonPhase, 8);
            Vector3f moonUp = new Vector3f(moonDir).cross(right).normalize();
            renderDisc(playerPos, moonDir, right, moonUp, MOON_SIZE, moonTex,
                       new Vector4f(0.88f, 0.93f, 1.0f, t));
            phase = -1f;
        }

        emissive = 1.15f;
        renderClouds(playerPos, totalTime, daylight, cloudiness);

        glDisable(GL_BLEND);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
        shader.unbind();
    }

    private void renderDisc(Vector3f playerPos, Vector3f dir, Vector3f right, Vector3f up,
                            float size, int texId, Vector4f color) {
        Vector3f c = new Vector3f(playerPos).add(new Vector3f(dir).mul(DIST));

        // Interleaved: [x,y,z,u,v] × 4
        float[] v = {
            c.x - right.x*size - up.x*size, c.y - right.y*size - up.y*size, c.z - right.z*size - up.z*size,  0f, 1f,
            c.x + right.x*size - up.x*size, c.y + right.y*size - up.y*size, c.z + right.z*size - up.z*size,  1f, 1f,
            c.x + right.x*size + up.x*size, c.y + right.y*size + up.y*size, c.z + right.z*size + up.z*size,  1f, 0f,
            c.x - right.x*size + up.x*size, c.y - right.y*size + up.y*size, c.z - right.z*size + up.z*size,  0f, 0f,
        };

        renderQuad(v, texId, color);
    }

    private void renderStars(Vector3f playerPos, float gameTime, float totalTime, float night) {
        float drift = gameTime * 0.025f;
        float twinkle = 1f;
        Vector4f tint = new Vector4f(1f, 1f, 1f, night * twinkle);
        float y = playerPos.y + 260f;
        float s = STAR_SIZE;
        float uv0 = drift;
        float uv1 = drift + 3.75f;

        float[] top = {
            playerPos.x - s, y, playerPos.z - s,  uv0, uv0,
            playerPos.x + s, y, playerPos.z - s,  uv1, uv0,
            playerPos.x + s, y, playerPos.z + s,  uv1, uv1,
            playerPos.x - s, y, playerPos.z + s,  uv0, uv1,
        };
        renderQuad(top, starsTex, tint);

        float wallY0 = playerPos.y - 45f;
        float wallY1 = playerPos.y + 235f;
        float wall = s * 0.92f;
        float span = 2.5f;
        renderQuad(new float[] {
            playerPos.x - s, wallY0, playerPos.z - wall,  uv0, uv0 + span,
            playerPos.x + s, wallY0, playerPos.z - wall,  uv1, uv0 + span,
            playerPos.x + s, wallY1, playerPos.z - wall,  uv1, uv0,
            playerPos.x - s, wallY1, playerPos.z - wall,  uv0, uv0,
        }, starsTex, tint);
        renderQuad(new float[] {
            playerPos.x + s, wallY0, playerPos.z + wall,  uv0, uv0 + span,
            playerPos.x - s, wallY0, playerPos.z + wall,  uv1, uv0 + span,
            playerPos.x - s, wallY1, playerPos.z + wall,  uv1, uv0,
            playerPos.x + s, wallY1, playerPos.z + wall,  uv0, uv0,
        }, starsTex, tint);
        renderQuad(new float[] {
            playerPos.x - wall, wallY0, playerPos.z + s,  uv0, uv0 + span,
            playerPos.x - wall, wallY0, playerPos.z - s,  uv1, uv0 + span,
            playerPos.x - wall, wallY1, playerPos.z - s,  uv1, uv0,
            playerPos.x - wall, wallY1, playerPos.z + s,  uv0, uv0,
        }, starsTex, tint);
        renderQuad(new float[] {
            playerPos.x + wall, wallY0, playerPos.z - s,  uv0, uv0 + span,
            playerPos.x + wall, wallY0, playerPos.z + s,  uv1, uv0 + span,
            playerPos.x + wall, wallY1, playerPos.z + s,  uv1, uv0,
            playerPos.x + wall, wallY1, playerPos.z - s,  uv0, uv0,
        }, starsTex, tint);
    }

    private void renderClouds(Vector3f playerPos, float totalTime, float daylight, float cloudiness) {
        float s = CLOUD_SIZE;
        float y = CLOUD_HEIGHT;
        float scrollX = (playerPos.x - s + totalTime * (1.5f + cloudiness * 2.5f)) / 384f;
        float scrollZ = (playerPos.z - s) / 384f;
        float repeat = 2f * s / 384f;
        // Тучи гуще и темнее ясных облаков: в бурю небо низкое и свинцовое.
        // В метель их не видно вовсе — мгла закрывает небо целиком, и тёмные
        // пятна облаков поверх белой мглы выглядели бы дырами в ней.
        // В ясную погоду облака редкие и полупрозрачные: ясная ночь — это
        // звёзды и сияние, а не сплошная крышка из облачных плит.
        float alpha = (0.34f + 0.64f * cloudiness) * (1f - Math.min(1f, haze * 1.1f));
        float dark = 1f - 0.45f * cloudiness;
        Vector4f tint = new Vector4f(
                (0.20f + 0.80f * daylight) * dark,
                (0.23f + 0.77f * daylight) * dark,
                (0.30f + 0.70f * daylight) * dark,
                alpha);
        float[] v = {
            playerPos.x - s, y, playerPos.z - s,  scrollX, scrollZ,
            playerPos.x + s, y, playerPos.z - s,  scrollX + repeat, scrollZ,
            playerPos.x + s, y, playerPos.z + s,  scrollX + repeat, scrollZ + repeat,
            playerPos.x - s, y, playerPos.z + s,  scrollX, scrollZ + repeat,
        };
        renderQuad(v, cloudsTex, tint);
    }

    private void renderQuad(float[] vertices, int texId, Vector4f color) {
        shader.setFloat("uEmissive", emissive);
        shader.setFloat("uLinearOut", linearOut);
        shader.setFloat("uPhase", phase);
        FloatBuffer fb = MemoryUtil.memAllocFloat(vertices.length);
        fb.put(vertices).flip();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, fb);
        MemoryUtil.memFree(fb);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texId);
        shader.setVec4("uColor", color);

        glBindVertexArray(vao);
        glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0);
        glBindVertexArray(0);
    }

    public void destroy() {
        domeShader.destroy();
        glDeleteVertexArrays(domeVao);
        shader.destroy();
        glDeleteTextures(sunTex);
        glDeleteTextures(moonTex);
        glDeleteTextures(starsTex);
        glDeleteTextures(cloudsTex);
        glDeleteBuffers(vbo);
        glDeleteBuffers(ebo);
        glDeleteVertexArrays(vao);
    }

    // -------------------------------------------------------------------------
    //  Sprite loading / generation
    // -------------------------------------------------------------------------

    @FunctionalInterface
    private interface Drawer { void draw(BufferedImage img); }

    private static int loadOrGenerate(String name, Drawer drawer) {
        return loadOrGenerate(name, drawer, false);
    }

    private static int loadOrGenerate(String name, Drawer drawer, boolean repeat) {
        AppPaths.file(TEX_DIR).mkdirs();
        File file = new File(AppPaths.file(TEX_DIR), name);
        BufferedImage img = null;
        if (file.exists()) {
            try { img = ImageIO.read(file); } catch (IOException ignored) {}
        }
        if (img == null) {
            int size = repeat ? SKY_SZ : SPRITE_SZ;
            img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            drawer.draw(img);
            try {
                ImageIO.write(img, "png", file);
                System.out.println("Generated sky sprite: " + file.getPath());
            } catch (IOException e) {
                System.err.println("Failed to save " + file + ": " + e.getMessage());
            }
        }
        return uploadTexture(img, repeat);
    }

    private static int uploadTexture(BufferedImage img, boolean repeat) {
        int w = img.getWidth(), h = img.getHeight();
        ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                buf.put((byte)((argb >> 16) & 0xFF));
                buf.put((byte)((argb >>  8) & 0xFF));
                buf.put((byte)( argb        & 0xFF));
                buf.put((byte)((argb >> 24) & 0xFF));
            }
        buf.flip();
        int id = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, id);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, repeat ? GL_REPEAT : GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, repeat ? GL_REPEAT : GL_CLAMP_TO_EDGE);
        return id;
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    // -------------------------------------------------------------------------
    //  Procedural sprite drawers  (64×64 ARGB)
    // -------------------------------------------------------------------------

    /** Minecraft-style sun: warm yellow square, white-hot centre, pixelated look. */
    private static void drawSun(BufferedImage img) {
        int s = img.getWidth();
        int ps = s / 16; // each "Minecraft pixel" = 4 real pixels (16x16 -> 64x64)
        for (int py = 0; py < 16; py++) {
            for (int px = 0; px < 16; px++) {
                float cx = px - 7.5f, cy = py - 7.5f;
                float dist = Math.max(Math.abs(cx), Math.abs(cy)); // Chebyshev
                int argb;
                if (dist <= 2f) {
                    argb = 0xFFFFFFDD; // hot white-yellow core
                } else if (dist <= 5f) {
                    float t = (dist - 2f) / 3f;
                    int R = 255, G = (int)(255 - 30 * t), B = (int)(80 - 70 * t);
                    argb = (255 << 24) | (R << 16) | (G << 8) | B;
                } else if (dist <= 7.5f) {
                    float t = (dist - 5f) / 2.5f;
                    int R = 255, G = (int)(225 - 25 * t), B = 10;
                    argb = (255 << 24) | (R << 16) | (G << 8) | B;
                } else {
                    argb = 0; // transparent corner
                }
                for (int dy = 0; dy < ps; dy++)
                    for (int dx = 0; dx < ps; dx++)
                        img.setRGB(px * ps + dx, py * ps + dy, argb);
            }
        }
    }

    /** Square lunar disc, with coarse crater clusters and no soft halo. */
    private static void drawMoon(BufferedImage img) {
        int s = img.getWidth();
        for (int y = 0; y < s; y++)
            for (int x = 0; x < s; x++) {
                int px = x * 16 / s, py = y * 16 / s;
                int shade = 235;
                if ((px >= 3 && px <= 6 && py >= 4 && py <= 7)
                        || (px >= 9 && px <= 12 && py >= 9 && py <= 12))
                    shade = 170;
                else if (px < 2 || py < 2 || px > 13 || py > 13)
                    shade = 205;
                img.setRGB(x, y, 0xff000000 | shade << 16 | shade << 8 | shade);
            }
    }

    private static void drawStars(BufferedImage img) {
        int s = img.getWidth();
        Random rnd = new Random(0x51A7E55L);
        for (int i = 0; i < 100; i++) {
            int x = rnd.nextInt(s);
            int y = rnd.nextInt(s);
            int base = 150 + rnd.nextInt(106);
            int a = 120 + rnd.nextInt(136);
            int color = (a << 24) | (base << 16) | (base << 8) | base;
            img.setRGB(x, y, color);
        }
    }

    /** Wrapped coarse noise gives large connected rectangular cloud patches. */
    private static void drawClouds(BufferedImage img) {
        int cells = 32;
        boolean[][] field = new boolean[cells][cells];
        Random rnd = new Random(0xC10D5L);
        for (int y = 0; y < cells; y++)
            for (int x = 0; x < cells; x++)
                field[x][y] = rnd.nextFloat() < 0.48f;
        for (int pass = 0; pass < 2; pass++) {
            boolean[][] smoothed = new boolean[cells][cells];
            for (int y = 0; y < cells; y++)
                for (int x = 0; x < cells; x++) {
                    int count = 0;
                    for (int dy = -1; dy <= 1; dy++)
                        for (int dx = -1; dx <= 1; dx++)
                            if (field[Math.floorMod(x + dx, cells)][Math.floorMod(y + dy, cells)])
                                count++;
                    smoothed[x][y] = count >= 5;
                }
            field = smoothed;
        }
        int s = img.getWidth();
        for (int y = 0; y < s; y++)
            for (int x = 0; x < s; x++)
                img.setRGB(x, y, field[x * cells / s][y * cells / s] ? 0xffffffff : 0);
    }
}

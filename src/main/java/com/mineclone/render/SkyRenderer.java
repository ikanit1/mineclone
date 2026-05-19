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
    private final int    vao, vbo, ebo;
    private final int    sunTex, moonTex, starsTex, cloudsTex;

    public SkyRenderer() {
        shader  = new Shader(Shaders.SKY_VERTEX, Shaders.SKY_FRAGMENT);
        sunTex  = loadOrGenerate("sun.png",  SkyRenderer::drawSun);
        moonTex = loadOrGenerate("moon.png", SkyRenderer::drawMoon);
        starsTex = loadOrGenerate("stars.png", SkyRenderer::drawStars, true);
        cloudsTex = loadOrGenerate("clouds.png", SkyRenderer::drawClouds, true);

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

    public void render(Matrix4f proj, Matrix4f view, Vector3f playerPos, float gameTime, float daylight, float totalTime) {
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

        if (night > 0.01f) {
            renderStars(playerPos, gameTime, totalTime, night);
        }

        // Sun — visible above horizon, warm tint fades near horizon
        if (sunDir.y > -0.25f) {
            float t = Math.min(1f, (sunDir.y + 0.25f) / 0.35f);
            float r = 1.0f, g = 0.92f + 0.08f * t, b = 0.70f + 0.30f * t;
            Vector3f sunUp = new Vector3f(sunDir).cross(right).normalize();
            renderDisc(playerPos, sunDir, right, sunUp, SUN_SIZE, sunTex,
                       new Vector4f(r, g, b, t));
        }

        // Moon — opposite side, cool pale white
        if (moonDir.y > -0.25f) {
            float t = Math.min(1f, (moonDir.y + 0.25f) / 0.35f) * 0.95f;
            Vector3f moonUp = new Vector3f(moonDir).cross(right).normalize();
            renderDisc(playerPos, moonDir, right, moonUp, MOON_SIZE, moonTex,
                       new Vector4f(0.88f, 0.93f, 1.0f, t));
        }

        renderClouds(playerPos, totalTime, daylight);

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
        float twinkle = 0.92f + 0.08f * (float) Math.sin(totalTime * 0.85f);
        Vector4f tint = new Vector4f(0.78f, 0.84f, 1.0f, night * twinkle);
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

    private void renderClouds(Vector3f playerPos, float totalTime, float daylight) {
        float s = CLOUD_SIZE;
        float y = Math.max(CLOUD_HEIGHT, playerPos.y + 58f);
        float scrollX = totalTime * 0.006f;
        float scrollZ = totalTime * 0.002f;
        float repeat = 4.6f;
        float alpha = 0.22f + daylight * 0.36f;
        Vector4f tint = new Vector4f(
                0.62f + 0.38f * daylight,
                0.68f + 0.32f * daylight,
                0.82f + 0.18f * daylight,
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
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
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

    /** Minecraft-style moon: pale white crescent with slight blue tint. */
    private static void drawMoon(BufferedImage img) {
        int s = img.getWidth();
        float cx = s / 2f - 0.5f, cy = s / 2f - 0.5f, r = s / 2f - 2f;
        float hx = cx + r * 0.32f, hy = cy - r * 0.08f, hr = r * 0.76f;
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
                float d1 = (float) Math.sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy));
                float d2 = (float) Math.sqrt((x - hx) * (x - hx) + (y - hy) * (y - hy));
                if (d1 >= r || d2 < hr) { img.setRGB(x, y, 0); continue; }
                float f = 1f - d1 / r;
                float edge = Math.min(1f, f * 5f);
                int v = (int)(200 + 55f * f);
                int A = (int)(255 * edge);
                img.setRGB(x, y, (A << 24) | (v << 16) | (v << 8) | Math.min(255, v + 20));
            }
        }
    }

    private static void drawStars(BufferedImage img) {
        int s = img.getWidth();
        Random rnd = new Random(0x51A7E55L);
        for (int i = 0; i < 210; i++) {
            int x = rnd.nextInt(s);
            int y = rnd.nextInt(s);
            int base = 150 + rnd.nextInt(106);
            int a = 120 + rnd.nextInt(136);
            int color = (a << 24) | (base << 16) | (base << 8) | 255;
            img.setRGB(x, y, color);
            if (rnd.nextFloat() < 0.22f) {
                int halo = (a / 3 << 24) | (base << 16) | (base << 8) | 255;
                if (x > 0) img.setRGB(x - 1, y, halo);
                if (x < s - 1) img.setRGB(x + 1, y, halo);
                if (y > 0) img.setRGB(x, y - 1, halo);
                if (y < s - 1) img.setRGB(x, y + 1, halo);
            }
        }
    }

    private static void drawClouds(BufferedImage img) {
        int s = img.getWidth();
        int low = 32;
        float[][] noise = new float[low + 1][low + 1];
        Random rnd = new Random(0xC10D5L);
        for (int y = 0; y <= low; y++) {
            for (int x = 0; x <= low; x++) {
                noise[x][y] = rnd.nextFloat();
            }
        }
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
                float gx = x / (float) s * low;
                float gy = y / (float) s * low;
                int ix = (int) gx;
                int iy = (int) gy;
                float fx = gx - ix;
                float fy = gy - iy;
                float n00 = noise[ix][iy];
                float n10 = noise[ix + 1][iy];
                float n01 = noise[ix][iy + 1];
                float n11 = noise[ix + 1][iy + 1];
                float sx = fx * fx * (3f - 2f * fx);
                float sy = fy * fy * (3f - 2f * fy);
                float n = lerp(lerp(n00, n10, sx), lerp(n01, n11, sx), sy);
                float bands = 0.5f + 0.5f * (float) Math.sin((x * 0.055f) + (y * 0.035f));
                float density = n * 0.75f + bands * 0.25f;
                int a = (int) (255f * clamp((density - 0.43f) / 0.36f));
                if (a < 18) {
                    img.setRGB(x, y, 0);
                    continue;
                }
                int shade = 220 + (int) (35f * clamp((density - 0.55f) / 0.45f));
                img.setRGB(x, y, (a << 24) | (shade << 16) | (shade << 8) | 255);
            }
        }
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}

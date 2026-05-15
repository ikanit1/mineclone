package com.mineclone.render;

import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBImage;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Random;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL13.*;

/**
 * Procedural texture atlas. 16x16 tiles in a 16-tile-wide grid.
 * Tile indices used by BlockType.
 */
public class TextureAtlas {
    public static final int TILE = 16;
    public static final int TILES_PER_ROW = 16;
    public static final int ATLAS_SIZE = TILE * TILES_PER_ROW;

    public static final String DEFAULT_PATH = "assets/atlas.png";

    private final int textureId;

    /** Generates procedurally, or loads from PNG if it exists. Pass regen=true to force regenerate + save. */
    public TextureAtlas(String pngPath, boolean regen) {
        BufferedImage img;
        File file = new File(pngPath);
        if (!regen && file.exists()) {
            img = loadPng(file);
            if (img == null) img = generate();
        } else {
            img = generate();
            savePng(img, file);
        }

        ByteBuffer buffer = imageToRgbaBuffer(img);

        textureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, textureId);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, ATLAS_SIZE, ATLAS_SIZE, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        // No mipmaps: the atlas packs tiles edge-to-edge, so mipmap filtering would
        // bleed neighbouring tiles into each other and leave seams on distant blocks.
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    }

    private static BufferedImage generate() {
        BufferedImage img = new BufferedImage(ATLAS_SIZE, ATLAS_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0, 0, 0, 0));
        g.fillRect(0, 0, ATLAS_SIZE, ATLAS_SIZE);
        g.dispose();

        drawGrassTop(img, 0, 0);
        drawGrassSide(img, 1, 0);
        drawDirt(img, 2, 0);
        drawStone(img, 3, 0);
        drawSand(img, 4, 0);
        drawWoodSide(img, 5, 0);
        drawWoodTop(img, 6, 0);
        drawLeaves(img, 7, 0);
        drawWater(img, 8, 0);
        drawBedrock(img, 9, 0);
        drawCobble(img, 10, 0);
        drawPlanks(img, 11, 0);
        return img;
    }

    private static void savePng(BufferedImage img, File file) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            ImageIO.write(img, "png", file);
            System.out.println("Wrote atlas: " + file.getAbsolutePath());
        } catch (IOException e) {
            System.err.println("Failed to save atlas: " + e.getMessage());
        }
    }

    private static BufferedImage loadPng(File file) {
        // Use stb_image for parity with how shipped assets would be loaded.
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer ch = stack.mallocInt(1);
            STBImage.stbi_set_flip_vertically_on_load(false);
            ByteBuffer pixels = STBImage.stbi_load(file.getAbsolutePath(), w, h, ch, 4);
            if (pixels == null) {
                System.err.println("stbi_load failed: " + STBImage.stbi_failure_reason());
                return null;
            }
            int width = w.get(0), height = h.get(0);
            if (width != ATLAS_SIZE || height != ATLAS_SIZE) {
                System.err.println("Atlas PNG has unexpected size " + width + "x" + height + "; regenerating");
                STBImage.stbi_image_free(pixels);
                return null;
            }
            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int i = (y * width + x) * 4;
                    int r = pixels.get(i)     & 0xFF;
                    int gg = pixels.get(i + 1) & 0xFF;
                    int b = pixels.get(i + 2) & 0xFF;
                    int a = pixels.get(i + 3) & 0xFF;
                    img.setRGB(x, y, (a << 24) | (r << 16) | (gg << 8) | b);
                }
            }
            STBImage.stbi_image_free(pixels);
            return img;
        }
    }

    private static ByteBuffer imageToRgbaBuffer(BufferedImage img) {
        ByteBuffer buffer = BufferUtils.createByteBuffer(ATLAS_SIZE * ATLAS_SIZE * 4);
        for (int y = 0; y < ATLAS_SIZE; y++) {
            for (int x = 0; x < ATLAS_SIZE; x++) {
                int argb = img.getRGB(x, y);
                buffer.put((byte) ((argb >> 16) & 0xFF));
                buffer.put((byte) ((argb >> 8) & 0xFF));
                buffer.put((byte) (argb & 0xFF));
                buffer.put((byte) ((argb >> 24) & 0xFF));
            }
        }
        buffer.flip();
        return buffer;
    }

    public void bind(int unit) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D, textureId);
    }

    public int getTextureId() { return textureId; }

    public void destroy() { glDeleteTextures(textureId); }

    /** UV coords for a tile index. Returns {u0,v0,u1,v1}. */
    public static float[] uv(int tileIndex) {
        int col = tileIndex % TILES_PER_ROW;
        int row = tileIndex / TILES_PER_ROW;
        float u0 = (col * TILE) / (float) ATLAS_SIZE;
        float v0 = (row * TILE) / (float) ATLAS_SIZE;
        float u1 = ((col + 1) * TILE) / (float) ATLAS_SIZE;
        float v1 = ((row + 1) * TILE) / (float) ATLAS_SIZE;
        // small inset to prevent bleed
        float inset = 0.5f / ATLAS_SIZE;
        return new float[]{u0 + inset, v0 + inset, u1 - inset, v1 - inset};
    }

    // -------- procedural drawers --------

    private static void put(BufferedImage img, int tileX, int tileY, int x, int y, int rgb) {
        img.setRGB(tileX * TILE + x, tileY * TILE + y, rgb);
    }

    private static int rgb(int r, int g, int b) {
        return 0xFF000000 | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    private static int jitter(Random r, int base, int spread) {
        int v = base + r.nextInt(spread * 2 + 1) - spread;
        return Math.max(0, Math.min(255, v));
    }

    private static void drawGrassTop(BufferedImage img, int tx, int ty) {
        Random r = new Random(11);
        for (int y = 0; y < TILE; y++)
            for (int x = 0; x < TILE; x++) {
                int g = jitter(r, 130, 30);
                int rd = jitter(r, 70, 15);
                int b = jitter(r, 50, 15);
                put(img, tx, ty, x, y, rgb(rd, g, b));
            }
    }

    private static void drawDirt(BufferedImage img, int tx, int ty) {
        Random r = new Random(22);
        for (int y = 0; y < TILE; y++)
            for (int x = 0; x < TILE; x++) {
                int rd = jitter(r, 120, 25);
                int g = jitter(r, 85, 20);
                int b = jitter(r, 55, 15);
                put(img, tx, ty, x, y, rgb(rd, g, b));
            }
    }

    private static void drawGrassSide(BufferedImage img, int tx, int ty) {
        Random rd = new Random(33);
        Random rg = new Random(11);
        for (int y = 0; y < TILE; y++)
            for (int x = 0; x < TILE; x++) {
                int color;
                if (y < 3 || (y < 5 && rd.nextInt(3) == 0)) {
                    int gg = 130 + rg.nextInt(30);
                    int rr = 60 + rg.nextInt(20);
                    int bb = 45 + rg.nextInt(15);
                    color = rgb(rr, gg, bb);
                } else {
                    int rr = 110 + rd.nextInt(30);
                    int gg = 80 + rd.nextInt(20);
                    int bb = 50 + rd.nextInt(15);
                    color = rgb(rr, gg, bb);
                }
                put(img, tx, ty, x, y, color);
            }
    }

    private static void drawStone(BufferedImage img, int tx, int ty) {
        Random r = new Random(44);
        for (int y = 0; y < TILE; y++)
            for (int x = 0; x < TILE; x++) {
                int v = jitter(r, 120, 25);
                put(img, tx, ty, x, y, rgb(v, v, v));
            }
    }

    private static void drawSand(BufferedImage img, int tx, int ty) {
        Random r = new Random(55);
        for (int y = 0; y < TILE; y++)
            for (int x = 0; x < TILE; x++) {
                int rd = jitter(r, 220, 20);
                int g = jitter(r, 200, 20);
                int b = jitter(r, 140, 15);
                put(img, tx, ty, x, y, rgb(rd, g, b));
            }
    }

    private static void drawWoodSide(BufferedImage img, int tx, int ty) {
        Random r = new Random(66);
        for (int y = 0; y < TILE; y++)
            for (int x = 0; x < TILE; x++) {
                int base = ((x + 1) % 4 == 0) ? 70 : 110;
                int v = jitter(r, base, 15);
                put(img, tx, ty, x, y, rgb(v, (int) (v * 0.7), (int) (v * 0.4)));
            }
    }

    private static void drawWoodTop(BufferedImage img, int tx, int ty) {
        Random r = new Random(77);
        int cx = TILE / 2, cy = TILE / 2;
        for (int y = 0; y < TILE; y++)
            for (int x = 0; x < TILE; x++) {
                double d = Math.sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy));
                int ring = ((int) d) % 3 == 0 ? 80 : 140;
                int v = jitter(r, ring, 12);
                put(img, tx, ty, x, y, rgb(v, (int) (v * 0.7), (int) (v * 0.4)));
            }
    }

    private static void drawLeaves(BufferedImage img, int tx, int ty) {
        Random r = new Random(88);
        for (int y = 0; y < TILE; y++)
            for (int x = 0; x < TILE; x++) {
                int rd = jitter(r, 45, 20);
                int g = jitter(r, 120, 35);
                int b = jitter(r, 40, 15);
                int alpha = r.nextInt(8) == 0 ? 0 : 255; // tiny holes
                int argb = (alpha << 24) | (rd << 16) | (g << 8) | b;
                img.setRGB(tx * TILE + x, ty * TILE + y, argb);
            }
    }

    private static void drawWater(BufferedImage img, int tx, int ty) {
        Random r = new Random(99);
        for (int y = 0; y < TILE; y++)
            for (int x = 0; x < TILE; x++) {
                int rd = jitter(r, 40, 10);
                int g = jitter(r, 90, 15);
                int b = jitter(r, 200, 20);
                int argb = (200 << 24) | (rd << 16) | (g << 8) | b;
                img.setRGB(tx * TILE + x, ty * TILE + y, argb);
            }
    }

    private static void drawBedrock(BufferedImage img, int tx, int ty) {
        Random r = new Random(111);
        for (int y = 0; y < TILE; y++)
            for (int x = 0; x < TILE; x++) {
                int v = jitter(r, 50, 25);
                put(img, tx, ty, x, y, rgb(v, v, v));
            }
    }

    private static void drawCobble(BufferedImage img, int tx, int ty) {
        Random r = new Random(122);
        for (int y = 0; y < TILE; y++)
            for (int x = 0; x < TILE; x++) {
                boolean edge = (x % 4 == 0) || (y % 4 == 0);
                int v = edge ? 80 : jitter(r, 130, 20);
                put(img, tx, ty, x, y, rgb(v, v, v));
            }
    }

    private static void drawPlanks(BufferedImage img, int tx, int ty) {
        Random r = new Random(133);
        for (int y = 0; y < TILE; y++)
            for (int x = 0; x < TILE; x++) {
                boolean line = (y % 4 == 0);
                int base = line ? 80 : 160;
                int v = jitter(r, base, 10);
                put(img, tx, ty, x, y, rgb(v, (int) (v * 0.75), (int) (v * 0.45)));
            }
    }
}

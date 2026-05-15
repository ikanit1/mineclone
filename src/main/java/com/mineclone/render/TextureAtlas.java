package com.mineclone.render;

import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBImage;

import javax.imageio.ImageIO;
import java.awt.*;
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
 * Texture atlas assembled from individual PNG sprites.
 *
 * Block sprites live in {@value #BLOCKS_DIR} as 16×16 PNG files named by
 * {@link #TILE_NAMES}[index]. On first run (or when a sprite is missing) the
 * procedural fallback generates and saves the PNG so artists can override it.
 *
 * Entity / mob textures use a separate atlas — see EntityTextureAtlas (future).
 */
public class TextureAtlas {
    public static final int TILE = 16;
    public static final int TILES_PER_ROW = 16;
    public static final int ATLAS_SIZE = TILE * TILES_PER_ROW;
    public static final String DEFAULT_PATH = "assets/atlas.png";
    public static final String BLOCKS_DIR = "assets/textures/blocks";

    /**
     * Ordered tile registry. A tile's position in this array IS its tile index
     * (used by BlockType.sideTile / topTile / bottomTile).
     * To add a new block texture: append an entry here, drop a matching PNG in
     * BLOCKS_DIR, and reference the new index in BlockType.
     */
    public static final String[] TILE_NAMES = {
            "grass_top", // 0
            "grass_side", // 1
            "dirt", // 2
            "stone", // 3
            "sand", // 4
            "log_side", // 5
            "log_top", // 6
            "leaves", // 7
            "water", // 8
            "bedrock", // 9
            "cobblestone", // 10
            "planks", // 11
            "torch", // 12
            "particle", // 13 – solid white circle for coloured flame/smoke particles
            "glass", // 14
            "door",     // 15
            "door_top", // 16
            // Water flow animation frames 0..15 — cycled at runtime by the shader.
            "water_flow_00", "water_flow_01", "water_flow_02", "water_flow_03",
            "water_flow_04", "water_flow_05", "water_flow_06", "water_flow_07",
            "water_flow_08", "water_flow_09", "water_flow_10", "water_flow_11",
            "water_flow_12", "water_flow_13", "water_flow_14", "water_flow_15",
    };

    /** First tile index of the water_flow animation strip (16 frames). */
    public static final int WATER_FLOW_FRAME0 = 17;
    public static final int WATER_FLOW_FRAMES = 16;

    private final int textureId;

    /**
     * Loads or assembles the atlas.
     * 
     * @param pngPath path to the cached full atlas PNG
     * @param regen   if true, regenerate all procedural tile PNGs and the atlas
     *                cache
     */
    public TextureAtlas(String pngPath, boolean regen) {
        // Always assemble from individual tile PNGs so newly added tiles are always included.
        // Each tile PNG is cached in BLOCKS_DIR; only missing ones are regenerated via generateTile().
        BufferedImage img = assemble(regen);
        savePng(img, new File(pngPath));

        ByteBuffer buffer = imageToRgbaBuffer(img);
        textureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, textureId);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, ATLAS_SIZE, ATLAS_SIZE, 0,
                GL_RGBA, GL_UNSIGNED_BYTE, buffer);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    }

    // -------------------------------------------------------------------------
    // Atlas assembly
    // -------------------------------------------------------------------------

    private static BufferedImage assemble(boolean regen) {
        new File(BLOCKS_DIR).mkdirs();

        BufferedImage atlas = new BufferedImage(ATLAS_SIZE, ATLAS_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = atlas.createGraphics();
        g.setColor(new Color(0, 0, 0, 0));
        g.fillRect(0, 0, ATLAS_SIZE, ATLAS_SIZE);
        g.dispose();

        for (int i = 0; i < TILE_NAMES.length; i++) {
            File tileFile = new File(BLOCKS_DIR, TILE_NAMES[i] + ".png");
            BufferedImage tile = null;
            if (!regen && tileFile.exists()) {
                tile = loadTile(tileFile);
            }
            if (tile == null) {
                tile = generateTile(i);
                savePng(tile, tileFile);
                System.out.println("Generated sprite: " + tileFile.getPath());
            }
            blitTile(atlas, tile, i);
        }
        return atlas;
    }

    /** Copy a 16×16 tile into the correct cell of the atlas image. */
    private static void blitTile(BufferedImage atlas, BufferedImage tile, int idx) {
        int col = idx % TILES_PER_ROW;
        int row = idx / TILES_PER_ROW;
        for (int y = 0; y < TILE; y++)
            for (int x = 0; x < TILE; x++)
                atlas.setRGB(col * TILE + x, row * TILE + y, tile.getRGB(x, y));
    }

    /** Load an individual tile PNG and ensure it is TILE×TILE ARGB. */
    private static BufferedImage loadTile(File f) {
        try {
            BufferedImage raw = ImageIO.read(f);
            if (raw == null)
                return null;
            if (raw.getWidth() == TILE && raw.getHeight() == TILE
                    && raw.getType() == BufferedImage.TYPE_INT_ARGB) {
                return raw;
            }
            // Scale / convert to canonical format
            BufferedImage tile = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = tile.createGraphics();
            g.drawImage(raw, 0, 0, TILE, TILE, null);
            g.dispose();
            return tile;
        } catch (IOException e) {
            System.err.println("Failed to load tile " + f + ": " + e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Procedural tile generator
    // -------------------------------------------------------------------------

    private static BufferedImage generateTile(int index) {
        BufferedImage t = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_ARGB);
        switch (index) {
            case 0 -> drawGrassTop(t);
            case 1 -> drawGrassSide(t);
            case 2 -> drawDirt(t);
            case 3 -> drawStone(t);
            case 4 -> drawSand(t);
            case 5 -> drawWoodSide(t);
            case 6 -> drawWoodTop(t);
            case 7 -> drawLeaves(t);
            case 8 -> drawWater(t);
            case 9 -> drawBedrock(t);
            case 10 -> drawCobble(t);
            case 11 -> drawPlanks(t);
            case 12 -> drawTorch(t);
            case 13 -> drawParticle(t);
            case 14 -> drawGlass(t);
            case 15 -> drawDoorBottom(t);
            case 16 -> drawDoorTop(t);
            default -> {
                if (index >= WATER_FLOW_FRAME0 && index < WATER_FLOW_FRAME0 + WATER_FLOW_FRAMES) {
                    drawWaterFlowFrame(t, index - WATER_FLOW_FRAME0);
                }
                /* else leave transparent */
            }
        }
        return t;
    }

    // -------------------------------------------------------------------------
    // IO helpers
    // -------------------------------------------------------------------------

    private static void savePng(BufferedImage img, File file) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists())
                parent.mkdirs();
            ImageIO.write(img, "png", file);
        } catch (IOException e) {
            System.err.println("Failed to save " + file + ": " + e.getMessage());
        }
    }

    /** Load the big cached atlas PNG via STBImage (same path as before). */
    private static BufferedImage loadAtlasPng(File file) {
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1), ch = stack.mallocInt(1);
            STBImage.stbi_set_flip_vertically_on_load(false);
            ByteBuffer pixels = STBImage.stbi_load(file.getAbsolutePath(), w, h, ch, 4);
            if (pixels == null)
                return null;
            int width = w.get(0), height = h.get(0);
            if (width != ATLAS_SIZE || height != ATLAS_SIZE) {
                System.err.println("Atlas PNG size mismatch; regenerating");
                STBImage.stbi_image_free(pixels);
                return null;
            }
            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < height; y++)
                for (int x = 0; x < width; x++) {
                    int i = (y * width + x) * 4;
                    int r = pixels.get(i) & 0xFF, g = pixels.get(i + 1) & 0xFF,
                            b = pixels.get(i + 2) & 0xFF, a = pixels.get(i + 3) & 0xFF;
                    img.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
                }
            STBImage.stbi_image_free(pixels);
            return img;
        }
    }

    private static ByteBuffer imageToRgbaBuffer(BufferedImage img) {
        ByteBuffer buf = BufferUtils.createByteBuffer(ATLAS_SIZE * ATLAS_SIZE * 4);
        for (int y = 0; y < ATLAS_SIZE; y++)
            for (int x = 0; x < ATLAS_SIZE; x++) {
                int argb = img.getRGB(x, y);
                buf.put((byte) ((argb >> 16) & 0xFF));
                buf.put((byte) ((argb >> 8) & 0xFF));
                buf.put((byte) (argb & 0xFF));
                buf.put((byte) ((argb >> 24) & 0xFF));
            }
        buf.flip();
        return buf;
    }

    // -------------------------------------------------------------------------
    // Public GL API
    // -------------------------------------------------------------------------

    public void bind(int unit) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D, textureId);
    }

    public int getTextureId() {
        return textureId;
    }

    public void destroy() {
        glDeleteTextures(textureId);
    }

    /** UV coordinates for a tile index. Returns {u0, v0, u1, v1}. */
    public static float[] uv(int tileIndex) {
        int col = tileIndex % TILES_PER_ROW;
        int row = tileIndex / TILES_PER_ROW;
        float u0 = (col * TILE) / (float) ATLAS_SIZE;
        float v0 = (row * TILE) / (float) ATLAS_SIZE;
        float u1 = ((col + 1) * TILE) / (float) ATLAS_SIZE;
        float v1 = ((row + 1) * TILE) / (float) ATLAS_SIZE;
        float inset = 0.5f / ATLAS_SIZE;
        return new float[] { u0 + inset, v0 + inset, u1 - inset, v1 - inset };
    }

    // -------------------------------------------------------------------------
    // Procedural sprite drawers
    // Each method fills a 16×16 BufferedImage (TYPE_INT_ARGB).
    // -------------------------------------------------------------------------

    private static void px(BufferedImage t, int x, int y, int argb) {
        t.setRGB(x, y, argb);
    }

    private static int rgb(int r, int g, int b) {
        return 0xFF000000 | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    private static int jitter(Random r, int base, int spread) {
        int v = base + r.nextInt(spread * 2 + 1) - spread;
        return Math.max(0, Math.min(255, v));
    }

    private static void drawGrassTop(BufferedImage t) {
        Random r = new Random(11);
        for (int y = 0; y < TILE; y++)
            for (int x = 0; x < TILE; x++)
                px(t, x, y, rgb(jitter(r, 70, 15), jitter(r, 130, 30), jitter(r, 50, 15)));
    }

    private static void drawGrassSide(BufferedImage t) {
        Random rd = new Random(33), rg = new Random(11);
        for (int y = 0; y < TILE; y++)
            for (int x = 0; x < TILE; x++) {
                if (y < 3 || (y < 5 && rd.nextInt(3) == 0))
                    px(t, x, y, rgb(60 + rg.nextInt(20), 130 + rg.nextInt(30), 45 + rg.nextInt(15)));
                else
                    px(t, x, y, rgb(110 + rd.nextInt(30), 80 + rd.nextInt(20), 50 + rd.nextInt(15)));
            }
    }

    private static void drawDirt(BufferedImage t) {
        Random r = new Random(22);
        for (int y = 0; y < TILE; y++)
            for (int x = 0; x < TILE; x++)
                px(t, x, y, rgb(jitter(r, 120, 25), jitter(r, 85, 20), jitter(r, 55, 15)));
    }

    private static void drawStone(BufferedImage t) {
        Random r = new Random(44);
        for (int y = 0; y < TILE; y++)
            for (int x = 0; x < TILE; x++) {
                int v = jitter(r, 120, 25);
                px(t, x, y, rgb(v, v, v));
            }
    }

    private static void drawSand(BufferedImage t) {
        Random r = new Random(55);
        for (int y = 0; y < TILE; y++)
            for (int x = 0; x < TILE; x++)
                px(t, x, y, rgb(jitter(r, 220, 20), jitter(r, 200, 20), jitter(r, 140, 15)));
    }

    private static void drawWoodSide(BufferedImage t) {
        Random r = new Random(66);
        for (int y = 0; y < TILE; y++)
            for (int x = 0; x < TILE; x++) {
                int base = ((x + 1) % 4 == 0) ? 70 : 110;
                int v = jitter(r, base, 15);
                px(t, x, y, rgb(v, (int) (v * 0.7), (int) (v * 0.4)));
            }
    }

    private static void drawWoodTop(BufferedImage t) {
        Random r = new Random(77);
        int cx = TILE / 2, cy = TILE / 2;
        for (int y = 0; y < TILE; y++)
            for (int x = 0; x < TILE; x++) {
                double d = Math.sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy));
                int ring = ((int) d) % 3 == 0 ? 80 : 140;
                int v = jitter(r, ring, 12);
                px(t, x, y, rgb(v, (int) (v * 0.7), (int) (v * 0.4)));
            }
    }

    private static void drawLeaves(BufferedImage t) {
        Random r = new Random(88);
        for (int y = 0; y < TILE; y++)
            for (int x = 0; x < TILE; x++) {
                int alpha = r.nextInt(8) == 0 ? 0 : 255;
                int argb = (alpha << 24)
                        | (jitter(r, 45, 20) << 16)
                        | (jitter(r, 120, 35) << 8)
                        | jitter(r, 40, 15);
                px(t, x, y, argb);
            }
    }

    private static void drawWater(BufferedImage t) {
        Random r = new Random(99);
        for (int y = 0; y < TILE; y++)
            for (int x = 0; x < TILE; x++) {
                int argb = (200 << 24)
                        | (jitter(r, 40, 10) << 16)
                        | (jitter(r, 90, 15) << 8)
                        | jitter(r, 200, 20);
                px(t, x, y, argb);
            }
    }

    /**
     * One frame of the water-flow animation. Three sine layers in (x,y,phase)
     * with phase = frameIdx/WATER_FLOW_FRAMES * 2π give a seamlessly looping
     * ripple. Per-pixel noise is reseeded each frame to keep the base texture
     * stable across the loop so it reads as moving water, not static.
     */
    private static void drawWaterFlowFrame(BufferedImage t, int frameIdx) {
        Random noise = new Random(99);
        float phase = (frameIdx / (float) WATER_FLOW_FRAMES) * (float) (Math.PI * 2);
        for (int y = 0; y < TILE; y++) {
            for (int x = 0; x < TILE; x++) {
                double a = Math.sin(x * 0.55 + phase * 1.0);
                double b = Math.sin(y * 0.40 + phase * 1.3 + 1.0);
                double c = Math.sin((x + y * 0.5) * 0.35 + phase * 0.7);
                double intensity = (a + b + c) / 3.0; // -1..1
                double bright = 0.5 + intensity * 0.30; // ~0.2..0.8

                int rNoise = noise.nextInt(13) - 6;
                int gNoise = noise.nextInt(13) - 6;
                int bNoise = noise.nextInt(13) - 6;

                int red = clamp255((int) (35 + bright * 25) + rNoise);
                int grn = clamp255((int) (80 + bright * 35) + gNoise);
                int blu = clamp255((int) (175 + bright * 45) + bNoise);
                int argb = (200 << 24) | (red << 16) | (grn << 8) | blu;
                px(t, x, y, argb);
            }
        }
    }

    private static int clamp255(int v) { return Math.max(0, Math.min(255, v)); }

    private static void drawBedrock(BufferedImage t) {
        Random r = new Random(111);
        for (int y = 0; y < TILE; y++)
            for (int x = 0; x < TILE; x++) {
                int v = jitter(r, 50, 25);
                px(t, x, y, rgb(v, v, v));
            }
    }

    private static void drawCobble(BufferedImage t) {
        Random r = new Random(122);
        for (int y = 0; y < TILE; y++)
            for (int x = 0; x < TILE; x++) {
                boolean edge = (x % 4 == 0) || (y % 4 == 0);
                int v = edge ? 80 : jitter(r, 130, 20);
                px(t, x, y, rgb(v, v, v));
            }
    }

    private static void drawPlanks(BufferedImage t) {
        Random r = new Random(133);
        for (int y = 0; y < TILE; y++)
            for (int x = 0; x < TILE; x++) {
                int base = (y % 4 == 0) ? 80 : 160;
                int v = jitter(r, base, 10);
                px(t, x, y, rgb(v, (int) (v * 0.75), (int) (v * 0.45)));
            }
    }

    private static void drawParticle(BufferedImage t) {
        // Soft white circle — tinted at runtime via uColor.rgb in the particle shader
        float cx = TILE / 2f - 0.5f, cy = TILE / 2f - 0.5f, r = TILE / 2f - 0.5f;
        for (int y = 0; y < TILE; y++)
            for (int x = 0; x < TILE; x++) {
                float d = (float) Math.sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy));
                int a = d >= r ? 0 : (int) (255 * Math.max(0f, 1f - d / r));
                px(t, x, y, (a << 24) | 0xFFFFFF);
            }
    }

    private static void drawTorch(BufferedImage t) {
        // Transparent background — only stick and flame are opaque
        for (int y = 0; y < TILE; y++)
            for (int x = 0; x < TILE; x++)
                px(t, x, y, 0x00000000);
        // Stick: 2px wide centered column
        int sx = TILE / 2 - 1;
        for (int y = 4; y < TILE; y++) {
            px(t, sx, y, 0xFF5A3A1C);
            px(t, sx + 1, y, 0xFF482E16);
        }
        // Flame: 4-row gradient
        int[] flame = { 0xFFFFE650, 0xFFFFB41E, 0xFFDC640A, 0xFFA03205 };
        for (int row = 0; row < 4; row++)
            for (int col = sx - 1; col <= sx + 2; col++)
                if (col >= 0 && col < TILE)
                    px(t, col, row, flame[row]);
    }

    private static void drawGlass(BufferedImage t) {
        // Alpha=0 interior so the cutout shader (discard if alpha<0.1) makes glass see-through
        int border = 0xFF_B8D4E8; // opaque blue-white frame
        for (int y = 0; y < TILE; y++)
            for (int x = 0; x < TILE; x++) {
                boolean edge = x == 0 || x == TILE - 1 || y == 0 || y == TILE - 1;
                px(t, x, y, edge ? border : 0x00000000);
            }
    }

    /** Shared door panel drawing — fills the tile with wood grain and frame. */
    private static void drawDoorPanel(BufferedImage t, Random r,
            boolean topBorder, boolean bottomBorder, boolean midBar,
            boolean hasHandle) {
        int frame  = rgb(55, 36, 18);
        int shadow = rgb(70, 48, 24);
        int light  = rgb(185, 138, 82);

        // Wood grain fill
        for (int y = 0; y < TILE; y++)
            for (int x = 0; x < TILE; x++) {
                int base = (x % 3 == 0) ? 90 : 148;
                int v = jitter(r, base, 12);
                px(t, x, y, rgb(v, (int)(v * 0.72), (int)(v * 0.42)));
            }

        // Outer frame borders
        if (topBorder) {
            for (int x = 0; x < TILE; x++) { px(t, x, 0, frame); px(t, x, 1, frame); }
        }
        if (bottomBorder) {
            for (int x = 0; x < TILE; x++) { px(t, x, TILE-1, frame); px(t, x, TILE-2, frame); }
        }
        // Middle horizontal bar (at top or bottom of tile, shared between halves)
        if (midBar) {
            int my = bottomBorder ? TILE - 4 : 2; // bar near opposite end from border
            if (!topBorder && !bottomBorder) my = TILE / 2 - 1;
            for (int x = 0; x < TILE; x++) { px(t, x, my, frame); px(t, x, my + 1, frame); }
        }
        // Side frame
        for (int y = 0; y < TILE; y++) {
            px(t, 0, y, frame); px(t, 1, y, frame);
            px(t, TILE-1, y, frame); px(t, TILE-2, y, frame);
        }
        // Center vertical bar dividing two sub-panels
        for (int y = 0; y < TILE; y++) {
            px(t, 7, y, frame); px(t, 8, y, frame);
        }
        // Panel inset shadow edges (simulate depth)
        for (int y = 2; y < TILE - 2; y++) {
            px(t, 2, y, shadow); px(t, TILE-3, y, shadow);
        }
        for (int x = 2; x < TILE - 2; x++) {
            if (topBorder    || x < 7) px(t, x, 2, shadow);
            if (bottomBorder || x < 7) px(t, x, TILE-3, shadow);
        }
        // Panel inset highlights
        for (int y = 3; y < TILE - 3; y++) {
            px(t, 3, y, light); px(t, TILE-4, y, light);
        }

        // Handle (golden knob) on bottom half, right sub-panel
        if (hasHandle) {
            int hx = 12, hy = 6;
            px(t, hx, hy,   rgb(210, 175, 45));
            px(t, hx, hy+1, rgb(210, 175, 45));
            px(t, hx, hy+2, rgb(180, 145, 30));
        }
    }

    /** Tile 15 — bottom half of door (has handle, top is the mid-bar joint). */
    private static void drawDoorBottom(BufferedImage t) {
        drawDoorPanel(t, new Random(201), false, true, true, true);
        // Reinforce top edge as mid-bar (joins top tile)
        int frame = rgb(55, 36, 18);
        for (int x = 0; x < TILE; x++) { px(t, x, 0, frame); px(t, x, 1, frame); }
    }

    /** Tile 16 — top half of door (no handle, bottom is the mid-bar joint). */
    private static void drawDoorTop(BufferedImage t) {
        drawDoorPanel(t, new Random(202), true, false, true, false);
        // Reinforce bottom edge as mid-bar (joins bottom tile)
        int frame = rgb(55, 36, 18);
        for (int x = 0; x < TILE; x++) { px(t, x, TILE-1, frame); px(t, x, TILE-2, frame); }
    }
}

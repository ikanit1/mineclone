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

    /** Top of grass block — green base with brighter blade tips and shaded patches. */
    private static void drawGrassTop(BufferedImage t) {
        Random r = new Random(11);
        // base green field with slight per-pixel noise
        for (int y = 0; y < TILE; y++)
            for (int x = 0; x < TILE; x++) {
                px(t, x, y, rgb(jitter(r, 72, 8), jitter(r, 138, 14), jitter(r, 48, 8)));
            }
        // ~22 bright blade tips
        Random tip = new Random(101);
        for (int i = 0; i < 22; i++) {
            int x = tip.nextInt(TILE), y = tip.nextInt(TILE);
            px(t, x, y, rgb(105 + tip.nextInt(20), 175 + tip.nextInt(25), 65 + tip.nextInt(15)));
        }
        // ~14 darker shadow patches
        Random dk = new Random(202);
        for (int i = 0; i < 14; i++) {
            int x = dk.nextInt(TILE), y = dk.nextInt(TILE);
            px(t, x, y, rgb(48 + dk.nextInt(12), 100 + dk.nextInt(15), 32 + dk.nextInt(10)));
        }
    }

    /** Side of grass block — dirt body with a jagged grass overhang on top. */
    private static void drawGrassSide(BufferedImage t) {
        Random rd = new Random(33);
        // dirt body — fills entire tile first
        for (int y = 0; y < TILE; y++)
            for (int x = 0; x < TILE; x++) {
                px(t, x, y, rgb(jitter(rd, 118, 18), jitter(rd, 82, 13), jitter(rd, 52, 10)));
            }
        // pebble specks in dirt
        Random pb = new Random(34);
        for (int i = 0; i < 10; i++) {
            int x = pb.nextInt(TILE), y = 4 + pb.nextInt(TILE - 4);
            px(t, x, y, rgb(70 + pb.nextInt(15), 50 + pb.nextInt(10), 32 + pb.nextInt(8)));
        }
        // grass overhang — jagged top edge, height varies per column
        Random gr = new Random(35);
        int[] grassHeight = new int[TILE];
        for (int x = 0; x < TILE; x++) grassHeight[x] = 3 + gr.nextInt(3); // 3..5 rows
        for (int x = 0; x < TILE; x++) {
            int h = grassHeight[x];
            for (int y = 0; y < h; y++) {
                int br = (y == h - 1) ? 25 : 0; // brighter tips on the bottom edge of grass
                px(t, x, y, rgb(60 + br + gr.nextInt(15), 130 + br + gr.nextInt(20), 45 + gr.nextInt(12)));
            }
        }
        // a few hanging blade tips one row below the overhang
        Random hb = new Random(36);
        for (int x = 0; x < TILE; x++) {
            if (hb.nextInt(3) == 0) {
                int y = grassHeight[x];
                if (y < TILE) px(t, x, y, rgb(55 + hb.nextInt(15), 125 + hb.nextInt(20), 40 + hb.nextInt(10)));
            }
        }
    }

    /** Plain dirt — warm brown with pebble specks and rare organic darks. */
    private static void drawDirt(BufferedImage t) {
        Random r = new Random(22);
        for (int y = 0; y < TILE; y++)
            for (int x = 0; x < TILE; x++) {
                px(t, x, y, rgb(jitter(r, 118, 16), jitter(r, 82, 12), jitter(r, 52, 10)));
            }
        Random pb = new Random(24);
        for (int i = 0; i < 14; i++) {
            int x = pb.nextInt(TILE), y = pb.nextInt(TILE);
            px(t, x, y, rgb(78 + pb.nextInt(14), 56 + pb.nextInt(10), 36 + pb.nextInt(8)));
        }
        // 3 organic darks
        Random og = new Random(26);
        for (int i = 0; i < 3; i++) {
            int x = og.nextInt(TILE), y = og.nextInt(TILE);
            px(t, x, y, rgb(55, 38, 24));
        }
    }

    /** Stone — gray with occasional darker pebble specks and faint diagonal cracks. */
    private static void drawStone(BufferedImage t) {
        Random r = new Random(44);
        for (int y = 0; y < TILE; y++)
            for (int x = 0; x < TILE; x++) {
                int v = jitter(r, 128, 14);
                px(t, x, y, rgb(v, v, v + 2));
            }
        // dark pebbles (round-ish clusters of 1-2 px)
        Random pb = new Random(46);
        for (int i = 0; i < 12; i++) {
            int x = pb.nextInt(TILE), y = pb.nextInt(TILE);
            int v = 85 + pb.nextInt(10);
            px(t, x, y, rgb(v, v, v));
            if (pb.nextBoolean() && x + 1 < TILE) px(t, x + 1, y, rgb(v + 5, v + 5, v + 5));
        }
        // 2 subtle hairline cracks
        Random cr = new Random(48);
        for (int n = 0; n < 2; n++) {
            int x = cr.nextInt(TILE);
            int y = cr.nextInt(TILE);
            int dx = cr.nextBoolean() ? 1 : -1;
            for (int s = 0; s < 5; s++) {
                if (x >= 0 && x < TILE && y >= 0 && y < TILE) {
                    px(t, x, y, rgb(95, 95, 100));
                }
                if (cr.nextInt(2) == 0) y++;
                x += dx;
            }
        }
    }

    /** Sand — warm tan with subtle horizontal wave ripples and a few darker grains. */
    private static void drawSand(BufferedImage t) {
        Random r = new Random(55);
        for (int y = 0; y < TILE; y++) {
            // subtle horizontal ripple: every few rows brighter, others slightly darker
            int rowOffset = (int) (Math.sin(y * 0.85) * 4);
            for (int x = 0; x < TILE; x++) {
                int rd = jitter(r, 218 + rowOffset, 10);
                int g  = jitter(r, 198 + rowOffset, 10);
                int b  = jitter(r, 140, 8);
                px(t, x, y, rgb(rd, g, b));
            }
        }
        // a few darker grains for visual texture
        Random gr = new Random(57);
        for (int i = 0; i < 8; i++) {
            int x = gr.nextInt(TILE), y = gr.nextInt(TILE);
            px(t, x, y, rgb(185 + gr.nextInt(15), 165 + gr.nextInt(15), 115 + gr.nextInt(12)));
        }
    }

    /** Wood log side — vertical bark grain with a knot and darker grain lines. */
    private static void drawWoodSide(BufferedImage t) {
        Random r = new Random(66);
        // base brown
        for (int y = 0; y < TILE; y++)
            for (int x = 0; x < TILE; x++) {
                int v = jitter(r, 118, 10);
                px(t, x, y, rgb(v, (int) (v * 0.66), (int) (v * 0.38)));
            }
        // dark vertical grain lines at fixed x columns (irregular spacing)
        int[] grainCols = { 1, 4, 7, 11, 13 };
        Random gn = new Random(67);
        for (int gx : grainCols) {
            for (int y = 0; y < TILE; y++) {
                int v = 65 + gn.nextInt(15);
                px(t, gx, y, rgb(v, (int) (v * 0.62), (int) (v * 0.34)));
            }
        }
        // one knot (oval, 3x4) at random position
        Random kn = new Random(68);
        int kx = 5 + kn.nextInt(5), ky = 4 + kn.nextInt(7);
        for (int dy = -1; dy <= 1; dy++)
            for (int dx = -1; dx <= 1; dx++) {
                int x = kx + dx, y = ky + dy;
                if (x < 0 || x >= TILE || y < 0 || y >= TILE) continue;
                int v = (dx == 0 && dy == 0) ? 45 : 70;
                px(t, x, y, rgb(v, (int) (v * 0.55), (int) (v * 0.3)));
            }
    }

    /** Wood log top — concentric tree rings with a darker core. */
    private static void drawWoodTop(BufferedImage t) {
        Random r = new Random(77);
        double cx = TILE / 2.0 - 0.5, cy = TILE / 2.0 - 0.5;
        for (int y = 0; y < TILE; y++)
            for (int x = 0; x < TILE; x++) {
                double d = Math.sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy));
                // 3 rings: dark every 2.5 units of distance
                double rd = d * 0.85;
                int ringIdx = (int) rd;
                boolean ring = (ringIdx % 3) == 0;
                int base = ring ? 80 : 138;
                int v = jitter(r, base, 8);
                px(t, x, y, rgb(v, (int) (v * 0.68), (int) (v * 0.4)));
            }
        // central pith — single dark pixel cluster
        px(t, (int) cx, (int) cy, rgb(48, 32, 18));
        px(t, (int) cx + 1, (int) cy, rgb(55, 38, 22));
        px(t, (int) cx, (int) cy + 1, rgb(55, 38, 22));
    }

    /** Leaves — varied greens with cutout holes and brighter highlights. */
    private static void drawLeaves(BufferedImage t) {
        Random r = new Random(88);
        for (int y = 0; y < TILE; y++)
            for (int x = 0; x < TILE; x++) {
                // ~14% holes (alpha 0)
                int alpha = r.nextInt(7) == 0 ? 0 : 255;
                int rd = jitter(r, 50, 12);
                int g  = jitter(r, 118, 22);
                int b  = jitter(r, 42, 10);
                px(t, x, y, (alpha << 24) | (rd << 16) | (g << 8) | b);
            }
        // brighter leaf highlights (clusters of 1-2 px)
        Random hl = new Random(89);
        for (int i = 0; i < 16; i++) {
            int x = hl.nextInt(TILE), y = hl.nextInt(TILE);
            int rd = 75 + hl.nextInt(20);
            int g  = 155 + hl.nextInt(30);
            int b  = 60 + hl.nextInt(15);
            int prevArgb = t.getRGB(x, y);
            if ((prevArgb >>> 24) == 0) continue; // don't paint holes
            px(t, x, y, rgb(rd, g, b));
        }
        // a few darker shadow leaves
        Random sh = new Random(90);
        for (int i = 0; i < 10; i++) {
            int x = sh.nextInt(TILE), y = sh.nextInt(TILE);
            int prevArgb = t.getRGB(x, y);
            if ((prevArgb >>> 24) == 0) continue;
            px(t, x, y, rgb(30 + sh.nextInt(12), 75 + sh.nextInt(20), 28 + sh.nextInt(8)));
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
     * One frame of the water-flow animation. The pattern is a set of bands
     * along a diagonal axis that SHIFT across the tile each frame, so the
     * loop reads as actual current/flow rather than a static ripple.
     *
     * Math: phase = frameIdx / WATER_FLOW_FRAMES * 2π. The main wave is
     * sin(flow_coord * 2 bands per tile - phase) — subtracting phase means
     * the wave peaks travel in the +flow direction. Two bands per tile
     * means peaks shift by 8 pixels (one band-width) over the 16-frame loop,
     * giving a continuous-looking flow.
     */
    private static void drawWaterFlowFrame(BufferedImage t, int frameIdx) {
        Random noise = new Random(99);
        double phase = (frameIdx / (double) WATER_FLOW_FRAMES) * Math.PI * 2.0;
        double bandFreq = (2.0 * Math.PI) / TILE * 2.0; // 2 bands per tile
        for (int y = 0; y < TILE; y++) {
            for (int x = 0; x < TILE; x++) {
                // Diagonal flow axis — mostly +Y with a slight +X tilt for visual interest
                double flow = y * 0.92 + x * 0.18;
                // Main travelling band — high amplitude
                double main = Math.sin(flow * bandFreq - phase * 2.0);
                // Higher-frequency secondary wave at a slightly different angle
                double cross = (x * 0.18 - y * 0.92);
                double detail = Math.sin(cross * bandFreq * 1.4 - phase * 1.0) * 0.45;
                // Slow drift that breaks the regularity
                double drift = Math.sin((x + y * 0.5) * 0.4 - phase * 0.6) * 0.25;

                double sum = main + detail + drift;
                // tanh sharpens the bands → more visible flow lines, less mushy
                double shaped = Math.tanh(sum * 1.3);
                double bright = 0.5 + 0.50 * shaped; // ~0..1

                int rNoise = noise.nextInt(11) - 5;
                int gNoise = noise.nextInt(11) - 5;
                int bNoise = noise.nextInt(11) - 5;

                // Wider range so dark bands sit much darker than bright crests
                int red = clamp255((int) (25 + bright * 55) + rNoise);
                int grn = clamp255((int) (60 + bright * 80) + gNoise);
                int blu = clamp255((int) (150 + bright * 80) + bNoise);
                int argb = (200 << 24) | (red << 16) | (grn << 8) | blu;
                px(t, x, y, argb);
            }
        }
    }

    private static int clamp255(int v) { return Math.max(0, Math.min(255, v)); }

    /** Bedrock — dark gray with high-contrast jagged rock fragments. */
    private static void drawBedrock(BufferedImage t) {
        Random r = new Random(111);
        for (int y = 0; y < TILE; y++)
            for (int x = 0; x < TILE; x++) {
                int v = jitter(r, 55, 18);
                px(t, x, y, rgb(v, v, v + 1));
            }
        // ~6 chunky lighter fragments (3-4 px clusters)
        Random fg = new Random(113);
        for (int i = 0; i < 6; i++) {
            int x = fg.nextInt(TILE - 2), y = fg.nextInt(TILE - 2);
            int v = 90 + fg.nextInt(20);
            int w = 1 + fg.nextInt(2);
            for (int dy = 0; dy <= w; dy++)
                for (int dx = 0; dx <= w; dx++) {
                    if (x + dx < TILE && y + dy < TILE && fg.nextInt(4) != 0)
                        px(t, x + dx, y + dy, rgb(v + fg.nextInt(15), v + fg.nextInt(15), v + fg.nextInt(15)));
                }
        }
        // ~4 very dark void specks
        Random vd = new Random(115);
        for (int i = 0; i < 5; i++) {
            int x = vd.nextInt(TILE), y = vd.nextInt(TILE);
            px(t, x, y, rgb(20, 20, 22));
        }
    }

    /** Cobblestone — Voronoi-style rounded rocks separated by darker mortar joints. */
    private static void drawCobble(BufferedImage t) {
        Random r = new Random(122);
        // place 7 seed points for Voronoi cells
        int[][] seeds = new int[7][2];
        for (int i = 0; i < seeds.length; i++) {
            seeds[i][0] = r.nextInt(TILE);
            seeds[i][1] = r.nextInt(TILE);
        }
        // per-rock base brightness (so rocks vary)
        int[] rockShade = new int[seeds.length];
        for (int i = 0; i < seeds.length; i++) rockShade[i] = 105 + r.nextInt(40);

        for (int y = 0; y < TILE; y++)
            for (int x = 0; x < TILE; x++) {
                // find nearest and 2nd-nearest seed (joint = d2 - d1)
                int n1 = 0;
                double d1 = 1e9, d2 = 1e9;
                for (int i = 0; i < seeds.length; i++) {
                    int dx = x - seeds[i][0], dy = y - seeds[i][1];
                    double d = Math.sqrt(dx * dx + dy * dy);
                    if (d < d1) { d2 = d1; d1 = d; n1 = i; }
                    else if (d < d2) { d2 = d; }
                }
                double joint = d2 - d1; // pixels near cell boundary have small value
                int v;
                if (joint < 0.85) {
                    // mortar joint — dark
                    v = 65 + r.nextInt(8);
                } else {
                    // inside rock
                    int base = rockShade[n1];
                    // edge-darken slightly for rounded look
                    double edgeFade = Math.min(1.0, joint / 4.0);
                    base = (int) (base - 15 + 15 * edgeFade);
                    v = base + r.nextInt(10) - 5;
                }
                v = Math.max(50, Math.min(190, v));
                px(t, x, y, rgb(v, v, v + 1));
            }
    }

    /** Wood planks — 4 horizontal planks with grooves, vertical grain, and 2 knots. */
    private static void drawPlanks(BufferedImage t) {
        Random r = new Random(133);
        // 4 planks, each 4 rows tall. groove rows at y=3, 7, 11
        int plankH = 4;
        for (int y = 0; y < TILE; y++) {
            int rowInPlank = y % plankH;
            boolean groove = rowInPlank == (plankH - 1);
            // alternate plank tones row by row for variety
            int plankIdx = y / plankH;
            int tone = (plankIdx % 2 == 0) ? 158 : 148;
            for (int x = 0; x < TILE; x++) {
                int v;
                if (groove) {
                    v = jitter(r, 72, 6);
                } else {
                    // vertical grain: subtle darker columns
                    int colMod = x % 3;
                    int colShift = (colMod == 0) ? -10 : (colMod == 2 ? +4 : 0);
                    v = jitter(r, tone + colShift, 7);
                }
                px(t, x, y, rgb(v, (int) (v * 0.74), (int) (v * 0.44)));
            }
        }
        // 2 knots (small darker oval clusters)
        Random kn = new Random(134);
        for (int n = 0; n < 2; n++) {
            int kx = 2 + kn.nextInt(TILE - 4);
            // place knot inside a plank body (not on groove rows)
            int plankIdx = kn.nextInt(4);
            int ky = plankIdx * plankH + 1 + kn.nextInt(2);
            px(t, kx, ky, rgb(80, 55, 30));
            if (kx + 1 < TILE) px(t, kx + 1, ky, rgb(95, 65, 35));
            if (ky + 1 < TILE) px(t, kx, ky + 1, rgb(95, 65, 35));
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

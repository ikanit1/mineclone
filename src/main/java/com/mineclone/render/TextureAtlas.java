package com.mineclone.render;

import com.mineclone.core.AppPaths;
import org.lwjgl.BufferUtils;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL13.*;

/**
 * Texture atlas assembled purely from PNG sprites on disk.
 *
 * Block sprites live in {@value #BLOCKS_DIR} as PNG files named by
 * {@link #TILE_NAMES}[index]. The engine does NOT draw pixels: it reads each
 * sprite, rescales it to TILE×TILE if needed, packs them into one atlas and
 * hands it to OpenGL.
 *
 * If a sprite file is missing or unreadable, a magenta/black checkerboard
 * placeholder is used instead — the game keeps running and the missing asset
 * is obvious on screen and logged to stderr (Source / Minecraft convention).
 *
 * The source of truth for textures is therefore the asset folder, not code.
 * {@code assets/atlas.png} is only a write-only debug dump of the packed
 * result; it is never read back.
 *
 * Entity / mob textures use a separate atlas — see EntityTextureAtlas (future).
 */
public class TextureAtlas {
    /**
     * Pixel size of one tile in the atlas. Source PNGs of any size are
     * rescaled to fill TILE×TILE, so higher-resolution art is preserved when
     * this is bumped (e.g. 32 → 64).
     */
    public static final int TILE = 32;
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
            "water_particle", // 33
    };

    /** First tile index of the water_flow animation strip (16 frames). */
    public static final int WATER_FLOW_FRAME0 = 17;
    public static final int WATER_FLOW_FRAMES = 16;

    private final int textureId;

    /**
     * Loads every sprite from {@link #BLOCKS_DIR}, packs the atlas and uploads
     * it to OpenGL.
     *
     * @param pngPath    where to write the debug atlas dump
     * @param dumpAtlas  if true, also write the packed atlas to {@code pngPath}
     *                   for inspection. No effect on what the game renders —
     *                   textures always come from the sprite files. (This is
     *                   the old {@code --regen-atlas} flag; there is no longer
     *                   any procedural generation to "regenerate".)
     */
    public TextureAtlas(String pngPath, boolean dumpAtlas) {
        BufferedImage img = assemble();
        if (dumpAtlas)
            savePng(img, AppPaths.file(pngPath));

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
    // Atlas assembly — pure asset loading, no procedural drawing
    // -------------------------------------------------------------------------

    private static BufferedImage assemble() {
        BufferedImage atlas = new BufferedImage(ATLAS_SIZE, ATLAS_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = atlas.createGraphics();
        g.setColor(new Color(0, 0, 0, 0));
        g.fillRect(0, 0, ATLAS_SIZE, ATLAS_SIZE);
        g.dispose();

        int missing = 0;
        for (int i = 0; i < TILE_NAMES.length; i++) {
            File tileFile = new File(AppPaths.file(BLOCKS_DIR), TILE_NAMES[i] + ".png");
            BufferedImage tile = loadTile(tileFile);
            if (tile == null) {
                System.err.println("Missing sprite: " + tileFile.getPath()
                        + " — using placeholder");
                tile = placeholder();
                missing++;
            }
            blitTile(atlas, tile, i);
        }
        if (missing > 0)
            System.err.println(missing + " sprite(s) missing — see "
                    + BLOCKS_DIR + " (placeholders shown in-game)");
        return atlas;
    }

    /** Copy a TILE×TILE tile into the correct cell of the atlas image. */
    private static void blitTile(BufferedImage atlas, BufferedImage tile, int idx) {
        int col = idx % TILES_PER_ROW;
        int row = idx / TILES_PER_ROW;
        for (int y = 0; y < TILE; y++)
            for (int x = 0; x < TILE; x++)
                atlas.setRGB(col * TILE + x, row * TILE + y, tile.getRGB(x, y));
    }

    /** Load a sprite PNG and ensure it is TILE×TILE ARGB. Null if absent/bad. */
    private static BufferedImage loadTile(File f) {
        try {
            if (!f.exists())
                return null;
            BufferedImage raw = ImageIO.read(f);
            if (raw == null)
                return null;
            if (raw.getWidth() == TILE && raw.getHeight() == TILE
                    && raw.getType() == BufferedImage.TYPE_INT_ARGB) {
                return raw;
            }
            // Scale / convert to canonical format (nearest-neighbour keeps
            // pixel art crisp; matches the GL_NEAREST atlas filter).
            BufferedImage tile = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = tile.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(raw, 0, 0, TILE, TILE, null);
            g.dispose();
            return tile;
        } catch (IOException e) {
            System.err.println("Failed to load tile " + f + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Magenta/black checkerboard shown when a sprite file is missing — the
     * classic "no texture" marker so the gap is impossible to miss.
     */
    private static BufferedImage placeholder() {
        BufferedImage t = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_ARGB);
        int cell = Math.max(1, TILE / 4);
        int magenta = 0xFFFF00FF, black = 0xFF000000;
        for (int y = 0; y < TILE; y++)
            for (int x = 0; x < TILE; x++) {
                boolean odd = ((x / cell) + (y / cell)) % 2 == 0;
                t.setRGB(x, y, odd ? magenta : black);
            }
        return t;
    }

    // -------------------------------------------------------------------------
    // IO helpers
    // -------------------------------------------------------------------------

    /** Write-only debug dump of the packed atlas. Never read back. */
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
}

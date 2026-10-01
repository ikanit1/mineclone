import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** RND-09 part 2: imports the reviewed source sheets as hard-alpha 32px atlas sprites. */
public final class ImportRoadmapTextures {
    private static final Path SOURCE = Path.of("assets/textures/source");
    private static final String[] ARMOR = {"leather", "iron", "gold", "diamond"};
    private static final String[] PARTS = {"helmet", "chestplate", "leggings", "boots"};
    private static final String[] HOES = {"wood", "stone", "copper", "iron", "gold", "diamond"};
    private static final String[] CROPS = {"wheat", "carrot", "potato"};
    private static final int[][] RAMPS = {
        {0x252323,0x513823,0x76502e,0xa07742,0xc69a59,0xe5bd79},
        {0x252323,0x454747,0x646963,0x878e81,0xaab19f,0xcdd2bd},
        {0x252323,0x63392c,0x965136,0xc77747,0xe8a46b,0xffce92},
        {0x252323,0x424c52,0x67757b,0x939fa2,0xc3cecd,0xeaf1e9},
        {0x252323,0x715021,0xa77a27,0xd2a438,0xf0ca5c,0xffeb9b},
        {0x252323,0x205458,0x287f83,0x39afb2,0x72d9d4,0xc0f4e5}
    };

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length == 0 ? "assets/textures/blocks" : args[0]);
        Files.createDirectories(out);
        BufferedImage armor = source("armor", "75c73b7251e704ba0e65fbc9cefe04a4eef9243fac1c0f9743e542fa32be31b5");
        BufferedImage hoes = source("hoes", "bb38beca40d8e0eaa3f8b33da2b3e1c3ab7577638dda530a80fabf987c12163e");
        BufferedImage crops = source("crops", "f87d87073e40352cee9dcfdb71b5c76bd338831b7ba3cc641e186909fc49f2ea");
        for (int row = 0; row < 4; row++) for (int col = 0; col < 4; col++)
            write(out, ARMOR[row] + "_" + PARTS[col], tile(armor, col, row, 4, 4));
        // One reviewed silhouette for every material. Strict alpha removes the source's soft halos.
        BufferedImage shape = tile(hoes, 0, 0, 3, 2);
        for (int material = 0; material < HOES.length; material++) {
            BufferedImage sprite = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) {
                int rgba = shape.getRGB(x, y);
                if ((rgba >>> 24) == 0) continue;
                int r = rgba >>> 16 & 255, g = rgba >>> 8 & 255, b = rgba & 255;
                // The blade is in the top-right; grip and handle retain the shared wood palette.
                int[] palette = x >= 16 && y <= 12 ? RAMPS[material] : RAMPS[0];
                int luminance = (r + g + b) / 3;
                int level = 0;
                for (int cut : new int[] {44, 80, 120, 165, 211}) if (luminance > cut) level++;
                sprite.setRGB(x, y, 0xff000000 | palette[level]);
            }
            write(out, HOES[material] + "_hoe", sprite);
        }
        for (int row = 0; row < 3; row++) for (int stage = 0; stage < 4; stage++)
            write(out, CROPS[row] + "_stage_" + stage, tile(crops, stage, row, 4, 3));
        System.out.println("Imported 34 roadmap sprites into " + out);
    }

    private static BufferedImage source(String name, String sha256) throws Exception {
        Path file = SOURCE.resolve(name + "-2026-09-24.png");
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        if (!digest.equals(sha256)) throw new IllegalArgumentException("source digest changed: " + file);
        BufferedImage image = ImageIO.read(file.toFile());
        if (image == null) throw new IllegalArgumentException("unreadable source: " + file);
        return image;
    }

    private static BufferedImage tile(BufferedImage image, int col, int row, int columns, int rows) {
        BufferedImage tile = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 1; y < 31; y++) for (int x = 1; x < 31; x++) {
            int sx = (int) ((col + (x + .5) / 32) * image.getWidth() / columns);
            int sy = (int) ((row + (y + .5) / 32) * image.getHeight() / rows);
            int rgba = image.getRGB(sx, sy);
            if ((rgba >>> 24) >= 240) tile.setRGB(x, y, rgba | 0xff000000);
        }
        return tile;
    }

    private static void write(Path out, String name, BufferedImage image) throws Exception {
        ImageIO.write(image, "png", out.resolve(name + ".png").toFile());
    }
}

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;

/**
 * One-off generator for biome block sprites (32x32 PNG) in assets/textures/blocks.
 * Run from the repo root:  java tools\GenBiomeSprites.java
 */
public class GenBiomeSprites {
    static final int S = 32;

    public static void main(String[] args) throws Exception {
        File dir = new File("assets/textures/blocks");
        if (!dir.isDirectory())
            throw new IllegalStateException("run from repo root, missing " + dir.getAbsolutePath());
        Random r = new Random(42);

        // --- snowy_grass_top: white snow with subtle noise ---
        BufferedImage top = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                int v = 235 + r.nextInt(21);
                top.setRGB(x, y, rgb(v, v, Math.min(255, v + 4)));
            }
        ImageIO.write(top, "png", new File(dir, "snowy_grass_top.png"));

        // --- snowy_grass_side: grass_side base, top 8 px replaced with a ragged snow cap ---
        BufferedImage grassSide = ImageIO.read(new File(dir, "grass_side.png"));
        BufferedImage side = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        side.getGraphics().drawImage(grassSide, 0, 0, S, S, null);
        for (int y = 0; y < 8; y++)
            for (int x = 0; x < S; x++) {
                boolean ragged = y == 7 && r.nextInt(3) == 0; // uneven bottom edge of the cap
                if (!ragged) {
                    int v = 230 + r.nextInt(26);
                    side.setRGB(x, y, rgb(v, v, Math.min(255, v + 4)));
                }
            }
        ImageIO.write(side, "png", new File(dir, "snowy_grass_side.png"));

        // --- cactus_side: green column with darker vertical ribs ---
        BufferedImage cs = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                boolean rib = (x % 8) < 2;
                int g = rib ? 90 + r.nextInt(15) : 130 + r.nextInt(25);
                cs.setRGB(x, y, rgb(20, g, 30));
            }
        for (int i = 0; i < S; i++) {
            cs.setRGB(0, i, rgb(15, 74, 24));
            cs.setRGB(S - 1, i, rgb(15, 74, 24));
        }
        ImageIO.write(cs, "png", new File(dir, "cactus_side.png"));

        // --- cactus_top: lighter green with a dark border ---
        BufferedImage ct = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++) {
                boolean border = x < 2 || y < 2 || x >= S - 2 || y >= S - 2;
                int g = border ? 80 : 150 + r.nextInt(20);
                ct.setRGB(x, y, rgb(20, g, 30));
            }
        ImageIO.write(ct, "png", new File(dir, "cactus_top.png"));

        System.out.println("OK: 4 sprites written to " + dir.getPath());
    }

    private static int rgb(int r, int g, int b) {
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}

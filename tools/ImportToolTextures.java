import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import java.awt.Color;

/** Imports the Higgsfield 4x2 tool sheet into native atlas sprites. */
public final class ImportToolTextures {
    public static void main(String[] args) throws Exception {
        BufferedImage sheet = ImageIO.read(new File("assets/textures/source/higgsfield-tools.png"));
        String[] names = {"lava", "lava_flow", "gold_pickaxe", "gold_axe",
                "gold_shovel", "copper_pickaxe", "copper_axe", "stick"};
        for (int n = 0; n < names.length; n++) {
            BufferedImage tile = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
            int col = n % 4, row = n / 4;
            for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) {
                int sx = (int) ((col + (x + 0.5) / 32) * sheet.getWidth() / 4);
                int sy = (int) ((row + (y + 0.5) / 32) * sheet.getHeight() / 2);
                int argb = sheet.getRGB(sx, sy);
                // Higgsfield's tool cells use a dark checkerboard. Make only that
                // backdrop transparent so the same sprite can texture a 3D mesh.
                if (n >= 2 && n != 0 && n != 1) {
                    int r = (argb >> 16) & 255, g = (argb >> 8) & 255, b = argb & 255;
                    if (Math.max(r, Math.max(g, b)) < 62) argb &= 0x00FFFFFF;
                }
                tile.setRGB(x, y, argb);
            }
            ImageIO.write(tile, "png", new File("assets/textures/blocks/" + names[n] + ".png"));
        }
        solid("tool_gold", new Color(224, 166, 24));
        solid("tool_copper", new Color(190, 83, 43));
        solid("tool_wood", new Color(125, 77, 35));
        solid("copper_ingot", new Color(190, 83, 43));
    }
    private static void solid(String name, Color color) throws Exception {
        BufferedImage tile = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) {
            int d = ((x * 17 + y * 11) & 7) - 3;
            tile.setRGB(x, y, new Color(Math.max(0, Math.min(255, color.getRed() + d)),
                    Math.max(0, Math.min(255, color.getGreen() + d)),
                    Math.max(0, Math.min(255, color.getBlue() + d)), 255).getRGB());
        }
        ImageIO.write(tile, "png", new File("assets/textures/blocks/" + name + ".png"));
    }
}

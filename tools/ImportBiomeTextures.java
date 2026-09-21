import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Crop the Higgsfield source sheet into the game's native 32px material tiles. */
public final class ImportBiomeTextures {
    public static void main(String[] args) throws Exception {
        BufferedImage sheet = ImageIO.read(new File("assets/textures/source/higgsfield-biomes.png"));
        String[] names = {"podzol", "peat", "dry_grass", "red_sand", "terracotta", "limestone", "basalt", "gravel"};
        for (int n = 0; n < names.length; n++) {
            BufferedImage tile = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
            int col = n % 4, row = n / 4;
            for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) {
                int sx = (int) ((col + (x + 0.5) / 32) * sheet.getWidth() / 4);
                int sy = (int) ((row + (y + 0.5) / 32) * sheet.getHeight() / 2);
                tile.setRGB(x, y, sheet.getRGB(sx, sy));
            }
            ImageIO.write(tile, "png", new File("assets/textures/blocks/" + names[n] + ".png"));
        }
    }
}

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** GEN-08: imports copper flecks over the exact same stone base as the shipped ores. */
public final class ImportCopperOreTexture {
    public static void main(String[] args) throws Exception {
        File folder = new File("assets/textures/blocks");
        BufferedImage stone = ImageIO.read(new File(folder, "stone.png"));
        BufferedImage iron = ImageIO.read(new File(folder, "iron_ore.png"));
        int[] ironRamp = {0xff72533d, 0xff99724e, 0xffbb9369};
        int[] copperRamp = {0xff743622, 0xffbe532b, 0xffe39a5d};
        BufferedImage copper = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) {
            int base = stone.getRGB(x, y), fleck = iron.getRGB(x, y);
            if (fleck != base) {
                boolean found = false;
                for (int i = 0; i < ironRamp.length; i++) if (fleck == ironRamp[i]) {
                    base = copperRamp[i]; found = true; break;
                }
                if (!found) throw new IllegalArgumentException("unexpected source ore colour at " + x + "," + y);
            }
            copper.setRGB(x, y, base);
        }
        ImageIO.write(copper, "png", new File(folder, "copper_ore.png"));
    }
}

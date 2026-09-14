import com.mineclone.render.MobSkins;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

/** Converts the reviewed Higgsfield sheet into the renderer's 4x2 UV layout. */
public class ImportMobAtlas {
    public static void main(String[] args) throws Exception {
        BufferedImage source = ImageIO.read(Path.of("assets/higgsfield-mobs-source.png").toFile());
        if (source.getWidth() != 1168 || source.getHeight() != 880)
            throw new IllegalArgumentException("Unexpected source; review cell boundaries before import");
        int[] xs = {0, 148, 294, 438, 584, 748, 874, 1018, 1168};
        int[] ys = {0, 147, 293, 440, 586, 732, 880};
        String[] names = {"cow", "pig", "sheep", "chicken", "zombie", "player"};
        Path dir = Path.of(MobSkins.OVERRIDE_DIR);
        Files.createDirectories(dir);
        BufferedImage atlas = new BufferedImage(MobSkins.WIDTH, MobSkins.HEIGHT * names.length,
                BufferedImage.TYPE_INT_ARGB);
        for (int row = 0; row < names.length; row++) {
            BufferedImage skin = new BufferedImage(MobSkins.WIDTH, MobSkins.HEIGHT,
                    BufferedImage.TYPE_INT_ARGB);
            for (int tile = 0; tile < 8; tile++) {
                int column = row == 5 && tile == 5 ? 6 : tile;
                int left = xs[column] + 4, right = xs[column + 1] - 4;
                if (row == 5 && column == 6) { left = 915; right = 984; }
                int top = ys[row] + 4, bottom = ys[row + 1] - 4;
                for (int y = 0; y < MobSkins.TILE; y++)
                    for (int x = 0; x < MobSkins.TILE; x++) {
                        int rgb = source.getRGB(left + (2 * x + 1) * (right - left) / (2 * MobSkins.TILE),
                                top + (2 * y + 1) * (bottom - top) / (2 * MobSkins.TILE));
                        int px = tile % 4 * MobSkins.TILE + x;
                        int py = tile / 4 * MobSkins.TILE + y;
                        skin.setRGB(px, py, rgb);
                        atlas.setRGB(px, row * MobSkins.HEIGHT + py, rgb);
                    }
            }
            Path file = dir.resolve(names[row] + ".png");
            if (Files.exists(file)) {
                Path backup = dir.resolve("pre-higgsfield");
                Files.createDirectories(backup);
                if (!Files.exists(backup.resolve(file.getFileName())))
                    Files.copy(file, backup.resolve(file.getFileName()));
            }
            ImageIO.write(skin, "png", file.toFile());
        }
        ImageIO.write(atlas, "png", dir.resolve("atlas.png").toFile());
        System.out.println("Imported five mob skins and player arm; atlas 128x384.");
    }
}

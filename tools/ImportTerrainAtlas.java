import com.mineclone.render.TextureAtlas;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

/** Imports the fixed 4x4 Higgsfield sheet into the game's existing tile registry. */
public class ImportTerrainAtlas {
    public static void main(String[] args) throws Exception {
        Path source = Path.of("assets/higgsfield-terrain-source.png");
        BufferedImage sheet = ImageIO.read(source.toFile());
        if (sheet == null || sheet.getWidth() != sheet.getHeight() || sheet.getWidth() % 4 != 0)
            throw new IllegalArgumentException("Expected a square 4x4 atlas");
        String[] names = {"grass_top", "grass_side", "dirt", "stone", "sand",
                "log_side", "log_top", "leaves", "bedrock", "cobblestone", "planks",
                "snowy_grass_top", "snowy_grass_side", "cactus_side", "cactus_top", "water"};
        Path backup = Path.of("assets/textures/pre-higgsfield");
        Files.createDirectories(backup);
        int cell = sheet.getWidth() / 4;
        for (int i = 0; i < names.length; i++) {
            Path destination = Path.of(TextureAtlas.BLOCKS_DIR, names[i] + ".png");
            Path original = backup.resolve(destination.getFileName());
            if (!Files.exists(original)) Files.copy(destination, original);
            BufferedImage mask = ImageIO.read(original.toFile());
            BufferedImage tile = new BufferedImage(TextureAtlas.TILE, TextureAtlas.TILE,
                    BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < TextureAtlas.TILE; y++) {
                for (int x = 0; x < TextureAtlas.TILE; x++) {
                    int sx = i % 4 * cell + (2 * x + 1) * cell / (2 * TextureAtlas.TILE);
                    int sy = i / 4 * cell + (2 * y + 1) * cell / (2 * TextureAtlas.TILE);
                    int rgb = sheet.getRGB(sx, sy);
                    if (names[i].equals("leaves") || names[i].equals("water")) {
                        int alpha = mask.getRGB(x * mask.getWidth() / TextureAtlas.TILE,
                                y * mask.getHeight() / TextureAtlas.TILE) & 0xff000000;
                        rgb = alpha | (rgb & 0xffffff);
                    }
                    tile.setRGB(x, y, rgb);
                }
            }
            ImageIO.write(tile, "png", destination.toFile());
        }
        ImageIO.write(TextureAtlas.assemble(), "png", Path.of(TextureAtlas.DEFAULT_PATH).toFile());
        System.out.println("Imported 16 materials; packed " + TextureAtlas.TILE_NAMES.length + " tiles.");
    }
}

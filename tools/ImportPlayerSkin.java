import com.mineclone.render.MobSkins;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Cuts a reviewed 4x2 Higgsfield sheet into the player's skin override.
 *
 * The mob sheet ({@link ImportMobAtlas}) carries six creatures at once, so
 * every tile there is tiny. The player is the only model the camera ever gets
 * close to - their own third-person body and everyone else's in a room - so
 * they get a sheet to themselves, one tile per cell.
 *
 *   java -cp "out;libs/*" tools/ImportPlayerSkin.java [source.png]
 *
 * Source layout, left to right and top to bottom, matching {@link MobSkins}:
 * face, head profile, top of the head, shirt, fist, arm side, arm front,
 * trousers. Writes assets/mobs/player.png and keeps the previous file under
 * assets/mobs/pre-import/.
 *
 * Two tiles get fixed up on the way in. A cube face is filled edge to edge,
 * but an image model draws an arm as an object standing on a background, so
 * the long arm faces would come out as a stick on a slab. Their middle column
 * is stretched across the tile: arm art is vertical stripes, and the column
 * through the middle is the honest profile of it.
 *
 * Messages are Latin on purpose: the Windows console is not UTF-8.
 */
public class ImportPlayerSkin {

    /** Tiles whose content is a vertical stripe, not a free-standing object. */
    private static final int[] STRIPE_TILES = { MobSkins.T_LIMB, MobSkins.T_ACCENT };

    public static void main(String[] args) throws Exception {
        Path source = Path.of(args.length > 0 ? args[0] : "assets/higgsfield-player-source.png");
        if (!Files.exists(source)) {
            System.out.println("no source sheet: " + source);
            System.exit(2);
            return;
        }
        BufferedImage sheet = ImageIO.read(source.toFile());
        if (sheet == null) {
            System.out.println("cannot read " + source);
            System.exit(2);
            return;
        }
        int cellW = sheet.getWidth() / MobSkins.COLS;
        int cellH = sheet.getHeight() / MobSkins.ROWS;
        if (cellW <= 0 || cellH <= 0) {
            System.out.println("sheet is smaller than its own grid: "
                    + sheet.getWidth() + "x" + sheet.getHeight());
            System.exit(2);
            return;
        }

        BufferedImage skin = new BufferedImage(MobSkins.WIDTH, MobSkins.HEIGHT,
                BufferedImage.TYPE_INT_ARGB);
        for (int tile = 0; tile < MobSkins.COLS * MobSkins.ROWS; tile++) {
            int left = (tile % MobSkins.COLS) * cellW;
            int top = (tile / MobSkins.COLS) * cellH;
            boolean stripe = isStripe(tile);
            for (int y = 0; y < MobSkins.TILE; y++) {
                // Point sampling, not averaging: the sheet is already drawn in
                // chunky pixels, and an average would smear their edges into
                // the mush this project spent a generator avoiding.
                int sy = top + (2 * y + 1) * cellH / (2 * MobSkins.TILE);
                for (int x = 0; x < MobSkins.TILE; x++) {
                    int column = stripe ? MobSkins.TILE / 2 : x;
                    int sx = left + (2 * column + 1) * cellW / (2 * MobSkins.TILE);
                    int rgb = sheet.getRGB(sx, sy) | 0xFF000000;
                    skin.setRGB((tile % MobSkins.COLS) * MobSkins.TILE + x,
                            (tile / MobSkins.COLS) * MobSkins.TILE + y, rgb);
                }
            }
        }

        Path dir = Path.of(MobSkins.OVERRIDE_DIR);
        Files.createDirectories(dir);
        Path file = dir.resolve("player.png");
        if (Files.exists(file)) {
            Path backup = dir.resolve("pre-import");
            Files.createDirectories(backup);
            Files.copy(file, backup.resolve("player.png"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        ImageIO.write(skin, "png", file.toFile());
        System.out.println("wrote " + file + " (" + MobSkins.WIDTH + "x" + MobSkins.HEIGHT
                + ") from " + source + " cells " + cellW + "x" + cellH);
    }

    private static boolean isStripe(int tile) {
        for (int t : STRIPE_TILES)
            if (t == tile)
                return true;
        return false;
    }
}

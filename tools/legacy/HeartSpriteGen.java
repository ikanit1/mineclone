import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Generates MC-style 16x16 heart sprites (empty, full, half).
 * Output: assets/textures/blocks/heart_empty.png, heart_full.png, heart_half.png
 */
public class HeartSpriteGen {

    static int argb(int r, int g, int b, int a) {
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    // 16x16 heart shape — 1 = opaque pixel, 0 = transparent
    static final int[][] SHAPE = {
        {0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0}, // y=0
        {0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0}, // y=1
        {0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0}, // y=2
        {0,0,0,1,1,0,0,0,1,1,0,0,0,0,0,0}, // y=3  ##   ##
        {0,0,1,1,1,1,0,1,1,1,1,0,0,0,0,0}, // y=4  #### ####
        {0,1,1,1,1,1,1,1,1,1,1,1,0,0,0,0}, // y=5  ##########
        {0,1,1,1,1,1,1,1,1,1,1,1,0,0,0,0}, // y=6  ##########
        {0,1,1,1,1,1,1,1,1,1,1,1,0,0,0,0}, // y=7  ##########
        {0,0,1,1,1,1,1,1,1,1,1,0,0,0,0,0}, // y=8   ########
        {0,0,0,1,1,1,1,1,1,1,0,0,0,0,0,0}, // y=9    ######
        {0,0,0,0,1,1,1,1,1,0,0,0,0,0,0,0}, // y=10    #####  (wait, let me recount)
        {0,0,0,0,0,1,1,1,0,0,0,0,0,0,0,0}, // y=11     ###
        {0,0,0,0,0,0,1,0,0,0,0,0,0,0,0,0}, // y=12      #
        {0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0}, // y=13
        {0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0}, // y=14
        {0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0}, // y=15
    };

    // x divider for half heart — pixels at x < 6 are the "left" (filled) side
    static final int HALF_DIV = 6;

    public static void main(String[] args) throws Exception {
        // Full heart colors
        int hilight = argb(252,  88,  88, 255); // bright highlight, top-left
        int red     = argb(212,  20,  20, 255); // main body
        int dark    = argb(140,   8,   8, 255); // tip/bottom

        // Empty container color
        int empty   = argb( 90,  12,  12, 200); // dim maroon silhouette

        BufferedImage imgFull  = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        BufferedImage imgEmpty = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        BufferedImage imgHalf  = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);

        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int filled = SHAPE[y][x];
                if (filled == 0) {
                    // transparent outside the heart shape
                    continue;
                }

                // Full heart: bright highlight in top-left zone, dark at the point
                int fc;
                if (y <= 5 && x <= 5)       fc = hilight;
                else if (y >= 10)            fc = dark;
                else                         fc = red;

                imgFull .setRGB(x, y, fc);
                imgEmpty.setRGB(x, y, empty);
                // Half: left side = full colors, right side = empty
                imgHalf .setRGB(x, y, x < HALF_DIV ? fc : empty);
            }
        }

        write(imgFull,  "assets/textures/blocks/heart_full.png");
        write(imgEmpty, "assets/textures/blocks/heart_empty.png");
        write(imgHalf,  "assets/textures/blocks/heart_half.png");
    }

    static void write(BufferedImage img, String path) throws Exception {
        File f = new File(path);
        ImageIO.write(img, "PNG", f);
        System.out.println("Written: " + f.getAbsolutePath());
    }
}

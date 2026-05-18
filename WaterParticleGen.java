import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Generates a 16x16 water-drop particle sprite.
 * Same blue palette as WaterTextureGen (BASE/HI).
 * White highlight on top-left, darker base elsewhere.
 * Transparent background — compatible with atlas alpha blending.
 */
public class WaterParticleGen {

    static int argb(int r, int g, int b, int a) {
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static void main(String[] args) throws Exception {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);

        // Color palette matching WaterTextureGen
        int dark   = argb( 24,  55, 148, 220); // BASE — deep blue
        int mid    = argb( 43,  82, 172, 225); // mid tone
        int bright = argb( 62, 108, 196, 230); // HI   — lighter blue
        int lite   = argb(110, 155, 215, 220); // highlight (top area)

        // Raindrop shape — teardrop pointing down, centered in 16x16
        // Row : x-columns that are filled
        int[][] shape = {
            /* y=0  */ {},
            /* y=1  */ {},
            /* y=2  */ {6, 7, 8, 9},
            /* y=3  */ {5, 6, 7, 8, 9, 10},
            /* y=4  */ {4, 5, 6, 7, 8, 9, 10, 11},
            /* y=5  */ {4, 5, 6, 7, 8, 9, 10, 11},
            /* y=6  */ {4, 5, 6, 7, 8, 9, 10, 11},
            /* y=7  */ {5, 6, 7, 8, 9, 10},
            /* y=8  */ {6, 7, 8, 9},
            /* y=9  */ {7, 8},
            /* y=10 */ {7, 8},
            /* y=11 */ {},
            /* y=12 */ {},
            /* y=13 */ {},
            /* y=14 */ {},
            /* y=15 */ {},
        };

        for (int y = 0; y < shape.length; y++) {
            for (int x : shape[y]) {
                int color;
                // Top highlight area (top-left quadrant of the drop)
                if (y <= 4 && x <= 7)
                    color = lite;
                // Upper body
                else if (y <= 6)
                    color = bright;
                // Lower body / tapered point
                else if (y <= 8)
                    color = mid;
                else
                    color = dark;
                img.setRGB(x, y, color);
            }
        }

        File out = new File("assets/textures/blocks/water_particle.png");
        ImageIO.write(img, "PNG", out);
        System.out.println("Written: " + out.getAbsolutePath());
    }
}

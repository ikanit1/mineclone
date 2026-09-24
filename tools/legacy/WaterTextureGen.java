import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Generates seamlessly-tiling water textures.
 *
 * Seamless tile rule: for a sine wave with period P and diagonal lean L,
 * the tile (16x16) is seamless iff  (L * 16) mod P == 0.
 *
 * We use P=8, L=0.5  →  0.5 * 16 = 8  ≡ 0 (mod 8)  ✓  perfect tiling.
 */
public class WaterTextureGen {
    static final int S = 16;

    static final double[] BASE = { 24,  55, 148 };
    static final double[] HI   = { 62, 108, 196 };

    static int argb(double t, int alpha) {
        t = Math.max(0, Math.min(1, t));
        int r = (int)(BASE[0] + (HI[0] - BASE[0]) * t);
        int g = (int)(BASE[1] + (HI[1] - BASE[1]) * t);
        int b = (int)(BASE[2] + (HI[2] - BASE[2]) * t);
        return (alpha << 24) | (r << 16) | (g << 8) | b;
    }

    // Soft sine — no sharpening, smooth transitions
    static double wave(double phase) {
        return Math.sin(2 * Math.PI * phase) * 0.5 + 0.5;
    }

    public static void main(String[] args) throws Exception {
        String dir = "assets/textures/blocks/";

        // ── Static water tile (tile 8) ──
        // Two opposing diagonals, both seamless (P=8, L=±0.5).
        // Soft pattern; this tile is now only used as fallback.
        {
            BufferedImage img = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < S; y++) {
                for (int x = 0; x < S; x++) {
                    double t = wave((y - x * 0.5) / 8.0) * 0.70
                             + wave((y + x * 0.5) / 8.0) * 0.30;
                    img.setRGB(x, y, argb(t, 182));
                }
            }
            ImageIO.write(img, "PNG", new File(dir + "water.png"));
            System.out.println("water.png");
        }

        // ── Animated flow frames 0..15 ──
        // Primary wave: P=8, lean=0.5  → seamless. Each frame shifts Y by 1 px.
        // 8 frames per scroll cycle; 16 frames = 2 cycles per animation loop.
        // Secondary wave: opposite lean, weight 0.25 → subtle cross-pattern, also seamless.
        for (int frame = 0; frame < 16; frame++) {
            BufferedImage img = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < S; y++) {
                for (int x = 0; x < S; x++) {
                    double t = wave((y - frame - x * 0.5) / 8.0) * 0.75
                             + wave((y - frame + x * 0.5) / 8.0) * 0.25;
                    img.setRGB(x, y, argb(t, 192));
                }
            }
            String name = String.format(dir + "water_flow_%02d.png", frame);
            ImageIO.write(img, "PNG", new File(name));
        }
        System.out.println("water_flow_00..15.png  Done.");
    }
}

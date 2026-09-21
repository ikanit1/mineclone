import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import java.util.TreeSet;

/**
 * Pixel comparison of two preview folders.
 *
 * Written for the switch to the batched UI renderer: the old renderer takes the
 * baseline shots, the new one takes them again, and every frame has to match
 * except for antialiasing noise. A pixel counts as different when any channel
 * moves by more than 40 levels; a frame fails when more than 0.3 percent of its
 * pixels differ or its size changed.
 *
 *   java tools/ComparePreviews.java out-test/previews-baseline out-test/previews
 *
 * Exit code 1 when any frame fails. Frames present in only one folder are
 * listed and do not fail the run.
 */
public class ComparePreviews {

    static final int CHANNEL_TOLERANCE = 40;
    static final double MAX_DIFF_FRACTION = 0.003;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("usage: ComparePreviews <baseline-dir> <new-dir> [name-prefix]");
            System.exit(2);
        }
        File base = new File(args[0]), next = new File(args[1]);
        String prefix = args.length > 2 ? args[2] : "";
        TreeSet<String> names = new TreeSet<>();
        TreeSet<String> onlyBase = new TreeSet<>(), onlyNew = new TreeSet<>();
        for (String n : list(base))
            if (n.startsWith(prefix)) (new File(next, n).isFile() ? names : onlyBase).add(n);
        for (String n : list(next))
            if (n.startsWith(prefix) && !new File(base, n).isFile()) onlyNew.add(n);

        boolean failed = false;
        for (String n : names) {
            BufferedImage a = ImageIO.read(new File(base, n));
            BufferedImage b = ImageIO.read(new File(next, n));
            if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
                System.out.printf("%-40s size changed  FAIL%n", n);
                failed = true;
                continue;
            }
            int w = a.getWidth(), h = a.getHeight();
            int[] pa = a.getRGB(0, 0, w, h, null, 0, w);
            int[] pb = b.getRGB(0, 0, w, h, null, 0, w);
            long diff = 0;
            for (int i = 0; i < pa.length; i++)
                if (differs(pa[i], pb[i])) diff++;
            double fraction = diff / (double) pa.length;
            boolean ok = fraction <= MAX_DIFF_FRACTION;
            failed |= !ok;
            System.out.printf("%-40s %7.3f%%  %s%n", n, fraction * 100.0, ok ? "OK" : "FAIL");
        }
        for (String n : onlyBase) System.out.println("only in baseline: " + n);
        for (String n : onlyNew) System.out.println("only in new:      " + n);
        System.out.println(failed ? "RESULT: FAIL" : "RESULT: OK");
        System.exit(failed ? 1 : 0);
    }

    static boolean differs(int x, int y) {
        for (int shift = 0; shift <= 16; shift += 8) {
            int ca = (x >> shift) & 255, cb = (y >> shift) & 255;
            if (Math.abs(ca - cb) > CHANNEL_TOLERANCE) return true;
        }
        return false;
    }

    static String[] list(File dir) {
        String[] names = dir.list((d, n) -> n.endsWith(".png"));
        if (names == null) return new String[0];
        Arrays.sort(names);
        return names;
    }
}

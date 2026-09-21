import com.mineclone.world.Chunk;
import com.mineclone.world.World;
import com.mineclone.world.WaterSimulator;

/**
 * Skol'ko stoit tik vody nad okeanom.
 *
 * Simulyator prohodit po KAZHDOY vodyanoy yacheyke aktivnogo chanka, a v
 * okeanskom chanke ih tysyachi. Do pravki kazhdaya poverhnostnaya yacheyka
 * zapuskala chetyre poiska stoka (dropDistance) — i eto pri tom, chto so vseh
 * chetyryoh storon u neyo voda i techʹ nekuda. Tik shyol 25–40 ms i povtoryalsya
 * pyat' raz v sekundu: rovno tot ryvok, kotoryy vidno v igre.
 *
 * Konsol' Windows ne v UTF-8, poetomu vyvod latinitsey — kak i v ostal'nyh
 * instrumentah.
 *
 * Zapusk iz kornya repozitoriya:
 *   java -cp "out;libs/*" tools/BenchWater.java
 */
public class BenchWater {

    /** Radius chankov vokrug igroka — kak RENDER_RADIUS + 1 v Game. */
    private static final int RADIUS = 7;
    private static final int TICKS = 40;

    public static void main(String[] args) {
        long seed = 20260920L;
        World world = null;
        int bestWater = -1;
        // Ishchem sid s samym mokrym pyatnom: bench dolzhen merit' okean, a ne les.
        for (int attempt = 0; attempt < 6; attempt++) {
            World candidate = new World(seed + attempt * 7919L);
            int water = 0;
            for (int cx = -2; cx <= 2; cx++)
                for (int cz = -2; cz <= 2; cz++)
                    water += candidate.getChunk(cx, cz).waterCellCount();
            if (water > bestWater) {
                bestWater = water;
                world = candidate;
            }
        }

        int chunks = 0, water = 0;
        for (int cx = -RADIUS; cx <= RADIUS; cx++)
            for (int cz = -RADIUS; cz <= RADIUS; cz++) {
                Chunk c = world.getChunk(cx, cz);
                chunks++;
                water += c.waterCellCount();
            }
        System.out.printf("world: %d chunks, %d water cells (%d per chunk)%n",
                chunks, water, water / Math.max(1, chunks));

        double worst = 0, total = 0;
        for (int i = 0; i < TICKS; i++) {
            // Kazhdyy tik zanovo budim vse chanki — tak zhe, kak eto delaet
            // zagruzka mesha pri strimminge.
            WaterSimulator.reset();
            for (int cx = -RADIUS; cx <= RADIUS; cx++)
                for (int cz = -RADIUS; cz <= RADIUS; cz++)
                    WaterSimulator.activateChunkIfWater(world, cx, cz);
            long t = System.nanoTime();
            WaterSimulator.tick(world);
            double ms = (System.nanoTime() - t) / 1e6;
            if (i >= TICKS / 4) {          // pervye tiki — progrev JIT
                total += ms;
                worst = Math.max(worst, ms);
            }
        }
        int counted = TICKS - TICKS / 4;
        System.out.printf("water tick: avg %.2f ms, worst %.2f ms  (%d ticks)%n",
                total / counted, worst, counted);
        System.out.printf("at %.2f s per tick that is %.0f%% of a 16.7 ms frame%n",
                0.18, total / counted / 16.7 * 100);
    }
}

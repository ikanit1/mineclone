import com.mineclone.world.Chunk;
import com.mineclone.world.ChunkLoader;
import com.mineclone.world.ChunkMesher;
import com.mineclone.world.BlockType;
import com.mineclone.world.World;
import com.mineclone.save.SaveManager;

/**
 * Skolko stoit konveyer chankov glavnomu potoku.
 *
 * Do etoy pravki meshi stroilis' pryamo v kadre — do vos'mi shtuk za raz, i
 * kazhdyy eto obkhod 32 768 yacheek chanka s raschyotom AO na gran'. Zamer
 * pokazyvaet obe tsifry ryadom: chto kadr platil ran'she i chto platit
 * seychas. Srednee zdes' pochti nichego ne znachit — ryvok eto khudshiy kadr,
 * poetomu on i pechataetsya otdel'no vmeste s 99-m pertsentilem.
 *
 * Konsol' Windows ne v UTF-8, poetomu vyvod latinitsey — kak i v ostal'nykh
 * instrumentakh.
 *
 * Zapusk iz kornya repozitoriya:
 *   java -cp "out;libs/*" tools/BenchChunkPipeline.java
 */
public class BenchChunkPipeline {

    /** Radius potoka chankov vokrug igroka — kak RENDER_RADIUS + 1 v Game. */
    private static final int RADIUS = 7;
    /** Skol'ko chankov proletaem po pryamoy. */
    private static final int FLIGHT_CHUNKS = 24;
    /** Skol'ko blokov lomaem/stavim vo vtoroy chasti zamera. */
    private static final int EDITS = 200;
    /** Staryy potolok perestroek za kadr — s nim i sravnivaem. */
    private static final int OLD_REBUILDS_PER_FRAME = 8;

    public static void main(String[] args) throws Exception {
        java.io.File tmp = java.nio.file.Files.createTempDirectory("mineclone-bench").toFile();
        tmp.deleteOnExit();

        System.out.println("=== mesh build cost (the work that used to sit in the frame) ===");
        benchMeshBuild();

        System.out.println();
        System.out.println("=== emitter lookup: cube scan vs list ===");
        benchEmitterLookup();

        System.out.println();
        System.out.println("=== main-thread cost of a flight across " + FLIGHT_CHUNKS + " chunks ===");
        // Pervyy progon vybrasyvaem: na nyom JIT kompiliruet goryachie puti.
        flight(tmp, 1L, false);
        flight(tmp, 2L, true);

        System.out.println();
        System.out.println("=== main-thread cost of " + EDITS + " block edits ===");
        edits(tmp, 3L, false);
        edits(tmp, 4L, true);
    }

    // ------------------------------------------------------------------ mesh

    private static void benchMeshBuild() {
        World world = new World(20260915L);
        ChunkMesher mesher = new ChunkMesher(world);
        for (int cx = -2; cx <= 2; cx++)
            for (int cz = -2; cz <= 2; cz++)
                world.getChunk(cx, cz);

        double[] samples = new double[9];
        int n = 0;
        for (int cx = -1; cx <= 1; cx++)
            for (int cz = -1; cz <= 1; cz++) {
                Chunk c = world.getChunkIfExists(cx, cz);
                mesher.buildData(c);                       // warm
                long t = System.nanoTime();
                mesher.buildData(c);
                samples[n++] = (System.nanoTime() - t) / 1e6;
            }
        java.util.Arrays.sort(samples);
        double avg = 0;
        for (double v : samples) avg += v;
        avg /= samples.length;
        System.out.printf("  one chunk: avg %.2f ms, worst %.2f ms%n", avg, samples[samples.length - 1]);
        System.out.printf("  OLD worst frame at %d rebuilds: %.1f ms  <-- this was the hitch%n",
                OLD_REBUILDS_PER_FRAME, samples[samples.length - 1] * OLD_REBUILDS_PER_FRAME);
        System.out.println("  NEW: 0.00 ms in frame (built on the mesh pool, frame only uploads)");
    }

    // --------------------------------------------------------------- emitters

    private static void benchEmitterLookup() {
        World world = new World(4242L);
        Chunk c = world.getChunk(0, 0);
        BlockType lamp = null;
        for (BlockType t : BlockType.values())
            if (t.emittedLight > 0) { lamp = t; break; }
        // Desyatok fakelov — tipichnaya osveshchyonnaya peshchera.
        for (int i = 0; i < 10; i++)
            c.set(i, 30 + i, i, lamp);

        long t = System.nanoTime();
        int found = 0;
        for (int rep = 0; rep < 100; rep++)
            for (int x = 0; x < Chunk.SIZE_X; x++)
                for (int y = 0; y < Chunk.SIZE_Y; y++)
                    for (int z = 0; z < Chunk.SIZE_Z; z++)
                        if (c.get(x, y, z).emittedLight > 0) found++;
        double cube = (System.nanoTime() - t) / 1e6 / 100.0;

        t = System.nanoTime();
        int found2 = 0;
        for (int rep = 0; rep < 100; rep++)
            for (int i = 0; i < c.emitterCount(); i++) { c.emitterAt(i); found2++; }
        double list = (System.nanoTime() - t) / 1e6 / 100.0;

        System.out.printf("  cube scan: %.3f ms/chunk  (%d emitters found)%n", cube, found / 100);
        System.out.printf("  list walk: %.3f ms/chunk  (%d emitters found)%n", list, found2 / 100);
        System.out.printf("  OLD worst frame at 8 chunks: %.2f ms   NEW: %.3f ms%n",
                cube * 8, list * 8);
    }

    // ----------------------------------------------------------------- flight

    private static void flight(java.io.File saves, long seed, boolean report) throws Exception {
        World world = new World(seed);
        ChunkMesher mesher = new ChunkMesher(world);
        SaveManager save = new SaveManager(saves);
        ChunkLoader loader = new ChunkLoader(world, mesher, save, "bench-" + seed);

        java.util.List<Double> frames = new java.util.ArrayList<>();
        for (int step = 0; step < FLIGHT_CHUNKS; step++) {
            // Odin "kadr" na chank puti, plus neskol'ko kholostykh na dobor
            // ochered: imenno tak eto vyglyadit v igre pri khod'be.
            for (int f = 0; f < 6; f++) {
                long t = System.nanoTime();
                loader.setPriorityCenter(step, 0);
                loader.ensureRadius(step, 0, RADIUS);
                loader.drainLightFlood(4);
                // Upload trebuet GL; berem tol'ko snyatie s ocheredi — ostal'noe
                // v igre eto glBufferData uzhe gotovogo massiva.
                loader.drainReady(8);
                frames.add((System.nanoTime() - t) / 1e6);
            }
        }
        // Dozhidaemsya fona, chtoby ne merit' nedodelannuyu rabotu.
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline
                && (loader.pendingGenCount() > 0 || loader.pendingMeshCount() > 0))
            Thread.sleep(5);
        loader.shutdown();
        if (report)
            printFrames("  flight", frames);
    }

    // ------------------------------------------------------------------ edits

    private static void edits(java.io.File saves, long seed, boolean report) throws Exception {
        World world = new World(seed);
        ChunkMesher mesher = new ChunkMesher(world);
        SaveManager save = new SaveManager(saves);
        ChunkLoader loader = new ChunkLoader(world, mesher, save, "bench-edit-" + seed);
        for (int cx = -2; cx <= 2; cx++)
            for (int cz = -2; cz <= 2; cz++)
                world.getChunk(cx, cz);
        loader.drainLightFlood(64);

        java.util.Random rnd = new java.util.Random(seed);
        java.util.List<Double> frames = new java.util.ArrayList<>();
        for (int i = 0; i < EDITS; i++) {
            int x = rnd.nextInt(16), z = rnd.nextInt(16);
            int y = surfaceY(world, x, z);
            long t = System.nanoTime();
            world.setBlock(x, y, z, i % 2 == 0 ? BlockType.AIR : BlockType.STONE);
            // Tochno to, chto delaet kadr posle pravki: postavit' zayavku.
            loader.submitMesh(0, 0, World.key(0, 0), true);
            loader.drainReady(8);
            frames.add((System.nanoTime() - t) / 1e6);
        }
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline && loader.pendingMeshCount() > 0)
            Thread.sleep(5);
        loader.shutdown();
        if (report)
            printFrames("  edits", frames);
    }

    /** Verkhniy tvyordyy blok v kolonne — kuda by udaril igrok. */
    private static int surfaceY(World world, int x, int z) {
        for (int y = Chunk.SIZE_Y - 1; y > 1; y--)
            if (world.getBlock(x, y, z).solid)
                return y;
        return 1;
    }

    // ----------------------------------------------------------------- report

    private static void printFrames(String label, java.util.List<Double> frames) {
        double[] a = new double[frames.size()];
        for (int i = 0; i < a.length; i++) a[i] = frames.get(i);
        java.util.Arrays.sort(a);
        double sum = 0;
        for (double v : a) sum += v;
        double p99 = a[Math.min(a.length - 1, (int) (a.length * 0.99))];
        System.out.printf("%s: avg %.3f ms   p99 %.3f ms   worst %.3f ms   (%d frames)%n",
                label, sum / a.length, p99, a[a.length - 1], a.length);
    }
}

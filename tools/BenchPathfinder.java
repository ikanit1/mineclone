import com.mineclone.world.Chunk;
import com.mineclone.world.World;
import com.mineclone.world.entity.PathFinder;

/**
 * Во сколько обходится зомби его маршрут.
 *
 * A* по вокселям легко превращается в тихого пожирателя кадра: каждый запрос
 * — тысячи обращений к чанкам, а стая думает одновременно. Этот замер идёт по
 * настоящей сгенерированной местности (с пещерами, горами и водой), а не по
 * плоскости, и печатает и среднее, и худший случай — кадр роняет именно
 * худший.
 *
 * Запуск из корня репозитория:
 *   java -cp "out;libs/*" tools/BenchPathfinder.java
 */
public class BenchPathfinder {

    private static final int HEIGHT = 2;      // зомби ростом 1.8 -> 2 блока
    private static final int RADIUS = 4;      // чанков вокруг нуля
    private static final int SAMPLES = 400;

    public static void main(String[] args) {
        World world = new World(20260915L);
        long t0 = System.nanoTime();
        for (int cx = -RADIUS; cx <= RADIUS; cx++)
            for (int cz = -RADIUS; cz <= RADIUS; cz++)
                world.getChunk(cx, cz);
        System.out.printf("сгенерировано %d чанков за %d мс%n",
                (2 * RADIUS + 1) * (2 * RADIUS + 1), (System.nanoTime() - t0) / 1_000_000L);

        java.util.Random rnd = new java.util.Random(99L);
        int span = RADIUS * Chunk.SIZE_X - 8;

        // Первый прогон греет JIT — его результат ничего не значит.
        run(world, rnd, span, SAMPLES, false);
        run(world, rnd, span, SAMPLES, true);
    }

    private static void run(World world, java.util.Random rnd, int span, int samples,
                            boolean report) {
        long total = 0, worst = 0;
        int found = 0, tried = 0;
        for (int i = 0; i < samples; i++) {
            int sx = rnd.nextInt(span * 2) - span, sz = rnd.nextInt(span * 2) - span;
            int sy = surface(world, sx, sz);
            // Цель в пределах реальной дистанции погони.
            int tx = sx + rnd.nextInt(33) - 16, tz = sz + rnd.nextInt(33) - 16;
            int ty = surface(world, tx, tz);
            if (sy < 0 || ty < 0)
                continue;
            tried++;
            long t = System.nanoTime();
            var path = PathFinder.find(world, sx, sy, sz, tx, ty, tz, HEIGHT);
            long dt = System.nanoTime() - t;
            total += dt;
            worst = Math.max(worst, dt);
            if (path != null)
                found++;
        }
        if (!report || tried == 0)
            return;
        System.out.printf("запросов %d, найдено %d (%.0f%%)%n",
                tried, found, 100.0 * found / tried);
        System.out.printf("среднее %.3f мс, худший %.3f мс%n",
                total / 1e6 / tried, worst / 1e6);
        // Восемь враждебных мобов (HOSTILE_CAP) перестраивают маршрут раз в
        // 0.6 с — вот столько это стоит в секунду.
        System.out.printf("стая из 8 при перестройке раз в 0.6 с: %.2f мс/с%n",
                total / 1e6 / tried * 8 / 0.6);
    }

    /** Первая клетка над землёй, куда влезет моб. */
    private static int surface(World world, int x, int z) {
        for (int y = Chunk.SIZE_Y - HEIGHT - 1; y > 1; y--)
            if (PathFinder.standable(world, x, y, z, HEIGHT))
                return y;
        return -1;
    }
}

package com.mineclone.game;

import com.mineclone.world.Lightning;

import java.util.ArrayList;
import java.util.List;

/**
 * Живая гроза: вспышки, болты на экране и раскаты, которые ещё летят.
 *
 * Удары рождает {@link Lightning} — чистая функция от сида и времени. Здесь
 * они превращаются в то, что видно и слышно, и главное тут — что три части
 * события расходятся во времени. Вспышка мгновенна, болт держится доли
 * секунды, а гром идёт до уха столько, сколько положено по расстоянию. Именно
 * этот разрыв и отличает грозу от шумовой подложки: далёкий разряд видно
 * сейчас, а слышно через несколько секунд.
 *
 * Без GL и без OpenAL: мир достаётся через {@link Ground}, а звук и поджог
 * забирает вызывающий из {@link Tick}. Поэтому гроза проверяется числами.
 */
public final class Storm {

    /** Сколько держится вспышка, секунды. */
    public static final float FLASH_TIME = 0.22f;
    /** Сколько живёт нарисованный болт. */
    public static final float BOLT_TIME = 0.18f;
    /**
     * Скорость звука в блоках за секунду.
     *
     * Не физические 340: в блочном мире дальний край видимости — полторы
     * сотни блоков, и настоящая скорость дала бы задержку в полсекунды,
     * которую никто не прочитает как расстояние. Сорок блоков в секунду
     * растягивают тот же край на три с лишним секунды — так гроза и звучит.
     */
    public static final float SOUND_SPEED = 42f;
    /** Дальше этого удар не ищется. */
    public static final int RANGE = 128;
    /** В каком радиусе от удара занимается огонь. */
    public static final int IGNITE_RANGE = 1;

    /** Высота поверхности под открытым небом, или −1, если колонна закрыта. */
    public interface Ground {
        int surfaceUnderSky(int x, int z);
    }

    /** Нарисованный разряд и сколько ему осталось. */
    public record Bolt(float[] segments, float life) {}

    /** Что случилось за этот кадр и что с этим делать вызывающему. */
    public record Tick(List<Lightning.Strike> struck, List<Float> thunder) {
        static final Tick QUIET = new Tick(List.of(), List.of());
    }

    private final List<Bolt> bolts = new ArrayList<>();
    /** Раскаты в пути: когда прозвучать и насколько глухо. */
    private final List<float[]> flying = new ArrayList<>();
    private float flash;
    private float clock = Float.NaN;

    /** Яркость вспышки 0..1 прямо сейчас. */
    public float flash() {
        return flash;
    }

    /** Болты, которые ещё видно. */
    public List<Bolt> bolts() {
        return bolts;
    }

    /** Забыть грозу при смене мира: иначе раскат догонит игрока в другом сиде. */
    public void reset() {
        bolts.clear();
        flying.clear();
        flash = 0f;
        clock = Float.NaN;
    }

    /**
     * Двигает грозу на кадр.
     *
     * @param worldClock мировое время — то же, по которому живёт погода
     * @param ground     мир: даёт высоту под открытым небом
     * @return что зажечь и что озвучить в этом кадре
     */
    public Tick update(float dt, long seed, float worldClock, float storm, float snow,
                int px, int pz, Ground ground) {
        flash = Math.max(0f, flash - dt / FLASH_TIME);
        age(dt);
        if (Float.isNaN(clock)) {          // первый кадр мира: истории у грозы нет
            clock = worldClock;
            return Tick.QUIET;
        }
        float from = clock;
        clock = worldClock;
        // Время могло прыгнуть назад командой /time — тогда грозу просто
        // подхватываем с нового места, а не разыгрываем весь промежуток.
        if (clock < from || clock - from > Lightning.WINDOW)
            return matured(dt);

        List<Lightning.Strike> hits = null;
        for (Lightning.Strike s : Lightning.collect(seed, from, clock, storm, snow, px, pz, RANGE)) {
            int y = ground.surfaceUnderSky(s.x(), s.z());
            if (y < 0)
                continue;                  // под крышей молния не бьёт
            bolts.add(new Bolt(Lightning.bolt(s, y), BOLT_TIME));
            flash = 1f;
            float dx = s.x() - px, dz = s.z() - pz;
            float dist = (float) Math.sqrt(dx * dx + dz * dz);
            flying.add(new float[] { dist / SOUND_SPEED, dist });
            if (hits == null)
                hits = new ArrayList<>(2);
            hits.add(s);
        }
        Tick ripe = matured(dt);
        return hits == null ? ripe : new Tick(hits, ripe.thunder());
    }

    /** Стареет всё, что уже на экране и в пути. */
    private void age(float dt) {
        for (int i = bolts.size() - 1; i >= 0; i--) {
            Bolt b = bolts.get(i);
            float left = b.life() - dt;
            if (left <= 0f)
                bolts.remove(i);
            else
                bolts.set(i, new Bolt(b.segments(), left));
        }
    }

    /** Раскаты, долетевшие за этот кадр; число — расстояние, с которого пришли. */
    private Tick matured(float dt) {
        List<Float> ready = null;
        for (int i = flying.size() - 1; i >= 0; i--) {
            float[] roll = flying.get(i);
            if ((roll[0] -= dt) > 0f)
                continue;
            if (ready == null)
                ready = new ArrayList<>(2);
            ready.add(roll[1]);
            flying.remove(i);
        }
        return ready == null ? Tick.QUIET : new Tick(List.of(), ready);
    }
}

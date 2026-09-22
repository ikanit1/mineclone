package com.mineclone;

import com.mineclone.world.Lightning;

import java.util.List;

/**
 * Молния — чистая функция от сида и времени, и именно поэтому её можно
 * проверить числами: ни окна, ни мира, ни звука здесь не нужно.
 */
final class LightningTests {
    static void runAll(NetworkTests.Runner r) {
        r.run("a storm strikes often enough to notice, rarely enough to live with",
                LightningTests::frequency);
        r.run("no bolt in a blizzard, none in mere rain", LightningTests::silentWeather);
        r.run("the same seed and clock give the same strike to everyone",
                LightningTests::deterministic);
        r.run("a bolt reaches from the clouds down to the spot it hit", LightningTests::boltShape);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    /** Один час мирового времени, кадрами по полсекунды, как в игре. */
    private static List<Lightning.Strike> hour(long seed, float storm, float snow, int radius) {
        List<Lightning.Strike> all = new java.util.ArrayList<>();
        for (float t = 0f; t < 3600f; t += 0.5f)
            all.addAll(Lightning.collect(seed, t, t + 0.5f, storm, snow, 0, 0, radius));
        return all;
    }

    /**
     * Частота в виду игрока: не реже одного удара на 40 секунд и не чаще
     * одного на 14. Реже — грозы не заметно, чаще — она превращается в
     * стробоскоп.
     */
    private static void frequency() {
        for (long seed : new long[] { 1L, 7L, 12345L }) {
            int n = hour(seed, 1f, 0f, 128).size();
            float perStrike = 3600f / n;
            check(perStrike >= 14f && perStrike <= 40f,
                    "seed " + seed + ": one strike per " + perStrike + "s, want 14..40");
        }
    }

    /** Порог бури и метель — два разных запрета, и оба обязаны молчать. */
    private static void silentWeather() {
        check(hour(3L, 1f, 1f, 128).isEmpty(), "a blizzard has no thunder");
        check(hour(3L, 0.49f, 0f, 128).isEmpty(), "heavy rain below the threshold is not a storm");
        check(!hour(3L, 1f, 0f, 128).isEmpty(), "a full storm must actually strike");
    }

    /**
     * То же, что делает сеть: два наблюдателя с одним сидом и часами обязаны
     * получить один список. Иначе гроза требовала бы пакета в протоколе.
     */
    private static void deterministic() {
        var a = Lightning.collect(42L, 0f, 600f, 1f, 0f, 0, 0, 128);
        var b = Lightning.collect(42L, 0f, 600f, 1f, 0f, 0, 0, 128);
        check(a.equals(b), "the same query must give the same strikes");
        check(!a.isEmpty(), "ten minutes of storm must strike at least once");

        // Наблюдатель, стоящий в стороне, видит те же удары в общих ячейках.
        var near = Lightning.collect(42L, 0f, 600f, 1f, 0f, 30, 0, 128);
        long shared = a.stream().filter(near::contains).count();
        check(shared > 0, "two players in one storm must share the bolts they both can see");

        var other = Lightning.collect(43L, 0f, 600f, 1f, 0f, 0, 0, 128);
        check(!a.equals(other), "a different world must get a different storm");
    }

    /** Болт начинается у облаков, кончается ровно в точке удара и ветвится. */
    private static void boltShape() {
        var strikes = Lightning.collect(9L, 0f, 3600f, 1f, 0f, 0, 0, 128);
        check(!strikes.isEmpty(), "need a strike to shape");
        for (var s : strikes.subList(0, Math.min(20, strikes.size()))) {
            float[] bolt = Lightning.bolt(s, 64);
            check(bolt.length % 6 == 0 && bolt.length >= 6 * 12, "a bolt is a run of segments");
            check(bolt[1] == 64 + Lightning.CLOUD_HEIGHT, "it starts at the clouds, got " + bolt[1]);
            float endX = bolt[bolt.length - 3], endY = bolt[bolt.length - 2], endZ = bolt[bolt.length - 1];
            check(endY == 64f, "it ends on the ground, got " + endY);
            check(Math.abs(endX - (s.x() + 0.5f)) < 1e-4 && Math.abs(endZ - (s.z() + 0.5f)) < 1e-4,
                    "it ends where it struck, not near it");
            check(java.util.Arrays.equals(bolt, Lightning.bolt(s, 64)), "one strike, one shape");
        }
    }
}

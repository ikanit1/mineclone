package com.mineclone;

import com.mineclone.game.Storm;

/**
 * Гроза как событие: вспышка сейчас, раскат потом, под крышей — ничего.
 */
final class StormTests {
    static void runAll(NetworkTests.Runner r) {
        r.run("thunder arrives after the flash, later the further it struck", StormTests::delay);
        r.run("the flash fades and the bolt goes with it", StormTests::fades);
        r.run("lightning does not strike through a roof", StormTests::roof);
        r.run("a new world does not inherit the old one's thunder", StormTests::reset);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    /** Плоская земля на высоте 64 под открытым небом. */
    private static final Storm.Ground FLAT = (x, z) -> 64;
    /** Всё закрыто крышей. */
    private static final Storm.Ground ROOFED = (x, z) -> -1;

    private static final float DT = 1f / 60f;

    /**
     * Ловит первый удар и меряет, через сколько после него пришёл раскат.
     * Возвращает {@code {расстояние, задержка}} или null, если не дождались.
     */
    private static float[] firstStrike(long seed, Storm.Ground ground) {
        Storm storm = new Storm();
        float clock = 0f;
        float struckAt = -1f, distance = 0f;
        for (int i = 0; i < 60 * 600; i++) {
            clock += DT;
            var tick = storm.update(DT, seed, clock, 1f, 0f, 0, 0, ground);
            if (!tick.struck().isEmpty() && struckAt < 0f) {
                var s = tick.struck().get(0);
                distance = (float) Math.hypot(s.x(), s.z());
                struckAt = clock;
                check(storm.flash() == 1f, "the sky lights up the instant it strikes");
            }
            if (struckAt >= 0f && !tick.thunder().isEmpty())
                return new float[] { distance, clock - struckAt };
        }
        return null;
    }

    /**
     * Раскат отстаёт от вспышки ровно на время полёта звука. Это и есть то,
     * по чему на слух читается расстояние до грозы.
     */
    private static void delay() {
        int checked = 0;
        for (long seed : new long[] { 5L, 11L, 23L, 44L }) {
            float[] hit = firstStrike(seed, FLAT);
            if (hit == null)
                continue;
            float expected = hit[0] / Storm.SOUND_SPEED;
            check(hit[1] > 0f, "thunder must not land on the same frame as the flash");
            check(Math.abs(hit[1] - expected) < 0.05f,
                    "seed " + seed + ": heard after " + hit[1] + "s, want " + expected + "s");
            checked++;
        }
        check(checked > 0, "no storm struck in ten minutes across four seeds");
    }

    /** Вспышка и болт живут доли секунды и уходят вместе. */
    private static void fades() {
        Storm storm = new Storm();
        float clock = 0f;
        for (int i = 0; i < 60 * 600; i++) {
            clock += DT;
            if (storm.update(DT, 5L, clock, 1f, 0f, 0, 0, FLAT).struck().isEmpty())
                continue;
            check(!storm.bolts().isEmpty(), "a strike must be visible when it happens");
            float lit = storm.flash();
            for (int k = 0; k < 4; k++) {
                clock += DT;
                storm.update(DT, 5L, clock, 1f, 0f, 0, 0, FLAT);
            }
            check(storm.flash() < lit, "the flash must decay, not hold");
            for (int k = 0; k < 60; k++) {
                clock += DT;
                storm.update(DT, 5L, clock, 1f, 0f, 0, 0, FLAT);
            }
            check(storm.flash() == 0f, "the flash is gone within a second");
            check(storm.bolts().isEmpty(), "and the bolt with it");
            return;
        }
        throw new AssertionError("no strike to fade");
    }

    /** Колонна под крышей удара не получает — ни болта, ни поджога. */
    private static void roof() {
        Storm storm = new Storm();
        float clock = 0f;
        for (int i = 0; i < 60 * 600; i++) {
            clock += DT;
            var tick = storm.update(DT, 5L, clock, 1f, 0f, 0, 0, ROOFED);
            check(tick.struck().isEmpty(), "nothing may be struck under a roof");
            check(storm.bolts().isEmpty(), "and nothing may be drawn");
        }
        check(storm.flash() == 0f, "a roofed world never lights up");
    }

    /** Смена мира обязана унести с собой и раскаты, которые ещё летели. */
    private static void reset() {
        Storm storm = new Storm();
        float clock = 0f;
        for (int i = 0; i < 60 * 600; i++) {
            clock += DT;
            if (storm.update(DT, 5L, clock, 1f, 0f, 0, 0, FLAT).struck().isEmpty())
                continue;
            storm.reset();
            check(storm.flash() == 0f && storm.bolts().isEmpty(), "reset clears what is on screen");
            // Ни один раскат из прошлого мира не должен догнать игрока.
            for (int k = 0; k < 60 * 20; k++) {
                var tick = storm.update(DT, 77L, k * DT, 0f, 0f, 0, 0, FLAT);
                check(tick.thunder().isEmpty(), "a calm world stays silent after a reset");
            }
            return;
        }
        throw new AssertionError("no strike to reset");
    }
}

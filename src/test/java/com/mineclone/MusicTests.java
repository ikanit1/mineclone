package com.mineclone;

import com.mineclone.audio.MusicLibrary;
import com.mineclone.audio.MusicMood;
import com.mineclone.audio.MusicSituation;
import com.mineclone.audio.MusicTrack;
import com.mineclone.core.AppPaths;

import java.io.File;
import java.util.EnumSet;

/**
 * Проверки музыки: каталог, ситуация, режиссёр, декодер и чтение мира.
 *
 * Отдельным классом по той же причине, что {@link MenuTests}: раннер и
 * счётчики общие, {@code TestMain} зовёт {@link #runAll} перед итогом.
 */
final class MusicTests {
    private MusicTests() {}

    interface Check { void run() throws Exception; }
    interface Runner { void run(String name, Check check); }

    static void runAll(Runner r) {
        r.run("day parts follow the sun", MusicTests::testDayParts);
        r.run("catalog files exist and every mood has a track", MusicTests::testCatalog);
        r.run("unknown tracks play as day and menu music", MusicTests::testUnknownTrack);
    }

    // ---- каталог и ситуация ----------------------------------------------------

    private static void testDayParts() {
        double pi = Math.PI;
        assertEq("just after sunrise", MusicMood.DAWN, MusicSituation.dayPart(0.1f));
        assertEq("just before sunrise", MusicMood.DAWN, MusicSituation.dayPart((float) (2 * pi - 0.1)));
        assertEq("noon", MusicMood.DAY, MusicSituation.dayPart((float) (pi / 2)));
        assertEq("before sunset", MusicMood.DUSK, MusicSituation.dayPart((float) (pi - 0.3)));
        assertEq("after sunset", MusicMood.DUSK, MusicSituation.dayPart((float) (pi + 0.1)));
        assertEq("midnight", MusicMood.NIGHT, MusicSituation.dayPart((float) (1.5 * pi)));
        assertEq("time wraps forward", MusicMood.DAY, MusicSituation.dayPart((float) (10 * pi + pi / 2)));
        assertEq("time wraps backward", MusicMood.NIGHT, MusicSituation.dayPart((float) (-pi / 2)));
    }

    private static void testCatalog() {
        File dir = AppPaths.file("assets/music");
        for (MusicLibrary.Entry e : MusicLibrary.CATALOG) {
            assertTrue(e.id() + ".mp3 is in assets/music", new File(dir, e.id() + ".mp3").isFile());
            assertTrue(e.id() + " is only turned down", e.gainDb() <= 0f);
        }
        MusicLibrary lib = MusicLibrary.scan(dir);
        assertEq("every catalog track is found", MusicLibrary.CATALOG.size(), lib.tracks().size());
        EnumSet<MusicMood> covered = EnumSet.noneOf(MusicMood.class);
        int menu = 0;
        for (MusicTrack t : lib.tracks()) {
            covered.addAll(t.moods());
            if (t.has(MusicMood.MENU))
                menu++;
        }
        assertEq("every mood has a track", EnumSet.allOf(MusicMood.class), covered);
        assertTrue("the menu has a choice of tracks", menu >= 2);
        assertTrue("Deep Pressure is the danger track",
                lib.byId("Deep Pressure") != null && lib.byId("Deep Pressure").has(MusicMood.DANGER));
    }

    private static void testUnknownTrack() {
        MusicTrack t = MusicLibrary.track("Something New", "Something New.mp3");
        assertEq("unknown track moods", MusicLibrary.UNKNOWN_MOODS, t.moods());
        assertEq("unknown track is not turned down", 0f, t.gainDb());
        MusicTrack known = MusicLibrary.track("deep pressure", "x.mp3");
        assertTrue("catalog lookup ignores case", known.has(MusicMood.CAVE));
        assertTrue("gain in dB becomes a multiplier", Math.abs(MusicLibrary.track("Nocturnal Drift", "x").gain()
                - (float) Math.pow(10, -2.4 / 20)) < 1e-5f);
    }

    // ---- утверждения ---------------------------------------------------------

    static void assertTrue(String what, boolean cond) {
        if (!cond) throw new AssertionError("expected true: " + what);
    }

    static void assertEq(String what, Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual))
            throw new AssertionError(what + ": expected <" + expected + "> but was <" + actual + ">");
    }
}

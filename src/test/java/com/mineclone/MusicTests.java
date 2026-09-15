package com.mineclone;

import com.mineclone.audio.MusicDirector;
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
        r.run("menu music starts at once and never falls silent for long", MusicTests::testMenuNeverSilent);
        r.run("world music plays some of the time, not all of it", MusicTests::testWorldShare);
        r.run("no track plays twice in a row", MusicTests::testNoRepeats);
        r.run("tracks fit the scene: cave, night, day, fight", MusicTests::testPools);
        r.run("a fight ducks calm music, a long one silences it", MusicTests::testDanger);
        r.run("death silences the music, respawn may bring it back", MusicTests::testDeathAndRespawn);
        r.run("leaving the world brings a menu track, a fitting one survives arrival",
                MusicTests::testMenuWorldTransitions);
        r.run("a silent sunset starts music about a third of the time", MusicTests::testSunsetMoment);
        r.run("moments do not reroll within their cooldown", MusicTests::testMomentCooldown);
        r.run("a cave track fades out on the sunny surface", MusicTests::testMismatchFade);
        r.run("waking replaces a night track with a morning one", MusicTests::testWake);
        r.run("music slider at zero stops and holds the music", MusicTests::testDisabled);
        r.run("mp3 stream reads the same PCM in any chunk size", MusicTests::testMp3Chunks);
        r.run("cave and shelter probes read the column above the head", MusicTests::testSenseProbes);
        r.run("building and travel counters rise and fade", MusicTests::testSenseActivity);
        r.run("only a hunting hostile counts as danger", MusicTests::testSenseDanger);
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

    // ---- режиссёр: помощники ---------------------------------------------------

    static final float DT = 0.05f;

    /** Настоящий каталог без файлов: режиссёру нужны только роли. */
    static MusicLibrary catalog() {
        java.util.List<MusicTrack> tracks = new java.util.ArrayList<>();
        for (MusicLibrary.Entry e : MusicLibrary.CATALOG)
            tracks.add(MusicLibrary.track(e.id(), e.id() + ".mp3"));
        return new MusicLibrary(tracks);
    }

    static MusicTrack track(String id, MusicMood... moods) {
        return new MusicTrack(id, id + ".mp3", EnumSet.copyOf(java.util.Arrays.asList(moods)), 0f);
    }

    /** Плеер без звука: трек «играет» ровно свою длину, фейд гасит сразу. */
    static final class FakePlayer implements MusicDirector.Playback, MusicDirector.Output {
        final java.util.Map<String, Float> lengths = new java.util.HashMap<>(java.util.Map.of(
                "Deep Pressure", 226f, "Fading into Warmth", 150f, "Nocturnal Drift", 227f,
                "Svetloe Pianino", 185f, "Walking over", 228f, "Warm Moog Entry", 213f,
                "Weightless Lullaby", 205f));
        final java.util.List<String> started = new java.util.ArrayList<>();
        final java.util.Set<String> failed = new java.util.HashSet<>();
        String current;
        float left;
        float duck = 1f;
        boolean muffled;
        boolean enabled = true;
        int fades;

        @Override public boolean available() { return true; }
        @Override public boolean enabled() { return enabled; }
        @Override public String currentTrackId() { return current; }
        @Override public boolean failed(String id) { return failed.contains(id); }

        @Override
        public void play(MusicTrack t, float fadeIn) {
            current = t.id();
            left = lengths.getOrDefault(t.id(), 200f);
            started.add(t.id());
        }

        @Override
        public void fadeOut(float seconds) {
            current = null;
            fades++;
        }

        @Override public void setDuck(float level) { duck = level; }
        @Override public void setMuffled(boolean m) { muffled = m; }

        void tick(float dt) {
            if (current != null && (left -= dt) <= 0f)
                current = null;
        }
    }

    /** Ситуация по частям: по умолчанию спокойный день на поверхности. */
    static final class Sit {
        MusicSituation.Scene scene = MusicSituation.Scene.WORLD;
        MusicMood part = MusicMood.DAY;
        boolean paused, underground, sheltered, danger, exploring, building, flying, underwater;

        static Sit world(MusicMood part) { Sit s = new Sit(); s.part = part; return s; }
        static Sit menu() { Sit s = new Sit(); s.scene = MusicSituation.Scene.MENU; return s; }
        static Sit dead() { Sit s = new Sit(); s.scene = MusicSituation.Scene.DEAD; return s; }
        Sit cave() { underground = true; return this; }
        Sit danger() { danger = true; return this; }

        MusicSituation get() {
            return new MusicSituation(scene, paused, part, underground, sheltered, danger,
                    exploring, building, flying, underwater);
        }
    }

    /** Гоняет режиссёра в неизменной ситуации; возвращает долю кадров с музыкой. */
    static float simulate(MusicDirector d, FakePlayer p, MusicSituation s, float seconds) {
        int steps = Math.round(seconds / DT), on = 0;
        for (int i = 0; i < steps; i++) {
            d.update(DT, s, p, p);
            p.tick(DT);
            if (p.current != null)
                on++;
        }
        return on / (float) Math.max(1, steps);
    }

    /** Гоняет, пока не заиграет трек; возвращает прошедшее время или −1. */
    static float untilPlaying(MusicDirector d, FakePlayer p, MusicSituation s, float limit) {
        for (float t = 0f; t < limit; t += DT) {
            d.update(DT, s, p, p);
            p.tick(DT);
            if (p.current != null)
                return t;
        }
        return -1f;
    }

    // ---- режиссёр: ритм и выбор ------------------------------------------------

    private static void testMenuNeverSilent() {
        MusicLibrary lib = catalog();
        MusicDirector d = new MusicDirector(lib, new java.util.Random(1));
        FakePlayer p = new FakePlayer();
        MusicSituation menu = Sit.menu().get();
        float first = untilPlaying(d, p, menu, 10f);
        assertTrue("first menu track within 2 s (was " + first + ")", first >= 0f && first <= 2.05f);
        float silence = 0f, longest = 0f;
        for (int i = 0; i < Math.round(30 * 60 / DT); i++) {
            d.update(DT, menu, p, p);
            p.tick(DT);
            silence = p.current == null ? silence + DT : 0f;
            longest = Math.max(longest, silence);
        }
        assertTrue("menu silence stays under the gap (was " + longest + ")",
                longest <= MusicDirector.MENU_GAP_MAX + 0.1f);
        for (String id : p.started)
            assertTrue(id + " is a menu track", lib.byId(id).has(MusicMood.MENU));
        assertTrue("half an hour of menu plays several tracks", p.started.size() >= 6);
    }

    private static void testWorldShare() {
        MusicDirector d = new MusicDirector(catalog(), new java.util.Random(2));
        FakePlayer p = new FakePlayer();
        float share = simulate(d, p, Sit.world(MusicMood.DAY).get(), 3 * 3600f);
        assertTrue("music plays 25-60% of a calm day (was " + share + ")", share >= 0.25f && share <= 0.60f);
    }

    private static void testNoRepeats() {
        for (MusicSituation s : new MusicSituation[] { Sit.menu().get(), Sit.world(MusicMood.DAY).get() }) {
            MusicDirector d = new MusicDirector(catalog(), new java.util.Random(3));
            FakePlayer p = new FakePlayer();
            simulate(d, p, s, 4 * 3600f);
            assertTrue("enough tracks to judge", p.started.size() >= 10);
            for (int i = 1; i < p.started.size(); i++)
                assertTrue(s.scene() + ": " + p.started.get(i) + " twice in a row",
                        !p.started.get(i).equals(p.started.get(i - 1)));
        }
    }

    private static void testPools() {
        MusicLibrary lib = catalog();
        for (int seed = 0; seed < 30; seed++) {
            assertFirstHas(lib, seed, Sit.world(MusicMood.DAY).cave().get(), MusicMood.CAVE);
            assertFirstHas(lib, seed, Sit.world(MusicMood.NIGHT).get(), MusicMood.NIGHT);
            assertFirstHas(lib, seed, Sit.world(MusicMood.DAY).get(), MusicMood.DAY);
            MusicDirector d = new MusicDirector(lib, new java.util.Random(seed));
            FakePlayer p = new FakePlayer();
            assertTrue("a track starts in a fight", untilPlaying(d, p, Sit.world(MusicMood.NIGHT).danger().get(), 400f) >= 0f);
            assertEq("the fight track", "Deep Pressure", p.current);
        }
    }

    // ---- режиссёр: реакция -----------------------------------------------------

    private static void testDanger() {
        MusicDirector d = new MusicDirector(catalog(), new java.util.Random(4));
        FakePlayer p = new FakePlayer();
        assertTrue("a calm day track starts", untilPlaying(d, p, Sit.world(MusicMood.DAY).get(), 400f) >= 0f);
        String calm = p.current;
        MusicSituation fight = Sit.world(MusicMood.DAY).danger().get();
        simulate(d, p, fight, 1.0f);
        assertEq("no duck in the first second", 1f, p.duck);
        simulate(d, p, fight, 1.0f);
        assertEq("ducked after 1.5 s of danger", MusicDirector.DANGER_DUCK, p.duck);
        simulate(d, p, Sit.world(MusicMood.DAY).get(), 0.1f);
        assertEq("a short skirmish gives the volume back", 1f, p.duck);
        assertEq("and keeps the track", calm, p.current);
        simulate(d, p, fight, 12.2f);
        assertEq("a long fight silences calm music", null, p.current);
        assertEq("by a fade", 1, p.fades);

        MusicDirector d2 = new MusicDirector(catalog(), new java.util.Random(5));
        FakePlayer p2 = new FakePlayer();
        assertTrue("a fight track starts", untilPlaying(d2, p2, Sit.world(MusicMood.NIGHT).danger().get(), 400f) >= 0f);
        simulate(d2, p2, Sit.world(MusicMood.NIGHT).danger().get(), 15f);
        assertEq("Deep Pressure survives the fight", "Deep Pressure", p2.current);
        assertEq("and is never ducked", 1f, p2.duck);
    }

    private static void testDeathAndRespawn() {
        MusicDirector d = new MusicDirector(catalog(), new java.util.Random(6));
        FakePlayer p = new FakePlayer();
        untilPlaying(d, p, Sit.world(MusicMood.DAY).get(), 400f);
        simulate(d, p, Sit.dead().get(), DT);
        assertEq("death fades the music", null, p.current);
        int before = p.started.size();
        simulate(d, p, Sit.dead().get(), 1800f);
        assertEq("nothing starts while dead", before, p.started.size());

        int trials = 300, respawned = 0;
        for (int seed = 0; seed < trials; seed++) {
            MusicDirector t = new MusicDirector(catalog(), new java.util.Random(1000 + seed));
            FakePlayer q = new FakePlayer();
            simulate(t, q, Sit.world(MusicMood.DAY).get(), 1f);
            simulate(t, q, Sit.dead().get(), 5f);
            simulate(t, q, Sit.world(MusicMood.DAY).get(), 7f);
            if (!q.started.isEmpty()) {
                respawned++;
                assertEq("respawn plays the warm home track", "Fading into Warmth", q.started.get(0));
            }
        }
        float share = respawned / (float) trials;
        assertTrue("respawn brings music about half the time (was " + share + ")", share > 0.40f && share < 0.60f);
    }

    private static void testMenuWorldTransitions() {
        MusicLibrary leave = new MusicLibrary(java.util.List.of(
                track("World", MusicMood.DAY), track("Menu", MusicMood.MENU)));
        MusicDirector d = new MusicDirector(leave, new java.util.Random(7));
        FakePlayer p = new FakePlayer();
        untilPlaying(d, p, Sit.world(MusicMood.DAY).get(), 400f);
        assertEq("the world track plays", "World", p.current);
        simulate(d, p, Sit.menu().get(), DT);
        assertEq("leaving the world fades it", null, p.current);
        float wait = untilPlaying(d, p, Sit.menu().get(), 10f);
        assertTrue("a menu track follows within 3 s (was " + wait + ")", wait >= 0f && wait <= 3.05f);
        assertEq("and it is the menu track", "Menu", p.current);

        MusicLibrary arrive = new MusicLibrary(java.util.List.of(
                track("Day menu", MusicMood.MENU, MusicMood.DAY), track("Night", MusicMood.NIGHT)));
        MusicDirector day = new MusicDirector(arrive, new java.util.Random(8));
        FakePlayer pd = new FakePlayer();
        untilPlaying(day, pd, Sit.menu().get(), 10f);
        simulate(day, pd, Sit.world(MusicMood.DAY).get(), 1f);
        assertEq("a fitting menu track survives arrival", "Day menu", pd.current);
        assertEq("without a fade", 0, pd.fades);
        MusicDirector night = new MusicDirector(arrive, new java.util.Random(8));
        FakePlayer pn = new FakePlayer();
        untilPlaying(night, pn, Sit.menu().get(), 10f);
        simulate(night, pn, Sit.world(MusicMood.NIGHT).get(), DT);
        assertEq("an unfitting one fades on arrival", null, pn.current);
    }

    private static void testSunsetMoment() {
        int trials = 300, started = 0;
        for (int seed = 0; seed < trials; seed++) {
            MusicDirector d = new MusicDirector(catalog(), new java.util.Random(2000 + seed));
            FakePlayer p = new FakePlayer();
            simulate(d, p, Sit.world(MusicMood.DAY).get(), 1f);
            simulate(d, p, Sit.world(MusicMood.DUSK).get(), 6f);
            if (!p.started.isEmpty()) {
                started++;
                assertTrue(p.started.get(0) + " is a dusk track", catalog().byId(p.started.get(0)).has(MusicMood.DUSK));
            }
        }
        float share = started / (float) trials;
        assertTrue("a silent sunset starts music about a third of the time (was " + share + ")",
                share > 0.25f && share < 0.45f);
    }

    private static void testMomentCooldown() {
        int trials = 300, first = 0;
        for (int seed = 0; seed < trials; seed++) {
            MusicDirector d = new MusicDirector(catalog(), new java.util.Random(3000 + seed));
            FakePlayer p = new FakePlayer();
            MusicSituation cave = Sit.world(MusicMood.DAY).cave().get();
            simulate(d, p, Sit.world(MusicMood.DAY).get(), 1f);
            simulate(d, p, cave, 21f);
            if (!p.started.isEmpty()) {
                first++;
                continue;
            }
            simulate(d, p, Sit.world(MusicMood.DAY).get(), 10f);
            simulate(d, p, cave, 21f);
            assertTrue("a second cave visit does not reroll within the cooldown", p.started.isEmpty());
        }
        float share = first / (float) trials;
        assertTrue("the cave moment fires about 40% of the time (was " + share + ")", share > 0.30f && share < 0.50f);
    }

    private static void testMismatchFade() {
        MusicDirector d = new MusicDirector(catalog(), new java.util.Random(9));
        FakePlayer p = new FakePlayer();
        untilPlaying(d, p, Sit.world(MusicMood.DAY).cave().get(), 400f);
        String caveTrack = p.current;
        simulate(d, p, Sit.world(MusicMood.DAY).get(), 19.5f);
        assertEq("a cave track holds for the grace period", caveTrack, p.current);
        simulate(d, p, Sit.world(MusicMood.DAY).get(), 1f);
        assertEq("then fades on the sunny surface", null, p.current);
    }

    private static void testWake() {
        int trials = 300, morning = 0;
        for (int seed = 0; seed < trials; seed++) {
            MusicDirector d = new MusicDirector(catalog(), new java.util.Random(4000 + seed));
            FakePlayer p = new FakePlayer();
            untilPlaying(d, p, Sit.world(MusicMood.NIGHT).get(), 400f);
            int before = p.started.size();
            d.onWake();
            simulate(d, p, Sit.world(MusicMood.DAWN).get(), DT);
            assertEq("waking fades the night track", null, p.current);
            simulate(d, p, Sit.world(MusicMood.DAWN).get(), 5f);
            if (p.started.size() > before) {
                morning++;
                assertEq("the morning track", "Warm Moog Entry", p.current);
            }
        }
        float share = morning / (float) trials;
        assertTrue("waking brings the morning track about 60% of the time (was " + share + ")",
                share > 0.50f && share < 0.70f);
    }

    private static void testDisabled() {
        MusicDirector d = new MusicDirector(catalog(), new java.util.Random(10));
        FakePlayer p = new FakePlayer();
        untilPlaying(d, p, Sit.menu().get(), 10f);
        p.enabled = false;
        simulate(d, p, Sit.menu().get(), DT);
        assertEq("the slider at zero fades the track", null, p.current);
        int before = p.started.size();
        simulate(d, p, Sit.menu().get(), 60f);
        assertEq("and nothing starts", before, p.started.size());
        p.enabled = true;
        float wait = untilPlaying(d, p, Sit.menu().get(), 20f);
        assertTrue("raising the slider brings the menu music back (was " + wait + ")",
                wait >= 0f && wait <= MusicDirector.MENU_GAP_MAX + 0.1f);
    }

    // ---- декодер ---------------------------------------------------------------

    private static void testMp3Chunks() throws Exception {
        File file = new File(AppPaths.file("assets/music"), "Fading into Warmth.mp3");
        int total = 48000 * 2 * 4;   // четыре секунды стерео
        short[] whole = new short[total];
        try (com.mineclone.audio.Mp3Stream s = new com.mineclone.audio.Mp3Stream(file)) {
            assertEq("sample rate", 48000, s.sampleRate());
            assertEq("channels", 2, s.channels());
            assertEq("one big read fills the buffer", total, s.read(whole, 0, total));
        }
        short[] pieces = new short[total];
        int[] sizes = { 1, 7, 1153, 3001, 4097, 2 };
        try (com.mineclone.audio.Mp3Stream s = new com.mineclone.audio.Mp3Stream(file)) {
            int at = 0, i = 0;
            while (at < total) {
                int n = s.read(pieces, at, Math.min(sizes[i++ % sizes.length], total - at));
                assertTrue("a chunk is read", n > 0);
                at += n;
            }
        }
        assertTrue("chunks of any size join into the same PCM", java.util.Arrays.equals(whole, pieces));
        boolean audible = false;
        for (short v : whole)
            audible |= Math.abs(v) > 1000;
        assertTrue("the decoded start is not silence", audible);

        long samples = 0;
        try (com.mineclone.audio.Mp3Stream s = new com.mineclone.audio.Mp3Stream(file)) {
            short[] buf = new short[24000];
            int n;
            while ((n = s.read(buf, 0, buf.length)) > 0)
                samples += n;
            assertEq("after the end only -1", -1, s.read(buf, 0, buf.length));
        }
        double seconds = samples / 2.0 / 48000.0;
        assertTrue("the whole track decodes, 2:30 (was " + seconds + ")", Math.abs(seconds - 150.0) < 1.0);
    }

    // ---- ситуация из мира ------------------------------------------------------

    private static void testSenseProbes() {
        com.mineclone.world.World w = new com.mineclone.world.World(99L);
        com.mineclone.world.Chunk c = w.getChunk(0, 0);
        for (int x = 0; x < com.mineclone.world.Chunk.SIZE_X; x++)
            for (int z = 0; z < com.mineclone.world.Chunk.SIZE_Z; z++)
                for (int y = 0; y < com.mineclone.world.Chunk.SIZE_Y; y++)
                    c.set(x, y, z, y <= 10 ? com.mineclone.world.BlockType.STONE : com.mineclone.world.BlockType.AIR);
        for (int y = 20; y < 26; y++)
            c.set(4, y, 4, com.mineclone.world.BlockType.STONE);
        assertEq("six blocks of rock above the head", 6, com.mineclone.game.MusicSense.solidAbove(w, 4, 12, 4));
        assertEq("the roof is eight blocks up", 8, com.mineclone.game.MusicSense.roofDistance(w, 4, 12, 4));
        assertEq("open sky has no rock", 0, com.mineclone.game.MusicSense.solidAbove(w, 8, 12, 8));
        assertEq("open sky has no roof", -1, com.mineclone.game.MusicSense.roofDistance(w, 8, 12, 8));

        assertTrue("dark under rock is a cave", com.mineclone.game.MusicSense.isUnderground(0, 6));
        assertTrue("lit under rock is not", !com.mineclone.game.MusicSense.isUnderground(12, 6));
        assertTrue("a thin roof is not a cave", !com.mineclone.game.MusicSense.isUnderground(0, 2));
        assertTrue("a roof and a torch make a shelter", com.mineclone.game.MusicSense.isSheltered(false, 3, 9));
        assertTrue("a roof without light is not", !com.mineclone.game.MusicSense.isSheltered(false, 3, 4));
        assertTrue("a lit cave is not a home", !com.mineclone.game.MusicSense.isSheltered(true, 3, 9));
        assertTrue("a torch under the open sky is not", !com.mineclone.game.MusicSense.isSheltered(false, -1, 15));
        assertTrue("a roof too high is not", !com.mineclone.game.MusicSense.isSheltered(false, 13, 15));
    }

    private static void testSenseActivity() {
        com.mineclone.game.MusicSense sense = new com.mineclone.game.MusicSense();
        com.mineclone.game.Player pl = new com.mineclone.game.Player();
        pl.position.set(0f, 70f, 0f);
        java.util.List<com.mineclone.world.entity.Mob> none = java.util.List.of();
        float noon = (float) (Math.PI / 2);
        MusicSituation.Scene w = MusicSituation.Scene.WORLD;
        for (int i = 0; i < 12; i++) {
            sense.onBlockPlaced();
            sense.sample(0.5f, w, false, null, pl, none, noon);
        }
        assertTrue("a dozen blocks in six seconds is building", sense.sample(DT, w, false, null, pl, none, noon).building());
        for (int i = 0; i < 2400; i++)
            sense.sample(DT, w, false, null, pl, none, noon);
        assertTrue("two idle minutes is not", !sense.building());

        sense.reset();
        for (float t = 0f; t < 70f; t += DT) {
            pl.position.x += 2f * DT;
            sense.sample(DT, w, false, null, pl, none, noon);
        }
        assertTrue("walking away at 2 blocks/s is travel", sense.exploring());
        sense.reset();
        for (float t = 0f; t < 120f; t += DT) {
            pl.position.set(5f * (float) Math.cos(t), 70f, 5f * (float) Math.sin(t));
            sense.sample(DT, w, false, null, pl, none, noon);
        }
        assertTrue("circling the base is not", !sense.exploring());
        MusicSituation paused = sense.sample(DT, w, true, null, pl, none, noon);
        assertTrue("pause is reported", paused.paused());
        assertEq("day part comes from the clock", MusicMood.DAY, paused.dayPart());
    }

    private static void testSenseDanger() {
        var chase = com.mineclone.world.entity.Mob.State.CHASE;
        assertTrue("a chasing zombie", com.mineclone.game.MusicSense.threatens(false, true, false, chase, 10f));
        assertTrue("an angry wolf attacking",
                com.mineclone.game.MusicSense.threatens(false, false, true, com.mineclone.world.entity.Mob.State.ATTACK, 3f));
        assertTrue("a zombie hesitating at the light",
                com.mineclone.game.MusicSense.threatens(false, true, false, com.mineclone.world.entity.Mob.State.STALK, 8f));
        assertTrue("a wandering zombie is not",
                !com.mineclone.game.MusicSense.threatens(false, true, false, com.mineclone.world.entity.Mob.State.WANDER, 5f));
        assertTrue("a dead one is not", !com.mineclone.game.MusicSense.threatens(true, true, false, chase, 5f));
        assertTrue("a far one is not", !com.mineclone.game.MusicSense.threatens(false, true, false, chase, 17f));
        assertTrue("a calm wolf hunting rabbits is not",
                !com.mineclone.game.MusicSense.threatens(false, false, false, com.mineclone.world.entity.Mob.State.HUNT, 5f));

        com.mineclone.game.MusicSense sense = new com.mineclone.game.MusicSense();
        com.mineclone.game.Player pl = new com.mineclone.game.Player();
        pl.position.set(0f, 70f, 0f);
        var zombie = new com.mineclone.world.entity.Mob(com.mineclone.world.entity.MobType.ZOMBIE,
                5f, 70f, 0f, new java.util.Random(1));
        zombie.state = chase;
        MusicSituation.Scene w = MusicSituation.Scene.WORLD;
        assertTrue("a chase is danger", sense.sample(DT, w, false, null, pl, java.util.List.of(zombie), 1f).danger());
        zombie.state = com.mineclone.world.entity.Mob.State.WANDER;
        assertTrue("danger lingers a moment", sense.sample(2f, w, false, null, pl, java.util.List.of(zombie), 1f).danger());
        for (int i = 0; i < 100; i++)
            sense.sample(DT, w, false, null, pl, java.util.List.of(zombie), 1f);
        assertTrue("and then passes", !sense.sample(DT, w, false, null, pl, java.util.List.of(zombie), 1f).danger());
    }

    private static void assertFirstHas(MusicLibrary lib, int seed, MusicSituation s, MusicMood mood) {
        MusicDirector d = new MusicDirector(lib, new java.util.Random(seed));
        FakePlayer p = new FakePlayer();
        assertTrue("a track starts for " + mood, untilPlaying(d, p, s, 400f) >= 0f);
        assertTrue(p.current + " fits " + mood, lib.byId(p.current).has(mood));
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

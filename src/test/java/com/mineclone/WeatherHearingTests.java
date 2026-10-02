package com.mineclone;

import com.mineclone.sim.EntityStore;
import com.mineclone.sim.Participants;
import com.mineclone.sim.WorldClock;
import com.mineclone.sim.WorldEvents;
import com.mineclone.sim.WorldSession;
import com.mineclone.sim.ecology.WeatherHearing;
import com.mineclone.world.Biome;
import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.GenProfile;
import com.mineclone.world.Weather;
import com.mineclone.world.World;
import com.mineclone.world.entity.Mob;
import com.mineclone.world.entity.MobSpawner;
import com.mineclone.world.entity.MobType;
import java.util.Random;
import org.joml.Vector3f;

/** LW-09: actual mob hearing, source exposure and the host/server weather seam. */
public final class WeatherHearingTests {
    private static final long SEED = 99L;
    private static final float NOISE = WorldSession.NOISE_BREAK;

    public static void runAll(TestMain.Runner r) {
        r.run("a spider investigates stone broken at twelve blocks in clear weather but not thunder", WeatherHearingTests::spider);
        r.run("rain hearing masks only exposed rainy sources, never desert, snow or a roof", WeatherHearingTests::sourceExposure);
        r.run("scheduled rain hearing fades deterministically with the actual weather front", WeatherHearingTests::fade);
        r.run("host weather and dedicated natural sampling give identical mob hearing", WeatherHearingTests::parity);
        r.run("host weather overrides affect living hearing while ecology-off noise stays unchanged", WeatherHearingTests::overrideAndLegacy);
    }

    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private static Weather.State state(Weather.Kind kind) {
        return new Weather.State(kind, kind.precipitation, kind.cloudiness, kind.wind, kind.storm);
    }

    private static final class Source {
        final World world = new World(SEED, GenProfile.FLAT);
        final Vector3f at;

        Source(Biome biome) {
            at = locate(world, biome);
            world.getChunk(Math.floorDiv((int) Math.floor(at.x), Chunk.SIZE_X), Math.floorDiv((int) Math.floor(at.z), Chunk.SIZE_Z));
        }

        float loudness(Weather.State weather) {
            return WeatherHearing.atSource(world, weather, at.x, at.y, at.z, NOISE);
        }

        Mob spider() { return new Mob(MobType.SPIDER, at.x + 12f, at.y, at.z, new Random(7)); }

        void noise(Mob mob, Weather.State weather) {
            mob.hearNoise(at.x, at.y, at.z, loudness(weather));
        }
    }

    private static void spider() {
        Source f = new Source(Biome.PLAINS);
        Mob clear = f.spider(), storm = f.spider();
        f.noise(clear, state(Weather.Kind.CLEAR));
        f.noise(storm, state(Weather.Kind.STORM));
        check(clear.isInvestigating(), "clear-weather stone noise did not attract the actual spider at twelve blocks");
        check(!storm.isInvestigating(), "thunder failed to mask the actual spider's twelve-block noise");
        check(f.loudness(state(Weather.Kind.CLEAR)) == NOISE && f.loudness(state(Weather.Kind.STORM)) == 4f,
                "clear radius changed or full thunder did not retain a four-block nearby hearing radius");
        f.world.getChunk(Math.floorDiv((int) Math.floor(clear.position.x), Chunk.SIZE_X),
                Math.floorDiv((int) Math.floor(clear.position.z), Chunk.SIZE_Z));
        float before = clear.position.x;
        clear.onGround = true;
        clear.update(f.world, new Vector3f(clear.position).add(0, 0, 100), .1f, 0f, true);
        check(clear.isInvestigating() && clear.position.x < before, "the clear-weather spider did not move toward the noise it heard");
        check(f.loudness(state(Weather.Kind.HEAVY)) < 12f, "heavy rain did not mask this distant noise");
    }

    private static void sourceExposure() {
        Weather.State storm = state(Weather.Kind.STORM);
        for (Biome biome : new Biome[] {Biome.DESERT, Biome.BADLANDS, Biome.VOLCANIC, Biome.TUNDRA, Biome.TAIGA, Biome.ALPINE}) {
            Source f = new Source(biome);
            check(f.loudness(storm) == NOISE, "a dust or snow source acquired a rain hearing mask in " + biome);
        }
        Source high = new Source(Biome.PLAINS);
        high.at.y = World.SEA_LEVEL + Weather.SNOW_ALTITUDE + 1;
        check(high.loudness(storm) == NOISE, "high-altitude snow was treated as audible rain");
        Source covered = new Source(Biome.PLAINS);
        int x = (int) Math.floor(covered.at.x), z = (int) Math.floor(covered.at.z), y = (int) Math.floor(covered.at.y) + 3;
        covered.world.setBlock(x, y, z, BlockType.GLASS);
        check(covered.loudness(storm) == NOISE, "a transparent solid roof failed to shelter the sound source");
        covered.world.setBlock(x, y, z, BlockType.AIR);
        covered.world.setBlock(x + 12, y, z, BlockType.STONE);
        check(covered.loudness(storm) == 4f, "a listener's roof incorrectly sheltered the exposed sound source");
        Weather.State dryStorm = new Weather.State(Weather.Kind.STORM, 0f, 1f, 4.2f, 1f);
        check(covered.loudness(dryStorm) == NOISE, "the front kind or wind alone masked a source without rain");
        World unloaded = new World(SEED, GenProfile.FLAT);
        float result = WeatherHearing.atSource(unloaded, storm, covered.at.x, covered.at.y, covered.at.z, NOISE);
        check(result == NOISE && !unloaded.getLoadedChunks().iterator().hasNext(), "a weather hearing query generated or guessed shelter in an unloaded source column");
    }

    /** An actual clear -> thunder -> clear sequence, not manually fabricated intermediate strengths. */
    private static long stormFront() {
        for (long front = 1; front < 10000; front++)
            if (Weather.kindAt(SEED, front - 1) == Weather.Kind.CLEAR && Weather.kindAt(SEED, front) == Weather.Kind.STORM
                    && Weather.kindAt(SEED, front + 1) == Weather.Kind.CLEAR) return front;
        throw new AssertionError("fixture lacks a scheduled clear/thunder/clear sequence");
    }

    private static void fade() {
        Source f = new Source(Biome.PLAINS);
        float start = stormFront() * Weather.FRONT_LENGTH;
        float previous = NOISE;
        for (int i = 0; i <= 60; i++) {
            Weather.State weather = Weather.sample(SEED, start + i);
            float next = f.loudness(weather);
            check(next <= previous && next >= 4f && next == f.loudness(Weather.sample(SEED, start + i)),
                    "rain hearing did not fade in monotonically and deterministically at second " + i);
            if (i == 0) check(next == NOISE, "the new STORM enum jumped ahead of its clear blended front");
            if (i == 30) check(next > 4f && next < NOISE, "the front had no intermediate hearing strength");
            if (i == 60) check(next == 4f, "the settled scheduled thunder did not reach its hearing mask");
            previous = next;
        }
        float clearStart = start + Weather.FRONT_LENGTH;
        previous = 4f;
        for (int i = 0; i <= 60; i++) {
            float next = f.loudness(Weather.sample(SEED, clearStart + i));
            check(next >= previous && next <= NOISE, "rain hearing did not fade out with the front");
            previous = next;
        }
        check(previous == NOISE, "the clear front retained residual rain masking");
    }

    private static Mob sessionNoise(Source f, WorldClock clock, Weather.State override, boolean living) {
        EntityStore entities = new EntityStore();
        Mob spider = f.spider();
        entities.mobs.add(spider);
        WorldSession session = new WorldSession(f.world, clock, new MobSpawner(SEED), entities, new Participants(),
                new WorldSession.Host() {
                    @Override public Vector3f mobFocus() { return f.at; }
                    @Override public boolean spawnMobs() { return false; }
                    @Override public Weather.State hearingWeather() { return override; }
                }, WorldEvents.NONE);
        session.ecology().setEnabled(living);
        session.noise(f.at.x, f.at.y, f.at.z, NOISE);
        return spider;
    }

    private static void parity() {
        float start = stormFront() * Weather.FRONT_LENGTH;
        for (float seconds : new float[] {start, start + 30, start + 60, start + Weather.FRONT_LENGTH + 60}) {
            WorldClock clock = new WorldClock(seconds * WorldClock.TIME_SCALE);
            Weather.State actual = Weather.sample(SEED, clock.frontSeconds());
            Mob host = sessionNoise(new Source(Biome.PLAINS), clock, actual, true);
            Mob server = sessionNoise(new Source(Biome.PLAINS), new WorldClock(clock.gameTime()), null, true);
            check(host.isInvestigating() == server.isInvestigating(), "host and dedicated natural weather gave different hearing at " + seconds);
            boolean expected = new Source(Biome.PLAINS).loudness(actual) >= 12;
            check(host.isInvestigating() == expected, "both hosts bypassed the actual sampled weather strength");
        }
    }

    private static void overrideAndLegacy() {
        WorldClock clearClock = new WorldClock(0.5);
        check(Weather.sample(SEED, clearClock.frontSeconds()).precipitation() == 0f, "override fixture's natural weather is not clear");
        Mob natural = sessionNoise(new Source(Biome.PLAINS), clearClock, null, true);
        Mob forced = sessionNoise(new Source(Biome.PLAINS), new WorldClock(0.5), state(Weather.Kind.STORM), true);
        Mob legacy = sessionNoise(new Source(Biome.PLAINS), new WorldClock(0.5), state(Weather.Kind.STORM), false);
        check(natural.isInvestigating() && !forced.isInvestigating(), "a host's actual weather override was not preferred over its clear schedule");
        check(legacy.isInvestigating(), "ecology OFF changed the legacy noise radius under a forced storm");
    }

    private static Vector3f locate(World world, Biome biome) {
        for (int x = -2048; x <= 2048; x += 32)
            for (int z = -2048; z <= 2048; z += 32)
                if (world.biomes.biomeAt(x, z) == biome) return new Vector3f(x + .5f, 65.001f, z + .5f);
        throw new AssertionError("missing fixture biome " + biome);
    }
}

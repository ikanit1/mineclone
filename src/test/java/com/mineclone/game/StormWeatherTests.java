package com.mineclone.game;

import com.mineclone.audio.SoundEngine;
import com.mineclone.audio.StormAmbience;
import com.mineclone.world.Biome;
import com.mineclone.world.Weather;
import com.mineclone.world.World;
import org.joml.Vector3f;
import java.util.List;

public final class StormWeatherTests {
    public static void runAll(PlayerPhysicsTests.Runner r) {
        r.run("sandstorms are dry and limited to sandy biomes", StormWeatherTests::desert);
        r.run("storm fog stays dense at every draw distance", StormWeatherTests::visibility);
        r.run("weather transitions smoothly across desert boundaries and clears fully", StormWeatherTests::transitions);
        r.run("storm wind loops continuously and fades in shelter or water", StormWeatherTests::audio);
    }

    private static void desert() {
        World w = new World(99);
        for (Biome biome : new Biome[] {Biome.DESERT, Biome.BADLANDS, Biome.FOREST, Biome.TUNDRA}) {
            Atmosphere a = new Atmosphere();
            a.force(Weather.Kind.STORM, 600);
            a.update(1f / 60, w, 1.57f, 1, locate(w, biome));
            if (biome == Biome.DESERT || biome == Biome.BADLANDS) {
                check(a.dust == 1 && a.rain() == 0 && a.snowfall() == 0, "sandstorm without rain/snow in " + biome);
                check(a.thunderstorm() == 0, "dry storm has no rain lightning");
                check(a.visibility <= .081f && a.haze > .03f, "sandstorm must obscure the view");
            } else {
                check(a.dust == 0, "no desert dust in " + biome);
                check(biome == Biome.FOREST ? a.thunderstorm() == 1 : a.blizzard() == 1 && a.thunderstorm() == 0,
                        "retain rain storms and blizzards");
            }
        }
        check(Weather.dust(Biome.VOLCANIC, 1) == 0, "arid alone does not imply sand");
        check(Weather.dust(Biome.DESERT, 0) == 0, "clear desert has no storm dust");
    }

    private static void visibility() {
        for (int radius : new int[] {2, 6, 12, 24}) {
            float distance = radius * 16 * 1.05f;
            float rain = Weather.visibility(1, 1, false);
            float dust = Weather.visibility(0, 1, false, 1);
            check(Weather.fogEnd(distance, rain, 1, 0) <= 28, "rain storm cap independent of graphics setting");
            check(Weather.fogEnd(distance, dust, 1, 1) <= 12, "sandstorm has very poor visibility");
            check(Weather.fogEnd(distance, 1, 0, 0) == distance, "clear weather preserves draw distance");
            float previous = distance;
            for (int i = 0; i <= 100; i++) {
                float k = i / 100f;
                float end = Weather.fogEnd(distance, Weather.visibility(0, k, false, k), k, k);
                check(end <= previous + .001f && end >= 8, "fog strengthens smoothly without hiding own feet");
                previous = end;
            }
        }
    }

    private static void transitions() {
        World w = new World(99);
        Vector3f desert = locate(w, Biome.DESERT), forest = locate(w, Biome.FOREST);
        var a = new Atmosphere();
        a.force(Weather.Kind.STORM, 600);
        a.update(.016f, w, 1.57f, 1, desert);
        a.update(.016f, w, 1.57f, 1, forest);
        check(a.dust > .98f && a.dust < 1 && a.rain() > 0 && a.rain() < .02f, "biome boundary blends both effects");
        a.force(Weather.Kind.CLEAR, 600);
        for (int i = 0; i < 1800; i++) a.update(1f / 60, w, 1.57f, 1, forest);
        check(a.dust < .001f && a.storm < .001f && a.visibility > .999f, "storm clears completely");
        a.snap();
        a.update(1f / 60, w, 1.57f, 1, desert);
        check(a.dust == 0 && a.visibility == 1, "world reset has no residual dust");
    }

    private static final class Audio extends SoundEngine {
        boolean playing;
        int updates;
        @Override public void updateLoopOneOf(String key, List<String> paths, float gain, float pitch) {
            check(key.equals(StormAmbience.LOOP_KEY) && gain > 0 && gain <= .65f, "bounded storm source");
            playing = true; updates++;
        }
        @Override public void stopLoop(String key) { playing = false; }
    }

    private static void audio() {
        for (int state = 0; state < 4; state++) {
            var sound = new Audio();
            var wind = new StormAmbience(sound, List.of("wind.ogg"));
            for (int i = 0; i < 600; i++) wind.update(1f / 60, 1, 1, 6, 15, true, false);
            check(sound.updates == 600 && sound.playing, "continuous bed without timer gaps");
            float loud = wind.gain();
            for (int i = 0; i < 300; i++) wind.update(1f / 60, state == 0 ? 0 : 1, state == 0 ? 0 : 1,
                    6, state == 1 ? 0 : 15, state != 3, state == 2);
            if (state < 3) check(!sound.playing && wind.gain() == 0, "clear/cave/underwater releases source");
            else check(wind.gain() < loud * .25f, "roof muffles gusts");
            wind.reset(); check(!sound.playing && wind.gain() == 0, "world exit releases source");
        }
    }

    private static Vector3f locate(World w, Biome biome) {
        for (int x = -2048; x <= 2048; x += 32) for (int z = -2048; z <= 2048; z += 32)
            if (w.biomes.biomeAt(x, z) == biome) return new Vector3f(x, World.SEA_LEVEL + 2, z);
        throw new AssertionError("Missing biome in fixture: " + biome);
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}

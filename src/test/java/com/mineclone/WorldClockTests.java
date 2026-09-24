package com.mineclone;

import com.mineclone.game.Hud;
import com.mineclone.sim.WorldClock;
import com.mineclone.world.NightSky;
import com.mineclone.world.SleepRules;
import java.nio.ByteBuffer;

public final class WorldClockTests {
    public static void runAll(TestMain.Runner runner) {
        runner.run("time commands preserve day and moon while simulation ticks stay monotonic", WorldClockTests::commands);
        runner.run("client frames and server ticks produce equal world light", WorldClockTests::timestepParity);
        runner.run("clock sections retain double time and fractional simulation ticks", WorldClockTests::persistence);
        runner.run("HUD and sleep share world-clock conversion", WorldClockTests::presentation);
        runner.run("invalid time and clock sections fail without changing the clock", WorldClockTests::invalidValues);
    }

    public static void main(String[] args) throws Exception {
        commands(); timestepParity(); persistence(); presentation(); invalidValues();
        System.out.println("WORLD_CLOCK PASS: commands, timestep parity, persistence, presentation, validation");
    }

    private static void commands() {
        WorldClock clock = new WorldClock(5 * NightSky.CYCLE + 0.1);
        clock.advance(1);
        check(clock.worldTicks() == 20, "one second must advance 20 simulation ticks");
        clock.setHours(12);
        check(NightSky.dayIndex(clock.gameTime()) == 5 && clock.moonPhase() == 5,
                "/time set changed the accumulated day or moon phase");
        near(clock.daylight(), 1, 1e-6, "noon daylight");
        clock.setHours(0);
        check(NightSky.dayIndex(clock.gameTime()) == 5 && clock.moonPhase() == 5, "midnight reset the day");
        clock.addHours(24);
        check(clock.moonPhase() == 6, "adding one day did not advance the moon");
        clock.advanceToDawn();
        check(clock.moonPhase() == 7, "sleep did not move to the next dawn");
        check(clock.worldTicks() == 20, "commands incorrectly changed the simulation counter");
        clock.setGameTime(0);
        clock.advance(0.1);
        check(clock.worldTicks() == 22, "setting time back rewound worldTicks");
    }

    private static void timestepParity() {
        WorldClock client = new WorldClock(), server = new WorldClock();
        for (int second = 0; second < 1200; second++) {
            for (int i = 0; i < 60; i++) client.advance(1.0 / 60.0);
            for (int i = 0; i < 20; i++) server.advance(0.05);
            near(client.daylight(), server.daylight(), 1e-6, "client/server daylight mismatch");
            near(client.dayPhase(), server.dayPhase(), 1e-6, "client/server day phase mismatch");
            check(client.worldTicks() == server.worldTicks(), "frame grouping changed simulation ticks");
        }
        check(client.worldTicks() == 24_000, "20 Hz tick count drifted");
        near(client.gameTime(), server.gameTime(), 1e-10, "time integration drift");
    }

    private static void persistence() {
        WorldClock clock = new WorldClock(10_000 * NightSky.CYCLE + 0.321123456789);
        clock.advance(0.021);
        WorldClock restored = WorldClock.fromSaved(0, clock.encode());
        near(restored.gameTime(), clock.gameTime(), 0, "double precision lost during save");
        check(restored.worldTicks() == clock.worldTicks() && restored.moonPhase() == clock.moonPhase(),
                "clock save lost world ticks or moon");
        clock.advance(0.029);
        restored.advance(0.029);
        check(clock.worldTicks() == 1 && restored.worldTicks() == 1, "fractional tick lost at reload");
        near(restored.gameTime(), clock.gameTime(), 0, "reload changed future integration");
        double legacyTime = (float) (Math.PI / 6.0);
        WorldClock legacy = WorldClock.fromSaved(legacyTime, null);
        check(legacy.worldTicks() == (long) (legacyTime / WorldClock.TIME_SCALE * 20),
                "legacy world ticks were not derived from old game time");
    }

    private static void presentation() {
        for (int hour = 0; hour < 24; hour++) {
            double time = WorldClock.hoursToGameTime(hour);
            check(WorldClock.clockMinutes(time) == hour * 60, "clock minute conversion at " + hour);
            check(Hud.clockText((float) time).equals(String.format("%02d:00", hour)), "HUD clock at " + hour);
            near(WorldClock.dayPhaseAt(time), WorldClock.normalize(time) / NightSky.CYCLE, 1e-7,
                    "negative/positive day phase");
        }
        float night = (float) (3 * NightSky.CYCLE + Math.PI * 1.5);
        near(SleepRules.nextDawn(night), WorldClock.nextDawn(night), 2e-6, "sleep clock diverged");
        check(WorldClock.nextDawn(NightSky.CYCLE) == NightSky.CYCLE * 2, "dawn must be strictly in the future");
    }

    private static void invalidValues() {
        WorldClock clock = new WorldClock();
        byte[] before = clock.encode();
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            expectInvalid(() -> clock.advance(value));
            expectInvalid(() -> clock.setGameTime(value));
            expectInvalid(() -> clock.setHours(value));
        }
        expectInvalid(() -> clock.advance(-1));
        check(java.util.Arrays.equals(before, clock.encode()), "invalid input partially changed clock state");
        expectInvalid(() -> WorldClock.fromSaved(0, new byte[1]));
        byte[] unknownVersion = before.clone();
        ByteBuffer.wrap(unknownVersion).putInt(2);
        expectInvalid(() -> WorldClock.fromSaved(0, unknownVersion));
        byte[] negativeTicks = before.clone();
        ByteBuffer.wrap(negativeTicks).putLong(12, -1);
        expectInvalid(() -> WorldClock.fromSaved(0, negativeTicks));
    }

    private static void near(double value, double expected, double epsilon, String message) {
        check(Math.abs(value - expected) <= epsilon, message + ": " + value + " != " + expected);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void expectInvalid(Runnable operation) {
        try { operation.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("invalid clock input was accepted");
    }
}

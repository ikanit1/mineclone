package com.mineclone.sim;

import com.mineclone.world.NightSky;
import java.nio.ByteBuffer;

/** World time and a monotonic 20 Hz simulation clock, independent of rendering. */
public final class WorldClock {
    /** Radians per real second: one day takes approximately 21 real minutes. */
    public static final double TIME_SCALE = 0.005;
    public static final int TICKS_PER_SECOND = 20;
    public static final double DEFAULT_TIME = Math.PI / 6.0;
    public static final String SAVE_SECTION = "clock";
    private static final int SECTION_VERSION = 1;
    private static final int SECTION_BYTES = Integer.BYTES + Double.BYTES + Long.BYTES + Double.BYTES;

    private double gameTime;
    private long worldTicks;
    private double fractionalTick;

    public WorldClock() { this(DEFAULT_TIME, 0, 0); }

    /** Construct a new simulation at a given absolute time, with no elapsed ticks. */
    public WorldClock(double gameTime) { this(gameTime, 0, 0); }

    /** Another clock's time and ticks — a guest joining the host's world (protocol v8). */
    public static WorldClock synced(double gameTime, long worldTicks) {
        return new WorldClock(gameTime, worldTicks, 0);
    }

    private WorldClock(double gameTime, long worldTicks, double fractionalTick) {
        requireFinite(gameTime, "gameTime");
        if (worldTicks < 0 || !Double.isFinite(fractionalTick) || fractionalTick < 0 || fractionalTick >= 1)
            throw new IllegalArgumentException("Invalid world tick counter");
        this.gameTime = gameTime;
        this.worldTicks = worldTicks;
        this.fractionalTick = fractionalTick;
    }

    public double gameTime() { return gameTime; }
    /** Float view for the existing rendering/network APIs; accumulation stays double. */
    public float gameTimeFloat() { return (float) gameTime; }
    public long worldTicks() { return worldTicks; }
    public float daylight() { return daylightAt(gameTime); }
    /** Seconds of game time as weather fronts count them ({@code Weather.sample}). */
    public float frontSeconds() { return (float) (gameTime / TIME_SCALE); }
    public float dayPhase() { return dayPhaseAt(gameTime); }
    public int moonPhase() { return NightSky.moonPhase(gameTime); }

    /** Advance elapsed simulation time. Commands and sleep never rewind this counter. */
    public void advance(double dt) {
        requireFinite(dt, "dt");
        if (dt < 0) throw new IllegalArgumentException("dt must not be negative");
        double nextTime = gameTime + dt * TIME_SCALE;
        requireFinite(nextTime, "advanced gameTime");
        double ticks = fractionalTick + dt * TICKS_PER_SECOND;
        // Sub-nanotick rounding prevents 3 * (1/60 s) from missing its 20 Hz tick.
        double whole = Math.floor(ticks + 1e-9);
        if (!Double.isFinite(whole) || whole >= Long.MAX_VALUE || whole > Long.MAX_VALUE - worldTicks)
            throw new IllegalArgumentException("World tick counter overflow");
        gameTime = nextTime;
        worldTicks += (long) whole;
        fractionalTick = Math.max(0, ticks - whole);
    }

    /** Set an absolute time (load/network/console); elapsed simulation ticks are unchanged. */
    public void setGameTime(double value) {
        requireFinite(value, "gameTime");
        gameTime = value;
    }

    /** Change the time within the same day, preserving the moon phase. */
    public void setTimeOfDay(double radians) {
        requireFinite(radians, "timeOfDay");
        gameTime = NightSky.withTimeOfDay(gameTime, radians);
    }

    public void setHours(double hours) { setTimeOfDay(hoursToGameTime(hours)); }
    public void addHours(double hours) { setGameTime(Math.max(0, gameTime + hoursToRadians(hours))); }
    public void advanceToDawn() { setGameTime(nextDawn(gameTime)); }

    public static float daylightAt(double gameTime) {
        return Math.max(0f, (float) Math.sin(gameTime));
    }

    public static float dayPhaseAt(double gameTime) {
        return (float) (normalize(gameTime) / NightSky.CYCLE);
    }

    public static double normalize(double radians) {
        requireFinite(radians, "radians");
        double phase = radians % NightSky.CYCLE;
        return phase < 0 ? phase + NightSky.CYCLE : phase;
    }

    public static double hoursToRadians(double hours) {
        requireFinite(hours, "hours");
        return hours / 24.0 * NightSky.CYCLE;
    }

    /** 06:00 is dawn (zero radians); normalize when a phase in [0, 2pi) is needed. */
    public static double hoursToGameTime(double hours) { return hoursToRadians(hours) - Math.PI / 2.0; }

    /** Rounded clock minute, independent of display formatting and locale. */
    public static int clockMinutes(double gameTime) {
        double hours = normalize(gameTime) / NightSky.CYCLE * 24.0 + 6.0;
        return (int) Math.floorMod(Math.round(hours * 60.0), 24L * 60L);
    }

    /** First dawn strictly after the supplied absolute game time. */
    public static double nextDawn(double gameTime) {
        requireFinite(gameTime, "gameTime");
        return (Math.floor(gameTime / NightSky.CYCLE) + 1) * NightSky.CYCLE;
    }

    /** Versioned opaque section, to be stored only by a format supporting the clock section. */
    public byte[] encode() {
        return ByteBuffer.allocate(SECTION_BYTES).putInt(SECTION_VERSION)
                .putDouble(gameTime).putLong(worldTicks).putDouble(fractionalTick).array();
    }

    /**
     * Restore precise time and ticks, or derive legacy ticks when no section exists.
     * Invalid/newer section bytes fail explicitly so callers cannot silently replace them.
     */
    public static WorldClock fromSaved(double legacyTime, byte[] section) {
        if (section == null) {
            requireFinite(legacyTime, "legacyTime");
            double ticks = Math.max(0, legacyTime / TIME_SCALE * TICKS_PER_SECOND);
            if (ticks >= Long.MAX_VALUE) throw new IllegalArgumentException("Legacy world tick counter overflow");
            long whole = (long) Math.floor(ticks);
            return new WorldClock(legacyTime, whole, ticks - whole);
        }
        if (section.length != SECTION_BYTES) throw new IllegalArgumentException("Invalid clock section length");
        ByteBuffer bytes = ByteBuffer.wrap(section);
        if (bytes.getInt() != SECTION_VERSION) throw new IllegalArgumentException("Unsupported clock section version");
        return new WorldClock(bytes.getDouble(), bytes.getLong(), bytes.getDouble());
    }

    private static void requireFinite(double value, String field) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(field + " must be finite");
    }
}

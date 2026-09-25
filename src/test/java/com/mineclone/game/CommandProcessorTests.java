package com.mineclone.game;

import com.mineclone.sim.WorldClock;
import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.GameMode;
import com.mineclone.world.NightSky;
import com.mineclone.world.Weather;
import com.mineclone.world.World;
import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3f;

/**
 * SIM-08: the console commands, moved out of {@code Game}, run against a
 * fake target — no window, GL or sound. Each case is what the command did
 * in {@code Game.executeCommand} before the move.
 */
public final class CommandProcessorTests {
    public interface Check { void run() throws Exception; }
    public interface Runner { void run(String name, Check check); }

    public static void runAll(Runner r) {
        r.run("time commands set and add hours, keep the day, refuse bad input", CommandProcessorTests::time);
        r.run("weather is forced for ten minutes, queried, refused when unknown", CommandProcessorTests::weather);
        r.run("teleport, spawn point, speed and flight act on the player", CommandProcessorTests::player);
        r.run("game mode, instamine, debug, music and help reach the game", CommandProcessorTests::toggles);
        r.run("a line without a slash is chat in a room and nothing alone", CommandProcessorTests::chat);
        r.run("fill sets a square of blocks around the player", CommandProcessorTests::fill);
    }

    private static void check(boolean ok, String why) {
        if (!ok)
            throw new AssertionError(why);
    }

    /** Records what the commands asked of the game. */
    private static final class Fake implements CommandProcessor.CommandTarget {
        boolean room, debug, instant, saved, teleported, helped, next;
        int timeChanges;
        final List<String> chat = new ArrayList<>(), toasts = new ArrayList<>();
        final Player player = new Player();
        World world = new World(1L);
        GameMode mode = GameMode.SURVIVAL;
        final WorldClock clock = new WorldClock(Math.PI / 6.0 + 5 * NightSky.CYCLE);
        Weather.Kind weather = Weather.Kind.CLEAR, forced;
        float forcedFor;
        final Vector3f spawn = new Vector3f(8.5f, 80f, 8.5f);

        @Override public boolean inRoom() { return room; }
        @Override public void chat(String line) { chat.add(line); }
        @Override public Player player() { return player; }
        @Override public World world() { return world; }
        @Override public GameMode gameMode() { return mode; }
        @Override public void setGameMode(GameMode m) { mode = m; player.setGameMode(m); }
        @Override public WorldClock clock() { return clock; }
        @Override public void timeChanged() { timeChanges++; }
        @Override public Weather.Kind weather() { return weather; }
        @Override public float windSpeed() { return 2.5f; }
        @Override public void forceWeather(Weather.Kind kind, float seconds) { forced = kind; forcedFor = seconds; }
        @Override public Vector3f worldSpawn() { return spawn; }
        @Override public void saveAll() { saved = true; }
        @Override public void teleported() { teleported = true; }
        @Override public String musicStatus() { return "silence"; }
        @Override public void nextTrack() { next = true; }
        @Override public void toggleDebug() { debug = !debug; }
        @Override public boolean toggleInstantBreak() { return instant = !instant; }
        @Override public void toast(String message) { toasts.add(message); }
        @Override public void showHelp() { helped = true; }

        String lastToast() { return toasts.isEmpty() ? "" : toasts.get(toasts.size() - 1); }
    }

    private static void time() {
        Fake f = new Fake();
        CommandProcessor c = new CommandProcessor(f);
        long day = NightSky.dayIndex(f.clock.gameTime());
        c.execute("/time set noon");
        check(Math.abs(WorldClock.normalize(f.clock.gameTime()) - Math.PI / 2) < 1e-6, "noon");
        check(NightSky.dayIndex(f.clock.gameTime()) == day, "/time set changed the day");
        check(f.timeChanges == 1 && f.lastToast().equals("Time set to noon"), f.lastToast());
        c.execute("/time set 18");
        check(WorldClock.clockMinutes(f.clock.gameTime()) == 18 * 60 && f.lastToast().equals("Time: 18:00"), f.lastToast());
        c.execute("/time add 24");
        check(NightSky.dayIndex(f.clock.gameTime()) == day + 1 && f.lastToast().equals("Added 24.0 hours"), f.lastToast());
        c.execute("/time add 1.5");
        check(f.lastToast().equals("Added 1.5 hours"), f.lastToast());
        c.execute("/time");
        check(f.lastToast().startsWith("Time: "), f.lastToast());
        c.execute("/time query");
        check(f.lastToast().startsWith("Time: "), f.lastToast());
        int changes = f.timeChanges;
        double before = f.clock.gameTime();
        for (String bad : new String[] { "/time set 25", "/time set -1", "/time set NaN", "/time set", "/time turn 5" }) {
            c.execute(bad);
            check(f.lastToast().equals("Usage: /time set <preset|0-24>"), bad + " -> " + f.lastToast());
        }
        c.execute("/time set dusk");
        check(f.lastToast().equals("Unknown time preset"), f.lastToast());
        c.execute("/time add Infinity");
        check(f.lastToast().equals("Usage: /time add <hours>"), f.lastToast());
        check(f.timeChanges == changes && f.clock.gameTime() == before, "a refused /time moved the clock");
        long ticks = f.clock.worldTicks();
        c.execute("/time add 100");
        check(f.clock.worldTicks() == ticks, "/time moved the tick counter");
    }

    private static void weather() {
        Fake f = new Fake();
        CommandProcessor c = new CommandProcessor(f);
        c.execute("/weather");
        check(f.lastToast().equals("Weather: clear" + String.format("  wind %.1f", 2.5f)) && f.forced == null, f.lastToast());
        String[][] cases = { { "sun", "CLEAR" }, { "clouds", "CLOUDY" }, { "drizzle", "LIGHT" }, { "snow", "HEAVY" },
                { "sandstorm", "STORM" }, { "THUNDER", "STORM" } };
        for (String[] w : cases) {
            c.execute("/weather " + w[0]);
            check(f.forced == Weather.Kind.valueOf(w[1]) && f.forcedFor == 600f, w[0] + " -> " + f.forced);
            check(f.lastToast().equals("Weather set to " + w[1].toLowerCase()), f.lastToast());
        }
        f.forced = null;
        c.execute("/weather fog");
        check(f.forced == null && f.lastToast().startsWith("Usage: /weather"), f.lastToast());
    }

    private static void player() {
        Fake f = new Fake();
        CommandProcessor c = new CommandProcessor(f);
        c.execute("/tp 10 70.5 -3");
        check(f.player.position.equals(new Vector3f(10f, 70.5f, -3f)) && f.teleported, "teleport");
        f.teleported = false;
        c.execute("/tp 1 2");
        check(!f.teleported && f.player.position.x == 10f, "a short /tp moved the player");
        c.execute("/tp a b c");
        check(f.lastToast().equals("Invalid number"), f.lastToast());

        c.execute("/spawnpoint");
        check(f.spawn.equals(f.player.position) && f.saved, "spawn point here");
        f.saved = false;
        c.execute("/spawnpoint 1 2 3");
        check(f.spawn.equals(new Vector3f(1f, 2f, 3f)) && f.saved
                && f.lastToast().equals(String.format("Spawn point set: %.1f %.1f %.1f", 1f, 2f, 3f)), f.lastToast());
        f.saved = false;
        c.execute("/spawnpoint 4 5");
        check(!f.saved && f.spawn.equals(new Vector3f(1f, 2f, 3f)) && f.lastToast().equals("Usage: /spawnpoint [x y z]"), f.lastToast());

        c.execute("/speed 100");
        check(f.player.flySpeed == 64f && !f.player.flying, "survival speed: clamped, not flying");
        f.setGameMode(GameMode.CREATIVE);
        c.execute("/speed 0.1");
        check(f.player.flySpeed == 0.5f && f.player.flying && f.lastToast().equals(String.format("Fly speed: %.1f", 0.5f)), f.lastToast());
        c.execute("/speed NaN");
        check(f.lastToast().equals("Invalid number") && f.player.flySpeed == 0.5f, f.lastToast());
        c.execute("/speed");
        check(f.lastToast().equals("Usage: /speed <value>"), f.lastToast());

        f.player.flying = false;
        c.execute("/fly");
        check(f.player.flying, "/fly in creative");
        f.setGameMode(GameMode.SURVIVAL);
        f.player.flying = false;
        c.execute("/fly");
        check(!f.player.flying && f.lastToast().startsWith("Полёт доступен"), f.lastToast());
    }

    private static void toggles() {
        Fake f = new Fake();
        CommandProcessor c = new CommandProcessor(f);
        c.execute("/gm 1");
        check(f.mode == GameMode.CREATIVE && f.lastToast().equals("Gamemode: Creative"), f.lastToast());
        f.player.flying = true;
        c.execute("/gamemode survival");
        check(f.mode == GameMode.SURVIVAL && !f.player.flying && f.lastToast().equals("Gamemode: Survival"), f.lastToast());
        c.execute("/gm c");
        check(f.mode == GameMode.CREATIVE, "/gm c");
        c.execute("/gm s");
        check(f.mode == GameMode.SURVIVAL, "/gm s");
        c.execute("/gm spectator");
        check(f.mode == GameMode.SURVIVAL && f.lastToast().equals("Usage: /gamemode <creative|survival>"), f.lastToast());
        c.execute("/gm");
        check(f.lastToast().equals("Usage: /gamemode <creative|survival>"), f.lastToast());

        c.execute("/instamine");
        check(f.instant && f.lastToast().equals("Instamine ON"), f.lastToast());
        c.execute("/instamine");
        check(!f.instant && f.lastToast().equals("Instamine OFF"), f.lastToast());
        c.execute("/debug");
        check(f.debug, "/debug");
        c.execute("/music");
        check(f.lastToast().equals("Music: silence") && !f.next, f.lastToast());
        c.execute("/music NEXT");
        check(f.next && f.lastToast().equals("Music: next track"), f.lastToast());
        c.execute("/commands");
        check(f.helped, "/commands shows help");
        int toasts = f.toasts.size();
        c.execute("/nosuchcommand 1 2");
        check(f.toasts.size() == toasts, "an unknown command said something");
        check(CommandProcessor.HELP[0].equals("Commands") && CommandProcessor.HELP.length == 15, "help lines");
    }

    private static void chat() {
        Fake f = new Fake();
        CommandProcessor c = new CommandProcessor(f);
        c.execute("hello");
        check(f.chat.isEmpty() && f.toasts.isEmpty(), "alone, a plain line is nothing");
        f.room = true;
        c.execute("hello there");
        check(f.chat.equals(List.of("hello there")), "in a room a plain line is chat");
        c.execute("");
        c.execute("/debug");
        check(f.chat.size() == 1 && f.debug, "a command in a room is still a command");
    }

    private static void fill() {
        Fake f = new Fake();
        World w = new World(2L);
        Chunk c0 = w.getChunk(0, 0);
        for (int cx = -1; cx <= 1; cx++)
            for (int cz = -1; cz <= 1; cz++)
                w.getChunk(cx, cz);
        f.world = w;
        f.player.position.set(8.5f, 100.2f, 8.5f);
        CommandProcessor c = new CommandProcessor(f);
        c.execute("/fill stone 1");
        int stone = 0;
        for (int x = 6; x <= 10; x++)
            for (int z = 6; z <= 10; z++)
                if (w.getBlock(x, 100, z) == BlockType.STONE)
                    stone++;
        check(stone == 9 && w.getBlock(7, 100, 7) == BlockType.STONE && w.getBlock(9, 100, 9) == BlockType.STONE
                && w.getBlock(8, 101, 8) != BlockType.STONE, "a 3x3 of stone at the feet: " + stone);
        c.execute("/fill nosuchblock 0");
        check(w.getBlock(8, 100, 8) == BlockType.WATER, "an unknown block fills water");
        c.execute("/fill stone x");
        check(f.lastToast().equals("Invalid number"), f.lastToast());
        check(c0 == w.getChunkIfExists(0, 0), "chunk replaced");
    }
}

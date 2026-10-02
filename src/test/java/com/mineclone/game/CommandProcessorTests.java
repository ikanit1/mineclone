package com.mineclone.game;

import com.mineclone.sim.WorldClock;
import com.mineclone.sim.EntityStore;
import com.mineclone.sim.Participants;
import com.mineclone.sim.ecology.Ecology;
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
        r.run("guest and disabled commands cannot mutate the world but local commands and chat work",CommandProcessorTests::permissions);
        r.run("time commands set and add hours, keep the day, refuse bad input", CommandProcessorTests::time);
        r.run("weather is forced for ten minutes, queried, refused when unknown", CommandProcessorTests::weather);
        r.run("teleport, spawn point, speed and flight act on the player", CommandProcessorTests::player);
        r.run("game mode, instamine, debug, music and help reach the game", CommandProcessorTests::toggles);
        r.run("a line without a slash is chat in a room and nothing alone", CommandProcessorTests::chat);
        r.run("fill sets a square of blocks around the player", CommandProcessorTests::fill);
        r.run("ecology owner commands report, persist switches and advance bounded days", CommandProcessorTests::ecology);
        r.run("ecology commands deny a guest before touching the host model", CommandProcessorTests::ecologyGuest);
        r.run("world rules disable ecology commands before model access", CommandProcessorTests::ecologyRules);
    }

    private static void permissions(){var fake=new Fake();fake.guest=true;fake.room=true;var commands=new CommandProcessor(fake);var initial=fake.clock.encode();for(String command:List.of("/fill stone 1","/time add 4","/weather storm","/gamemode creative","/tp 100 100 100","/difficulty hard","/gamerule keepInventory false","/effect speed 100"))commands.execute(command);check(java.util.Arrays.equals(initial,fake.clock.encode())&&fake.mode==GameMode.SURVIVAL&&!fake.teleported&&fake.forced==null,"guest mutation");commands.execute("/debug");commands.execute("hello");commands.execute("/help");check(fake.debug&&fake.helped&&fake.chat.contains("hello"),"guest local commands");fake.guest=false;fake.world.rules=fake.world.rules.set("allowCommands",false);commands.execute("/time add 1");commands.execute("/fill stone 1");check(java.util.Arrays.equals(initial,fake.clock.encode()),"disabled host commands");}

    private static void check(boolean ok, String why) {
        if (!ok)
            throw new AssertionError(why);
    }

    /** Records what the commands asked of the game. */
    private static final class Fake implements CommandProcessor.CommandTarget {
        boolean room, debug, instant, saved, teleported, helped, next, guest, ecologyOwner = true;
        int timeChanges, saves, ecologyReads;
        String[] ecologyLines;
        final List<String> chat = new ArrayList<>(), toasts = new ArrayList<>();
        final Player player = new Player();
        World world = new World(1L);
        GameMode mode = GameMode.SURVIVAL;
        final WorldClock clock = new WorldClock(Math.PI / 6.0 + 5 * NightSky.CYCLE);
        Ecology ecology;
        Weather.Kind weather = Weather.Kind.CLEAR, forced;
        float forcedFor;
        final Vector3f spawn = new Vector3f(8.5f, 80f, 8.5f);

        @Override public boolean inRoom() { return room; }
        @Override public boolean guest() { return guest; }
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
        @Override public void saveAll() { saved = true; saves++; }
        @Override public void teleported() { teleported = true; }
        @Override public String musicStatus() { return "silence"; }
        @Override public void nextTrack() { next = true; }
        @Override public void toggleDebug() { debug = !debug; }
        @Override public boolean toggleInstantBreak() { return instant = !instant; }
        @Override public void toast(String message) { toasts.add(message); }
        @Override public void showHelp() { helped = true; }
        @Override public Ecology ecology() { ecologyReads++; return ecology; }
        @Override public boolean canManageEcology() { return ecologyOwner; }
        @Override public void showEcology(String[] lines) { ecologyLines = lines; }

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
        check(f.clock.worldTicks() >= ticks, "/time rewound the tick counter");
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
        check(CommandProcessor.HELP[0].equals("Commands") && CommandProcessor.HELP.length == 16, "help lines");
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

    private static void ecology() {
        Fake f = new Fake();
        f.ecology = new Ecology(f.world, f.clock, new EntityStore(), new Participants());
        f.player.position.set(8.5f, 80f, -8.5f);
        CommandProcessor c = new CommandProcessor(f);
        c.execute("/ecology info");
        check(f.ecologyLines != null && f.ecologyLines.length > 0, "info did not reach its readable panel");
        check(f.saves == 0 && f.timeChanges == 0, "info mutated time or saved the world");
        f.ecologyLines = null;
        c.execute("/ecology");
        check(f.ecologyLines != null, "bare ecology should show info");

        c.execute("/ecology on");
        check(f.ecology.enabled() && f.saves == 1 && f.lastToast().equals("Ecology ON"), f.lastToast());
        c.execute("/ecology off");
        check(!f.ecology.enabled() && f.saves == 2 && f.lastToast().equals("Ecology OFF"), f.lastToast());
        c.execute("/ecology ON");
        check(f.ecology.enabled() && f.saves == 3, "case-insensitive on");

        long day = NightSky.dayIndex(f.clock.gameTime());
        long ticks = f.clock.worldTicks();
        c.execute("/ecology advance 1");
        check(NightSky.dayIndex(f.clock.gameTime()) == day + 1
                && Math.abs(f.clock.worldTicks() - ticks - WorldClock.TICKS_PER_DAY) <= 1,
                "advance must move an absolute day and its scheduled world ticks");
        check(f.saves == 4 && f.timeChanges == 1 && f.lastToast().equals("Ecology advanced 1 day(s)"), f.lastToast());
        c.execute("/ecology advance 60");
        check(NightSky.dayIndex(f.clock.gameTime()) == day + 61 && f.saves == 5 && f.timeChanges == 2,
                "upper bound was refused or not persisted");
        double before = f.clock.gameTime();
        for (String bad : new String[] { "/ecology advance 0", "/ecology advance 61", "/ecology advance -1",
                "/ecology advance NaN", "/ecology advance 1.5", "/ecology advance", "/ecology advance 2 extra",
                "/ecology on extra", "/ecology info extra", "/ecology unknown" }) {
            c.execute(bad);
            check(f.lastToast().startsWith("Usage: /ecology"), bad + " -> " + f.lastToast());
        }
        check(f.clock.gameTime() == before && f.saves == 5 && f.timeChanges == 2,
                "an invalid ecology command changed the world");
        c.execute("/ecology lair");
        check(f.ecologyLines != null && f.ecologyLines[0].startsWith("Spider lairs")
                && java.util.Arrays.stream(f.ecologyLines).anyMatch(line -> line.equals("No known spider nest in this region")),
                "empty lair query did not report the current region");
        var region = new com.mineclone.sim.ecology.EcologyRegion(0, -1, Ecology.day(f.clock.gameTime()));
        var lair = new com.mineclone.sim.ecology.Lair(8, 60, -8, region.lastDay(),
                com.mineclone.sim.ecology.LairTable.get().byType(com.mineclone.world.entity.MobType.SPIDER));
        lair.claim(701);
        try {
            region.lairs(java.util.List.of(lair.encode()));
            f.ecology.restore(java.util.Map.of("ecology", com.mineclone.sim.ecology.EcologyCodec.encode(
                    new com.mineclone.sim.ecology.EcologyCodec.Snapshot(java.util.List.of(region), java.util.Map.of(), new byte[0]))),
                    bytes -> { throw new AssertionError("valid diagnostic fixture requested recovery"); });
        } catch (java.io.IOException invalid) { throw new AssertionError(invalid); }
        c.execute("/ecology lair");
        check(java.util.Arrays.stream(f.ecologyLines).anyMatch(line -> line.equals("Nest 8,60,-8 alive | spiders 6/8 | outside 1")),
                "lair query lost heart position, finite population or emitted-body count");
        check(f.saves == 5 && f.timeChanges == 2 && f.clock.gameTime() == before, "read-only lair query changed or saved the world");

        f.ecology = null;
        c.execute("/ecology on");
        check(f.lastToast().equals("Ecology is unavailable in this world") && f.saves == 5, f.lastToast());
    }

    private static void ecologyGuest() {
        Fake f = new Fake();
        f.room = true;
        f.guest = true;
        CommandProcessor c = new CommandProcessor(f);
        double before = f.clock.gameTime();
        String[] commands = { "/ecology", "/ecology info", "/ecology lair", "/ecology on",
                "/ecology off", "/ecology advance 2", "/ecology advance NaN" };
        for (String cmd : commands) {
            c.execute(cmd);
            check(f.lastToast().equals("Команды выполняет хозяин"), cmd + " -> " + f.lastToast());
        }
        // The ecology-specific authority seam also fails closed when the
        // network adapter is absent or has not established ownership yet.
        f.guest = false;
        f.ecologyOwner = false;
        for (String cmd : commands) {
            c.execute(cmd);
            check(f.lastToast().equals("Ecology commands are available to the world owner only"), cmd + " -> " + f.lastToast());
        }
        check(f.ecologyReads == 0 && f.ecologyLines == null && f.saves == 0 && f.timeChanges == 0
                && f.clock.gameTime() == before, "a guest reached or mutated ecology");
    }

    private static void ecologyRules() {
        Fake f = new Fake();
        f.world.rules = f.world.rules.set("allowCommands", false);
        CommandProcessor c = new CommandProcessor(f);
        byte[] before = f.clock.encode();
        for (String command : new String[] { "/ecology", "/ecology info", "/ecology on", "/ecology off",
                "/ecology advance 2", "/ecology lair" }) {
            c.execute(command);
            check(f.lastToast().equals("Команды отключены правилами мира"), command + " -> " + f.lastToast());
        }
        check(f.ecologyReads == 0 && f.saves == 0 && f.timeChanges == 0 && f.ecologyLines == null
                && java.util.Arrays.equals(before, f.clock.encode()), "disabled owner commands reached ecology");
        c.execute("/help"); c.execute("/debug");
        check(f.helped && f.debug, "disabling world commands also disabled local help or debug");
    }
}

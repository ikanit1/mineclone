package com.mineclone.game;

import com.mineclone.sim.WorldClock;
import com.mineclone.world.BlockType;
import com.mineclone.world.GameMode;
import com.mineclone.world.Weather;
import com.mineclone.world.World;
import org.joml.Vector3f;

/**
 * The console line's commands: {@code /time}, {@code /weather}, {@code /tp},
 * {@code /gamemode} and the rest, moved out of {@code Game} whole (SIM-08).
 * What a command may touch goes through {@link CommandTarget}, so
 * {@code CommandProcessorTests} runs them without a window.
 */
final class CommandProcessor {
    /** The help panel's lines; the first is its title. */
    static final String[] HELP = {
            "Commands",
            "/help  - show this list",
            "/time [query]",
            "/time set day|sunrise|noon|sunset|night|midnight|0-24",
            "/time add <hours>",
            "/weather clear|cloudy|light|heavy|storm|sandstorm",
            "/tp <x> <y> <z>",
            "/spawnpoint [x y z]",
            "/fly",
            "/speed <value>",
            "/fill <block> [radius]",
            "/instamine",
            "/music [next] - what plays, or a fitting track now",
            "/debug",
            "/gamemode <creative|survival> - Switch game mode"
    };

    /** What the commands need of the game. */
    interface CommandTarget {
        /** In a network room a line without a slash is chat. */
        boolean inRoom();
        void chat(String line);
        Player player();
        World world();
        GameMode gameMode();
        void setGameMode(GameMode mode);
        WorldClock clock();
        /** The time of day changed: daylight follows at once. */
        void timeChanged();
        Weather.Kind weather();
        float windSpeed();
        void forceWeather(Weather.Kind kind, float seconds);
        Vector3f worldSpawn();
        void saveAll();
        /** The player jumped elsewhere: what the music knew of the place is stale. */
        void teleported();
        String musicStatus();
        void nextTrack();
        void toggleDebug();
        /** Flips instant breaking; returns the new state. */
        boolean toggleInstantBreak();
        void toast(String message);
        void showHelp();
    }

    private final CommandTarget target;

    CommandProcessor(CommandTarget target) {
        this.target = target;
    }

    void execute(String cmd) {
        // В комнате строка без команды — это реплика, а не опечатка: чат и
        // консоль делят одно поле, как в Minecraft.
        if (target.inRoom() && !cmd.isEmpty() && !cmd.startsWith("/")) {
            target.chat(cmd);
            return;
        }
        if (cmd.isEmpty())
            return;
        String[] parts = cmd.split("\\s+");
        Player player = target.player();
        try {
            switch (parts[0]) {
                case "/time" -> executeTimeCommand(parts);
                case "/weather" -> executeWeatherCommand(parts);
                case "/help", "/commands" -> target.showHelp();
                case "/speed" -> {
                    if (parts.length >= 2) {
                        float s = Float.parseFloat(parts[1]);
                        if (!Float.isFinite(s)) throw new NumberFormatException();
                        s = Math.max(0.5f, Math.min(64f, s));
                        player.flying = target.gameMode() == GameMode.CREATIVE;
                        player.flySpeed = s;
                        target.toast(String.format("Fly speed: %.1f", s));
                    } else {
                        target.toast("Usage: /speed <value>");
                    }
                }
                case "/tp" -> {
                    if (parts.length >= 4) {
                        float tx = Float.parseFloat(parts[1]);
                        float ty = Float.parseFloat(parts[2]);
                        float tz = Float.parseFloat(parts[3]);
                        player.position.set(tx, ty, tz);
                        target.teleported();
                    }
                }
                case "/music" -> {
                    if (parts.length >= 2 && parts[1].equalsIgnoreCase("next")) {
                        target.nextTrack();
                        target.toast("Music: next track");
                    } else {
                        target.toast("Music: " + target.musicStatus());
                    }
                }
                case "/spawnpoint" -> executeSpawnPointCommand(parts);
                case "/fly" -> {
                    if (target.gameMode() == GameMode.CREATIVE)
                        player.updateFlightControls(0f, true, false, true);
                    else target.toast("Полёт доступен в творческом режиме: /gamemode creative");
                }
                case "/fill" -> {
                    String blockName = parts.length >= 2 ? parts[1].toUpperCase() : "WATER";
                    int radius = parts.length >= 3 ? Integer.parseInt(parts[2]) : 4;
                    BlockType fill;
                    try {
                        fill = BlockType.valueOf(blockName);
                    } catch (IllegalArgumentException e) {
                        fill = BlockType.WATER;
                    }
                    int cx = (int) Math.floor(player.position.x);
                    int cy = (int) Math.floor(player.position.y);
                    int cz = (int) Math.floor(player.position.z);
                    World world = target.world();
                    for (int dx = -radius; dx <= radius; dx++)
                        for (int dz = -radius; dz <= radius; dz++)
                            world.setBlock(cx + dx, cy, cz + dz, fill);
                }
                case "/debug" -> target.toggleDebug();
                case "/instamine" -> target.toast(target.toggleInstantBreak() ? "Instamine ON" : "Instamine OFF");
                case "/gamemode", "/gm" -> {
                    if (parts.length >= 2) {
                        String m = parts[1].toLowerCase();
                        if (m.equals("creative") || m.equals("c") || m.equals("1")) {
                            target.setGameMode(GameMode.CREATIVE);
                            target.toast("Gamemode: Creative");
                        } else if (m.equals("survival") || m.equals("s") || m.equals("0")) {
                            target.setGameMode(GameMode.SURVIVAL);
                            player.flying = false;
                            target.toast("Gamemode: Survival");
                        } else {
                            target.toast("Usage: /gamemode <creative|survival>");
                        }
                    } else {
                        target.toast("Usage: /gamemode <creative|survival>");
                    }
                }
            }
        } catch (NumberFormatException e) {
            target.toast("Invalid number");
        }
    }

    private void executeSpawnPointCommand(String[] parts) {
        Vector3f worldSpawn = target.worldSpawn();
        if (parts.length == 1) {
            worldSpawn.set(target.player().position);
        } else if (parts.length >= 4) {
            float x = Float.parseFloat(parts[1]);
            float y = Float.parseFloat(parts[2]);
            float z = Float.parseFloat(parts[3]);
            worldSpawn.set(x, y, z);
        } else {
            target.toast("Usage: /spawnpoint [x y z]");
            return;
        }
        target.saveAll();
        target.toast(String.format("Spawn point set: %.1f %.1f %.1f",
                worldSpawn.x, worldSpawn.y, worldSpawn.z));
    }

    private void executeTimeCommand(String[] parts) {
        if (parts.length == 1 || (parts.length == 2 && parts[1].equalsIgnoreCase("query"))) {
            target.toast("Time: " + formatGameTime());
            return;
        }
        if (parts.length < 3) {
            target.toast("Usage: /time set <preset|0-24>");
            return;
        }

        WorldClock worldClock = target.clock();
        String op = parts[1].toLowerCase();
        switch (op) {
            case "set" -> {
                Float preset = timePreset(parts[2].toLowerCase());
                if (preset != null) {
                    // Часы переводятся внутри текущих суток: номер суток
                    // держит фазу луны и график погоды.
                    worldClock.setTimeOfDay(preset);
                    target.timeChanged();
                    target.toast("Time set to " + parts[2].toLowerCase());
                    return;
                }
                try {
                    float hours = Float.parseFloat(parts[2]);
                    if (!Float.isFinite(hours) || hours < 0f || hours > 24f) {
                        target.toast("Usage: /time set <preset|0-24>");
                        return;
                    }
                    worldClock.setHours(hours);
                    target.timeChanged();
                    target.toast("Time: " + formatGameTime());
                } catch (NumberFormatException e) {
                    target.toast("Unknown time preset");
                }
            }
            case "add" -> {
                try {
                    float hours = Float.parseFloat(parts[2]);
                    // Без нормализации: прибавленные сутки — это новая ночь и новая луна.
                    if (!Float.isFinite(hours)) throw new NumberFormatException("non-finite hours");
                    worldClock.addHours(hours);
                    target.timeChanged();
                    target.toast("Added " + formatHours(hours) + " hours");
                } catch (NumberFormatException e) {
                    target.toast("Usage: /time add <hours>");
                }
            }
            default -> target.toast("Usage: /time set <preset|0-24>");
        }
    }

    /**
     * {@code /weather clear|cloudy|light|heavy|storm} — погода на десять минут.
     * Переход всё равно плавный: заказанная буря накатывает, а не включается.
     */
    private void executeWeatherCommand(String[] parts) {
        if (parts.length < 2) {
            Weather.Kind k = target.weather();
            target.toast("Weather: " + k.name().toLowerCase()
                    + String.format("  wind %.1f", target.windSpeed()));
            return;
        }
        Weather.Kind kind = switch (parts[1].toLowerCase()) {
            case "clear", "sun" -> Weather.Kind.CLEAR;
            case "cloudy", "clouds" -> Weather.Kind.CLOUDY;
            case "light", "drizzle" -> Weather.Kind.LIGHT;
            case "heavy", "rain", "snow" -> Weather.Kind.HEAVY;
            case "storm", "thunder", "blizzard", "sandstorm", "dust" -> Weather.Kind.STORM;
            default -> null;
        };
        if (kind == null) {
            target.toast("Usage: /weather clear|cloudy|light|heavy|storm|sandstorm");
            return;
        }
        target.forceWeather(kind, 600f);
        target.toast("Weather set to " + kind.name().toLowerCase());
    }

    static Float timePreset(String name) {
        return switch (name) {
            case "day", "sunrise" -> (float) (Math.PI / 6.0);
            case "noon" -> (float) (Math.PI / 2.0);
            case "sunset" -> (float) (5.0 * Math.PI / 6.0);
            case "night" -> (float) (7.0 * Math.PI / 6.0);
            case "midnight" -> (float) (3.0 * Math.PI / 2.0);
            default -> null;
        };
    }

    private String formatGameTime() {
        // Одна формула на консоль и компас: разъехавшись, они спорили бы
        // о времени суток.
        return Hud.clockText(target.clock().gameTimeFloat());
    }

    static String formatHours(float hours) {
        if (Math.abs(hours - Math.round(hours)) < 0.0001f)
            return String.format("%.1f", hours);
        return String.valueOf(hours);
    }
}

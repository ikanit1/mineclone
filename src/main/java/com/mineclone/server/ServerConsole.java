package com.mineclone.server;

import com.mineclone.net.connect.RoomCode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Команды со стандартного ввода.
 *
 * <p>У сервера нет ни окна, ни чата, в который можно было бы написать: всё
 * общение — строка в терминале. Набор намеренно короткий и состоит из того,
 * без чего сервер нельзя обслуживать: посмотреть, кто внутри, записать мир,
 * остановиться по-человечески.
 *
 * <p>Читает ввод свой поток, а выполняются команды в игровом: мир трогать из
 * двух потоков нельзя, а чтение строки блокирует надолго. Поэтому команда
 * уезжает в {@link DedicatedServer#submit}.
 */
public final class ServerConsole {

    /**
     * Метка порядка байтов. Записана номером, а не самим знаком: в исходнике
     * он невидим, и строка {@code "".replace(...)} выглядела бы бессмыслицей.
     */
    private static final String BOM = String.valueOf((char) 0xFEFF);

    private final DedicatedServer server;

    public ServerConsole(DedicatedServer server) {
        this.server = server;
    }

    /** Запустить поток чтения. Он демон: цикл сервера решает, когда выходить. */
    public void start() {
        Thread t = new Thread(this::read, "mineclone-console");
        t.setDaemon(true);
        t.start();
    }

    private void read() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while (server.running() && (line = in.readLine()) != null) {
                final String command = clean(line);
                if (command.isEmpty())
                    continue;
                server.submit(() -> run(command));
            }
        } catch (IOException e) {
            // Ввод закрыли (сервер под nohup) — команд больше не будет, но
            // мир от этого останавливать незачем.
        }
    }

    /**
     * Привести строку команды к тому, что можно сравнивать.
     *
     * <p>Метка порядка байтов выбрасывается отдельно от пробелов: {@code trim}
     * её не считает пробельной и не трогает. А приходит она непременно —
     * PowerShell ставит её в начало перенаправленного ввода, и первая команда
     * скрипта, запускающего сервер, превращалась в {@code ?/stop}, на которую
     * сервер честно отвечал «неизвестная команда» и продолжал работать.
     */
    public static String clean(String line) {
        return line == null ? "" : line.replace(BOM, "").trim();
    }

    /** Выполнить команду. Зовётся из игрового потока. */
    void run(String line) {
        String[] parts = line.split("\\s+", 2);
        String command = parts[0].toLowerCase();
        String rest = parts.length > 1 ? parts[1].trim() : "";
        switch (command) {
            case "/stop", "stop", "/quit", "quit" -> server.stop("console");
            case "/save", "save" -> server.saveWorld();
            case "/backup", "backup" -> server.backupWorld();
            case "/list", "list" -> list();
            case "/say", "say" -> say(rest);
            case "/time", "time" -> time(rest);
            case "/seed", "seed" -> DedicatedServer.log(
                    server.world() == null ? "no world" : "seed " + server.world().seed);
            case "/room", "room" -> room();
            case "/help", "help", "?" -> help();
            default -> DedicatedServer.log("unknown command: " + command + " (try /help)");
        }
    }

    private void list() {
        var lines = server.playerLines();
        DedicatedServer.log(lines.size() + " player(s)");
        for (String l : lines)
            System.out.println("  " + l);
    }

    private void say(String text) {
        if (text.isEmpty()) {
            DedicatedServer.log("usage: /say <text>");
            return;
        }
        server.net().sendChat(text);
    }

    /**
     * Показать или перевести время.
     *
     * <p>Часы игровые: полный оборот — два пи. {@code /time day} и
     * {@code /time night} переводят вперёд, а не назад: назад время в этой
     * игре не ходит — от этого зависит фаза луны.
     */
    private void time(String arg) {
        float now = server.gameTime();
        if (arg.isEmpty()) {
            DedicatedServer.log("time " + String.format(java.util.Locale.ROOT, "%.2f", now)
                    + " (" + (Math.sin(now) > 0 ? "day" : "night") + ")");
            return;
        }
        float target;
        switch (arg.toLowerCase()) {
            case "day" -> target = forward(now, (float) (Math.PI / 6.0));
            case "noon" -> target = forward(now, (float) (Math.PI / 2.0));
            case "night" -> target = forward(now, (float) (Math.PI * 1.2));
            default -> {
                try {
                    target = Float.parseFloat(arg);
                    if (!Float.isFinite(target)) throw new NumberFormatException("non-finite time");
                } catch (NumberFormatException e) {
                    DedicatedServer.log("usage: /time [day|noon|night|<radians>]");
                    return;
                }
            }
        }
        server.setGameTime(target);
        DedicatedServer.log("time set");
    }

    /** Ближайший момент с такой фазой, но строго в будущем. */
    public static float forward(float now, float phase) {
        float full = (float) (Math.PI * 2.0);
        float turns = (float) Math.floor(now / full);
        float candidate = turns * full + phase;
        while (candidate <= now)
            candidate += full;
        return candidate;
    }

    private void room() {
        RoomCode code = server.roomCode();
        if (code == null) {
            DedicatedServer.log("no photon room; direct port "
                    + server.config().port);
            return;
        }
        DedicatedServer.log("room " + code.pretty() + " region " + code.region());
    }

    private void help() {
        System.out.println("  /list            who is connected");
        System.out.println("  /say <text>      message every player");
        System.out.println("  /save            write the world to disk");
        System.out.println("  /backup          save and create a manual world backup");
        System.out.println("  /time [day|noon|night|<radians>]");
        System.out.println("  /room            show the room code");
        System.out.println("  /seed            show the world seed");
        System.out.println("  /stop            save and shut down");
    }
}

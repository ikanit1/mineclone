package com.mineclone.server;

import com.mineclone.net.LanTransport;
import com.mineclone.net.NetSettings;
import com.mineclone.net.connect.RoomCode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Настройки выделенного сервера — обычный {@code server.properties}.
 *
 * <p>Текстовый файл, а не экран: сервер ставят на машину без окна, а правят его
 * по ssh. Формат намеренно скучный и знакомый всякому, кто держал сервер
 * Minecraft.
 *
 * <p>Файла нет — он пишется со значениями по умолчанию и объяснением у каждого
 * ключа. Это дешевле документации: она устаревает отдельно от кода, а этот
 * файл родится из него же.
 */
public final class ServerConfig {

    public static final String DEFAULT_FILE = "server.properties";

    /** Порт прямого соединения. */
    public final int port;
    /** Пускать ли через своё соединение вообще. */
    public final boolean direct;
    /** Пробовать ли открыть порт на роутере. */
    public final boolean upnp;
    /** Держать ли комнату Photon. */
    public final boolean photon;
    /** Регион облака; пустой — замерить и выбрать ближайший. */
    public final String region;
    /** Код комнаты; пустой — выдать новый. */
    public final String room;
    /** Свой ключ приложения Photon; пустой — встроенный. */
    public final String appId;

    /** Каталог сохранений. */
    public final String savesDir;
    /** Идентификатор мира внутри каталога. */
    public final String worldId;
    /** Как мир зовётся на экране участника. */
    public final String worldName;
    /** Сид нового мира; 0 — выбрать случайный. */
    public final long seed;
    /** Творческий режим вместо выживания. */
    public final boolean creative;

    /** Сколько участников впускаем. */
    public final int maxPlayers;
    /** В скольких чанках вокруг каждого участника сервер держит мир. */
    public final int viewDistance;
    /** Как часто мир пишется на диск, в секундах. */
    public final float autosaveSeconds;
    /** Number of ordinary backups kept; recent migration snapshots are protected separately. */
    public final int backups;
    /** Имя сервера в объявлении локальной сети. */
    public final String motd;

    private ServerConfig(Properties p) {
        port = intOf(p, "port", LanTransport.DEFAULT_PORT);
        direct = boolOf(p, "direct", true);
        upnp = boolOf(p, "upnp", true);
        photon = boolOf(p, "photon", true);
        region = strOf(p, "photon-region", "");
        room = RoomCode.typed(strOf(p, "room", ""));
        appId = strOf(p, "photon-app-id", "");
        savesDir = strOf(p, "saves-dir", "saves");
        worldId = strOf(p, "world", "server");
        worldName = strOf(p, "world-name", "Сервер");
        seed = longOf(p, "seed", 0L);
        creative = "creative".equalsIgnoreCase(strOf(p, "mode", "survival"));
        maxPlayers = Math.max(1, Math.min(LanTransport.MAX_PLAYERS,
                intOf(p, "max-players", LanTransport.MAX_PLAYERS)));
        viewDistance = Math.max(2, Math.min(16, intOf(p, "view-distance", 6)));
        autosaveSeconds = Math.max(10f, intOf(p, "autosave-seconds", 60));
        backups = Math.max(1, Math.min(1000, intOf(p, "backups", 5)));
        motd = strOf(p, "motd", "");
    }

    public static ServerConfig defaults() {
        return new ServerConfig(new Properties());
    }

    /**
     * Прочитать файл, а если его нет — создать со значениями по умолчанию.
     *
     * <p>Создать, а не молча взять умолчания: человек, запустивший сервер
     * впервые, должен увидеть рядом файл, в котором написано, что вообще можно
     * настроить.
     */
    public static ServerConfig load(File file) {
        Properties p = new Properties();
        if (file.isFile()) {
            try {
                String text = java.nio.file.Files.readString(file.toPath(),
                        StandardCharsets.UTF_8);
                p.load(new java.io.StringReader(sanitize(text)));
            } catch (IOException | RuntimeException e) {
                System.err.println("server.properties unreadable: " + e.getMessage()
                        + " - using defaults");
            }
        }
        ServerConfig config = new ServerConfig(p);
        if (!file.isFile())
            config.write(file);
        return config;
    }

    /**
     * Привести файл к тому, что понимает {@link Properties}.
     *
     * <p>Две ловушки, обе молчаливые и обе непременно случающиеся на Windows.
     *
     * <p>Первая — метка порядка байтов. Блокнот и {@code Out-File -Encoding utf8}
     * пишут в начало файла U+FEFF, и ключ {@code port} становится ключом
     * {@code \\uFEFFport}, которого никто не спрашивает: файл на экране
     * выглядит верно, а сервер слушает не тот порт.
     *
     * <p>Вторая — обратный слэш. В формате {@code .properties} он служебный,
     * поэтому {@code saves-dir=C:\\\\worlds\\\\new} читается как
     * {@code C:worlds<перевод строки>ew}. Человек, который впишет туда путь
     * Windows — а впишет его всякий, — получит мир не там, где просил, и ни
     * одного сообщения об ошибке. Поэтому слэши удваиваются: продолжение
     * строки и {@code \\n} внутри пути нам дороже не стоят, чем сам путь.
     */
    public static String sanitize(String text) {
        String body = text.startsWith("\uFEFF") ? text.substring(1) : text;
        StringBuilder sb = new StringBuilder(body.length() + 16);
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            sb.append(c);
            if (c == '\\')
                sb.append('\\');
        }
        return sb.toString();
    }

    /** Ключ приложения Photon с тем же порядком главенства, что у игры. */
    public String effectiveAppId() {
        return new NetSettings(NetSettings.PHOTON, "server", appId, region, room, "", port)
                .effectiveAppId();
    }

    /** Записать файл с объяснением у каждого ключа. */
    public void write(File file) {
        Map<String, String[]> rows = new LinkedHashMap<>();
        rows.put("port", new String[] { String.valueOf(port),
                "TCP port for direct connections" });
        rows.put("direct", new String[] { String.valueOf(direct),
                "accept direct connections at all" });
        rows.put("upnp", new String[] { String.valueOf(upnp),
                "ask the router to forward the port (UPnP / NAT-PMP)" });
        rows.put("photon", new String[] { String.valueOf(photon),
                "also keep a Photon cloud room open" });
        rows.put("photon-region", new String[] { region,
                "eu, us, ru, asia...; empty = measure and pick the nearest" });
        rows.put("room", new String[] { room,
                "six-character room code; empty = a fresh one every start" });
        rows.put("photon-app-id", new String[] { appId,
                "your own Photon key; empty = the one built into the build" });
        rows.put("saves-dir", new String[] { savesDir, "where worlds live" });
        rows.put("world", new String[] { worldId, "world folder inside saves-dir" });
        rows.put("world-name", new String[] { worldName, "name guests see" });
        rows.put("seed", new String[] { String.valueOf(seed),
                "seed for a new world; 0 = random" });
        rows.put("mode", new String[] { creative ? "creative" : "survival",
                "survival or creative" });
        rows.put("max-players", new String[] { String.valueOf(maxPlayers), "" });
        rows.put("view-distance", new String[] { String.valueOf(viewDistance),
                "chunks kept alive around each player" });
        rows.put("autosave-seconds", new String[] { String.valueOf((int) autosaveSeconds), "" });
        rows.put("backups", new String[] { String.valueOf(backups), "Number of ordinary world backups to keep" });
        rows.put("motd", new String[] { motd, "shown in the local-network list" });
        try (OutputStreamWriter out = new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8)) {
            // Комментарии латиницей: у консоли Windows кодировка не UTF-8, и
            // человек, открывший файл там, увидел бы кашу.
            out.write("# mineclone dedicated server\n");
            out.write("# Written automatically on first start; edit and restart.\n\n");
            for (Map.Entry<String, String[]> e : rows.entrySet()) {
                String comment = e.getValue()[1];
                if (!comment.isEmpty())
                    out.write("# " + comment + "\n");
                out.write(e.getKey() + "=" + e.getValue()[0] + "\n");
            }
        } catch (IOException e) {
            System.err.println("could not write " + file + ": " + e.getMessage());
        }
    }

    private static String strOf(Properties p, String key, String fallback) {
        String v = p.getProperty(key);
        return v == null ? fallback : v.trim();
    }

    private static int intOf(Properties p, String key, int fallback) {
        try {
            String v = p.getProperty(key);
            return v == null || v.isBlank() ? fallback : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long longOf(Properties p, String key, long fallback) {
        try {
            String v = p.getProperty(key);
            return v == null || v.isBlank() ? fallback : Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean boolOf(Properties p, String key, boolean fallback) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank())
            return fallback;
        return "true".equalsIgnoreCase(v.trim()) || "yes".equalsIgnoreCase(v.trim())
                || "1".equals(v.trim());
    }
}

package com.mineclone.net;

/**
 * Что игра помнит об игре по сети между запусками.
 *
 * <p>Живёт в {@code options.dat} рядом с раскладкой клавиш, а не в мире:
 * имя игрока, ключ приложения Photon и адрес последнего хозяина — свойства
 * этого компьютера, а не конкретного сохранения.
 *
 * <p>Ключ приложения Photon в сборку встроен ({@link #DEFAULT_APP_ID}):
 * заводить учётную запись ради того, чтобы зайти к другу, никто не будет.
 * Поле {@code appId} — это <b>переопределение</b>, а не сам ключ: пустое
 * означает «встроенный». Чей ключ используется, решает
 * {@link #effectiveAppId()}.
 */
public record NetSettings(int transport, String nickname, String appId, String region,
        String room, String address, int port) {

    /** Облако Photon: играть через интернет. */
    public static final int PHOTON = 0;
    /** Прямое соединение внутри своей сети. */
    public static final int LAN = 1;

    /**
     * Ключ приложения Photon, встроенный в сборку.
     *
     * <p>Он делит бесплатные сто одновременных игроков на всех, кто играет в
     * эту сборку, и виден каждому, у кого есть её файлы. Это осознанный
     * размен: своя учётная запись Photon ради одной партии с другом — барьер
     * выше, чем сама игра. Кому нужен свой лимит, вписывает свой ключ в
     * «Игра по сети» или задаёт {@code mineclone.photonAppId}.
     */
    public static final String DEFAULT_APP_ID = "8f6b4e4a-5804-4df7-af2f-13d813fc586a";

    /** Свойство и переменная окружения, которыми ключ подменяется снаружи. */
    public static final String APP_ID_PROPERTY = "mineclone.photonAppId";
    public static final String APP_ID_ENV = "MINECLONE_PHOTON_APPID";

    public NetSettings {
        nickname = clean(nickname, "Игрок", NetProto.NAME_LIMIT);
        appId = clean(appId, "", 64);
        region = region == null ? "" : region.trim();
        room = clean(room, "", 32);
        address = clean(address, "", 64);
        if (port <= 0 || port > 65535)
            port = LanTransport.DEFAULT_PORT;
        if (transport != PHOTON && transport != LAN)
            transport = PHOTON;
    }

    private static String clean(String s, String fallback, int limit) {
        if (s == null)
            return fallback;
        String t = s.trim();
        if (t.isEmpty())
            return fallback;
        return t.length() > limit ? t.substring(0, limit) : t;
    }

    public static NetSettings defaults() {
        return new NetSettings(PHOTON, "Игрок", "", "", "", "", LanTransport.DEFAULT_PORT);
    }

    public NetSettings withTransport(int t) {
        return new NetSettings(t, nickname, appId, region, room, address, port);
    }

    public NetSettings withNickname(String n) {
        return new NetSettings(transport, n, appId, region, room, address, port);
    }

    public NetSettings withAppId(String id) {
        return new NetSettings(transport, nickname, id, region, room, address, port);
    }

    public NetSettings withRegion(String r) {
        return new NetSettings(transport, nickname, appId, r, room, address, port);
    }

    public NetSettings withRoom(String r) {
        return new NetSettings(transport, nickname, appId, region, r, address, port);
    }

    public NetSettings withAddress(String a) {
        return new NetSettings(transport, nickname, appId, region, room, a, port);
    }

    public NetSettings withPort(int p) {
        return new NetSettings(transport, nickname, appId, region, room, address, p);
    }

    /**
     * Ключ, с которым пойдём в Photon.
     *
     * <p>Порядок: запуск (свойство или переменная окружения) → настройки
     * игрока → встроенный. Первым идёт запуск, потому что им пользуются
     * проверки и чужие сборки, и он обязан перебивать всё остальное.
     */
    public String effectiveAppId() {
        String fromRun = System.getProperty(APP_ID_PROPERTY);
        if (fromRun == null || fromRun.isBlank())
            fromRun = System.getenv(APP_ID_ENV);
        if (fromRun != null && !fromRun.isBlank())
            return fromRun.trim();
        return appId.isEmpty() ? DEFAULT_APP_ID : appId;
    }

    /** Играем на своём ключе, а не на встроенном. */
    public boolean hasOwnAppId() {
        return !appId.isEmpty();
    }

    /** Хватает ли данных, чтобы вообще пытаться подключиться. */
    public boolean canConnect(boolean hosting) {
        if (transport == PHOTON)
            return !effectiveAppId().isEmpty() && !room.isEmpty();
        return hosting || !address.isEmpty();
    }
}

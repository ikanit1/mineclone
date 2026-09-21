package com.mineclone.ui;

import com.mineclone.net.LanTransport;
import com.mineclone.net.NetProto;
import com.mineclone.net.NetSettings;
import com.mineclone.net.NetTransport;
import com.mineclone.net.RoomBrowser;
import com.mineclone.save.SaveManager;

import java.util.List;
import java.util.function.Consumer;

/**
 * Экран игры по сети: кто мы, куда идём и что открываем.
 *
 * <p>Два пути, одна разметка. Photon — через интернет, по списку открытых
 * комнат; локальная сеть — напрямую по адресу хозяина. Больше выбора на этом
 * экране нет намеренно: всё остальное («какой мир», «какой сид») уже решено в
 * других местах, и дублировать их здесь значило бы завести второй список
 * миров.
 *
 * <p>Ключ приложения Photon встроен в сборку, и поле для него — это
 * переопределение, а не обязательный шаг. Пустое поле значит «встроенный»;
 * так зайти к другу можно сразу, не заводя учётную запись, а кому нужен свой
 * лимит одновременных игроков — вписывает свой.
 *
 * <p>Список комнат экран не добывает сам: соединение с лобби держит игра
 * ({@link RoomBrowser}), а экран только читает. Экран живёт один кадр и
 * рисуется заново — соединению внутри него было бы негде храниться.
 */
public final class MultiplayerScreen implements Screen {

    /** Регионы, которые помещаются в один ряд. Остальные — через «Авто». */
    private static final String[] REGION_IDS = { "", "eu", "ru", "us", "asia", "jp", "sa" };
    /**
     * Короткие подписи регионов.
     *
     * <p>Полные названия в ряд из семи ячеек не влезают и обрезаются в
     * «Евр…», «Рос…», «Южн…» — а коды Photon и так стоят в его кабинете, так
     * что игрок видит ровно то, что выбирал там.
     */
    private static final String[] REGION_LABELS = {
            "Авто", "EU", "RU", "US", "Азия", "JP", "SA" };

    /** Высота строки комнаты в списке. */
    private static final float ROOM_ROW = 34f;

    private final SaveManager save;
    private final Consumer<NetSettings> onSave;
    private final RoomBrowser browser;
    private final TextField nameField;
    private final TextField appIdField;
    private final TextField roomField;
    private final TextField addressField;
    private final TextField portField;
    private final ScrollState roomScroll = new ScrollState();
    private final ScrollState worldScroll = new ScrollState();

    private NetSettings net;
    /** Открыт список миров: выбираем, какой отдать под комнату. */
    private boolean pickingWorld;
    private List<SaveManager.WorldInfo> worlds = List.of();
    private String note = "";
    /** Регион, с которым открыто лобби: сменили — переоткрываем. */
    private String lobbyRegion;

    public MultiplayerScreen(NetSettings initial, SaveManager save, Consumer<NetSettings> onSave) {
        this(initial, save, onSave, null);
    }

    public MultiplayerScreen(NetSettings initial, SaveManager save, Consumer<NetSettings> onSave,
            RoomBrowser browser) {
        this.save = save;
        this.onSave = onSave;
        this.browser = browser;
        this.net = initial == null ? NetSettings.defaults() : initial;
        this.nameField = new TextField(net.nickname(), NetProto.NAME_LIMIT, TextField.ANY);
        this.appIdField = new TextField(net.appId(), 64, MultiplayerScreen::appIdChar);
        this.roomField = new TextField(net.room(), 24, TextField.ANY);
        this.addressField = new TextField(net.address(), 48, TextField.ANY);
        this.portField = new TextField(String.valueOf(net.port()), 5, c -> c >= '0' && c <= '9');
        openLobby();
    }

    /** Ключ приложения Photon — шестнадцатеричная строка с дефисами. */
    private static boolean appIdChar(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
                || c == '-';
    }

    private void openLobby() {
        if (browser == null)
            return;
        if (net.transport() != NetSettings.PHOTON) {
            browser.close();
            lobbyRegion = null;
            return;
        }
        browser.open(net);
        lobbyRegion = net.region();
    }

    @Override
    public MenuAction draw(MenuTheme t) {
        int sw = t.width(), sh = t.height();
        float pw = Math.min(560f, sw - 60f);
        float ph = Math.min(sh - 70f, pickingWorld ? 460f
                : net.transport() == NetSettings.PHOTON ? 604f : 452f);
        float px = (sw - pw) / 2f, py = (sh - ph) / 2f;
        t.panel(px, py, pw, ph);
        float inner = px + 24f, iw = pw - 48f;
        float y = t.header(pickingWorld ? "Какой мир открыть" : "Игра по сети", inner, py + 18f, iw);
        y += 12f;
        MenuAction a = pickingWorld ? drawWorlds(t, inner, y, iw, py + ph - y - 68f)
                : drawSetup(t, inner, y, iw);
        float backY = py + ph - 56f;
        if (t.button("net.back", inner, backY, iw, 44f, pickingWorld ? "К настройкам" : "Назад")) {
            if (pickingWorld) {
                pickingWorld = false;
                return MenuAction.NONE;
            }
            push();
            return MenuAction.back();
        }
        return a;
    }

    // ----------------------------------------------------------- настройки

    private MenuAction drawSetup(MenuTheme t, float x, float y, float w) {
        float rowH = MenuTheme.ROW_H;
        float labelW = 120f;

        t.text("Имя", x, t.baseline(t.font(), y, rowH), MenuTheme.TEXT_DIM, 1f);
        t.textField("net.name", x + labelW, y, w - labelW, rowH, nameField, "как вас зовут");
        y += rowH + 10f;

        int mode = t.segmented("net.mode", x, y, w, rowH,
                new String[] { "Photon", "Локальная сеть" }, net.transport());
        if (mode != net.transport()) {
            net = net.withTransport(mode);
            openLobby();
        }
        y += rowH + 10f;

        MenuAction picked = MenuAction.NONE;
        if (net.transport() == NetSettings.PHOTON)
            y = drawPhoton(t, x, y, w, labelW, rowH);
        else
            y = drawLan(t, x, y, w, labelW, rowH);

        y += 4f;
        float half = (w - 12f) / 2f;
        boolean canJoin = commit().canConnect(false)
                && (net.transport() == NetSettings.PHOTON ? !roomField.text().isBlank()
                        : !addressField.text().isBlank());
        if (t.button("net.join", x, y, half, 46f, "Войти в комнату",
                MenuTheme.Style.PRIMARY, canJoin)) {
            push();
            return MenuAction.netJoin(net);
        }
        if (t.button("net.host", x + half + 12f, y, half, 46f, "Открыть мир")) {
            push();
            worlds = save.listWorlds(false);
            pickingWorld = true;
            note = "";
        }
        y += 46f + 8f;

        // Подсказка и ошибка делят одну строку: ошибка важнее.
        String hint = note.isEmpty()
                ? (net.transport() == NetSettings.PHOTON ? lobbyLine()
                        : "Адрес хозяина в своей сети, например 192.168.1.5:"
                                + LanTransport.DEFAULT_PORT)
                : note;
        t.smallText(MenuTheme.ellipsize(t.small(), hint, w), x, y + 12f,
                note.isEmpty() ? MenuTheme.TEXT_FAINT : MenuTheme.DANGER, 1f);
        y += 20f;

        if (net.transport() == NetSettings.PHOTON) {
            // Свой ключ — внизу и мелко: он нужен единицам, а место занимал бы
            // у списка комнат, ради которого экран и открывают.
            t.smallText("Свой ключ", x, t.baseline(t.small(), y, 26f), MenuTheme.TEXT_FAINT, 1f);
            t.textField("net.appId", x + 96f, y, w - 96f, 26f, appIdField, "встроенный");
        }
        return picked;
    }

    private float drawPhoton(MenuTheme t, float x, float y, float w, float labelW, float rowH) {
        t.text("Регион", x, t.baseline(t.font(), y, rowH), MenuTheme.TEXT_DIM, 1f);
        int index = 0;
        for (int i = 0; i < REGION_IDS.length; i++)
            if (REGION_IDS[i].equals(net.region()))
                index = i;
        int picked = t.segmentedSmall("net.region", x + labelW, y, w - labelW, rowH,
                REGION_LABELS, index);
        if (picked != index) {
            net = net.withRegion(REGION_IDS[picked]);
            // Комнаты живут в своём регионе: сменил регион — смотришь другой
            // список, и старый показывать нельзя.
            openLobby();
        }
        y += rowH + 8f;

        y = drawRooms(t, x, y, w, 136f);

        t.text("Комната", x, t.baseline(t.font(), y, rowH), MenuTheme.TEXT_DIM, 1f);
        t.textField("net.room", x + labelW, y, w - labelW, rowH, roomField,
                "название комнаты");
        return y + rowH + 8f;
    }

    /** Список открытых комнат с кнопкой обновления. Возвращает y под ним. */
    private float drawRooms(MenuTheme t, float x, float y, float w, float h) {
        float refreshW = 150f;
        t.smallText("Открытые комнаты", x, t.baseline(t.small(), y, 28f),
                MenuTheme.TEXT_DIM, 1f);
        if (t.button("net.refresh", x + w - refreshW, y, refreshW, 28f, "Обновить",
                MenuTheme.Style.QUIET, browser != null))
            openLobby();
        y += 32f;

        t.quad(x, y, w, h, 0f, 0f, 0f, 0.22f);
        List<NetTransport.RoomInfo> rooms = browser == null ? List.of() : browser.rooms();
        if (rooms.isEmpty()) {
            String empty = browser == null ? "список недоступен"
                    : switch (browser.state()) {
                        case CONNECTING -> "подключение к лобби…";
                        case FAILED -> "лобби недоступно";
                        default -> "открытых комнат нет — откройте свою";
                    };
            t.smallCentered(empty, x + w / 2f, y + h / 2f, MenuTheme.TEXT_FAINT, 1f);
            return y + h + 12f;
        }

        float contentH = rooms.size() * ROOM_ROW;
        t.beginClip(x, y, w, h);
        float top = y - roomScroll.offset();
        String current = roomField.text().trim();
        for (int i = 0; i < rooms.size(); i++) {
            NetTransport.RoomInfo r = rooms.get(i);
            float ry = top + i * ROOM_ROW;
            if (ry + ROOM_ROW < y || ry > y + h)
                continue;
            boolean selected = r.name().equals(current);
            if (t.row("net.room." + r.name(), x, ry, w - 12f, ROOM_ROW, selected)) {
                roomField.setText(r.name());
                net = net.withRoom(r.name());
            }
            t.text(MenuTheme.ellipsize(t.font(), r.name(), w - 110f), x + 12f,
                    t.baseline(t.font(), ry, ROOM_ROW), MenuTheme.TEXT, 1f);
            // Полная комната — не ошибка, но войти в неё нельзя: пусть это
            // будет видно до нажатия, а не после отказа сервера.
            boolean full = r.players() >= r.maxPlayers();
            t.smallRight(r.players() + "/" + r.maxPlayers(), x + w - 24f,
                    t.baseline(t.small(), ry, ROOM_ROW),
                    full ? MenuTheme.DANGER : MenuTheme.GOOD, 1f);
        }
        t.endClip();
        t.scrollArea("net.roomScroll", x, y, w, h, roomScroll, contentH);
        return y + h + 12f;
    }

    /** Строка состояния лобби под кнопками. */
    private String lobbyLine() {
        String key = net.hasOwnAppId() ? "свой ключ" : "встроенный ключ";
        if (browser == null)
            return "Ключ Photon: " + key;
        String s = browser.status();
        return "Ключ Photon: " + key + (s.isEmpty() ? "" : " · " + s);
    }

    private float drawLan(MenuTheme t, float x, float y, float w, float labelW, float rowH) {
        t.text("Адрес", x, t.baseline(t.font(), y, rowH), MenuTheme.TEXT_DIM, 1f);
        t.textField("net.address", x + labelW, y, w - labelW, rowH, addressField,
                "адрес хозяина, например 192.168.1.5");
        y += rowH + 10f;

        t.text("Порт", x, t.baseline(t.font(), y, rowH), MenuTheme.TEXT_DIM, 1f);
        t.textField("net.port", x + labelW, y, 110f, rowH, portField,
                String.valueOf(LanTransport.DEFAULT_PORT));
        // Подпись обрезается по остатку строки: за панель она вылезать не
        // имеет права, а места рядом с полем ровно столько, сколько есть.
        float noteX = x + labelW + 122f;
        t.smallText(MenuTheme.ellipsize(t.small(), "порт открывает хозяин", x + w - noteX),
                noteX, t.baseline(t.small(), y, rowH), MenuTheme.TEXT_FAINT, 1f);
        return y + rowH + 10f;
    }

    // -------------------------------------------------------- выбор мира

    private MenuAction drawWorlds(MenuTheme t, float x, float y, float w, float h) {
        if (worlds.isEmpty()) {
            t.textCentered("Ни одного мира нет — сначала создайте его в одиночной игре",
                    x + w / 2f, y + 60f, MenuTheme.TEXT_DIM, 1f);
            return MenuAction.NONE;
        }
        float rowH = 52f, gap = 6f;
        float contentH = worlds.size() * (rowH + gap);
        t.beginClip(x, y, w, h);
        float top = y - worldScroll.offset();
        MenuAction result = MenuAction.NONE;
        for (int i = 0; i < worlds.size(); i++) {
            SaveManager.WorldInfo wi = worlds.get(i);
            float ry = top + i * (rowH + gap);
            if (ry + rowH < y || ry > y + h)
                continue;
            if (wi.corrupted) {
                t.quad(x, ry, w - 12f, rowH, 0.25f, 0.08f, 0.08f, 0.45f);
                t.text(wi.displayName + " — повреждён", x + 14f,
                        t.baseline(t.font(), ry, rowH), MenuTheme.DANGER, 1f);
                continue;
            }
            if (t.row("net.world." + wi.id, x, ry, w - 12f, rowH, false)) {
                push();
                result = MenuAction.netHost(wi.id, net);
            }
            t.text(MenuTheme.ellipsize(t.font(), wi.displayName, w - 40f), x + 14f,
                    t.baseline(t.font(), ry, rowH) - 6f, MenuTheme.TEXT, 1f);
            t.smallText(wi.id, x + 14f, t.baseline(t.small(), ry, rowH) + 12f,
                    MenuTheme.TEXT_FAINT, 1f);
        }
        t.endClip();
        t.scrollArea("net.worldScroll", x, y, w, h, worldScroll, contentH);
        return result;
    }

    // ---------------------------------------------------------------- прочее

    /** Собрать настройки из полей. */
    private NetSettings commit() {
        net = net.withNickname(nameField.text())
                .withAppId(appIdField.text())
                .withRoom(roomField.text())
                .withAddress(addressField.text())
                .withPort(parsePort());
        return net;
    }

    private int parsePort() {
        try {
            return Integer.parseInt(portField.text().trim());
        } catch (NumberFormatException e) {
            return LanTransport.DEFAULT_PORT;
        }
    }

    private void push() {
        commit();
        if (onSave != null)
            onSave.accept(net);
    }

    @Override
    public MenuAction escape() {
        if (pickingWorld) {
            pickingWorld = false;
            return MenuAction.NONE;
        }
        push();
        return MenuAction.back();
    }

    @Override
    public void closed() {
        push();
        // Лобби и комната — два разных подключения. Держать лобби открытым
        // после ухода с экрана значит занимать место из ста бесплатных.
        if (browser != null)
            browser.close();
    }
}

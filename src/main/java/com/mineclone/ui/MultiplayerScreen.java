package com.mineclone.ui;

import com.mineclone.net.LanTransport;
import com.mineclone.net.NetProto;
import com.mineclone.net.NetSettings;
import com.mineclone.net.NetTransport;
import com.mineclone.net.RoomBrowser;
import com.mineclone.net.connect.RoomCode;
import com.mineclone.net.direct.LanBeacon;
import com.mineclone.net.direct.LanBrowser;
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
 * рисуется заново — соединению внутри него было бы негде храниться. Список
 * миров своей сети приходит тем же путём — от {@link LanBrowser}.
 *
 * <p><b>Вместо пары «регион и комната» — один код.</b> Договариваться о двух
 * вещах сразу оказалось непосильно: про регион забывали, по умолчанию стояло
 * «Авто», и друзья из разных стран попадали на разные мастер-серверы, где
 * комнат друг друга просто нет. Регион теперь едет внутри кода
 * ({@link RoomCode}), и продиктовать код, не продиктовав регион, нельзя.
 * Поле принимает шесть знаков, читает {@code O} как ноль, а {@code I} и
 * {@code L} как единицу — код, переписанный с экрана от руки, всё равно
 * войдёт.
 */
public final class MultiplayerScreen implements Screen {

    /** Высота строки комнаты в списке. */
    private static final float ROOM_ROW = 34f;

    private final SaveManager save;
    private final Consumer<NetSettings> onSave;
    private final RoomBrowser browser;
    private final LanBrowser lan;
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
        this(initial, save, onSave, null, null);
    }

    public MultiplayerScreen(NetSettings initial, SaveManager save, Consumer<NetSettings> onSave,
            RoomBrowser browser) {
        this(initial, save, onSave, browser, null);
    }

    public MultiplayerScreen(NetSettings initial, SaveManager save, Consumer<NetSettings> onSave,
            RoomBrowser browser, LanBrowser lan) {
        this.save = save;
        this.onSave = onSave;
        this.browser = browser;
        this.lan = lan;
        this.net = initial == null ? NetSettings.defaults() : initial;
        this.nameField = new TextField(net.nickname(), NetProto.NAME_LIMIT, TextField.ANY);
        this.appIdField = new TextField(net.appId(), 64, MultiplayerScreen::appIdChar);
        // Поле принимает только знаки алфавита кода: набрать в нём то, что
        // кодом быть не может, физически нельзя.
        this.roomField = new TextField(RoomCode.typed(net.room()), RoomCode.LENGTH,
                MultiplayerScreen::codeChar);
        this.addressField = new TextField(net.address(), 48, TextField.ANY);
        this.portField = new TextField(String.valueOf(net.port()), 5, c -> c >= '0' && c <= '9');
        openLobby();
    }

    /** Знак кода комнаты: алфавит кода плюс то, что в него приводится. */
    private static boolean codeChar(char c) {
        return !RoomCode.normalize(String.valueOf(c)).isEmpty();
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
        // Высота панели считается под содержимое каждой вкладки: у Photon
        // вместо ряда регионов одно поле кода, у прямого соединения прибавился
        // список миров своей сети.
        float ph = Math.min(sh - 70f, pickingWorld ? 460f
                : net.transport() == NetSettings.PHOTON ? 556f : 548f);
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
        // Код разбирается до нажатия: отказ «такой комнаты нет» из-за
        // опечатки стоит минуты ожидания, а серая кнопка — ничего.
        boolean canJoin = commit().canConnect(false)
                && (net.transport() == NetSettings.PHOTON
                        ? RoomCode.looksLikeCode(roomField.text())
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
        y = drawRooms(t, x, y, w, 136f);

        // Своя ширина подписи: «Код друга» длиннее «Адреса» и «Порта», под
        // которые рассчитан общий отступ, и налезала бы на поле.
        float codeLabelW = labelW + 32f;
        t.text("Код друга", x, t.baseline(t.font(), y, rowH), MenuTheme.TEXT_DIM, 1f);
        t.textField("net.room", x + codeLabelW, y, 150f, rowH, roomField, "A4K7M2");
        // Приводим набранное к виду кода прямо в поле: игрок печатает
        // строчными и с дефисом, а диктовать будет то, что видит. Поле
        // переписывается только когда приведение и правда что-то поменяло:
        // переписывать каждый кадр значит каждый кадр гнать курсор в конец.
        String typed = RoomCode.typed(roomField.text());
        if (!typed.equals(roomField.text()))
            roomField.setText(typed);
        net = net.withRoom(typed);

        // Под полем — что именно мы разобрали. Это единственное место, где
        // игрок видит регион, и видеть его надо не выбирая: он приехал вместе
        // с кодом.
        RoomCode code = RoomCode.parse(typed);
        String hint;
        float[] colour;
        if (typed.isEmpty()) {
            hint = "Введите код, который продиктовал друг — или откройте свой мир";
            colour = MenuTheme.TEXT_FAINT;
        } else if (code != null) {
            hint = "Регион: " + code.regionLabel();
            colour = MenuTheme.GOOD;
        } else {
            hint = "Это не похоже на код: их шесть знаков, первый — буква";
            colour = MenuTheme.DANGER;
        }
        t.smallText(MenuTheme.ellipsize(t.small(), hint, w - codeLabelW - 162f),
                x + codeLabelW + 162f, t.baseline(t.small(), y, rowH), colour, 1f);
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
        y = drawLanWorlds(t, x, y, w, 96f);

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

    /**
     * Миры, объявившиеся в своей сети.
     *
     * <p>Раньше войти к другу в соседней комнате стоило диктовки адреса,
     * которого хозяин обычно и не знал. Теперь мир просто появляется в
     * списке, а поле адреса остаётся для тех, кто идёт через интернет на
     * проброшенный порт.
     */
    private float drawLanWorlds(MenuTheme t, float x, float y, float w, float h) {
        t.smallText("Миры в вашей сети", x, t.baseline(t.small(), y, 24f),
                MenuTheme.TEXT_DIM, 1f);
        y += 26f;
        t.quad(x, y, w, h, 0f, 0f, 0f, 0.22f);
        List<LanBeacon.Announcement> found = lan == null ? List.of() : lan.worlds();
        if (found.isEmpty()) {
            t.smallCentered(lan == null ? "поиск недоступен" : lan.status(),
                    x + w / 2f, y + h / 2f, MenuTheme.TEXT_FAINT, 1f);
            return y + h + 10f;
        }
        float contentH = found.size() * ROOM_ROW;
        t.beginClip(x, y, w, h);
        float top = y - worldScroll.offset();
        String current = addressField.text().trim();
        for (int i = 0; i < found.size(); i++) {
            LanBeacon.Announcement a = found.get(i);
            float ry = top + i * ROOM_ROW;
            if (ry + ROOM_ROW < y || ry > y + h)
                continue;
            boolean selected = a.dialable().equals(current);
            if (t.row("net.lan." + a.dialable(), x, ry, w - 12f, ROOM_ROW, selected)) {
                addressField.setText(a.dialable());
                portField.setText(String.valueOf(a.port()));
                net = net.withAddress(a.dialable()).withPort(a.port());
            }
            String label = a.world().isEmpty() ? a.dialable() : a.world();
            if (!a.host().isEmpty())
                label = label + " · " + a.host();
            t.text(MenuTheme.ellipsize(t.font(), label, w - 110f), x + 12f,
                    t.baseline(t.font(), ry, ROOM_ROW), MenuTheme.TEXT, 1f);
            boolean full = a.players() >= a.maxPlayers();
            t.smallRight(a.players() + "/" + a.maxPlayers(), x + w - 24f,
                    t.baseline(t.small(), ry, ROOM_ROW),
                    full ? MenuTheme.DANGER : MenuTheme.GOOD, 1f);
        }
        t.endClip();
        t.scrollArea("net.lanScroll", x, y, w, h, worldScroll, contentH);
        return y + h + 10f;
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

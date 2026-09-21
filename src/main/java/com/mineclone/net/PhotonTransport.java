package com.mineclone.net;

import com.mineclone.net.photon.PhotonCodes;
import com.mineclone.net.photon.PhotonJson;
import com.mineclone.net.photon.PhotonPeer;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Комната в облаке Photon.
 *
 * <p>Проходит штатный путь Photon Realtime: сервер имён отдаёт адрес мастера
 * нужного региона, мастер занимается подбором комнат, игровой сервер —
 * событиями внутри комнаты. Соединений три, живут они по очереди, и на каждое
 * приходится свой {@link PhotonPeer}.
 *
 * <p>Версия приложения склеена с версией протокола игры
 * ({@link NetProto#VERSION}). Это не косметика: Photon разделяет комнаты по
 * версии приложения, поэтому сборки с разным протоколом просто не видят комнат
 * друг друга — лучше, чем увидеть и рассыпаться на первом же пакете.
 *
 * <p>Деление на надёжные и ненадёжные сообщения здесь условно: вход по
 * WebSocket идёт поверх TCP, и доходит всё. Флаг сохранён потому, что у
 * {@link LanTransport} и у будущего UDP-входа он настоящий, а сессия не должна
 * знать, какой транспорт под ней.
 */
public final class PhotonTransport implements NetTransport {

    /** Код нашего события в комнате. Одно на все пакеты: внутри свой разбор. */
    private static final int EVENT_CODE = 1;
    /** Сколько игроков вмещает комната. */
    public static final int MAX_PLAYERS = 8;

    private final String appId;
    private final String region;
    private final boolean secure;
    private final Listener listener;

    /** Всё, что пришло из сети, ждёт игрового потока. */
    private final ConcurrentLinkedQueue<Runnable> inbox = new ConcurrentLinkedQueue<>();
    private final Map<Integer, String> names = new ConcurrentHashMap<>();

    private PhotonPeer nameServer;
    private PhotonPeer master;
    private PhotonPeer game;

    private volatile State state = State.IDLE;
    private String masterAddress = "";
    private String gameAddress = "";
    private String secret = "";
    private String nickname = "";
    private String roomName = "";
    private boolean createRoom;
    /** Только посмотреть список комнат и остановиться в лобби. */
    private boolean browseOnly;
    private boolean creator;
    private volatile int actor;
    private volatile int serverMasterActor;
    private final List<Integer> peers = new ArrayList<>();

    public PhotonTransport(String appId, String region, boolean secure, Listener listener) {
        this.appId = appId == null ? "" : appId.trim();
        this.region = region == null ? "" : region.trim();
        this.secure = secure;
        this.listener = listener;
    }

    private static String appVersion() {
        return "mineclone-" + NetProto.VERSION;
    }

    // ------------------------------------------------------------ подключение

    @Override
    public void connect(String room, boolean create, String nick) {
        this.nickname = nick == null ? "" : nick;
        this.roomName = room == null ? "" : room.trim();
        this.createRoom = create;
        this.browseOnly = this.roomName.isEmpty();
        if (appId.isEmpty()) {
            fail("не задан ключ приложения Photon (App ID)");
            return;
        }
        // Уже в лобби — дальше идти не надо, комната берётся оттуда.
        if (state == State.CONNECTING && master != null && master.isConnected() && !browseOnly) {
            requestRoom();
            return;
        }
        closePeers();
        setState(State.CONNECTING, "сервер имён Photon");
        nameServer = new PhotonPeer("NameServer", new PeerHandler());
        nameServer.connect(secure ? PhotonCodes.NAME_SERVER_WSS : PhotonCodes.NAME_SERVER_WS,
                appId, secure);
    }

    @Override
    public void requestRoomList() {
        if (state == State.IDLE || state == State.CLOSED || state == State.FAILED)
            connect("", false, nickname);
    }

    private void onNameServerConnected() {
        nameServer.sendOp(PhotonCodes.OP_AUTHENTICATE,
                PhotonCodes.P_APPLICATION_ID, appId,
                PhotonCodes.P_APP_VERSION, appVersion(),
                PhotonCodes.P_REGION, region);
    }

    private void onNameServerAuth(Map<Integer, Object> vals) {
        masterAddress = PhotonJson.strOr(vals, PhotonCodes.P_ADDRESS, "");
        secret = PhotonJson.strOr(vals, PhotonCodes.P_SECRET, "");
        if (masterAddress.isEmpty()) {
            fail("сервер имён не дал адрес мастера");
            return;
        }
        PhotonPeer old = nameServer;
        nameServer = null;
        if (old != null)
            old.close();
        setState(State.CONNECTING, "мастер-сервер " + PhotonCodes.regionLabel(region));
        master = new PhotonPeer("Master", new PeerHandler());
        master.connect(masterAddress, appId, secure);
    }

    private void onMasterConnected() {
        if (!secret.isEmpty())
            master.sendOp(PhotonCodes.OP_AUTHENTICATE, PhotonCodes.P_SECRET, secret);
        else
            master.sendOp(PhotonCodes.OP_AUTHENTICATE,
                    PhotonCodes.P_APPLICATION_ID, appId,
                    PhotonCodes.P_APP_VERSION, appVersion());
    }

    private void onMasterAuth(Map<Integer, Object> vals) {
        String s = PhotonJson.strOr(vals, PhotonCodes.P_SECRET, "");
        if (!s.isEmpty())
            secret = s;
        // В лобби заходим всегда: список комнат нужен и тому, кто пришёл
        // смотреть, и тому, кто через миг войдёт в свою — а второй вход в
        // лобби стоил бы лишнего круга.
        master.sendOp(PhotonCodes.OP_JOIN_LOBBY);
        if (!browseOnly)
            requestRoom();
        else
            setState(State.CONNECTING, "список комнат");
    }

    /** Попросить мастера подобрать комнату: создать или войти. */
    private void requestRoom() {
        if (master == null || !master.isConnected())
            return;
        setState(State.CONNECTING, (createRoom ? "создание комнаты " : "вход в комнату ") + roomName);
        List<Object> kv = new ArrayList<>();
        kv.add(PhotonCodes.P_GAME_ID);
        kv.add(roomName);
        if (createRoom) {
            kv.add(PhotonCodes.P_JOIN_MODE);
            kv.add(PhotonCodes.JOIN_MODE_CREATE_IF_NOT_EXISTS);
            addCreateOptions(kv);
        }
        master.sendOp(PhotonCodes.OP_JOIN_GAME, kv.toArray());
    }

    /**
     * Свойства новой комнаты.
     *
     * <p>Ключи свойств у Photon числовые, а в JSON объект ключи всё равно
     * строки — сервер это ждёт и разбирает сам.
     */
    private void addCreateOptions(List<Object> kv) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(String.valueOf(PhotonCodes.ROOM_MAX_PLAYERS), MAX_PLAYERS);
        props.put(String.valueOf(PhotonCodes.ROOM_IS_VISIBLE), true);
        props.put(String.valueOf(PhotonCodes.ROOM_IS_OPEN), true);
        kv.add(PhotonCodes.P_GAME_PROPERTIES);
        kv.add(props);
        kv.add(PhotonCodes.P_CLEANUP_CACHE_ON_LEAVE);
        kv.add(true);
        kv.add(PhotonCodes.P_BROADCAST);
        kv.add(true);
        // Комната не должна переживать выход хозяина: мир живёт у него, и
        // пустая комната с чужим именем только мешает следующему заходу.
        kv.add(PhotonCodes.P_EMPTY_ROOM_TTL);
        kv.add(0);
        kv.add(PhotonCodes.P_PLAYER_TTL);
        kv.add(0);
    }

    private void onRoomPicked(Map<Integer, Object> vals) {
        gameAddress = PhotonJson.strOr(vals, PhotonCodes.P_ADDRESS, "");
        String s = PhotonJson.strOr(vals, PhotonCodes.P_SECRET, "");
        if (!s.isEmpty())
            secret = s;
        if (gameAddress.isEmpty()) {
            fail("мастер не дал адрес игрового сервера");
            return;
        }
        PhotonPeer old = master;
        master = null;
        if (old != null)
            old.close();
        setState(State.CONNECTING, "игровой сервер");
        game = new PhotonPeer("Game", new PeerHandler());
        game.connect(gameAddress, appId, secure);
    }

    private void onGameConnected() {
        game.sendOp(PhotonCodes.OP_AUTHENTICATE,
                PhotonCodes.P_APPLICATION_ID, appId,
                PhotonCodes.P_APP_VERSION, appVersion(),
                PhotonCodes.P_SECRET, secret);
    }

    private void onGameAuth() {
        Map<String, Object> actorProps = new LinkedHashMap<>();
        actorProps.put(String.valueOf(PhotonCodes.ACTOR_PLAYER_NAME), nickname);
        List<Object> kv = new ArrayList<>();
        kv.add(PhotonCodes.P_GAME_ID);
        kv.add(roomName);
        kv.add(PhotonCodes.P_BROADCAST);
        kv.add(true);
        kv.add(PhotonCodes.P_ACTOR_PROPERTIES);
        kv.add(actorProps);
        if (createRoom) {
            kv.add(PhotonCodes.P_JOIN_MODE);
            kv.add(PhotonCodes.JOIN_MODE_CREATE_IF_NOT_EXISTS);
            addCreateOptions(kv);
        }
        game.sendOp(PhotonCodes.OP_JOIN_GAME, kv.toArray());
    }

    private void onJoined(Map<Integer, Object> vals) {
        actor = PhotonJson.intOr(vals, PhotonCodes.P_ACTOR_NR, 0);
        peers.clear();
        names.clear();
        names.put(actor, nickname);
        Map<String, Object> allProps = PhotonJson.objectOr(vals.get(PhotonCodes.P_ACTOR_PROPERTIES));
        List<Object> list = PhotonJson.listOr(vals.get(PhotonCodes.P_ACTOR_LIST));
        for (Object o : list) {
            Integer a = PhotonJson.asInt(o);
            if (a == null || a == actor)
                continue;
            peers.add(a);
            names.put(a, nameFrom(allProps.get(String.valueOf(a))));
        }
        Map<String, Object> gameProps = PhotonJson.objectOr(vals.get(PhotonCodes.P_GAME_PROPERTIES));
        Integer mc = PhotonJson.asInt(gameProps.get(String.valueOf(PhotonCodes.ROOM_MASTER_CLIENT_ID)));
        serverMasterActor = mc == null ? 0 : mc;
        // Комната наша, если внутри больше никого: Photon отдаёт один и тот же
        // ответ на «войти» и на «войти или создать», и отличить создание можно
        // только по составу.
        creator = peers.isEmpty();
        setState(State.JOINED, roomName);
        int myActor = actor;
        boolean created = creator;
        List<Integer> existing = new ArrayList<>(peers);
        inbox.add(() -> {
            listener.onJoined(myActor, created);
            for (Integer a : existing)
                listener.onActorJoin(a, actorName(a));
        });
    }

    private static String nameFrom(Object props) {
        Map<String, Object> m = PhotonJson.objectOr(props);
        Object n = m.get(String.valueOf(PhotonCodes.ACTOR_PLAYER_NAME));
        return n instanceof String s ? s : "";
    }

    // ------------------------------------------------------------- отправка

    @Override
    public void send(byte[] payload, boolean reliable, int target) {
        if (game == null || !game.isConnected() || state != State.JOINED)
            return;
        String data = Base64.getEncoder().encodeToString(payload);
        if (target == NetChannel.ALL) {
            game.sendOp(PhotonCodes.OP_RAISE_EVENT,
                    PhotonCodes.P_CODE, EVENT_CODE,
                    PhotonCodes.P_DATA, data);
        } else {
            game.sendOp(PhotonCodes.OP_RAISE_EVENT,
                    PhotonCodes.P_CODE, EVENT_CODE,
                    PhotonCodes.P_DATA, data,
                    PhotonCodes.P_ACTOR_LIST, new int[] { target });
        }
    }

    @Override
    public void poll() {
        Runnable r;
        while ((r = inbox.poll()) != null)
            r.run();
    }

    @Override
    public void disconnect() {
        if (game != null && game.isConnected() && state == State.JOINED)
            game.sendOp(PhotonCodes.OP_LEAVE);
        closePeers();
        peers.clear();
        names.clear();
        actor = 0;
        setState(State.CLOSED, "отключено");
    }

    private void closePeers() {
        PhotonPeer[] all = { nameServer, master, game };
        nameServer = null;
        master = null;
        game = null;
        for (PhotonPeer p : all)
            if (p != null)
                p.close();
    }

    private void setState(State s, String detail) {
        state = s;
        inbox.add(() -> listener.onState(s, detail));
    }

    private void fail(String why) {
        closePeers();
        state = State.FAILED;
        inbox.add(() -> listener.onState(State.FAILED, why));
    }

    // -------------------------------------------------------------- сведения

    @Override
    public State state() {
        return state;
    }

    @Override
    public int myActor() {
        return actor;
    }

    @Override
    public int masterActor() {
        if (actor == 0)
            return 0;
        // Слово сервера главнее, но только если этот номер ещё в комнате:
        // между выходом хозяина и новым свойством комнаты проходит тик.
        if (serverMasterActor > 0 && (serverMasterActor == actor || peers.contains(serverMasterActor)))
            return serverMasterActor;
        int lowest = actor;
        for (Integer a : peers)
            lowest = Math.min(lowest, a);
        return lowest;
    }

    @Override
    public List<Integer> actors() {
        return new ArrayList<>(peers);
    }

    @Override
    public String actorName(int a) {
        return names.getOrDefault(a, "");
    }

    @Override
    public String describe() {
        String where = region.isEmpty() ? "авто" : region;
        int rtt = game != null ? game.rtt() : (master != null ? master.rtt() : 0);
        return "Photon " + where + " " + rtt + " ms #" + actor;
    }

    // ------------------------------------------------------- ответы соединений

    /** Один обработчик на все три соединения: кто позвал, видно по объекту. */
    private final class PeerHandler implements PhotonPeer.Handler {

        @Override
        public void onConnected(PhotonPeer peer) {
            if (peer == nameServer)
                onNameServerConnected();
            else if (peer == master)
                onMasterConnected();
            else if (peer == game)
                onGameConnected();
        }

        @Override
        public void onResponse(PhotonPeer peer, int op, int errCode, String errMsg,
                Map<Integer, Object> vals) {
            if (errCode != PhotonCodes.ERR_OK) {
                // «Комнаты нет» при обычном входе — не поломка, а ответ: так
                // выглядит опечатка в имени комнаты.
                fail(PhotonCodes.errorText(errCode, errMsg));
                return;
            }
            if (peer == nameServer && op == PhotonCodes.OP_AUTHENTICATE) {
                onNameServerAuth(vals);
            } else if (peer == master) {
                if (op == PhotonCodes.OP_AUTHENTICATE)
                    onMasterAuth(vals);
                else if (op == PhotonCodes.OP_JOIN_GAME || op == PhotonCodes.OP_CREATE_GAME)
                    onRoomPicked(vals);
            } else if (peer == game) {
                if (op == PhotonCodes.OP_AUTHENTICATE)
                    onGameAuth();
                else if (op == PhotonCodes.OP_JOIN_GAME || op == PhotonCodes.OP_CREATE_GAME)
                    onJoined(vals);
            }
        }

        @Override
        public void onEvent(PhotonPeer peer, int code, Map<Integer, Object> vals) {
            switch (code) {
                case EVENT_CODE -> {
                    Integer from = PhotonJson.asInt(vals.get(PhotonCodes.P_ACTOR_NR));
                    Object data = vals.get(PhotonCodes.P_DATA);
                    if (from == null || !(data instanceof String s))
                        return;
                    byte[] bytes;
                    try {
                        bytes = Base64.getDecoder().decode(s);
                    } catch (IllegalArgumentException e) {
                        return;
                    }
                    int sender = from;
                    inbox.add(() -> listener.onPayload(sender, bytes));
                }
                case PhotonCodes.EV_JOIN -> {
                    Integer a = PhotonJson.asInt(vals.get(PhotonCodes.P_ACTOR_NR));
                    if (a == null || a == actor)
                        return;
                    String name = nameFrom(vals.get(PhotonCodes.P_ACTOR_PROPERTIES));
                    if (name.isEmpty()) {
                        Map<String, Object> all =
                                PhotonJson.objectOr(vals.get(PhotonCodes.P_ACTOR_PROPERTIES));
                        name = nameFrom(all.get(String.valueOf(a)));
                    }
                    if (!peers.contains(a))
                        peers.add(a);
                    names.put(a, name);
                    int joined = a;
                    String label = name;
                    inbox.add(() -> listener.onActorJoin(joined, label));
                }
                case PhotonCodes.EV_LEAVE, PhotonCodes.EV_DISCONNECT -> {
                    Integer a = PhotonJson.asInt(vals.get(PhotonCodes.P_ACTOR_NR));
                    if (a == null)
                        return;
                    peers.remove(a);
                    names.remove(a);
                    Integer mc = PhotonJson.asInt(vals.get(PhotonCodes.P_MASTER_CLIENT_ID));
                    if (mc != null)
                        serverMasterActor = mc;
                    int left = a;
                    inbox.add(() -> listener.onActorLeave(left));
                }
                case PhotonCodes.EV_PROPERTIES_CHANGED -> {
                    Map<String, Object> props = PhotonJson.objectOr(vals.get(PhotonCodes.P_PROPERTIES));
                    Integer mc = PhotonJson.asInt(
                            props.get(String.valueOf(PhotonCodes.ROOM_MASTER_CLIENT_ID)));
                    if (mc != null)
                        serverMasterActor = mc;
                }
                case PhotonCodes.EV_GAME_LIST, PhotonCodes.EV_GAME_LIST_UPDATE ->
                        onGameList(PhotonJson.objectOr(vals.get(PhotonCodes.P_GAME_LIST)),
                                code == PhotonCodes.EV_GAME_LIST);
                case PhotonCodes.EV_ERROR_INFO -> {
                    String info = PhotonJson.strOr(vals, PhotonCodes.P_INFO, "ошибка комнаты");
                    inbox.add(() -> listener.onState(State.FAILED, info));
                }
                default -> {
                }
            }
        }

        @Override
        public void onClosed(PhotonPeer peer, String reason, boolean wasConnected) {
            // Мы сами закрываем соединение при переходе на следующий сервер —
            // такой разрыв не ошибка.
            if (peer != nameServer && peer != master && peer != game)
                return;
            if (state == State.CLOSED || state == State.FAILED)
                return;
            fail(reason);
        }

        @Override
        public void onError(PhotonPeer peer, String message) {
            if (peer != nameServer && peer != master && peer != game)
                return;
            if (state == State.CLOSED || state == State.FAILED)
                return;
            fail(message);
        }
    }

    /** Список комнат из лобби: полный при входе и дополняющий потом. */
    private final Map<String, RoomInfo> rooms = new LinkedHashMap<>();

    private void onGameList(Map<String, Object> list, boolean full) {
        if (full)
            rooms.clear();
        for (Map.Entry<String, Object> e : list.entrySet()) {
            Map<String, Object> props = PhotonJson.objectOr(e.getValue());
            Object removed = props.get(String.valueOf(PhotonCodes.ROOM_REMOVED));
            if (Boolean.TRUE.equals(removed)) {
                rooms.remove(e.getKey());
                continue;
            }
            Integer count = PhotonJson.asInt(props.get(String.valueOf(PhotonCodes.ROOM_PLAYER_COUNT)));
            Integer max = PhotonJson.asInt(props.get(String.valueOf(PhotonCodes.ROOM_MAX_PLAYERS)));
            rooms.put(e.getKey(), new RoomInfo(e.getKey(),
                    count == null ? 0 : count, max == null ? MAX_PLAYERS : max));
        }
        List<RoomInfo> snapshot = new ArrayList<>(rooms.values());
        inbox.add(() -> listener.onRoomList(snapshot));
    }
}

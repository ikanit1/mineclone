package com.mineclone.net;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Две двери в одну комнату.
 *
 * <p>Выделенному серверу нужны обе сразу. Свой TCP-порт — это ни от кого не
 * зависящий вход для тех, у кого есть белый адрес или проброшенный порт;
 * комната Photon — вход для всех остальных, которым настраивать сеть негде и
 * некогда. Заставлять хозяина выбирать одно из двух значит отсечь половину
 * друзей, а поднимать два сервера — держать два мира.
 *
 * <p>Композит и есть хозяин: у него номер {@code 1}, и он же всем ведёт счёт.
 * Номера участников он раздаёт свои, подряд, а у себя держит таблицу «наш
 * номер — какая дверь и какой номер за ней». Арифметики со смещениями здесь
 * нет нарочно: у Photon номера в комнате растут сами по себе, и любая схема
 * вида «дверь умножить на сто» рано или поздно столкнула бы двух игроков в
 * один номер.
 *
 * <p><b>Перекрёстная пересылка.</b> Участник за одной дверью не слышит
 * участника за другой: широковещательное сообщение Photon доходит до Photon,
 * кадр TCP — до TCP. Поэтому всё, что пришло от участника, уходит в остальные
 * двери. Разбирать при этом, кому именно оно было адресовано, композит не
 * может — ни Photon, ни наш кадр адресата получателю не сообщают. Цена
 * известна: просьбы, адресованные хозяину ({@code C_*}), доезжают и до чужих
 * участников, которые их молча отбрасывают — каждый такой обработчик в
 * {@link Multiplayer} и так спрашивает роль. Это несколько лишних байт на
 * просьбу и ни одной лишней ветки логики.
 *
 * <p><b>Одна отказавшая дверь не закрывает комнату.</b> Нет интернета — играем
 * по своей сети; занят порт — играем через облако. Композит становится
 * {@code FAILED}, только когда не открылась ни одна.
 */
public final class CompositeTransport implements NetTransport {

    /** Хозяин композита — всегда первый номер. */
    public static final int HOST_ACTOR = 1;

    /** Дверь: как её зовут и как из неё сделать транспорт. */
    public interface Door {
        String label();

        /** Создать транспорт; он будет докладывать переданному слушателю. */
        NetTransport open(Listener listener);
    }

    /** Участник за какой-то из дверей. */
    private record Peer(int door, int localActor, String name) {
    }

    private final Listener listener;
    private final List<NetTransport> doors = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();
    /** Что каждая дверь сказала о себе последним. */
    private final List<String> details = new ArrayList<>();
    private final List<State> states = new ArrayList<>();

    private final Map<Integer, Peer> peers = new LinkedHashMap<>();
    private int nextActor = 2;
    private State state = State.IDLE;
    private boolean announcedJoin;

    public CompositeTransport(Listener listener, List<Door> doorList) {
        this.listener = listener;
        if (doorList == null || doorList.isEmpty())
            throw new IllegalArgumentException("композит без дверей");
        for (int i = 0; i < doorList.size(); i++) {
            Door d = doorList.get(i);
            labels.add(d.label());
            details.add("");
            states.add(State.IDLE);
            doors.add(d.open(new DoorListener(i)));
        }
    }

    // ------------------------------------------------------------ подключение

    @Override
    public void connect(String room, boolean create, String nickname) {
        state = State.CONNECTING;
        for (int i = 0; i < doors.size(); i++)
            connectDoor(i, room, create, nickname);
    }

    /**
     * Открыть одну дверь под своим именем комнаты.
     *
     * <p>У дверей имена разные: облаку нужен код комнаты, своему порту — имя,
     * которое всё равно никто не прочтёт, потому что там комната и есть порт.
     * Сводить их к одному было бы экономией на честности.
     */
    public void connectDoor(int index, String room, boolean create, String nickname) {
        if (index < 0 || index >= doors.size())
            return;
        if (state == State.IDLE)
            state = State.CONNECTING;
        doors.get(index).connect(room, create, nickname);
    }

    @Override
    public void poll() {
        for (NetTransport t : doors)
            t.poll();
    }

    @Override
    public void disconnect() {
        for (NetTransport t : doors)
            t.disconnect();
        peers.clear();
        state = State.CLOSED;
        listener.onState(State.CLOSED, "отключено");
    }

    // -------------------------------------------------------------- отправка

    @Override
    public void send(byte[] payload, boolean reliable, int target) {
        if (target == NetChannel.ALL) {
            for (NetTransport t : doors)
                t.send(payload, reliable, NetChannel.ALL);
            return;
        }
        Peer p = peers.get(target);
        if (p == null)
            return;
        doors.get(p.door()).send(payload, reliable, p.localActor());
    }

    /** Разослать то, что пришло от участника, в остальные двери. */
    private void crossForward(int fromDoor, byte[] payload) {
        for (int i = 0; i < doors.size(); i++) {
            if (i == fromDoor)
                continue;
            if (states.get(i) == State.JOINED)
                doors.get(i).send(payload, true, NetChannel.ALL);
        }
    }

    // -------------------------------------------------------------- сведения

    @Override
    public State state() {
        return state;
    }

    @Override
    public int myActor() {
        return state == State.JOINED ? HOST_ACTOR : 0;
    }

    @Override
    public int masterActor() {
        // Мир живёт здесь, и мигрировать ему некуда: композит поднимает тот,
        // у кого этот мир в памяти.
        return state == State.JOINED ? HOST_ACTOR : 0;
    }

    @Override
    public List<Integer> actors() {
        return new ArrayList<>(peers.keySet());
    }

    @Override
    public String actorName(int actor) {
        Peer p = peers.get(actor);
        return p == null ? "" : p.name();
    }

    @Override
    public void requestRoomList() {
        for (NetTransport t : doors)
            t.requestRoomList();
    }

    @Override
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < doors.size(); i++) {
            if (i > 0)
                sb.append(" · ");
            sb.append(labels.get(i)).append(": ").append(stateWord(states.get(i)));
        }
        sb.append(" · участников ").append(peers.size());
        return sb.toString();
    }

    /** Через какую дверь пришёл участник: для отладочной строки и консоли. */
    public String doorOf(int actor) {
        Peer p = peers.get(actor);
        return p == null ? "" : labels.get(p.door());
    }

    /** Открылась ли конкретная дверь. */
    public boolean doorOpen(int index) {
        return index >= 0 && index < states.size() && states.get(index) == State.JOINED;
    }

    /** Что дверь сказала о себе последним — причина отказа или ход дела. */
    public String doorDetail(int index) {
        return index >= 0 && index < details.size() ? details.get(index) : "";
    }

    public int doorCount() {
        return doors.size();
    }

    public String doorLabel(int index) {
        return index >= 0 && index < labels.size() ? labels.get(index) : "";
    }

    private static String stateWord(State s) {
        return switch (s) {
            case IDLE -> "не начата";
            case CONNECTING -> "открывается";
            case JOINED -> "открыта";
            case CLOSED -> "закрыта";
            case FAILED -> "не открылась";
        };
    }

    // ---------------------------------------------------------- пересчёт

    /**
     * Состояние комнаты из состояний дверей.
     *
     * <p>Открыта, если открыта хоть одна: игроку всё равно, какой дверью зашёл
     * его друг. Не открылась — только когда не открылась ни одна.
     */
    private void recomputeState() {
        boolean anyJoined = false;
        boolean anyConnecting = false;
        for (State s : states) {
            anyJoined |= s == State.JOINED;
            anyConnecting |= s == State.CONNECTING;
        }
        State next;
        if (anyJoined)
            next = State.JOINED;
        else if (anyConnecting)
            next = State.CONNECTING;
        else
            next = State.FAILED;
        state = next;
        // Сообщаем каждый раз, а не только при смене: вторая дверь могла
        // отказать, когда первая уже открыта, и игроку это надо знать.
        listener.onState(state, summary());
        if (state == State.JOINED && !announcedJoin) {
            announcedJoin = true;
            // Композит поднимает тот, у кого мир: комната по определению его.
            listener.onJoined(HOST_ACTOR, true);
        }
    }

    /** Строка о том, что открылось, а что нет — она и идёт на экран. */
    private String summary() {
        List<String> open = new ArrayList<>();
        List<String> broken = new ArrayList<>();
        for (int i = 0; i < doors.size(); i++) {
            if (states.get(i) == State.JOINED)
                open.add(labels.get(i));
            else if (states.get(i) == State.FAILED)
                broken.add(labels.get(i) + " — " + details.get(i));
        }
        StringBuilder sb = new StringBuilder();
        if (!open.isEmpty())
            sb.append("открыто: ").append(String.join(", ", open));
        if (!broken.isEmpty()) {
            if (sb.length() > 0)
                sb.append("; ");
            sb.append("не открылось: ").append(String.join("; ", broken));
        }
        return sb.length() == 0 ? "открываем двери" : sb.toString();
    }

    /** Ответы одной двери. Зовутся из её {@code poll()}, то есть из игрового потока. */
    private final class DoorListener implements Listener {
        private final int door;

        DoorListener(int door) {
            this.door = door;
        }

        @Override
        public void onState(State s, String detail) {
            states.set(door, s);
            details.set(door, detail == null ? "" : detail);
            recomputeState();
        }

        @Override
        public void onJoined(int myActor, boolean created) {
            // Композит сам решает, кто хозяин: своего номера за дверью нам
            // мало — за второй дверью он другой.
            states.set(door, State.JOINED);
            recomputeState();
        }

        @Override
        public void onActorJoin(int actor, String name) {
            long key = key(door, actor);
            for (Map.Entry<Integer, Peer> e : peers.entrySet())
                if (key(e.getValue().door(), e.getValue().localActor()) == key) {
                    // Переподключился под тем же номером: имя могло смениться.
                    peers.put(e.getKey(), new Peer(door, actor, name));
                    listener.onActorJoin(e.getKey(), name);
                    return;
                }
            int global = nextActor++;
            peers.put(global, new Peer(door, actor, name));
            listener.onActorJoin(global, name);
        }

        @Override
        public void onActorLeave(int actor) {
            long key = key(door, actor);
            Integer global = null;
            for (Map.Entry<Integer, Peer> e : peers.entrySet())
                if (key(e.getValue().door(), e.getValue().localActor()) == key) {
                    global = e.getKey();
                    break;
                }
            if (global == null)
                return;
            peers.remove(global);
            listener.onActorLeave(global);
        }

        @Override
        public void onPayload(int from, byte[] data) {
            long key = key(door, from);
            for (Map.Entry<Integer, Peer> e : peers.entrySet())
                if (key(e.getValue().door(), e.getValue().localActor()) == key) {
                    listener.onPayload(e.getKey(), data);
                    crossForward(door, data);
                    return;
                }
            // Байты от того, о чьём появлении нам ещё не сказали. Так бывает:
            // у Photon первое событие участника может обогнать событие входа.
            int global = nextActor++;
            peers.put(global, new Peer(door, from, ""));
            listener.onActorJoin(global, "");
            listener.onPayload(global, data);
            crossForward(door, data);
        }

        @Override
        public void onRoomList(List<RoomInfo> rooms) {
            listener.onRoomList(rooms);
        }

        private long key(int door, int localActor) {
            return ((long) door << 32) | (localActor & 0xFFFFFFFFL);
        }
    }
}

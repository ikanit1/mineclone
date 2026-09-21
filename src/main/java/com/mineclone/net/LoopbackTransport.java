package com.mineclone.net;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Комната внутри одного процесса.
 *
 * <p>Нужна не для игры, а для проверки: вся логика сессии — рукопожатие,
 * дельты чанков, власть хозяина, слияние состояний игроков — проверяется
 * двумя сессиями в одном тесте, без сокета, ключа приложения и интернета.
 * Именно поэтому транспорт вообще вынесен в интерфейс.
 *
 * <p>Доставка мгновенная и в очередь: байты кладутся получателю сразу, но
 * разбираются только в его {@link #poll()}. Тест управляет временем сам,
 * вызывая {@code poll} по очереди, и потому повторяется бит в бит.
 */
public final class LoopbackTransport implements NetTransport {

    /** Комнаты процесса. Одна на имя, как у настоящего сервера. */
    private static final Map<String, Room> ROOMS = new ConcurrentHashMap<>();

    private static final class Room {
        final String name;
        final AtomicInteger nextActor = new AtomicInteger(1);
        final Map<Integer, LoopbackTransport> members = new ConcurrentHashMap<>();

        Room(String name) {
            this.name = name;
        }
    }

    /** Забыть все комнаты: тест не должен зависеть от предыдущего теста. */
    public static void reset() {
        ROOMS.clear();
    }

    private record Incoming(int from, byte[] data) {
    }

    /** Отложенное событие: вход, выход и смена состояния тоже ждут poll. */
    private sealed interface Event {
    }

    private record StateEvent(State state, String detail) implements Event {
    }

    private record JoinedEvent(int actor, boolean created) implements Event {
    }

    private record ActorEvent(int actor, String name, boolean joined) implements Event {
    }

    private record PayloadEvent(int from, byte[] data) implements Event {
    }

    private final Listener listener;
    private final ConcurrentLinkedQueue<Event> inbox = new ConcurrentLinkedQueue<>();
    private Room room;
    private int actor;
    private String nickname = "";
    private State state = State.IDLE;

    public LoopbackTransport(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void connect(String roomName, boolean create, String nick) {
        this.nickname = nick;
        state = State.CONNECTING;
        inbox.add(new StateEvent(State.CONNECTING, "loopback"));
        Room existing = ROOMS.get(roomName);
        if (existing == null && !create) {
            state = State.FAILED;
            inbox.add(new StateEvent(State.FAILED, "комнаты нет"));
            return;
        }
        boolean created = existing == null;
        Room r = ROOMS.computeIfAbsent(roomName, Room::new);
        this.room = r;
        this.actor = r.nextActor.getAndIncrement();
        // Сначала рассказать своим о новичке, и только потом добавить его в
        // список: иначе он получил бы собственное появление как чужое.
        for (LoopbackTransport peer : r.members.values())
            peer.inbox.add(new ActorEvent(actor, nick, true));
        r.members.put(actor, this);
        state = State.JOINED;
        inbox.add(new JoinedEvent(actor, created));
        // Уже сидящие в комнате — для новичка они тоже «вошли».
        for (Map.Entry<Integer, LoopbackTransport> e : r.members.entrySet())
            if (e.getKey() != actor)
                inbox.add(new ActorEvent(e.getKey(), e.getValue().nickname, true));
    }

    @Override
    public void send(byte[] payload, boolean reliable, int target) {
        if (room == null || state != State.JOINED)
            return;
        byte[] copy = payload.clone();
        if (target == NetChannel.ALL) {
            for (Map.Entry<Integer, LoopbackTransport> e : room.members.entrySet())
                if (e.getKey() != actor)
                    e.getValue().inbox.add(new PayloadEvent(actor, copy));
        } else {
            LoopbackTransport peer = room.members.get(target);
            if (peer != null)
                peer.inbox.add(new PayloadEvent(actor, copy));
        }
    }

    @Override
    public void poll() {
        Event e;
        while ((e = inbox.poll()) != null) {
            if (e instanceof StateEvent s)
                listener.onState(s.state(), s.detail());
            else if (e instanceof JoinedEvent j)
                listener.onJoined(j.actor(), j.created());
            else if (e instanceof ActorEvent a) {
                if (a.joined())
                    listener.onActorJoin(a.actor(), a.name());
                else
                    listener.onActorLeave(a.actor());
            } else if (e instanceof PayloadEvent p)
                listener.onPayload(p.from(), p.data());
        }
    }

    @Override
    public void requestRoomList() {
        List<RoomInfo> list = new ArrayList<>();
        for (Room r : ROOMS.values())
            list.add(new RoomInfo(r.name, r.members.size(), 8));
        listener.onRoomList(list);
    }

    @Override
    public void disconnect() {
        if (room != null) {
            room.members.remove(actor);
            for (LoopbackTransport peer : room.members.values())
                peer.inbox.add(new ActorEvent(actor, nickname, false));
            if (room.members.isEmpty())
                ROOMS.remove(room.name, room);
        }
        room = null;
        actor = 0;
        state = State.CLOSED;
        inbox.clear();
    }

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
        if (room == null)
            return 0;
        int lowest = Integer.MAX_VALUE;
        for (Integer a : room.members.keySet())
            lowest = Math.min(lowest, a);
        return lowest == Integer.MAX_VALUE ? 0 : lowest;
    }

    @Override
    public List<Integer> actors() {
        List<Integer> out = new ArrayList<>();
        if (room != null)
            for (Integer a : room.members.keySet())
                if (a != actor)
                    out.add(a);
        return out;
    }

    @Override
    public String actorName(int a) {
        if (room == null)
            return "";
        LoopbackTransport peer = room.members.get(a);
        return peer == null ? "" : peer.nickname;
    }

    @Override
    public String describe() {
        return "loopback " + (room == null ? "-" : room.name) + " #" + actor;
    }
}

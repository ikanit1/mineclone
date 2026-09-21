package com.mineclone.net;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Комната в своей сети: хозяин слушает порт, остальные к нему подключаются.
 *
 * <p>Нужна потому, что Photon требует ключ приложения и выход в интернет.
 * Играть по локальной сети, не заводя учётную запись, — нормальное желание, а
 * заодно это единственный способ проверить сессию на настоящих сокетах и в
 * двух процессах, а не в одном.
 *
 * <p>Модель комнаты та же, что у Photon, и это не случайность: у хозяина номер
 * 1, остальным номера выдаёт он же по порядку, наименьший — хозяин мира.
 * Хозяин заодно пересыльщик: участники между собой не соединяются, всё идёт
 * через него. Так связей {@code n}, а не {@code n²}, и правки блоков приходят
 * ко всем в одном порядке — том, в котором их увидел хозяин.
 *
 * <p>Кадр на проводе — длина и тело; TCP склеивает и рвёт как хочет, поэтому
 * длину надо писать явно. Всё надёжно по построению: флаг {@code reliable}
 * здесь ни на что не влияет и сохранён только ради общего интерфейса.
 *
 * <p><b>Молчание считается обрывом.</b> Без этого мёртвое соединение висело
 * вечно: TCP не замечает выдернутого кабеля и уснувшего ноутбука, пока в него
 * не попробуют записать, — а хозяин участнику пишет далеко не каждый кадр.
 * Поэтому раз в {@link #HEARTBEAT_SECONDS} по пустому соединению уходит
 * {@link #F_PING}, а молчащее дольше {@link #SILENCE_SECONDS} закрывается.
 */
public final class LanTransport implements NetTransport {

    /** Порт по умолчанию. */
    public static final int DEFAULT_PORT = 25566;
    /** Сколько участников впускает хозяин, считая себя. */
    public static final int MAX_PLAYERS = 8;
    /** Больше этого в одном сообщении не бывает — защита от мусора в потоке. */
    private static final int MAX_FRAME = 1 << 20;
    /** Через сколько тишины напомнить о себе пустым кадром. */
    public static final float HEARTBEAT_SECONDS = 2f;
    /** Через сколько тишины считать соединение мёртвым. */
    public static final float SILENCE_SECONDS = 10f;

    private static final byte F_HELLO = 1;
    private static final byte F_WELCOME = 2;
    private static final byte F_DATA = 3;
    private static final byte F_JOIN = 4;
    private static final byte F_LEAVE = 5;
    private static final byte F_REJECT = 6;
    /** Пустой кадр: «я ещё здесь». Ответа не требует — ответ придёт своим. */
    private static final byte F_PING = 7;

    private final Listener listener;
    private final ConcurrentLinkedQueue<Runnable> inbox = new ConcurrentLinkedQueue<>();
    /** Соединения хозяина по номеру участника. */
    private final Map<Integer, Link> links = new ConcurrentHashMap<>();
    private final Map<Integer, String> names = new ConcurrentHashMap<>();
    private final AtomicInteger nextActor = new AtomicInteger(2);

    private final boolean hosting;
    private final String host;
    private final int port;

    private volatile State state = State.IDLE;
    private volatile int actor;
    private volatile boolean stopping;
    private ServerSocket server;
    /** Единственное соединение участника — с хозяином. */
    private Link upstream;
    private String nickname = "";
    private String roomName = "";

    private LanTransport(boolean hosting, String host, int port, Listener listener) {
        this.hosting = hosting;
        this.host = host;
        this.port = port;
        this.listener = listener;
    }

    /** Хозяин: слушать порт. */
    public static LanTransport host(int port, Listener listener) {
        return new LanTransport(true, "", port <= 0 ? DEFAULT_PORT : port, listener);
    }

    /** Участник: подключиться к хозяину. Адрес вида {@code host} или {@code host:port}. */
    public static LanTransport join(String address, Listener listener) {
        String h = address == null ? "" : address.trim();
        int p = DEFAULT_PORT;
        int colon = h.lastIndexOf(':');
        if (colon > 0 && colon == h.indexOf(':')) {
            try {
                p = Integer.parseInt(h.substring(colon + 1).trim());
            } catch (NumberFormatException ignored) {
                p = DEFAULT_PORT;
            }
            h = h.substring(0, colon).trim();
        }
        if (h.isEmpty())
            h = "127.0.0.1";
        return new LanTransport(false, h, p, listener);
    }

    // ------------------------------------------------------------ подключение

    @Override
    public void connect(String room, boolean create, String nick) {
        this.nickname = nick == null ? "" : nick;
        this.roomName = room == null ? "" : room;
        setState(State.CONNECTING, hosting ? "открываем порт " + port : "соединяемся с " + host);
        if (hosting)
            startHost();
        else
            startClient();
    }

    private void startHost() {
        actor = 1;
        names.put(1, nickname);
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(port));
        } catch (IOException e) {
            fail("порт " + port + " занят (" + e.getMessage() + ")");
            return;
        }
        setState(State.JOINED, "порт " + port);
        inbox.add(() -> listener.onJoined(1, true));
        Thread accept = new Thread(this::acceptLoop, "mineclone-lan-accept");
        accept.setDaemon(true);
        accept.start();
    }

    private void acceptLoop() {
        while (!stopping) {
            Socket socket;
            try {
                socket = server.accept();
            } catch (IOException e) {
                if (!stopping)
                    fail("приём соединений прекратился: " + e.getMessage());
                return;
            }
            try {
                socket.setTcpNoDelay(true);
                Link link = new Link(socket);
                Thread t = new Thread(() -> hostHandshake(link), "mineclone-lan-peer");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                closeQuietly(socket);
            }
        }
    }

    /** Хозяин: принять представление, выдать номер и рассказать о новичке всем. */
    private void hostHandshake(Link link) {
        try {
            if (link.in.readInt() != magic()) {
                link.close();
                return;
            }
            byte kind = link.in.readByte();
            if (kind != F_HELLO) {
                link.close();
                return;
            }
            int version = link.in.readInt();
            String nick = link.in.readUTF();
            link.in.readUTF();  // имя комнаты: у хозяина оно своё, читаем и забываем
            if (version != NetProto.VERSION) {
                link.sendReject("другая версия игры: у вас " + version
                        + ", у хозяина " + NetProto.VERSION);
                link.close();
                return;
            }
            if (links.size() + 1 >= MAX_PLAYERS) {
                link.sendReject("мир заполнен");
                link.close();
                return;
            }
            int id = nextActor.getAndIncrement();
            link.actor = id;
            names.put(id, nick);
            // Новичку — его номер и те, кто уже внутри.
            List<Integer> existing = new ArrayList<>(links.keySet());
            link.sendWelcome(id, existing);
            // Остальным — что он появился. Прежде чем добавить его самого:
            // иначе он получил бы собственное появление.
            for (Link other : links.values())
                other.sendJoin(id, nick);
            links.put(id, link);
            inbox.add(() -> listener.onActorJoin(id, nick));
            link.readLoop();
        } catch (IOException e) {
            // Оборвалось на рукопожатии — участника ещё нет, сообщать некому.
        } finally {
            dropLink(link);
        }
    }

    private void startClient() {
        Thread t = new Thread(() -> {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(host, port), 8000);
                socket.setTcpNoDelay(true);
                Link link = new Link(socket);
                upstream = link;
                link.out.writeInt(magic());
                link.out.writeByte(F_HELLO);
                link.out.writeInt(NetProto.VERSION);
                link.out.writeUTF(nickname);
                link.out.writeUTF(roomName);
                link.out.flush();
                link.readLoop();
            } catch (IOException e) {
                closeQuietly(socket);
                if (!stopping && state != State.FAILED)
                    fail("не удалось соединиться с " + host + ":" + port
                            + " (" + e.getMessage() + ")");
                return;
            } finally {
                if (!stopping && state == State.JOINED)
                    fail("соединение с хозяином потеряно");
            }
        }, "mineclone-lan-client");
        t.setDaemon(true);
        t.start();
    }

    private static int magic() {
        return 0x4D43_4C41;  // "MCLA"
    }

    // -------------------------------------------------------------- отправка

    @Override
    public void send(byte[] payload, boolean reliable, int target) {
        if (state != State.JOINED)
            return;
        if (hosting) {
            if (target == NetChannel.ALL) {
                for (Link l : links.values())
                    l.sendData(actor, payload);
            } else {
                Link l = links.get(target);
                if (l != null)
                    l.sendData(actor, payload);
            }
        } else {
            Link l = upstream;
            if (l != null)
                l.sendRelay(target, payload);
        }
    }

    /** Хозяин пересылает то, что пришло от участника. */
    private void relay(int from, int target, byte[] payload) {
        if (target == NetChannel.ALL) {
            for (Link l : links.values())
                if (l.actor != from)
                    l.sendData(from, payload);
            inbox.add(() -> listener.onPayload(from, payload));
        } else if (target == actor) {
            inbox.add(() -> listener.onPayload(from, payload));
        } else {
            Link l = links.get(target);
            if (l != null)
                l.sendData(from, payload);
        }
    }

    @Override
    public void poll() {
        Runnable r;
        while ((r = inbox.poll()) != null)
            r.run();
        tendConnections();
    }

    /**
     * Присмотреть за соединениями: напомнить о себе и убрать мёртвые.
     *
     * <p>Идёт здесь, а не в своём потоке, потому что {@link #poll()} и так
     * зовут каждый кадр, а закрытие соединения обязано попасть в ту же очередь
     * событий, что и всё остальное: иначе участник успел бы исчезнуть
     * посреди разбора собственного пакета.
     */
    private void tendConnections() {
        if (state != State.JOINED)
            return;
        long now = System.nanoTime();
        if (hosting) {
            for (Link l : links.values()) {
                if (l.silent(now))
                    l.close();
                else
                    l.heartbeat(now);
            }
        } else {
            Link up = upstream;
            if (up == null)
                return;
            if (up.silent(now))
                up.close();
            else
                up.heartbeat(now);
        }
    }

    @Override
    public void disconnect() {
        stopping = true;
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
                // Закрываем на выходе — жаловаться уже некому.
            }
            server = null;
        }
        for (Link l : links.values())
            l.close();
        links.clear();
        Link up = upstream;
        upstream = null;
        if (up != null)
            up.close();
        names.clear();
        actor = 0;
        state = State.CLOSED;
        inbox.clear();
    }

    private void dropLink(Link link) {
        if (link.actor <= 0)
            return;
        if (links.remove(link.actor) == null)
            return;
        names.remove(link.actor);
        int gone = link.actor;
        for (Link other : links.values())
            other.sendLeave(gone);
        inbox.add(() -> listener.onActorLeave(gone));
    }

    private void setState(State s, String detail) {
        state = s;
        inbox.add(() -> listener.onState(s, detail));
    }

    private void fail(String why) {
        if (state == State.FAILED || state == State.CLOSED)
            return;
        state = State.FAILED;
        inbox.add(() -> listener.onState(State.FAILED, why));
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // Уже закрывается — исход один и тот же.
        }
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
        // Хозяин мира — тот, кто слушает порт. Мигрировать некуда: мир живёт
        // в его памяти.
        return actor == 0 ? 0 : 1;
    }

    @Override
    public List<Integer> actors() {
        List<Integer> out = new ArrayList<>();
        if (hosting) {
            out.addAll(links.keySet());
        } else {
            for (Integer a : names.keySet())
                if (a != actor)
                    out.add(a);
        }
        return out;
    }

    @Override
    public String actorName(int a) {
        return names.getOrDefault(a, "");
    }

    @Override
    public String describe() {
        return (hosting ? "LAN хозяин :" + port : "LAN " + host + ":" + port) + " #" + actor;
    }

    /** Одно TCP-соединение: чтение в своём потоке, запись под замком. */
    private final class Link {
        private final Socket socket;
        private final DataInputStream in;
        private final DataOutputStream out;
        private final Object writeLock = new Object();
        volatile int actor;
        /** Когда с этого соединения последний раз что-то пришло. */
        volatile long lastHeardNanos = System.nanoTime();
        /** Когда в него последний раз что-то ушло. */
        volatile long lastSentNanos = System.nanoTime();

        Link(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new DataInputStream(new java.io.BufferedInputStream(socket.getInputStream()));
            this.out = new DataOutputStream(new java.io.BufferedOutputStream(socket.getOutputStream()));
        }

        void readLoop() throws IOException {
            while (!stopping) {
                byte kind;
                try {
                    kind = in.readByte();
                } catch (EOFException | SocketException e) {
                    return;
                }
                lastHeardNanos = System.nanoTime();
                switch (kind) {
                    case F_PING -> {
                        // Само появление кадра и есть весь его смысл.
                    }
                    case F_WELCOME -> readWelcome();
                    case F_JOIN -> {
                        int a = in.readInt();
                        String nick = in.readUTF();
                        names.put(a, nick);
                        inbox.add(() -> listener.onActorJoin(a, nick));
                    }
                    case F_LEAVE -> {
                        int a = in.readInt();
                        names.remove(a);
                        inbox.add(() -> listener.onActorLeave(a));
                    }
                    case F_DATA -> readData();
                    case F_REJECT -> {
                        String why = in.readUTF();
                        stopping = true;
                        fail(why);
                        return;
                    }
                    default -> {
                        // Мусор в потоке: дальше идти вслепую нельзя.
                        return;
                    }
                }
            }
        }

        private void readWelcome() throws IOException {
            int mine = in.readInt();
            String hostNick = in.readUTF();
            int count = in.readInt();
            actor = mine;
            LanTransport.this.actor = mine;
            names.put(mine, nickname);
            names.put(1, hostNick);
            List<Integer> existing = new ArrayList<>();
            for (int i = 0; i < Math.max(0, Math.min(MAX_PLAYERS, count)); i++) {
                int a = in.readInt();
                String nick = in.readUTF();
                names.put(a, nick);
                existing.add(a);
            }
            state = State.JOINED;
            inbox.add(() -> {
                listener.onState(State.JOINED, "мир хозяина");
                listener.onJoined(mine, false);
                listener.onActorJoin(1, names.getOrDefault(1, ""));
                for (Integer a : existing)
                    listener.onActorJoin(a, names.getOrDefault(a, ""));
            });
        }

        private void readData() throws IOException {
            int from = in.readInt();
            int target = in.readInt();
            int len = in.readInt();
            if (len < 0 || len > MAX_FRAME)
                throw new IOException("кадр длиной " + len);
            byte[] payload = in.readNBytes(len);
            if (payload.length != len)
                throw new EOFException("кадр оборвался");
            if (hosting)
                relay(actor, target, payload);
            else
                inbox.add(() -> listener.onPayload(from, payload));
        }

        void sendWelcome(int id, List<Integer> existing) {
            synchronized (writeLock) {
                try {
                    out.writeByte(F_WELCOME);
                    out.writeInt(id);
                    out.writeUTF(names.getOrDefault(1, ""));
                    out.writeInt(existing.size());
                    for (Integer a : existing) {
                        out.writeInt(a);
                        out.writeUTF(names.getOrDefault(a, ""));
                    }
                    out.flush();
                } catch (IOException e) {
                    close();
                }
            }
        }

        void sendJoin(int a, String nick) {
            synchronized (writeLock) {
                try {
                    out.writeByte(F_JOIN);
                    out.writeInt(a);
                    out.writeUTF(nick);
                    out.flush();
                } catch (IOException e) {
                    close();
                }
            }
        }

        void sendLeave(int a) {
            synchronized (writeLock) {
                try {
                    out.writeByte(F_LEAVE);
                    out.writeInt(a);
                    out.flush();
                } catch (IOException e) {
                    close();
                }
            }
        }

        /** Хозяин участнику: вот байты от такого-то. */
        void sendData(int from, byte[] payload) {
            synchronized (writeLock) {
                try {
                    out.writeByte(F_DATA);
                    out.writeInt(from);
                    out.writeInt(0);
                    out.writeInt(payload.length);
                    out.write(payload);
                    out.flush();
                    lastSentNanos = System.nanoTime();
                } catch (IOException e) {
                    close();
                }
            }
        }

        /** Участник хозяину: разошли это вот кому. */
        void sendRelay(int target, byte[] payload) {
            synchronized (writeLock) {
                try {
                    out.writeByte(F_DATA);
                    out.writeInt(actor);
                    out.writeInt(target);
                    out.writeInt(payload.length);
                    out.write(payload);
                    out.flush();
                    lastSentNanos = System.nanoTime();
                } catch (IOException e) {
                    close();
                }
            }
        }

        /** Напомнить о себе, если давно молчали. */
        void heartbeat(long now) {
            if (now - lastSentNanos < (long) (HEARTBEAT_SECONDS * 1e9))
                return;
            synchronized (writeLock) {
                try {
                    out.writeByte(F_PING);
                    out.flush();
                    lastSentNanos = now;
                } catch (IOException e) {
                    close();
                }
            }
        }

        /** Замолчало дольше терпимого: с той стороны уже никого. */
        boolean silent(long now) {
            return now - lastHeardNanos > (long) (SILENCE_SECONDS * 1e9);
        }

        void sendReject(String why) {
            synchronized (writeLock) {
                try {
                    out.writeByte(F_REJECT);
                    out.writeUTF(why);
                    out.flush();
                } catch (IOException ignored) {
                    // Отказ не дошёл — участник увидит обрыв, смысл тот же.
                }
            }
        }

        void close() {
            closeQuietly(socket);
        }
    }
}

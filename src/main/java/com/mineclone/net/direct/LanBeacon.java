package com.mineclone.net.direct;

import com.mineclone.net.NetProto;
import com.mineclone.net.PacketBuf;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Маяк: «здесь открыт мир» — раз в секунду на всю локальную сеть.
 *
 * <p>Раньше войти в мир друга в своей же квартире стоило диктовки адреса
 * («сто девяносто два, точка, сто шестьдесят восемь…»), которого хозяин к тому
 * же обычно не знал. Между тем локальная сеть для того и локальная: хозяин
 * кричит в неё широковещательным UDP, а у гостя мир просто появляется в
 * списке.
 *
 * <p>Объявление маленькое и самодостаточное: версия протокола, порт, имя мира,
 * имя хозяина и сколько внутри. Состояния у него нет, поэтому потерянный пакет
 * ничего не ломает — через секунду придёт следующий.
 *
 * <p>Порт объявлений {@link #PORT} отдельный от игрового: игровой слушает TCP
 * и занят, а на этот может встать сразу несколько гостей на одной машине —
 * отсюда {@code SO_REUSEADDR} у приёмника.
 */
public final class LanBeacon {

    /** Куда кричим и где слушаем. На единицу меньше игрового порта. */
    public static final int PORT = 25565;
    /** Как часто уходит объявление. */
    public static final float INTERVAL = 1f;
    /** Через сколько молчания мир пропадает из списка. */
    public static final float STALE = 4f;

    private static final int MAGIC = 0x4D43_4C42;  // "MCLB"

    /** Объявление о мире: ровно то, что показывает список. */
    public record Announcement(String address, int port, String world, String host,
            int players, int maxPlayers) {

        /** Адрес, которым к нему подключаются. */
        public String dialable() {
            return address + ":" + port;
        }
    }

    private LanBeacon() {
    }

    // -------------------------------------------------------------- вещание

    public static byte[] encode(int port, String world, String host, int players, int maxPlayers) {
        PacketBuf buf = new PacketBuf(128);
        buf.i32(MAGIC);
        buf.i32(NetProto.VERSION);
        buf.i32(port);
        buf.str(world);
        buf.str(host);
        buf.u8(Math.max(0, Math.min(255, players)));
        buf.u8(Math.max(0, Math.min(255, maxPlayers)));
        return buf.toBytes();
    }

    /**
     * Разобрать объявление.
     *
     * <p>Чужая версия протокола отбрасывается здесь же: показать такой мир в
     * списке значит дать в него ткнуть и получить отказ при входе.
     *
     * @param from откуда пришло — адрес в объявление не пишется, он и так
     *             известен приёмнику, а записанный мог бы разойтись с
     *             настоящим
     */
    public static Announcement decode(byte[] data, int length, String from) {
        PacketBuf in = PacketBuf.reading(data, 0, length);
        if (in.readI32() != MAGIC)
            return null;
        if (in.readI32() != NetProto.VERSION)
            return null;
        int port = in.readI32();
        String world = in.readStr();
        String host = in.readStr();
        int players = in.readU8();
        int max = in.readU8();
        if (in.truncated() || port <= 0 || port > 65535)
            return null;
        return new Announcement(from, port, world, host, players, max);
    }

    /** Кричит в сеть, пока его не закрыли. Живёт у хозяина мира. */
    public static final class Sender implements AutoCloseable {
        private final int port;
        private volatile String world = "";
        private volatile String host = "";
        private volatile int players;
        private volatile int maxPlayers;
        private volatile boolean stopped;
        private DatagramSocket socket;
        private float timer;

        public Sender(int port) {
            this.port = port;
            try {
                socket = new DatagramSocket();
                socket.setBroadcast(true);
            } catch (Exception e) {
                // Не открылся — маяка просто не будет; игра от этого не
                // перестаёт работать, адрес можно продиктовать.
                socket = null;
            }
        }

        /** Что объявлять. Зовётся каждый кадр: состав игроков меняется. */
        public void describe(String world, String host, int players, int maxPlayers) {
            this.world = world == null ? "" : world;
            this.host = host == null ? "" : host;
            this.players = players;
            this.maxPlayers = maxPlayers;
        }

        /** Раз в {@link #INTERVAL} отправить объявление. */
        public void update(float dt) {
            if (socket == null || stopped)
                return;
            timer -= dt;
            if (timer > 0f)
                return;
            timer = INTERVAL;
            byte[] payload = encode(port, world, host, players, maxPlayers);
            for (InetAddress target : broadcastAddresses()) {
                try {
                    socket.send(new DatagramPacket(payload, payload.length, target, PORT));
                } catch (Exception ignored) {
                    // Один интерфейс отказал — остальные ещё могут донести.
                }
            }
        }

        @Override
        public void close() {
            stopped = true;
            DatagramSocket s = socket;
            socket = null;
            if (s != null)
                s.close();
        }
    }

    /** Слушает объявления и держит список живых миров. Живёт у гостя. */
    public static final class Listener implements AutoCloseable {
        private final Map<String, Entry> seen = new LinkedHashMap<>();
        private final Object lock = new Object();
        private volatile boolean stopped;
        private DatagramSocket socket;
        private String error = "";

        private record Entry(Announcement announcement, long seenAtNanos) {
        }

        public Listener() {
            Thread t = new Thread(this::listen, "mineclone-lan-beacon");
            t.setDaemon(true);
            t.start();
        }

        private void listen() {
            try {
                DatagramSocket s = new DatagramSocket(null);
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(PORT));
                socket = s;
            } catch (Exception e) {
                error = "не удалось слушать сеть: " + e.getMessage();
                return;
            }
            byte[] buf = new byte[512];
            while (!stopped) {
                DatagramPacket in = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(in);
                } catch (Exception e) {
                    if (!stopped)
                        error = "приём объявлений прекратился";
                    return;
                }
                Announcement a = decode(in.getData(), in.getLength(),
                        in.getAddress().getHostAddress());
                if (a == null)
                    continue;
                synchronized (lock) {
                    seen.put(a.dialable(), new Entry(a, System.nanoTime()));
                }
            }
        }

        /** Миры, объявлявшиеся не дольше {@link #STALE} назад. */
        public List<Announcement> worlds() {
            long now = System.nanoTime();
            List<Announcement> out = new ArrayList<>();
            synchronized (lock) {
                seen.values().removeIf(e -> (now - e.seenAtNanos()) > (long) (STALE * 1e9));
                for (Entry e : seen.values())
                    out.add(e.announcement());
            }
            return out;
        }

        /** Почему список пуст, если дело не в отсутствии миров. */
        public String error() {
            return error;
        }

        @Override
        public void close() {
            stopped = true;
            DatagramSocket s = socket;
            socket = null;
            if (s != null)
                s.close();
        }
    }

    /**
     * Куда слать объявление.
     *
     * <p>Не {@code 255.255.255.255}: его режут и Windows, и часть точек
     * доступа. Широковещательный адрес каждой своей подсети доходит.
     */
    public static List<InetAddress> broadcastAddresses() {
        List<InetAddress> out = new ArrayList<>();
        try {
            for (java.net.NetworkInterface nif
                    : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback())
                    continue;
                for (java.net.InterfaceAddress ia : nif.getInterfaceAddresses()) {
                    InetAddress b = ia.getBroadcast();
                    if (b != null && !out.contains(b))
                        out.add(b);
                }
            }
        } catch (Exception ignored) {
            // Сеть не опрашивается — кричать некуда.
        }
        return out;
    }
}

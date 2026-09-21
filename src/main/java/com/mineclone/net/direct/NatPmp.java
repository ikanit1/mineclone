package com.mineclone.net.direct;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * NAT-PMP: попросить роутер открыть порт, не заходя в его настройки.
 *
 * <p>Протокол крошечный (RFC 6886): запрос — двенадцать байт по UDP на
 * пятый-триста-пятьдесят-первый порт шлюза, ответ — шестнадцать. Ровно поэтому
 * он здесь и есть: {@link UpnpGateway} умеет то же самое, но стоит рассылки,
 * загрузки XML и SOAP-запроса, а половина роутеров держит только один из двух.
 * Пробуются оба.
 *
 * <p>Адрес шлюза JDK не отдаёт: {@code NetworkInterface} знает свои адреса и
 * маски, но не маршрут по умолчанию. Поэтому шлюз угадывается — это первый
 * адрес подсети ({@code 192.168.1.1} для {@code 192.168.1.x}). Домашние
 * роутеры почти всегда стоят именно там, а когда это не так, запрос просто
 * остаётся без ответа и мы честно об этом сообщаем.
 */
public final class NatPmp {

    private NatPmp() {
    }

    /** Порт NAT-PMP на шлюзе. */
    public static final int PORT = 5351;
    /** Сколько ждём ответа: шлюз либо рядом, либо его нет вовсе. */
    public static final int TIMEOUT_MS = 700;
    /** На сколько секунд просим отображение. Роутер вправе дать меньше. */
    public static final int LIFETIME_SECONDS = 3600;

    private static final byte OP_EXTERNAL = 0;
    private static final byte OP_MAP_UDP = 1;
    private static final byte OP_MAP_TCP = 2;

    /** Ответ на «открой порт»: что получилось и на сколько. */
    public record Mapping(int externalPort, int lifetimeSeconds, String error) {

        public boolean ok() {
            return error.isEmpty();
        }
    }

    /**
     * Запрос на отображение порта.
     *
     * <p>Внутренний и внешний порт просим одинаковые: игроку проще диктовать
     * один номер, а роутер всё равно волен дать другой и сказать об этом в
     * ответе.
     */
    public static byte[] mapRequest(int port, int lifetimeSeconds) {
        byte[] req = new byte[12];
        req[0] = 0;              // версия протокола
        req[1] = OP_MAP_TCP;
        req[2] = 0;              // зарезервировано
        req[3] = 0;
        req[4] = (byte) (port >> 8);
        req[5] = (byte) port;
        req[6] = (byte) (port >> 8);
        req[7] = (byte) port;
        req[8] = (byte) (lifetimeSeconds >> 24);
        req[9] = (byte) (lifetimeSeconds >> 16);
        req[10] = (byte) (lifetimeSeconds >> 8);
        req[11] = (byte) lifetimeSeconds;
        return req;
    }

    /**
     * Разобрать ответ на отображение.
     *
     * <p>Ответ на операцию {@code n} приходит с кодом {@code n + 128}: так
     * устроен протокол, и это единственный способ отличить ответ от чужого
     * пакета, прилетевшего на тот же сокет.
     */
    public static Mapping parseMapping(byte[] data, int length) {
        if (length < 16)
            return new Mapping(0, 0, "короткий ответ NAT-PMP");
        if (data[0] != 0)
            return new Mapping(0, 0, "чужая версия NAT-PMP: " + data[0]);
        if ((data[1] & 0xFF) != (OP_MAP_TCP + 128))
            return new Mapping(0, 0, "ответ не про отображение порта");
        int result = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        if (result != 0)
            return new Mapping(0, 0, resultText(result));
        int external = ((data[10] & 0xFF) << 8) | (data[11] & 0xFF);
        int lifetime = ((data[12] & 0xFF) << 24) | ((data[13] & 0xFF) << 16)
                | ((data[14] & 0xFF) << 8) | (data[15] & 0xFF);
        return new Mapping(external, lifetime, "");
    }

    /** Коды отказа из RFC по-русски: игроку важно, чинится это или нет. */
    public static String resultText(int code) {
        return switch (code) {
            case 1 -> "роутер не понимает эту версию NAT-PMP";
            case 2 -> "роутер отказал в открытии порта";
            case 3 -> "у роутера нет внешнего адреса";
            case 4 -> "у роутера кончились свободные порты";
            case 5 -> "роутер не умеет открывать порты";
            default -> "роутер отказал, код " + code;
        };
    }

    /**
     * Открыть порт.
     *
     * <p>Блокирующий вызов на время {@link #TIMEOUT_MS}: звать его надо из
     * фонового потока.
     */
    public static Mapping open(int port) {
        return exchange(mapRequest(port, LIFETIME_SECONDS), NatPmp::parseMapping);
    }

    /** Убрать отображение: то же, но с нулевым сроком жизни. */
    public static void close(int port) {
        exchange(mapRequest(port, 0), NatPmp::parseMapping);
    }

    /** Внешний адрес глазами шлюза; пустая строка — не ответил. */
    public static String externalAddress() {
        for (InetAddress gateway : gateways()) {
            byte[] reply = ask(gateway, new byte[] { 0, OP_EXTERNAL });
            if (reply == null || reply.length < 12)
                continue;
            if ((reply[1] & 0xFF) != (OP_EXTERNAL + 128))
                continue;
            int result = ((reply[2] & 0xFF) << 8) | (reply[3] & 0xFF);
            if (result != 0)
                continue;
            return (reply[8] & 0xFF) + "." + (reply[9] & 0xFF) + "."
                    + (reply[10] & 0xFF) + "." + (reply[11] & 0xFF);
        }
        return "";
    }

    private interface Parser {
        Mapping parse(byte[] data, int length);
    }

    private static Mapping exchange(byte[] request, Parser parser) {
        String lastError = "шлюз не ответил";
        for (InetAddress gateway : gateways()) {
            byte[] reply = ask(gateway, request);
            if (reply == null)
                continue;
            Mapping m = parser.parse(reply, reply.length);
            if (m.ok())
                return m;
            lastError = m.error();
        }
        return new Mapping(0, 0, lastError);
    }

    private static byte[] ask(InetAddress gateway, byte[] request) {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(TIMEOUT_MS);
            socket.send(new DatagramPacket(request, request.length, gateway, PORT));
            byte[] buf = new byte[32];
            DatagramPacket in = new DatagramPacket(buf, buf.length);
            socket.receive(in);
            byte[] out = new byte[in.getLength()];
            System.arraycopy(buf, 0, out, 0, in.getLength());
            return out;
        } catch (Exception e) {
            // Нет шлюза, нет NAT-PMP, не успел — для нас это один исход.
            return null;
        }
    }

    /**
     * Где может стоять шлюз.
     *
     * <p>Первый адрес каждой своей подсети. Интерфейсов у машины бывает
     * несколько (Wi-Fi, кабель, виртуальные сети докера), поэтому кандидатов
     * тоже несколько, и опрашиваются они по очереди.
     */
    public static List<InetAddress> gateways() {
        List<InetAddress> out = new ArrayList<>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback())
                    continue;
                for (java.net.InterfaceAddress ia : nif.getInterfaceAddresses()) {
                    if (!(ia.getAddress() instanceof Inet4Address addr))
                        continue;
                    byte[] bytes = addr.getAddress().clone();
                    bytes[3] = 1;
                    InetAddress guess = InetAddress.getByAddress(bytes);
                    if (!guess.equals(addr) && !out.contains(guess))
                        out.add(guess);
                }
            }
        } catch (Exception ignored) {
            // Сеть не опрашивается — значит и открывать порт негде.
        }
        return out;
    }
}

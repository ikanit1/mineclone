package com.mineclone.net.direct;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.Collections;

/**
 * Свой адрес снаружи.
 *
 * <p>Хозяину прямого соединения надо продиктовать другу адрес, а тот адрес,
 * который видит сама машина ({@code 192.168.1.5}), снаружи не значит ничего.
 * Спросить можно трёх: роутера по UPnP, роутера по NAT-PMP и STUN-сервера.
 * Первые два знают точно и отвечают мгновенно, третий работает всегда.
 *
 * <p>STUN (RFC 5389) здесь ровно в том объёме, в каком он нужен: запрос
 * {@code Binding} — двадцать байт, в ответе ищется единственный атрибут
 * {@code XOR-MAPPED-ADDRESS}. Ни аутентификации, ни ICE, ни повторов: это
 * не установка связи, а один вопрос «как меня видно».
 *
 * <p>Наружу уходит один UDP-пакет со случайным номером обращения и ничем
 * больше — ни имени игрока, ни мира, ни ключа Photon.
 */
public final class PublicAddress {

    private PublicAddress() {
    }

    /** Публичные STUN-серверы: спрашиваем по очереди, пока кто-то не ответит. */
    public static final String[] STUN_SERVERS = {
            "stun.l.google.com:19302",
            "stun1.l.google.com:19302",
            "stun.cloudflare.com:3478",
    };

    public static final int TIMEOUT_MS = 1200;

    private static final int BINDING_REQUEST = 0x0001;
    private static final int BINDING_RESPONSE = 0x0101;
    private static final int MAGIC_COOKIE = 0x2112A442;
    private static final int ATTR_MAPPED = 0x0001;
    private static final int ATTR_XOR_MAPPED = 0x0020;

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Узнать внешний адрес.
     *
     * <p>Блокирующий вызов до {@link #TIMEOUT_MS} на сервер: звать из фонового
     * потока. Пустая строка — не ответил никто.
     */
    public static String discover() {
        String fromRouter = NatPmp.externalAddress();
        if (!fromRouter.isEmpty())
            return fromRouter;
        for (String server : STUN_SERVERS) {
            String addr = viaStun(server);
            if (!addr.isEmpty())
                return addr;
        }
        return "";
    }

    /** Адрес этой машины в своей сети — им диктуются адреса внутри дома. */
    public static String localAddress() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback() || nif.isVirtual())
                    continue;
                for (InetAddress addr : Collections.list(nif.getInetAddresses()))
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()
                            && addr.isSiteLocalAddress())
                        return addr.getHostAddress();
            }
        } catch (Exception ignored) {
            // Сеть не опрашивается — пусть игрок посмотрит адрес сам.
        }
        return "";
    }

    // ------------------------------------------------------------------ STUN

    public static byte[] bindingRequest(byte[] transactionId) {
        byte[] req = new byte[20];
        req[0] = (byte) (BINDING_REQUEST >> 8);
        req[1] = (byte) BINDING_REQUEST;
        req[2] = 0;  // длина тела: атрибутов нет
        req[3] = 0;
        req[4] = (byte) (MAGIC_COOKIE >>> 24);
        req[5] = (byte) (MAGIC_COOKIE >>> 16);
        req[6] = (byte) (MAGIC_COOKIE >>> 8);
        req[7] = (byte) MAGIC_COOKIE;
        System.arraycopy(transactionId, 0, req, 8, 12);
        return req;
    }

    /**
     * Вытащить адрес из ответа.
     *
     * <p>Атрибута два: современный {@code XOR-MAPPED-ADDRESS}, где адрес и порт
     * сложены по модулю два с постоянной протокола, и старый
     * {@code MAPPED-ADDRESS} открытым текстом. Складывание придумано не ради
     * тайны, а против домашних роутеров, которые «чинили» IP-адреса, замеченные
     * в теле пакета; читаются оба, предпочитается первый.
     *
     * @return адрес или пустая строка
     */
    public static String parseResponse(byte[] data, int length, byte[] transactionId) {
        if (length < 20)
            return "";
        int type = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
        if (type != BINDING_RESPONSE)
            return "";
        for (int i = 0; i < 12; i++)
            if (data[8 + i] != transactionId[i])
                return "";
        int bodyLength = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        int end = Math.min(length, 20 + bodyLength);
        int at = 20;
        String plain = "";
        while (at + 4 <= end) {
            int attr = ((data[at] & 0xFF) << 8) | (data[at + 1] & 0xFF);
            int len = ((data[at + 2] & 0xFF) << 8) | (data[at + 3] & 0xFF);
            int value = at + 4;
            if (value + len > end)
                break;
            // Адрес IPv4 занимает восемь байт: резерв, семейство, порт, адрес.
            if (len >= 8 && (data[value + 1] & 0xFF) == 0x01) {
                if (attr == ATTR_XOR_MAPPED)
                    return xorAddress(data, value);
                if (attr == ATTR_MAPPED && plain.isEmpty())
                    plain = (data[value + 4] & 0xFF) + "." + (data[value + 5] & 0xFF) + "."
                            + (data[value + 6] & 0xFF) + "." + (data[value + 7] & 0xFF);
            }
            // Атрибуты выровнены по четыре байта, длина — нет.
            at = value + len + ((4 - (len & 3)) & 3);
        }
        return plain;
    }

    private static String xorAddress(byte[] data, int value) {
        int a = (data[value + 4] & 0xFF) ^ ((MAGIC_COOKIE >>> 24) & 0xFF);
        int b = (data[value + 5] & 0xFF) ^ ((MAGIC_COOKIE >>> 16) & 0xFF);
        int c = (data[value + 6] & 0xFF) ^ ((MAGIC_COOKIE >>> 8) & 0xFF);
        int d = (data[value + 7] & 0xFF) ^ (MAGIC_COOKIE & 0xFF);
        return a + "." + b + "." + c + "." + d;
    }

    private static String viaStun(String server) {
        int colon = server.lastIndexOf(':');
        if (colon <= 0)
            return "";
        String host = server.substring(0, colon);
        int port;
        try {
            port = Integer.parseInt(server.substring(colon + 1));
        } catch (NumberFormatException e) {
            return "";
        }
        byte[] transactionId = new byte[12];
        RANDOM.nextBytes(transactionId);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(TIMEOUT_MS);
            byte[] req = bindingRequest(transactionId);
            socket.send(new DatagramPacket(req, req.length,
                    new InetSocketAddress(host, port)));
            byte[] buf = new byte[512];
            DatagramPacket in = new DatagramPacket(buf, buf.length);
            socket.receive(in);
            return parseResponse(buf, in.getLength(), transactionId);
        } catch (Exception e) {
            return "";
        }
    }
}

package com.mineclone.net.direct;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * UPnP IGD: попросить роутер открыть порт по-человечески.
 *
 * <p>Три шага. Рассылка {@code M-SEARCH} по UDP на {@code 239.255.255.250:1900}
 * находит шлюз и приносит адрес его описания; описание — XML, из которого нужен
 * один адрес управляющей точки; дальше по ней уходит SOAP-запрос
 * {@code AddPortMapping}. Всё на встроенном в JDK, без единой сторонней
 * библиотеки — как и клиент Photon.
 *
 * <p>XML разбирается поиском по строке, а не разборщиком. Это сознательно: от
 * описания нужны два тега из сотни, зато пространства имён у разных роутеров
 * разные, и полноценный разбор ломался бы на каждом втором. Промах здесь стоит
 * ровно «порт не открылся», а это и так один из ожидаемых исходов.
 *
 * <p>Роутер у половины людей UPnP не умеет или имеет его выключенным. Это не
 * поломка: {@link PortMapper} честно скажет, что порт открыть не удалось, и
 * останется облако.
 */
public final class UpnpGateway {

    private UpnpGateway() {
    }

    public static final String MULTICAST = "239.255.255.250";
    public static final int SSDP_PORT = 1900;
    /** Сколько слушаем ответы на рассылку. */
    public static final int DISCOVER_MS = 1500;
    /** На сколько секунд просим отображение. */
    public static final int LEASE_SECONDS = 3600;

    /** Два типа службы: у кабельных роутеров первая, у ADSL — вторая. */
    private static final String[] SERVICES = {
            "urn:schemas-upnp-org:service:WANIPConnection:1",
            "urn:schemas-upnp-org:service:WANPPPConnection:1",
    };

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .build();

    /** Найденный шлюз: куда слать SOAP и какой службе. */
    public record Control(String url, String service) {
    }

    /**
     * Найти шлюз.
     *
     * <p>Блокирующий вызов на {@link #DISCOVER_MS}: звать из фонового потока.
     */
    public static Control discover() {
        for (String location : search()) {
            Control c = describe(location);
            if (c != null)
                return c;
        }
        return null;
    }

    /** Открыть порт. Пустая строка — получилось, иначе причина отказа. */
    public static String addMapping(Control control, int port, String description,
            String localAddress) {
        String body = "<NewRemoteHost></NewRemoteHost>"
                + "<NewExternalPort>" + port + "</NewExternalPort>"
                + "<NewProtocol>TCP</NewProtocol>"
                + "<NewInternalPort>" + port + "</NewInternalPort>"
                + "<NewInternalClient>" + localAddress + "</NewInternalClient>"
                + "<NewEnabled>1</NewEnabled>"
                + "<NewPortMappingDescription>" + escape(description) + "</NewPortMappingDescription>"
                + "<NewLeaseDuration>" + LEASE_SECONDS + "</NewLeaseDuration>";
        String reply = soap(control, "AddPortMapping", body);
        if (reply == null)
            return "роутер не ответил на запрос открытия порта";
        if (reply.contains("AddPortMappingResponse"))
            return "";
        return errorOf(reply);
    }

    /** Убрать отображение. Тихо: на выходе из игры жаловаться уже некому. */
    public static void removeMapping(Control control, int port) {
        soap(control, "DeletePortMapping",
                "<NewRemoteHost></NewRemoteHost>"
                        + "<NewExternalPort>" + port + "</NewExternalPort>"
                        + "<NewProtocol>TCP</NewProtocol>");
    }

    /** Внешний адрес глазами роутера; пустая строка — не сказал. */
    public static String externalAddress(Control control) {
        String reply = soap(control, "GetExternalIPAddress", "");
        if (reply == null)
            return "";
        return tag(reply, "NewExternalIPAddress");
    }

    // ------------------------------------------------------------- рассылка

    /** Разослать {@code M-SEARCH} и собрать адреса описаний. */
    public static List<String> search() {
        List<String> locations = new ArrayList<>();
        String request = "M-SEARCH * HTTP/1.1\r\n"
                + "HOST: " + MULTICAST + ":" + SSDP_PORT + "\r\n"
                + "MAN: \"ssdp:discover\"\r\n"
                + "MX: 2\r\n"
                + "ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n\r\n";
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(DISCOVER_MS);
            byte[] bytes = request.getBytes(StandardCharsets.US_ASCII);
            socket.send(new DatagramPacket(bytes, bytes.length,
                    new InetSocketAddress(InetAddress.getByName(MULTICAST), SSDP_PORT)));
            long deadline = System.currentTimeMillis() + DISCOVER_MS;
            byte[] buf = new byte[2048];
            while (System.currentTimeMillis() < deadline) {
                DatagramPacket in = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(in);
                } catch (Exception timeout) {
                    break;
                }
                String text = new String(in.getData(), 0, in.getLength(), StandardCharsets.UTF_8);
                String location = header(text, "LOCATION");
                if (!location.isEmpty() && !locations.contains(location))
                    locations.add(location);
            }
        } catch (Exception ignored) {
            // Нет сети, заблокирована многоадресная рассылка — шлюза не будет.
        }
        return locations;
    }

    /** Значение заголовка в ответе SSDP; регистр в них произвольный. */
    public static String header(String response, String name) {
        for (String line : response.split("\r?\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0)
                continue;
            if (line.substring(0, colon).trim().equalsIgnoreCase(name))
                return line.substring(colon + 1).trim();
        }
        return "";
    }

    /** Скачать описание и вытащить из него управляющую точку. */
    private static Control describe(String location) {
        String xml = get(location);
        if (xml == null)
            return null;
        for (String service : SERVICES) {
            String control = controlUrl(xml, service);
            if (control.isEmpty())
                continue;
            return new Control(absolute(location, control), service);
        }
        return null;
    }

    /**
     * Адрес управления службой {@code service} из описания шлюза.
     *
     * <p>В описании таких служб несколько, и {@code controlURL} надо взять из
     * того же блока {@code <service>}, где лежит нужный {@code serviceType} —
     * иначе порт откроется не там, где просили.
     */
    public static String controlUrl(String xml, String service) {
        int type = xml.indexOf(service);
        if (type < 0)
            return "";
        int blockStart = xml.lastIndexOf("<service>", type);
        int blockEnd = xml.indexOf("</service>", type);
        if (blockStart < 0 || blockEnd < 0)
            return "";
        return tag(xml.substring(blockStart, blockEnd), "controlURL");
    }

    /** Содержимое тега без учёта пространства имён. */
    public static String tag(String xml, String name) {
        int open = xml.indexOf("<" + name + ">");
        if (open < 0)
            return "";
        int from = open + name.length() + 2;
        int close = xml.indexOf("</" + name + ">", from);
        if (close < 0)
            return "";
        return xml.substring(from, close).trim();
    }

    /** В описании путь бывает и полным, и относительным его собственному адресу. */
    public static String absolute(String location, String path) {
        if (path.startsWith("http://") || path.startsWith("https://"))
            return path;
        try {
            return URI.create(location).resolve(path.startsWith("/") ? path : "/" + path).toString();
        } catch (IllegalArgumentException e) {
            return path;
        }
    }

    // ------------------------------------------------------------ обращения

    private static String get(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(4))
                    .GET()
                    .build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            return res.statusCode() == 200 ? res.body() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String soap(Control control, String action, String body) {
        if (control == null)
            return null;
        String envelope = "<?xml version=\"1.0\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body><u:" + action + " xmlns:u=\"" + control.service() + "\">"
                + body
                + "</u:" + action + "></s:Body></s:Envelope>";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(control.url()))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "text/xml; charset=\"utf-8\"")
                    .header("SOAPAction", "\"" + control.service() + "#" + action + "\"")
                    .POST(HttpRequest.BodyPublishers.ofString(envelope, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            return res.body();
        } catch (Exception e) {
            return null;
        }
    }

    /** Ошибка UPnP из тела ответа: код важнее текста, текст бывает пустым. */
    public static String errorOf(String reply) {
        String code = tag(reply, "errorCode");
        String text = tag(reply, "errorDescription");
        if (code.isEmpty() && text.isEmpty())
            return "роутер отказал без объяснения";
        if ("718".equals(code))
            return "порт уже занят другим отображением";
        if ("725".equals(code))
            return "роутер открывает порты только навсегда";
        if (text.isEmpty())
            return "роутер отказал, код " + code;
        return text + (code.isEmpty() ? "" : " (" + code + ")");
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

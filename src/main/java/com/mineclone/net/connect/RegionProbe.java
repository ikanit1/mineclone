package com.mineclone.net.connect;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Какой регион облака ближе.
 *
 * <p>«Авто» в настройках означало «пусть сервер имён решит сам», и решал он
 * каждый раз заново — а хозяин и участник в разных странах получали разные
 * мастер-серверы и не видели комнат друг друга. Теперь «Авто» означает
 * «замерить один раз и запомнить конкретный регион»: дальше он лежит в
 * {@code options.dat} и едет внутри кода комнаты.
 *
 * <p>Замер — время установки TCP-соединения с мастер-сервером региона.
 * Это не идеальная задержка игры, но ровно тот же путь и тот же круг по сети,
 * а стоит он одно соединение вместо полного входа в Photon.
 *
 * <p>Класс не знает ни про Photon, ни про WebSocket: список регионов ему
 * приносят, а мерить он умеет чем угодно, что похоже на {@link Pinger}.
 * Поэтому выбор лучшего проверяется обычным тестом с поддельным замером.
 * Достаёт список {@link RegionFinder}.
 */
public final class RegionProbe {

    private RegionProbe() {
    }

    /** Замер одного региона. Отрицательный {@code rtt} — не ответил. */
    public record Result(String region, String address, int rtt) {

        public boolean reachable() {
            return rtt >= 0;
        }
    }

    /** Чем меряем. Тест подставляет свой и обходится без сети. */
    public interface Pinger {
        /** Время круга в миллисекундах; отрицательное — не достучались. */
        int pingMillis(String address);
    }

    /** Сколько ждём ответа одного региона. */
    public static final int TIMEOUT_MS = 1200;

    /**
     * Замерить все регионы сразу.
     *
     * <p>Параллельно: последовательный обход двенадцати регионов по секунде на
     * каждый — это двенадцать секунд на экране, за которые игрок успеет уйти.
     */
    public static List<Result> measure(Map<String, String> regions, Pinger pinger) {
        List<Result> out = new ArrayList<>();
        if (regions == null || regions.isEmpty())
            return out;
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(regions.size(), 8), r -> {
                    Thread t = new Thread(r, "mineclone-region-probe");
                    t.setDaemon(true);
                    return t;
                });
        try {
            List<Map.Entry<String, String>> entries = new ArrayList<>(regions.entrySet());
            List<Future<Integer>> futures = new ArrayList<>(entries.size());
            for (Map.Entry<String, String> e : entries)
                futures.add(pool.submit(() -> pinger.pingMillis(e.getValue())));
            for (int i = 0; i < entries.size(); i++) {
                Map.Entry<String, String> e = entries.get(i);
                int rtt;
                try {
                    rtt = futures.get(i).get(TIMEOUT_MS * 3L, TimeUnit.MILLISECONDS);
                } catch (Exception ignored) {
                    // Не ответил, не уложился или поток сняли — для нас это
                    // один и тот же исход: регион недоступен.
                    rtt = -1;
                }
                out.add(new Result(e.getKey(), e.getValue(), rtt));
            }
        } finally {
            pool.shutdownNow();
        }
        out.sort(Comparator.comparingInt(r -> r.reachable() ? r.rtt() : Integer.MAX_VALUE));
        return out;
    }

    /**
     * Лучший регион из замеренных.
     *
     * <p>Пустая строка — не ответил никто; звать «Авто» на этом месте нельзя,
     * из «Авто» кода комнаты не сделать.
     */
    public static String best(List<Result> results) {
        if (results == null)
            return "";
        Result best = null;
        for (Result r : results) {
            if (!r.reachable())
                continue;
            // При равной задержке берём того, кто раньше в списке: у сервера
            // имён он идёт первым не случайно.
            if (best == null || r.rtt() < best.rtt())
                best = r;
        }
        return best == null ? "" : best.region();
    }

    /** Замер настоящим соединением: столько миллисекунд занял TCP-рукопожатие. */
    public static Pinger tcpPinger() {
        return address -> {
            String hostPort = stripScheme(address);
            int colon = hostPort.lastIndexOf(':');
            if (colon <= 0)
                return -1;
            String host = hostPort.substring(0, colon);
            int port;
            try {
                port = Integer.parseInt(hostPort.substring(colon + 1));
            } catch (NumberFormatException e) {
                return -1;
            }
            long start = System.nanoTime();
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            } catch (Exception e) {
                return -1;
            }
            return (int) Math.min(9999, (System.nanoTime() - start) / 1_000_000L);
        };
    }

    /** Адрес от Photon приходит и со схемой, и без; путь нас не интересует. */
    public static String stripScheme(String address) {
        if (address == null)
            return "";
        String a = address.trim();
        int scheme = a.indexOf("://");
        if (scheme >= 0)
            a = a.substring(scheme + 3);
        int slash = a.indexOf('/');
        if (slash >= 0)
            a = a.substring(0, slash);
        return a;
    }
}

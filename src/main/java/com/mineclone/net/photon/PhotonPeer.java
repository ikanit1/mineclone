package com.mineclone.net.photon;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Одно соединение с сервером Photon.
 *
 * <p>Photon не выпускает Java-SDK: официальные клиенты — Unity, .NET, C++ и
 * JavaScript. Но у серверов Photon есть вход по WebSocket с подпротоколом
 * {@code Json}, тем самым, на котором работает их веб-клиент, — и он
 * реализуется здесь на встроенном в JDK {@link WebSocket}, без единой
 * сторонней библиотеки. ADR: {@code knowledge/decisions/multiplayer-photon.md}.
 *
 * <p>Кадрирование socket.io-подобное: {@code ~m~<длина>~m~<тело>}, и таких
 * кадров в одном сообщении WebSocket может быть несколько. Тело либо
 * {@code ~j~} и JSON, либо — ровно один раз, сразу после соединения, — номер
 * сессии открытым текстом. Пока он не пришёл, соединение не считается
 * установленным: сервер ещё не подтвердил, что принял приложение.
 *
 * <p>Отправка складывается в очередь и уходит по одной: {@code sendText} у
 * JDK нельзя звать до того, как закончилась предыдущая посылка, а звать его
 * будут и игровой поток, и поток тиканья.
 */
public final class PhotonPeer {

    /** Через сколько молчания отправить пинг: у сервера свой тайм-аут. */
    private static final long KEEPALIVE_MS = 3000;
    private static final String FRAME = "~m~";
    private static final String JSON_PREFIX = "~j~";

    /**
     * Один поток на все соединения: пинги нужны редко, а лишний поток на
     * каждое из трёх соединений — это три потока, которые ещё и гоняются за
     * очередь отправки.
     */
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mineclone-photon-keepalive");
        t.setDaemon(true);
        return t;
    });

    /** Что соединение сообщает владельцу. Зовётся из потока сети. */
    public interface Handler {
        /** Пришёл номер сессии — соединение живо. */
        void onConnected(PhotonPeer peer);

        void onResponse(PhotonPeer peer, int op, int errCode, String errMsg, Map<Integer, Object> vals);

        void onEvent(PhotonPeer peer, int code, Map<Integer, Object> vals);

        /** Закрылось. {@code wasConnected} — успели ли до этого войти. */
        void onClosed(PhotonPeer peer, String reason, boolean wasConnected);

        void onError(PhotonPeer peer, String message);
    }

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12))
            .build();

    private final String label;
    private final Handler handler;
    private final Object lock = new Object();
    private final ArrayDeque<String> outbox = new ArrayDeque<>();
    private final StringBuilder partial = new StringBuilder();
    private final long startedNanos = System.nanoTime();

    private WebSocket socket;
    private String sessionId;
    private boolean sending;
    private boolean closed;
    private boolean connected;
    private ScheduledFuture<?> keepalive;
    private volatile long lastSentMs;
    private volatile int rttMs;

    public PhotonPeer(String label, Handler handler) {
        this.label = label;
        this.handler = handler;
    }

    /** Имя соединения в отладочных строках: «NameServer», «Master», «Game». */
    public String label() {
        return label;
    }

    public int rtt() {
        return rttMs;
    }

    public boolean isConnected() {
        return connected && !closed;
    }

    /**
     * Соединиться.
     *
     * @param address адрес с протоколом или без: без него подставится тот же,
     *                что у сервера имён, — сервер отдаёт адреса без схемы
     * @param appId   ключ приложения Photon; он же часть пути
     */
    public void connect(String address, String appId, boolean secure) {
        String url = withScheme(address, secure) + "/" + appId
                + "?libversion=" + PhotonCodes.LIB_VERSION;
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            handler.onError(this, "неверный адрес Photon: " + url);
            return;
        }
        CLIENT.newWebSocketBuilder()
                .subprotocols(PhotonCodes.SUBPROTOCOL)
                .connectTimeout(Duration.ofSeconds(12))
                .buildAsync(uri, new Socket())
                .whenComplete((ws, err) -> {
                    if (err != null) {
                        handler.onError(this, label + ": не удалось соединиться ("
                                + rootMessage(err) + ")");
                        return;
                    }
                    synchronized (lock) {
                        socket = ws;
                    }
                    startKeepalive();
                });
    }

    /** Адрес от Photon приходит без схемы — навесить ту же, что у нас. */
    static String withScheme(String address, boolean secure) {
        if (address.startsWith("ws://") || address.startsWith("wss://"))
            return address;
        return (secure ? "wss://" : "ws://") + address;
    }

    /** Отправить операцию: код и плоский список «ключ, значение, ключ, значение». */
    public void sendOp(int op, Object... kv) {
        enqueue(operationJson(op, kv));
    }

    private void sendPing() {
        long ms = (System.nanoTime() - startedNanos) / 1_000_000L;
        enqueue("{\"irq\":1,\"vals\":[1," + ms + "]}");
    }

    /**
     * Обернуть JSON в кадр протокола.
     *
     * <p>Длина считается в знаках и включает префикс {@code ~j~} — так это
     * делает эталонный клиент, и сервер меряет ровно так же.
     */
    public static String frame(String json) {
        return FRAME + (JSON_PREFIX.length() + json.length()) + FRAME + JSON_PREFIX + json;
    }

    /** Разобрать сообщение на тела кадров; мусор обрывает разбор. */
    public static java.util.List<String> frames(String text) {
        java.util.List<String> out = new java.util.ArrayList<>();
        int i = 0;
        while (i + FRAME.length() <= text.length()) {
            if (!text.startsWith(FRAME, i))
                return out;
            i += FRAME.length();
            int digitsEnd = i;
            while (digitsEnd < text.length() && Character.isDigit(text.charAt(digitsEnd)))
                digitsEnd++;
            if (digitsEnd == i || !text.startsWith(FRAME, digitsEnd))
                return out;
            int length;
            try {
                length = Integer.parseInt(text.substring(i, digitsEnd));
            } catch (NumberFormatException e) {
                return out;
            }
            int bodyStart = digitsEnd + FRAME.length();
            int bodyEnd = Math.min(text.length(), bodyStart + length);
            out.add(text.substring(bodyStart, bodyEnd));
            i = bodyEnd;
        }
        return out;
    }

    /** Строка операции в том виде, в каком она уходит на сервер. */
    public static String operationJson(int op, Object... kv) {
        StringBuilder sb = new StringBuilder(64 + kv.length * 8);
        sb.append("{\"req\":").append(op).append(",\"vals\":[");
        for (int i = 0; i < kv.length; i++) {
            if (i > 0)
                sb.append(',');
            PhotonJson.write(sb, kv[i]);
        }
        sb.append("]}");
        return sb.toString();
    }

    private void enqueue(String json) {
        String framed = frame(json);
        synchronized (lock) {
            if (closed)
                return;
            outbox.add(framed);
            pumpLocked();
        }
    }

    /** Отправить следующее из очереди, если предыдущее уже ушло. */
    private void pumpLocked() {
        if (sending || socket == null || outbox.isEmpty())
            return;
        String next = outbox.poll();
        sending = true;
        lastSentMs = System.currentTimeMillis();
        WebSocket ws = socket;
        CompletionStage<WebSocket> stage;
        try {
            stage = ws.sendText(next, true);
        } catch (RuntimeException e) {
            sending = false;
            handler.onError(this, label + ": отправка не удалась (" + rootMessage(e) + ")");
            return;
        }
        stage.whenComplete((w, err) -> {
            synchronized (lock) {
                sending = false;
                if (err == null)
                    pumpLocked();
            }
            if (err != null)
                handler.onError(this, label + ": отправка не удалась (" + rootMessage(err) + ")");
        });
    }

    private void startKeepalive() {
        stopKeepalive();
        keepalive = TIMER.scheduleWithFixedDelay(() -> {
            if (closed || !connected)
                return;
            if (System.currentTimeMillis() - lastSentMs >= KEEPALIVE_MS)
                sendPing();
        }, KEEPALIVE_MS, KEEPALIVE_MS / 2, TimeUnit.MILLISECONDS);
    }

    private void stopKeepalive() {
        ScheduledFuture<?> k = keepalive;
        keepalive = null;
        if (k != null)
            k.cancel(false);
    }

    public void close() {
        WebSocket ws;
        boolean wasConnected;
        synchronized (lock) {
            if (closed)
                return;
            closed = true;
            wasConnected = connected;
            connected = false;
            ws = socket;
            socket = null;
            outbox.clear();
        }
        stopKeepalive();
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
            } catch (RuntimeException ignored) {
                ws.abort();
            }
        }
        handler.onClosed(this, "закрыто", wasConnected);
    }

    // --------------------------------------------------------------- приём

    /** Разобрать одно сообщение WebSocket: в нём может быть несколько кадров. */
    void onMessage(String text) {
        for (String body : frames(text))
            onFrame(body);
    }

    private void onFrame(String body) {
        if (body.startsWith(JSON_PREFIX)) {
            onJson(body.substring(JSON_PREFIX.length()));
            return;
        }
        if (sessionId == null) {
            sessionId = body;
            connected = true;
            // Первый пинг сразу: он же замер задержки, он же ответ серверу,
            // что клиент жив.
            sendPing();
            handler.onConnected(this);
        }
    }

    private void onJson(String json) {
        Map<String, Object> msg;
        try {
            msg = PhotonJson.parseMessage(json);
        } catch (RuntimeException e) {
            handler.onError(this, label + ": непонятный ответ сервера");
            return;
        }
        if (msg == null)
            return;
        Map<Integer, Object> vals = PhotonJson.vals(msg.get("vals"));
        Integer res = PhotonJson.asInt(msg.get("res"));
        if (res != null) {
            int err = orZero(msg.get("err"));
            Object m = msg.get("msg");
            handler.onResponse(this, res, err, m instanceof String s ? s : "", vals);
            return;
        }
        Integer evt = PhotonJson.asInt(msg.get("evt"));
        if (evt != null) {
            handler.onEvent(this, evt, vals);
            return;
        }
        Integer irs = PhotonJson.asInt(msg.get("irs"));
        if (irs != null && irs == 1) {
            // Ответ на пинг: во втором значении — время, которое мы послали.
            Object sent = vals.get(1);
            Integer sentMs = PhotonJson.asInt(sent);
            if (sentMs != null) {
                long now = (System.nanoTime() - startedNanos) / 1_000_000L;
                rttMs = (int) Math.max(0, Math.min(9999, now - sentMs));
            }
        }
    }

    private static int orZero(Object v) {
        Integer i = PhotonJson.asInt(v);
        return i == null ? 0 : i;
    }

    static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur)
            cur = cur.getCause();
        String m = cur.getMessage();
        return (m == null || m.isEmpty()) ? cur.getClass().getSimpleName() : m;
    }

    /** Приёмник JDK: собирает фрагменты и отдаёт целые сообщения. */
    private final class Socket implements WebSocket.Listener {

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            webSocket.request(1);
            partial.append(data);
            if (last) {
                String whole = partial.toString();
                partial.setLength(0);
                try {
                    onMessage(whole);
                } catch (RuntimeException e) {
                    handler.onError(PhotonPeer.this, label + ": " + rootMessage(e));
                }
            }
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            boolean wasConnected;
            synchronized (lock) {
                if (closed)
                    return null;
                closed = true;
                wasConnected = connected;
                connected = false;
                socket = null;
                outbox.clear();
            }
            stopKeepalive();
            // 1006 — обрыв без кадра закрытия: так выглядит и тайм-аут, и
            // отказ сервера в приёме приложения.
            String why = statusCode == 1006 ? "соединение потеряно"
                    : (reason == null || reason.isEmpty() ? "сервер закрыл соединение" : reason);
            handler.onClosed(PhotonPeer.this, why, wasConnected);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            boolean wasConnected;
            synchronized (lock) {
                closed = true;
                wasConnected = connected;
                connected = false;
                socket = null;
                outbox.clear();
            }
            stopKeepalive();
            handler.onError(PhotonPeer.this, label + ": " + rootMessage(error));
            handler.onClosed(PhotonPeer.this, rootMessage(error), wasConnected);
        }
    }
}

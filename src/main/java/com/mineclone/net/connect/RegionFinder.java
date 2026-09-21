package com.mineclone.net.connect;

import com.mineclone.net.photon.PhotonCodes;
import com.mineclone.net.photon.PhotonJson;
import com.mineclone.net.photon.PhotonPeer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Спросить у Photon список регионов и выбрать ближайший.
 *
 * <p>Сеть и только сеть: сам выбор считает {@link RegionProbe}, и он же
 * покрыт тестами. Здесь остаётся один разговор с сервером имён — операция
 * {@link PhotonCodes#OP_GET_REGIONS}, в ответ на которую приходят два
 * параллельных массива: коды регионов и адреса их мастер-серверов.
 *
 * <p>Ответ приходит в поток сети, а замер занимает около секунды, поэтому
 * замер уезжает в свой поток: держать на нём обработчик WebSocket значит
 * задержать ping-ответ и получить обрыв соединения на ровном месте.
 *
 * <p>Зовётся один раз за установку игры — дальше выбранный регион лежит в
 * {@code options.dat} и едет внутри {@link RoomCode}.
 */
public final class RegionFinder {

    /** Результат: выбранный регион и весь замер — второе идёт на экран. */
    public record Outcome(String region, List<RegionProbe.Result> results, String error) {

        public boolean ok() {
            return !region.isEmpty();
        }
    }

    private final String appId;
    private final RegionProbe.Pinger pinger;
    private final AtomicBoolean finished = new AtomicBoolean();
    private PhotonPeer peer;

    public RegionFinder(String appId) {
        this(appId, RegionProbe.tcpPinger());
    }

    public RegionFinder(String appId, RegionProbe.Pinger pinger) {
        this.appId = appId == null ? "" : appId.trim();
        this.pinger = pinger;
    }

    /**
     * Найти ближайший регион.
     *
     * <p>{@code done} зовётся ровно один раз и из чужого потока: вызывающий
     * обязан перенести результат в свой сам.
     */
    public void start(Consumer<Outcome> done) {
        if (appId.isEmpty()) {
            finish(done, new Outcome("", List.of(), "не задан ключ приложения Photon"));
            return;
        }
        peer = new PhotonPeer("Regions", new Handler(done));
        peer.connect(PhotonCodes.NAME_SERVER_WSS, appId, true);
    }

    public void close() {
        PhotonPeer p = peer;
        peer = null;
        if (p != null)
            p.close();
    }

    private void finish(Consumer<Outcome> done, Outcome outcome) {
        // Сервер имён отвечает один раз, но обрыв и ответ могут прийти
        // наперегонки: второй результат уже никому не нужен.
        if (!finished.compareAndSet(false, true))
            return;
        close();
        done.accept(outcome);
    }

    /** Разобрать два параллельных массива в карту «регион — адрес». */
    public static Map<String, String> pairs(Object regions, Object addresses) {
        List<Object> r = PhotonJson.listOr(regions);
        List<Object> a = PhotonJson.listOr(addresses);
        Map<String, String> out = new LinkedHashMap<>();
        int n = Math.min(r.size(), a.size());
        for (int i = 0; i < n; i++) {
            if (r.get(i) instanceof String region && a.get(i) instanceof String address
                    && !region.isBlank() && !address.isBlank())
                out.put(region.trim(), address.trim());
        }
        return out;
    }

    private final class Handler implements PhotonPeer.Handler {
        private final Consumer<Outcome> done;

        Handler(Consumer<Outcome> done) {
            this.done = done;
        }

        @Override
        public void onConnected(PhotonPeer p) {
            p.sendOp(PhotonCodes.OP_GET_REGIONS, PhotonCodes.P_APPLICATION_ID, appId);
        }

        @Override
        public void onResponse(PhotonPeer p, int op, int errCode, String errMsg,
                Map<Integer, Object> vals) {
            if (op != PhotonCodes.OP_GET_REGIONS)
                return;
            if (errCode != PhotonCodes.ERR_OK) {
                finish(done, new Outcome("", List.of(),
                        PhotonCodes.errorText(errCode, errMsg)));
                return;
            }
            Map<String, String> regions = pairs(vals.get(PhotonCodes.P_REGION),
                    vals.get(PhotonCodes.P_ADDRESS));
            if (regions.isEmpty()) {
                finish(done, new Outcome("", List.of(), "сервер имён не дал списка регионов"));
                return;
            }
            // Замер в своём потоке: обработчик WebSocket держать нельзя.
            Thread t = new Thread(() -> {
                List<RegionProbe.Result> results =
                        new ArrayList<>(RegionProbe.measure(regions, pinger));
                String best = RegionProbe.best(results);
                finish(done, new Outcome(best, results,
                        best.isEmpty() ? "ни один регион не ответил" : ""));
            }, "mineclone-region-measure");
            t.setDaemon(true);
            t.start();
        }

        @Override
        public void onEvent(PhotonPeer p, int code, Map<Integer, Object> vals) {
            // Сервер имён событий не шлёт.
        }

        @Override
        public void onClosed(PhotonPeer p, String reason, boolean wasConnected) {
            finish(done, new Outcome("", List.of(), reason));
        }

        @Override
        public void onError(PhotonPeer p, String message) {
            finish(done, new Outcome("", List.of(), message));
        }
    }
}

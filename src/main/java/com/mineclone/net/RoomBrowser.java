package com.mineclone.net;

import java.util.ArrayList;
import java.util.List;

/**
 * Список открытых комнат: подключение к лобби Photon и ничего больше.
 *
 * <p>Экран меню не может держать соединение сам — он живёт один кадр и
 * рисуется заново. Поэтому лобби держит игра, а экран только читает отсюда
 * список и строку состояния, как {@code SettingsScreen} читает
 * {@code SettingsModel}.
 *
 * <p>Обновлять список руками не нужно: Photon сам присылает и полный список
 * при входе в лобби, и добавления с удалениями потом. Кнопка «Обновить» есть
 * только для того, чтобы переподключиться после обрыва.
 *
 * <p>Соединение закрывается, как только начинается игра: лобби и комната —
 * это два разных подключения, и держать оба значит занимать два места из ста
 * бесплатных.
 */
public final class RoomBrowser implements NetTransport.Listener {

    public enum State {
        /** Ничего не открыто. */
        IDLE,
        /** Идёт подключение к лобби. */
        CONNECTING,
        /** Список получен — можно выбирать. */
        READY,
        /** Не вышло; причина в {@link #status()}. */
        FAILED
    }

    private final List<NetTransport.RoomInfo> rooms = new ArrayList<>();
    private NetTransport transport;
    private State state = State.IDLE;
    private String status = "";
    /** Прямое соединение лобби не имеет: там комната — это адрес хозяина. */
    private boolean supported;

    /**
     * Открыть лобби по этим настройкам.
     *
     * <p>Повторный вызов с теми же настройками переподключает: это и есть
     * «Обновить».
     */
    public void open(NetSettings settings) {
        close();
        if (settings == null || settings.transport() != NetSettings.PHOTON) {
            // У прямого соединения списка комнат нет и быть не может: никто
            // не ведёт их реестра, адрес хозяина приходит из чата.
            supported = false;
            state = State.IDLE;
            status = "";
            return;
        }
        supported = true;
        state = State.CONNECTING;
        status = "подключение к лобби…";
        transport = new PhotonTransport(settings.effectiveAppId(), settings.region(), true, this);
        transport.connect("", false, settings.nickname());
    }

    public void close() {
        if (transport != null) {
            transport.disconnect();
            transport.poll();   // дать закрытию дойти до слушателя
        }
        transport = null;
        rooms.clear();
        state = State.IDLE;
        status = "";
    }

    /** Кадр: разобрать всё, что пришло из сети. */
    public void update(float dt) {
        if (transport != null)
            transport.poll();
    }

    public State state() {
        return state;
    }

    public String status() {
        return status;
    }

    public boolean supported() {
        return supported;
    }

    /** Комнаты от последнего обновления, в порядке, в котором их прислал Photon. */
    public List<NetTransport.RoomInfo> rooms() {
        return rooms;
    }

    // ------------------------------------------------------ ответы транспорта

    @Override
    public void onState(NetTransport.State s, String detail) {
        switch (s) {
            case CONNECTING -> {
                if (state != State.READY)
                    status = detail;
            }
            case FAILED -> {
                state = State.FAILED;
                status = detail;
            }
            case CLOSED -> {
                if (state != State.FAILED)
                    state = State.IDLE;
            }
            default -> {
            }
        }
    }

    @Override
    public void onRoomList(List<NetTransport.RoomInfo> list) {
        rooms.clear();
        rooms.addAll(list);
        state = State.READY;
        status = list.isEmpty() ? "открытых комнат нет" : "комнат: " + list.size();
    }

    // Лобби — это не комната: в неё не входят и байтами в ней не обмениваются.
    @Override
    public void onJoined(int myActor, boolean created) {
    }

    @Override
    public void onActorJoin(int actor, String name) {
    }

    @Override
    public void onActorLeave(int actor) {
    }

    @Override
    public void onPayload(int from, byte[] data) {
    }
}

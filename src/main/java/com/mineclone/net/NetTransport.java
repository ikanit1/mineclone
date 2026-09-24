package com.mineclone.net;

import java.util.List;

/**
 * Транспорт: комната, участники и байты между ними.
 *
 * <p>Сессия ({@link Multiplayer}) не знает, чем именно они летят. Это не
 * абстракция ради абстракции: Photon требует ключ приложения и выход в
 * интернет, и без него игру нельзя было бы ни проверить тестом, ни включить в
 * локальной сети. Поэтому реализаций три — {@link PhotonTransport} (облако
 * Photon), {@link LanTransport} (прямое соединение в своей сети) и
 * {@link LoopbackTransport} (две сессии в одном процессе, на нём стоят тесты).
 *
 * <p>Модель комнаты взята у Photon, потому что она самая узкая: у участника
 * есть номер ({@code actor}), номера выдаёт сервер, наименьший из живых —
 * хозяин комнаты. Прямому соединению и петле такую модель изобразить легко,
 * обратно было бы нельзя.
 *
 * <p><b>Потоки.</b> Сеть живёт в своём потоке, игра — в главном. Транспорт
 * копит всё пришедшее в очередь, а отдаёт слушателю только внутри
 * {@link #poll()}, который зовёт игровой поток. Поэтому обработчики пакетов
 * трогают мир, GL и звук без единой блокировки.
 */
public interface NetTransport {

    /** Где мы в подключении. Ровно то, что показывает экран ожидания. */
    enum State {
        /** Ничего не начато. */
        IDLE,
        /** Идёт соединение и вход в комнату. */
        CONNECTING,
        /** В комнате. */
        JOINED,
        /** Отключено по своей воле. */
        CLOSED,
        /** Отключено с ошибкой; текст приходит в {@link Listener#onState}. */
        FAILED
    }

    /** Комната из списка: имя, сколько внутри и сколько вмещает. */
    record RoomInfo(String name, int players, int maxPlayers) {
    }

    /** Ответы транспорта. Зовутся только из {@link #poll()}, то есть из игрового потока. */
    interface Listener {

        /** Состояние сменилось; {@code detail} — человеческий текст для экрана. */
        void onState(State state, String detail);

        /**
         * Вошли в комнату.
         *
         * @param myActor номер, который нам выдали
         * @param created комната создана нами — значит мы хозяин мира
         */
        void onJoined(int myActor, boolean created);

        void onActorJoin(int actor, String name);

        void onActorLeave(int actor);

        /** Пришли байты от участника {@code from}. */
        void onPayload(int from, byte[] data);

        /** Обновился список комнат (только там, где транспорт его умеет). */
        void onRoomList(List<RoomInfo> rooms);
    }

    /**
     * Начать подключение и войти в комнату {@code room}.
     *
     * @param create создать, если такой нет; иначе — только войти в готовую
     */
    void connect(String room, boolean create, String nickname);

    /**
     * Отправить байты.
     *
     * @param target номер получателя; {@code 0} — всем, кроме себя
     */
    void send(byte[] payload, boolean reliable, int target);

    /** Разобрать пришедшее и позвать слушателя. Зовётся раз в кадр из игры. */
    void poll();

    /** Запросить список комнат, если транспорт это умеет. */
    default void requestRoomList() {
    }

    void disconnect();

    State state();

    /** Наш номер в комнате; 0 — ещё не в комнате. */
    int myActor();

    /** Номер хозяина комнаты: у кого живёт мир. */
    int masterActor();

    default boolean isMaster() {
        return myActor() != 0 && myActor() == masterActor();
    }

    /** Номера всех участников, кроме себя. */
    List<Integer> actors();

    /** Имя участника, если транспорт его знает. */
    String actorName(int actor);

    /** Короткая строка для отладочного экрана. */
    String describe();

    /**
     * Сколько байт одно сообщение такого размера стоит одному получателю на
     * проводе (NET-01): что транспорт делает из полезной нагрузки — base64 и
     * JSON у Photon, заголовок кадра у прямого соединения. TLS, TCP и IP не
     * считаются.
     */
    default int wireBytes(int payloadBytes) {
        return payloadBytes;
    }
}

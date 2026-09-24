package com.mineclone.net.connect;

import com.mineclone.net.photon.PhotonCodes;

/**
 * Отказ подключения человеческим языком.
 *
 * <p>Прежде игрок видел сообщение, написанное для того, кто читает исходники:
 * «Master: не удалось соединиться (Connection refused)». По нему нельзя понять
 * ни что случилось, ни что делать. Разница между «у вас закрыт порт», «друг
 * ещё не открыл мир» и «вы набрали код другого региона» — это три разных
 * действия игрока, и её надо называть вслух.
 *
 * <p>Класс чистый: строка на входе, строка на выходе. Поэтому каждая ветка
 * проверяется обычным тестом, а не воспроизведением поломки сети.
 */
public final class ConnectDiagnosis {

    private ConnectDiagnosis() {
    }

    /**
     * Объяснить отказ.
     *
     * @param failure  что сказал транспорт
     * @param next     на какую ступень собираемся перейти; {@code null} — некуда
     * @param finished лестница пройдена целиком
     * @param room     код комнаты, если он есть
     * @param hosting  мы открывали мир, а не входили в чужой
     */
    public static String explain(String failure, ConnectLadder.Step next, boolean finished,
            RoomCode room, boolean hosting) {
        String low = failure == null ? "" : failure.toLowerCase();

        if (low.contains(PhotonCodes.errorText(PhotonCodes.ERR_GAME_DOES_NOT_EXIST, "").toLowerCase())
                || low.contains("does not exist")) {
            if (room == null)
                return "такой комнаты нет";
            return "комнаты " + room.pretty() + " нет в регионе «" + room.regionLabel()
                    + "». Друг ещё не открыл мир, либо код набран с ошибкой";
        }
        if (low.contains("ключ приложения") || low.contains("invalid authentication"))
            return "Photon не принял ключ приложения. Очистите поле «Свой ключ», "
                    + "чтобы играть на встроенном";
        if (low.contains("лимит игроков") || low.contains("max ccu"))
            return "бесплатный лимит облака исчерпан. Попробуйте позже "
                    + "или впишите свой ключ Photon";
        if (low.contains("заполнена") || low.contains("game full"))
            return "в комнате уже нет мест";

        if (!finished && next != null)
            return connectionTrouble(low) + " — пробуем " + next.label();
        if (finished)
            return connectionTrouble(low) + ". " + advice(hosting);
        return connectionTrouble(low);
    }

    /** Во что превратить сетевую поломку, у которой нет своего кода ошибки. */
    private static String connectionTrouble(String low) {
        if (low.contains("timed out") || low.contains("timeout") || low.contains("тайм"))
            return "сервер Photon не отвечает";
        if (low.contains("connection refused") || low.contains("отказ"))
            return "соединение отклонено — похоже, порт закрыт";
        if (low.contains("unresolved") || low.contains("unknownhost") || low.contains("nodename"))
            return "не удалось найти сервер: проверьте, есть ли интернет";
        if (low.contains("соединение потеряно") || low.contains("1006"))
            return "связь оборвалась на полпути";
        if (low.contains("certificate") || low.contains("ssl") || low.contains("tls"))
            return "не прошла проверка шифрования";
        if (low.isEmpty())
            return "не удалось подключиться";
        return low;
    }

    /** Что делать, когда перебрано всё. */
    private static String advice(boolean hosting) {
        if (hosting)
            return "Можно открыть мир в локальной сети: вкладка «Прямое соединение»";
        return "Проверьте интернет, либо попросите друга открыть мир "
                + "в локальной сети";
    }

    /**
     * Хозяин отказывает участнику другой версии протокола (NET-02). Текст
     * пишет хозяин, а читает участник — поэтому в нём обе версии и сборка
     * хозяина, и сказано, кому обновляться: старая сборка участника прочтёт
     * его, даже не зная нового протокола.
     *
     * @param hostBuild строка сборки хозяина ({@code BuildInfo.summary()})
     */
    public static String versionMismatch(int guestVersion, int hostVersion, String hostBuild) {
        String both = "другая версия протокола: у вас v" + guestVersion + ", у хозяина v" + hostVersion
                + " (" + hostBuild + ")";
        return guestVersion < hostVersion
                ? both + ". Обновите игру до сборки хозяина"
                : both + ". У хозяина более старая сборка — обновиться нужно ему";
    }

    /**
     * Короткая строка о ходе подключения — та, что идёт на экран ожидания.
     *
     * <p>Отдельно от объяснения отказа: пока всё идёт, игроку нужно видеть не
     * причину поломки, а что происходит прямо сейчас.
     */
    public static String progress(ConnectLadder ladder, String stage) {
        String where = ladder == null ? "" : ladder.describe();
        if (stage == null || stage.isEmpty())
            return where;
        return where.isEmpty() ? stage : stage + " · " + where;
    }
}

package com.mineclone.net.connect;

import com.mineclone.net.photon.PhotonCodes;

import java.util.ArrayList;
import java.util.List;

/**
 * Лестница подключения: чем пробовать дойти до Photon и в каком порядке.
 *
 * <p>Прежде вход был один — {@code wss://ns.photonengine.io:19093}. Порт
 * нестандартный, и в корпоративных, школьных и части мобильных сетей он просто
 * закрыт: игра честно сообщала «не удалось соединиться» и ничего больше не
 * предпринимала, хотя незашифрованный вход на 9093 у тех же сетей обычно
 * открыт. Константа {@link PhotonCodes#NAME_SERVER_WS} в коде лежала с самого
 * начала — ею никто не пользовался.
 *
 * <p>Теперь ступеней две, и на каждой свои {@link Backoff#ATTEMPTS} попыток:
 * сперва шифрованный вход, потом обычный. Порядок именно такой — незашифрованный
 * канал это запасной путь, а не равноправный: по нему уходит ключ приложения.
 *
 * <p>Класс — чистый автомат без сокетов и без времени: он отвечает только на
 * вопрос «что пробовать дальше и сколько перед этим ждать». Поэтому вся логика
 * отказов проверяется обычным тестом, а не живым облаком.
 */
public final class ConnectLadder {

    /** Одна ступень: куда стучимся и шифровано ли. */
    public record Step(String label, String nameServer, boolean secure) {
    }

    /** Ступени по умолчанию: шифрованный вход, затем обычный. */
    public static List<Step> defaultSteps() {
        List<Step> steps = new ArrayList<>(2);
        steps.add(new Step("шифрованный вход", PhotonCodes.NAME_SERVER_WSS, true));
        steps.add(new Step("обычный вход", PhotonCodes.NAME_SERVER_WS, false));
        return steps;
    }

    private final List<Step> steps;
    private final Backoff backoff = new Backoff();
    private int step;
    /** Чем кончилась последняя попытка — для диагноза. */
    private String lastFailure = "";

    public ConnectLadder() {
        this(defaultSteps());
    }

    public ConnectLadder(List<Step> steps) {
        if (steps == null || steps.isEmpty())
            throw new IllegalArgumentException("лестница без ступеней");
        this.steps = List.copyOf(steps);
    }

    /** Ступень, которую пробуем сейчас; {@code null} — лестница пройдена. */
    public Step current() {
        return step < steps.size() ? steps.get(step) : null;
    }

    /** Лестница кончилась: больше пробовать нечего. */
    public boolean exhausted() {
        return step >= steps.size();
    }

    /**
     * Попытка не удалась.
     *
     * <p>Сначала повторяем ту же ступень, пока не кончатся попытки, и только
     * потом спускаемся на следующую. Наоборот было бы хуже: обрыв на секунду и
     * закрытый порт — разные поломки, и менять вход из-за первой значит
     * уходить с шифрованного канала на ровном месте.
     *
     * @param why чем кончилась попытка; уйдёт в {@link #diagnosis}
     * @return сколько секунд ждать до следующей попытки; отрицательное — всё
     */
    public float failed(String why) {
        lastFailure = why == null ? "" : why;
        float wait = backoff.failed();
        if (wait >= 0f)
            return wait;
        step++;
        backoff.reset();
        if (exhausted())
            return -1f;
        // Смена ступени — это уже не «повторим то же самое», а другой путь:
        // ждать перед ним незачем.
        return 0f;
    }

    /** Получилось: следующая поломка начнёт счёт заново. */
    public void succeeded() {
        backoff.reset();
    }

    /** Строка для экрана ожидания: «шифрованный вход, попытка 2 из 4». */
    public String describe() {
        Step s = current();
        if (s == null)
            return "все способы перебраны";
        return s.label() + ", " + backoff.describe();
    }

    /** Чем кончилась последняя попытка. */
    public String lastFailure() {
        return lastFailure;
    }

    /** Готовый текст отказа для игрока. */
    public String diagnosis(RoomCode room, boolean hosting) {
        return ConnectDiagnosis.explain(lastFailure, current(), exhausted(), room, hosting);
    }
}

package com.mineclone.net.connect;

import com.mineclone.net.photon.PhotonCodes;

import java.util.Random;

/**
 * Код комнаты: шесть знаков, в первом — регион.
 *
 * <p>Раньше друзья договаривались о двух вещах сразу — о названии комнаты и о
 * регионе облака. Второе проговорить забывали, а по умолчанию стоял «Авто»:
 * сервер имён Photon отдаёт каждому <i>ближайший</i> мастер-сервер, и хозяин
 * из Москвы оказывался в {@code ru}, а друг из Берлина — в {@code eu}. Комнаты
 * Photon живут внутри региона, поэтому они просто не видели комнат друг друга:
 * название набрано верно, а ответ — «такой комнаты нет».
 *
 * <p>Код лечит это по построению. Регион едет <b>внутри</b> кода первым
 * знаком, и продиктовать код, не продиктовав регион, физически нельзя.
 *
 * <p>Алфавит — Crockford Base32: без {@code I}, {@code L}, {@code O} и
 * {@code U}. Первые три выброшены потому, что их путают с единицей и нулём, а
 * {@code U} — чтобы из случайных знаков не сложилось слово, которое неловко
 * диктовать. При разборе {@code O} читается как {@code 0}, {@code I} и
 * {@code L} — как {@code 1}: код, переписанный с экрана от руки, всё равно
 * войдёт.
 *
 * <p>Знак региона берётся из буквенной части алфавита ({@link #REGION_BASE}),
 * поэтому код всегда начинается с буквы: «A4K7M2» читается как код, а
 * «14K7M2» — как непонятно что.
 */
public record RoomCode(String region, String code) {

    /** Crockford Base32: тридцать два знака, ни одной пары-близнеца. */
    public static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    /** С какого места алфавита начинаются буквы — там и живут регионы. */
    public static final int REGION_BASE = 10;

    /** Длина кода вместе со знаком региона. */
    public static final int LENGTH = 6;

    public RoomCode {
        region = region == null ? "" : region.trim();
        code = code == null ? "" : code.trim().toUpperCase();
    }

    /**
     * Выдать новый код для региона.
     *
     * <p>Регион обязан быть настоящим: «Авто» — это ещё не регион, а обещание
     * его выбрать. Выбирает {@link RegionProbe}, и только после этого сюда
     * приходит конкретная строка.
     */
    public static RoomCode generate(String region, Random random) {
        int idx = regionIndex(region);
        if (idx < 0)
            throw new IllegalArgumentException("код комнаты требует региона, а не «Авто»: " + region);
        StringBuilder sb = new StringBuilder(LENGTH);
        sb.append(ALPHABET.charAt(REGION_BASE + idx - 1));
        for (int i = 1; i < LENGTH; i++)
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        return new RoomCode(region, sb.toString());
    }

    /**
     * Разобрать то, что набрал игрок.
     *
     * <p>Терпимо ко всему, что случается при переписывании от руки: регистр,
     * дефисы и пробелы, {@code O} вместо нуля, {@code I} и {@code l} вместо
     * единицы. Не терпимо к длине и к неизвестному региону — там уже не
     * опечатка, а другой код.
     *
     * @return разобранный код или {@code null}, если это не код
     */
    public static RoomCode parse(String text) {
        String norm = normalize(text);
        if (norm.length() != LENGTH)
            return null;
        String region = regionOf(norm.charAt(0));
        if (region.isEmpty())
            return null;
        return new RoomCode(region, norm);
    }

    /** Похоже ли это вообще на код: столько знаков и известный регион. */
    public static boolean looksLikeCode(String text) {
        return parse(text) != null;
    }

    /**
     * Привести набранное к алфавиту кода.
     *
     * <p>Длину не трогает нарочно. Обрезать здесь было бы удобно полю ввода и
     * опасно разбору: код на знак длиннее — это чужой код, а не опечатка, и
     * молча отбросив хвост, игра увела бы игрока в комнату, которой он не
     * называл. Поле ввода режет длину само — {@link #typed}.
     */
    public static String normalize(String text) {
        if (text == null)
            return "";
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < text.length(); i++) {
            char c = Character.toUpperCase(text.charAt(i));
            switch (c) {
                case 'O' -> sb.append('0');
                case 'I', 'L' -> sb.append('1');
                default -> {
                    // Всё, чего нет в алфавите, — разделитель для глаза
                    // (дефис, пробел) или мусор: и то и другое просто
                    // выбрасывается.
                    if (ALPHABET.indexOf(c) >= 0)
                        sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    /**
     * То, что показывать в поле ввода, пока игрок печатает.
     *
     * <p>Здесь обрезать можно и нужно: лишние знаки в поле просто не
     * помещаются, и игрок видит это сразу, а не узнаёт из отказа.
     */
    public static String typed(String text) {
        String norm = normalize(text);
        return norm.length() > LENGTH ? norm.substring(0, LENGTH) : norm;
    }

    /** Номер региона в {@link PhotonCodes#REGIONS}; -1 — «Авто» или чужой. */
    public static int regionIndex(String region) {
        if (region == null || region.isBlank())
            return -1;
        String r = region.trim();
        for (int i = 1; i < PhotonCodes.REGIONS.length; i++)
            if (PhotonCodes.REGIONS[i].equals(r))
                return i;
        return -1;
    }

    /** Регион по первому знаку кода; пустая строка — знак не про регион. */
    public static String regionOf(char first) {
        int pos = ALPHABET.indexOf(Character.toUpperCase(first));
        if (pos < REGION_BASE)
            return "";
        int idx = pos - REGION_BASE + 1;
        if (idx >= PhotonCodes.REGIONS.length)
            return "";
        return PhotonCodes.REGIONS[idx];
    }

    /**
     * Имя комнаты у Photon.
     *
     * <p>Это сам код. Так список комнат в лобби показывает ровно те строки,
     * которые игроки диктуют друг другу, — иначе рядом с кодом пришлось бы
     * держать второе, внутреннее имя и следить, чтобы они не разошлись.
     */
    public String roomName() {
        return code;
    }

    /** Код для показа: {@code A4K-7M2}. Дефис только для глаза. */
    public String pretty() {
        if (code.length() != LENGTH)
            return code;
        return code.substring(0, 3) + "-" + code.substring(3);
    }

    /** Подпись региона по-русски: «Европа», «США, восток». */
    public String regionLabel() {
        return PhotonCodes.regionLabel(region);
    }

    @Override
    public String toString() {
        return pretty() + " (" + regionLabel() + ")";
    }
}

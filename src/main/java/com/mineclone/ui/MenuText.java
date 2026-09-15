package com.mineclone.ui;

import com.mineclone.world.NightSky;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Подписи меню: числа с правильным склонением, размеры, даты.
 *
 * <p>Отдельно от экранов и без GL: «21 чанков» или «вчера» у мира, открытого
 * сегодня, — ошибки, которые видно только глазами, поэтому они под тестами.
 */
public final class MenuText {

    private MenuText() {
    }

    /** Число со словом в нужной форме: 1 чанк, 2 чанка, 5 чанков. */
    public static String count(long n, String one, String few, String many) {
        return n + " " + plural(n, one, few, many);
    }

    public static String plural(long n, String one, String few, String many) {
        long a = Math.abs(n) % 100;
        long b = a % 10;
        if (a >= 11 && a <= 14)
            return many;
        if (b == 1)
            return one;
        if (b >= 2 && b <= 4)
            return few;
        return many;
    }

    public static String fileSize(long bytes) {
        if (bytes < 1024)
            return bytes + " Б";
        if (bytes < 1024L * 1024)
            return Math.round(bytes / 1024.0) + " КБ";
        if (bytes < 1024L * 1024 * 1024)
            return oneDecimal(bytes / (1024.0 * 1024)) + " МБ";
        return oneDecimal(bytes / (1024.0 * 1024 * 1024)) + " ГБ";
    }

    private static String oneDecimal(double v) {
        return String.format(Locale.ROOT, "%.1f", v).replace('.', ',');
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /** Когда в мир заходили: «сегодня, 14:38», «вчера, 09:12» или дата. */
    public static String lastPlayed(long millis, long now, ZoneId zone) {
        if (millis <= 0L)
            return "ещё не открывали";
        LocalDateTime at = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone);
        LocalDate today = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone).toLocalDate();
        if (at.toLocalDate().equals(today))
            return "сегодня, " + at.format(TIME);
        if (at.toLocalDate().equals(today.minusDays(1)))
            return "вчера, " + at.format(TIME);
        return at.format(DATE);
    }

    /** Номер игровых суток с единицы — так его и считают игроки. */
    public static String gameDay(float timeOfDay) {
        return "День " + (NightSky.dayIndex(timeOfDay) + 1);
    }
}

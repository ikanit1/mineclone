package com.mineclone.ui;

import com.mineclone.world.GameMode;

import java.util.Collection;
import java.util.function.LongSupplier;

/** Что игрок выбрал на экране создания мира. */
public final class WorldSettings {
    /** Префикс имени по умолчанию: «Мир 1», «Мир 2»… */
    public static final String DEFAULT_PREFIX = "Мир ";

    public final String name;
    public final long seed;
    public final GameMode mode;

    public WorldSettings(String name, long seed, GameMode mode) {
        this.name = name;
        this.seed = seed;
        this.mode = mode;
    }

    /**
     * Сид из текста поля. Пустое поле — случайный мир; число — ровно этот сид;
     * любой другой текст — его хеш, чтобы «остров» всегда давал один и тот же
     * мир. Число, не влезающее в long, тоже хешируется, а не молча обрезается.
     */
    public static long parseSeed(String text, LongSupplier random) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty())
            return random.getAsLong();
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            return t.hashCode();
        }
    }

    /** «Мир N» с наименьшим свободным N. */
    public static String defaultName(Collection<String> existing) {
        int n = 1;
        while (existing.contains(DEFAULT_PREFIX + n))
            n++;
        return DEFAULT_PREFIX + n;
    }
}

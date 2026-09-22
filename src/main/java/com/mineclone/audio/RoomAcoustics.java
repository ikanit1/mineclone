package com.mineclone.audio;

/**
 * Во что превращается замеренное пространство: влажность, хвост, глухота.
 *
 * Отдельно от {@link SoundEngine}, потому что там эти числа стоят за вызовами
 * OpenAL и проверить их можно было только на слух. Здесь это чистая
 * арифметика, и «комната звучит короче пещеры» становится обычным тестом.
 *
 * Разделение по источникам жёсткое и в этом весь смысл класса:
 * <ul>
 *   <li>замкнутость → сколько звука возвращается ({@link #sendGain},
 *       {@link #wetGain});</li>
 *   <li>размер → как долго он затухает и насколько теряет верх
 *       ({@link #decayTime}, {@link #dampHF}).</li>
 * </ul>
 */
public final class RoomAcoustics {

    /** Хвост в самом тесном закутке, секунды. */
    public static final float DECAY_MIN = 0.25f;
    /** Насколько хвост удлиняется к самой большой пещере. */
    public static final float DECAY_SPAN = 3.5f;
    /** Сколько сигнала уходит в эффект в полностью замкнутом месте. */
    public static final float SEND_MAX = 0.55f;
    /** Влажность самого эффекта: от почти сухого до заметного. */
    public static final float WET_MIN = 0.15f, WET_SPAN = 0.18f;
    /** Верх в маленькой комнате и сколько его съедает большой каменный объём. */
    public static final float DAMP_MAX = 0.92f, DAMP_SPAN = 0.55f;

    private RoomAcoustics() {}

    /**
     * Время затухания по размеру помещения.
     *
     * Растёт вместе с объёмом — раньше росло против него, и деревянный дом
     * звучал как собор.
     */
    public static float decayTime(float size) {
        return DECAY_MIN + norm(size) * DECAY_SPAN;
    }

    /** Сколько сигнала источники шлют в эхо. Ноль под открытым небом. */
    public static float sendGain(float closed) {
        return clamp(closed) * SEND_MAX;
    }

    /** Влажность самого эффекта. */
    public static float wetGain(float closed) {
        return WET_MIN + clamp(closed) * WET_SPAN;
    }

    /**
     * Сколько верха остаётся в хвосте. Большой каменный объём глушит высокие
     * частоты, тесная комната — почти нет.
     */
    public static float dampHF(float size) {
        return DAMP_MAX - norm(size) * DAMP_SPAN;
    }

    /** Размер в долях предельной дистанции зонда. */
    private static float norm(float size) {
        return clamp(size / AcousticProbe.MAX_DISTANCE);
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}

package com.mineclone.audio;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Трек каталога: где лежит, на что откликается и насколько его приглушить.
 *
 * @param gainDb поправка громкости до уровня самого тихого трека, всегда ≤ 0
 */
public record MusicTrack(String id, String path, Set<MusicMood> moods, float gainDb) {

    public MusicTrack {
        moods = Collections.unmodifiableSet(moods.isEmpty()
                ? EnumSet.noneOf(MusicMood.class) : EnumSet.copyOf(moods));
    }

    public boolean has(MusicMood mood) {
        return moods.contains(mood);
    }

    /** Поправка громкости множителем. */
    public float gain() {
        return (float) Math.pow(10.0, gainDb / 20.0);
    }
}

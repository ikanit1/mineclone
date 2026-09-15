package com.mineclone.audio;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Каталог музыки: что лежит в assets/music и на что каждый трек откликается.
 *
 * Роли расставлены по замеру ({@code tools/AnalyzeMusic.java}) и названиям.
 * Файл, которого нет в таблице, всё равно играет — как дневной трек и трек
 * меню: новая песня в папке звучит без правки кода, но не лезет ни в пещеры,
 * ни в бой, где ей может быть не место.
 */
public final class MusicLibrary {

    /** Строка таблицы каталога. */
    public record Entry(String id, float gainDb, MusicMood... moods) {
    }

    /**
     * Поправки — до уровня самого тихого трека по интегральной громкости:
     * Deep Pressure −15,5 LUFS, Fading into Warmth −15,0, Svetloe Pianino
     * −14,9, Walking over −14,5, Warm Moog Entry −13,8, Weightless Lullaby
     * −13,7, Nocturnal Drift −13,1. Только вниз: пики у фортепиано уже
     * упираются в 0 дБ.
     */
    public static final List<Entry> CATALOG = List.of(
            new Entry("Svetloe Pianino", -0.6f,
                    MusicMood.MENU, MusicMood.DAY, MusicMood.BUILD),
            new Entry("Warm Moog Entry", -1.7f,
                    MusicMood.MENU, MusicMood.DAWN, MusicMood.EXPLORE, MusicMood.FLIGHT),
            new Entry("Walking over", -0.9f,
                    MusicMood.EXPLORE, MusicMood.DAY, MusicMood.DUSK),
            new Entry("Fading into Warmth", -0.5f,
                    MusicMood.MENU, MusicMood.DUSK, MusicMood.HOME, MusicMood.BUILD),
            new Entry("Weightless Lullaby", -1.7f,
                    MusicMood.MENU, MusicMood.NIGHT, MusicMood.HOME, MusicMood.FLIGHT),
            new Entry("Nocturnal Drift", -2.4f,
                    MusicMood.NIGHT, MusicMood.EXPLORE, MusicMood.CAVE),
            new Entry("Deep Pressure", 0f,
                    MusicMood.CAVE, MusicMood.DANGER));

    /** Настроения трека, которого нет в таблице. */
    public static final Set<MusicMood> UNKNOWN_MOODS = EnumSet.of(MusicMood.MENU, MusicMood.DAY);

    private final List<MusicTrack> tracks;

    public MusicLibrary(List<MusicTrack> tracks) {
        this.tracks = List.copyOf(tracks);
    }

    /** Все MP3 из папки, по имени файла. Нет папки — пустой каталог. */
    public static MusicLibrary scan(File dir) {
        File[] files = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".mp3"));
        List<MusicTrack> out = new ArrayList<>();
        if (files != null) {
            Arrays.sort(files);
            for (File f : files) {
                String name = f.getName();
                out.add(track(name.substring(0, name.length() - 4), f.getAbsolutePath()));
            }
        }
        return new MusicLibrary(out);
    }

    /** Трек по имени файла: роль из таблицы или роль по умолчанию. */
    public static MusicTrack track(String id, String path) {
        for (Entry e : CATALOG)
            if (e.id().equalsIgnoreCase(id))
                return new MusicTrack(id, path, EnumSet.copyOf(Arrays.asList(e.moods())), e.gainDb());
        return new MusicTrack(id, path, UNKNOWN_MOODS, 0f);
    }

    public List<MusicTrack> tracks() {
        return tracks;
    }

    public boolean isEmpty() {
        return tracks.isEmpty();
    }

    /** Трек по id или null. */
    public MusicTrack byId(String id) {
        for (MusicTrack t : tracks)
            if (t.id().equals(id))
                return t;
        return null;
    }
}

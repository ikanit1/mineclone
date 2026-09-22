package com.mineclone.audio;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Что видит музыкальный режиссёр в этом кадре.
 *
 * Флаги мгновенные, а не накопленные: сколько игрок уже под землёй или в бою,
 * считает сам режиссёр. Поэтому ситуацию легко собрать в тесте руками.
 *
 * @param dayPart     одно из {@code DAWN, DAY, DUSK, NIGHT}
 * @param underground голова под толщей породы
 * @param sheltered   под крышей у огня — «дом», если сейчас вечер или ночь
 * @param danger      враг идёт на игрока
 * @param exploring   игрок уходит далеко
 * @param building    игрок строит
 * @param flying      полёт в творческом режиме
 * @param underwater  голова под водой
 * @param region      край под ногами, или null вне мира
 */
public record MusicSituation(
        Scene scene,
        boolean paused,
        MusicMood dayPart,
        boolean underground,
        boolean sheltered,
        boolean danger,
        boolean exploring,
        boolean building,
        boolean flying,
        boolean underwater,
        MusicMood region) {

    /** Прежняя форма без края: край неизвестен. */
    public MusicSituation(Scene scene, boolean paused, MusicMood dayPart, boolean underground,
                          boolean sheltered, boolean danger, boolean exploring, boolean building,
                          boolean flying, boolean underwater) {
        this(scene, paused, dayPart, underground, sheltered, danger, exploring, building,
                flying, underwater, null);
    }

    public enum Scene {
        /** Меню и загрузка. */
        MENU,
        /** Мир: игра, окна инвентаря, пауза. */
        WORLD,
        /** Экран смерти. */
        DEAD
    }

    /** Веса позиций в списке предпочтений: первое настроение важнее остальных. */
    public static final int[] PREFER_WEIGHTS = { 3, 2, 1, 1 };

    /** Рассвет по фазе суток: сколько радиан до восхода и после него. */
    public static final float DAWN_BEFORE = 0.2f, DAWN_AFTER = 0.5f;
    /** Закат: сколько радиан до захода и после него. */
    public static final float DUSK_BEFORE = 0.5f, DUSK_AFTER = 0.2f;

    /** Экран вне мира; время суток — фона меню. */
    public static MusicSituation menu(MusicMood dayPart) {
        return new MusicSituation(Scene.MENU, false, dayPart,
                false, false, false, false, false, false, false);
    }

    /**
     * Часть суток по игровому времени: {@code daylight = sin(gameTime)},
     * восход при нуле. Рассвет и закат при нынешнем темпе суток длятся
     * примерно по 140 секунд.
     */
    public static MusicMood dayPart(float gameTime) {
        double twoPi = Math.PI * 2;
        double t = ((gameTime % twoPi) + twoPi) % twoPi;
        if (t >= twoPi - DAWN_BEFORE || t < DAWN_AFTER)
            return MusicMood.DAWN;
        if (t >= Math.PI - DUSK_BEFORE && t < Math.PI + DUSK_AFTER)
            return MusicMood.DUSK;
        return t < Math.PI ? MusicMood.DAY : MusicMood.NIGHT;
    }

    /** Укрытие у огня — «дом» только вечером и ночью: днём в доме просто день. */
    public boolean home() {
        return sheltered && !underground && (dayPart == MusicMood.DUSK || dayPart == MusicMood.NIGHT);
    }

    /**
     * Настроения, по которым выбирается новый трек, — по убыванию важности:
     * опасность, потом место или занятие, потом время суток.
     */
    public List<MusicMood> prefer() {
        List<MusicMood> out = new ArrayList<>(4);
        switch (scene) {
            case MENU -> {
                out.add(MusicMood.MENU);
                out.add(dayPart);
            }
            case DEAD -> {
            }
            case WORLD -> {
                if (danger)
                    out.add(MusicMood.DANGER);
                if (underground) {
                    out.add(MusicMood.CAVE);
                } else {
                    if (flying)
                        out.add(MusicMood.FLIGHT);
                    else if (building)
                        out.add(MusicMood.BUILD);
                    else if (exploring)
                        out.add(MusicMood.EXPLORE);
                    else if (home())
                        out.add(MusicMood.HOME);
                    // Край важнее времени суток, но слабее занятия: игрок,
                    // который строит, слушает стройку и в лесу, и в пустыне.
                    if (region != null)
                        out.add(region);
                    out.add(dayPart);
                }
            }
        }
        return out;
    }

    /**
     * Настроения, при которых уже играющий трек звучит дальше.
     *
     * Набор шире, чем {@link #prefer()}: соседние части суток терпимы, иначе
     * дневной трек обрывался бы на первой минуте заката.
     */
    public Set<MusicMood> tolerate() {
        EnumSet<MusicMood> out = EnumSet.noneOf(MusicMood.class);
        switch (scene) {
            case MENU -> out.add(MusicMood.MENU);
            case DEAD -> {
            }
            case WORLD -> {
                if (underground) {
                    out.add(MusicMood.CAVE);
                    out.add(MusicMood.NIGHT);
                    out.add(MusicMood.DANGER);
                    out.add(MusicMood.EXPLORE);
                } else {
                    out.add(dayPart);
                    out.addAll(neighbours(dayPart));
                    if (sheltered)
                        out.add(MusicMood.HOME);
                    // Терпим и соседние края: игрок, вышедший из леса в
                    // саванну, не должен слышать обрыв на границе.
                    if (region != null) {
                        out.add(region);
                        out.addAll(REGIONS);
                    }
                }
                if (exploring)
                    out.add(MusicMood.EXPLORE);
                if (building)
                    out.add(MusicMood.BUILD);
                if (flying)
                    out.add(MusicMood.FLIGHT);
                if (danger)
                    out.add(MusicMood.DANGER);
            }
        }
        return out;
    }

    /** Все края разом: любой из них терпим, пока игрок на поверхности. */
    public static final Set<MusicMood> REGIONS = EnumSet.of(MusicMood.WOODS, MusicMood.ARID,
            MusicMood.FROZEN, MusicMood.WETLAND, MusicMood.SEA, MusicMood.ASHEN);

    /** Соседние части суток: рассвет граничит с ночью и днём и так по кругу. */
    public static Set<MusicMood> neighbours(MusicMood part) {
        return switch (part) {
            case DAWN -> EnumSet.of(MusicMood.NIGHT, MusicMood.DAY);
            case DAY -> EnumSet.of(MusicMood.DAWN, MusicMood.DUSK);
            case DUSK -> EnumSet.of(MusicMood.DAY, MusicMood.NIGHT);
            case NIGHT -> EnumSet.of(MusicMood.DUSK, MusicMood.DAWN);
            default -> EnumSet.noneOf(MusicMood.class);
        };
    }
}

package com.mineclone.audio;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Музыкальный режиссёр: какой трек, когда начать и когда погасить.
 *
 * Чистая логика — ни OpenAL, ни файлов. На входе ситуация кадра и то, что
 * сейчас играет; на выходе команды плееру. Случайность только через
 * переданный {@link Random}, поэтому часы игры моделируются в тестах целиком:
 * доля времени с музыкой, реакция на бой, моменты — это и есть вся механика, и
 * проверять её надо числами, а не на слух.
 *
 * Ритм как в Minecraft: трек, потом тишина. Ситуация решает, что играть;
 * моменты — рассвет, пещера, пробуждение — иногда начинают раньше срока.
 */
public final class MusicDirector {

    /** Что режиссёр видит у плеера. */
    public interface Playback {
        /** Есть устройство звука и плеер жив. */
        boolean available();

        /** Ползунок музыки не на нуле. */
        boolean enabled();

        /** Играющий (не догорающий) трек или null. */
        String currentTrackId();

        /** Трек не удалось декодировать — больше не выбираем. */
        boolean failed(String trackId);
    }

    /** Что режиссёр велит плееру. */
    public interface Output {
        void play(MusicTrack track, float fadeInSeconds);

        /** Погасить играющий трек. */
        void fadeOut(float seconds);

        /** Целевое приглушение 0..1; плеер доводит до него плавно. */
        void setDuck(float level);

        /** Глухой звук из-под воды. */
        void setMuffled(boolean muffled);
    }

    /** Моменты, которые иногда запускают трек раньше, чем кончится тишина. */
    public enum Moment {
        ARRIVE(0.60f, 5f, 10f),
        WAKE(0.60f, 2f, 4f),
        RESPAWN(0.50f, 3f, 6f),
        CAVE(0.40f, 2f, 5f),
        DAWN(0.35f, 2f, 5f),
        DUSK(0.35f, 2f, 5f),
        HOME(0.35f, 2f, 5f),
        EXPLORE(0.30f, 2f, 5f),
        BUILD(0.30f, 2f, 5f),
        FLIGHT(0.30f, 2f, 5f),
        DANGER(0.25f, 1f, 2f);

        public final float chance, delayMin, delayMax;

        Moment(float chance, float delayMin, float delayMax) {
            this.chance = chance;
            this.delayMin = delayMin;
            this.delayMax = delayMax;
        }

        /** Смена сцены, а не очередной трек: правило тишины после трека её не держит. */
        public boolean sceneChange() {
            return this == ARRIVE || this == WAKE || this == RESPAWN;
        }
    }

    // ---- меню ----
    public static final float MENU_FIRST_MIN = 1f, MENU_FIRST_MAX = 2f;
    public static final float MENU_GAP_MIN = 4f, MENU_GAP_MAX = 10f;
    public static final float MENU_RETURN_MIN = 2f, MENU_RETURN_MAX = 3f;

    // ---- тишина в мире ----
    /** После того как трек доиграл. */
    public static final float GAP_MIN = 150f, GAP_MAX = 420f;
    /** После того как трек погашен несовпадением или боем. */
    public static final float STOP_GAP_MIN = 60f, STOP_GAP_MAX = 150f;
    /** После входа в мир, если момент «прибытие» не сработал. */
    public static final float ARRIVE_GAP_MIN = 60f, ARRIVE_GAP_MAX = 180f;
    /** Моменты молчат, пока после трека в мире не прошло столько. */
    public static final float MIN_SILENCE = 45f;
    /** Жребий одного момента — не чаще. */
    public static final float MOMENT_COOLDOWN = 480f;
    /** Пул оказался пуст — повторить выбор через столько. */
    public static final float RETRY = 20f;

    // ---- сколько флаг держится, прежде чем момент бросает жребий ----
    public static final float CAVE_HOLD = 15f, HOME_HOLD = 10f, EXPLORE_HOLD = 5f;
    public static final float BUILD_HOLD = 5f, FLIGHT_HOLD = 10f, DANGER_HOLD = 6f;

    // ---- реакция ----
    public static final float FADE_IN = 1f;
    public static final float MISMATCH_GRACE = 20f, MISMATCH_FADE = 5f;
    public static final float DANGER_DUCK_DELAY = 1.5f, DANGER_DUCK = 0.35f;
    public static final float DANGER_STOP = 12f, DANGER_FADE = 3f;
    public static final float DEATH_FADE = 2f, MENU_EXIT_FADE = 1.5f, ARRIVE_FADE = 3f;
    public static final float WAKE_FADE = 2f, DISABLED_FADE = 0.5f, NEXT_FADE = 1.5f;
    public static final float PAUSE_DUCK = 0.5f, WATER_DUCK = 0.7f;
    /** Во сколько раз реже выпадают недавние треки и сколько их помнить. */
    public static final float RECENT_WEIGHT = 0.35f;
    public static final int RECENT_COUNT = 3;

    private final MusicLibrary library;
    private final Random rnd;

    private float clock;
    private MusicSituation.Scene scene;
    private MusicMood dayPart;
    private List<MusicMood> lastPrefer = List.of();

    private String playing;
    private MusicTrack playingTrack;
    private final Deque<String> recent = new ArrayDeque<>();
    private float gap;
    private float lastEnd = -1e9f;
    private Moment pending;
    private float pendingDelay;
    private final EnumMap<Moment, Float> lastRoll = new EnumMap<>(Moment.class);
    private boolean wake;
    private boolean nextRequested;

    private float undergroundFor, homeFor, exploringFor, buildingFor, flyingFor, dangerFor;
    private float mismatchFor;
    private boolean ducked;

    public MusicDirector(MusicLibrary library, Random rnd) {
        this.library = library;
        this.rnd = rnd;
    }

    /** Игрок проснулся: ночной трек гаснет, утренний может начаться. */
    public void onWake() {
        wake = true;
    }

    /** {@code /music next}: погасить играющий и начать подходящий сейчас. */
    public void requestNext() {
        nextRequested = true;
    }

    public void update(float dt, MusicSituation s, Playback playback, Output out) {
        clock += dt;
        if (!playback.available() || library.isEmpty())
            return;
        if (playing != null && !playing.equals(playback.currentTrackId()))
            trackEnded();

        MusicSituation.Scene prevScene = scene;
        MusicMood prevPart = dayPart;
        scene = s.scene();
        dayPart = s.dayPart();
        lastPrefer = s.prefer();

        if (!playback.enabled()) {
            if (playing != null)
                stop(out, DISABLED_FADE, scene != MusicSituation.Scene.MENU);
            pending = null;
            wake = false;
            nextRequested = false;
            mix(s, out);
            return;
        }

        if (prevScene == null) {
            gap = scene == MusicSituation.Scene.MENU
                    ? range(MENU_FIRST_MIN, MENU_FIRST_MAX) : range(ARRIVE_GAP_MIN, ARRIVE_GAP_MAX);
        } else if (prevScene != scene) {
            changeScene(prevScene, s, out);
            prevPart = dayPart;
        }

        if (wake) {
            wake = false;
            if (scene == MusicSituation.Scene.WORLD) {
                if (playingTrack != null && !playingTrack.has(MusicMood.DAWN) && !playingTrack.has(MusicMood.DAY))
                    stop(out, WAKE_FADE, true);
                if (playing == null) {
                    pending = null;
                    roll(Moment.WAKE);
                }
                // Скачок времени после сна — это пробуждение, а не рассвет.
                prevPart = dayPart;
            }
        }

        if (nextRequested) {
            nextRequested = false;
            if (scene != MusicSituation.Scene.DEAD) {
                if (playing != null)
                    stop(out, NEXT_FADE, scene == MusicSituation.Scene.WORLD);
                pending = null;
                start(s, lastPrefer, playback, out);
            }
        }

        if (scene == MusicSituation.Scene.WORLD)
            updateWorld(dt, s, prevPart, out);

        if (scene != MusicSituation.Scene.DEAD && playing == null) {
            if (pending != null) {
                pendingDelay -= dt;
                if (pendingDelay <= 0f) {
                    Moment m = pending;
                    pending = null;
                    start(s, preferFor(m, s), playback, out);
                }
            } else {
                gap -= dt;
                if (gap <= 0f && !start(s, lastPrefer, playback, out))
                    gap = RETRY;
            }
        }
        mix(s, out);
    }

    private void updateWorld(float dt, MusicSituation s, MusicMood prevPart, Output out) {
        float under = undergroundFor, home = homeFor, explore = exploringFor;
        float build = buildingFor, fly = flyingFor, threat = dangerFor;
        if (!s.paused()) {
            undergroundFor = s.underground() ? undergroundFor + dt : 0f;
            homeFor = s.home() ? homeFor + dt : 0f;
            exploringFor = s.exploring() ? exploringFor + dt : 0f;
            buildingFor = s.building() ? buildingFor + dt : 0f;
            flyingFor = s.flying() ? flyingFor + dt : 0f;
            dangerFor = s.danger() ? dangerFor + dt : 0f;
        }
        if (playingTrack != null)
            judge(dt, s, out);
        if (playing != null || pending != null || s.paused())
            return;
        if (prevPart != dayPart && dayPart == MusicMood.DAWN)
            roll(Moment.DAWN);
        else if (prevPart != dayPart && dayPart == MusicMood.DUSK)
            roll(Moment.DUSK);
        rollIfCrossed(threat, dangerFor, DANGER_HOLD, Moment.DANGER);
        rollIfCrossed(under, undergroundFor, CAVE_HOLD, Moment.CAVE);
        rollIfCrossed(home, homeFor, HOME_HOLD, Moment.HOME);
        rollIfCrossed(fly, flyingFor, FLIGHT_HOLD, Moment.FLIGHT);
        rollIfCrossed(build, buildingFor, BUILD_HOLD, Moment.BUILD);
        rollIfCrossed(explore, exploringFor, EXPLORE_HOLD, Moment.EXPLORE);
    }

    /** Играющий трек в мире: приглушить в бою, погасить, если не к месту. */
    private void judge(float dt, MusicSituation s, Output out) {
        boolean exposed = s.danger() && !dangerSafe(playingTrack);
        ducked = exposed && dangerFor >= DANGER_DUCK_DELAY;
        if (exposed && dangerFor >= DANGER_STOP) {
            stop(out, DANGER_FADE, true);
            return;
        }
        if (tolerated(playingTrack, s)) {
            mismatchFor = 0f;
            return;
        }
        if (!s.paused())
            mismatchFor += dt;
        if (mismatchFor >= MISMATCH_GRACE)
            stop(out, MISMATCH_FADE, true);
    }

    private void changeScene(MusicSituation.Scene prev, MusicSituation s, Output out) {
        pending = null;
        ducked = false;
        undergroundFor = homeFor = exploringFor = buildingFor = flyingFor = dangerFor = 0f;
        switch (scene) {
            case MENU -> {
                if (playingTrack != null && !playingTrack.has(MusicMood.MENU))
                    stop(out, MENU_EXIT_FADE, false);
                if (playing == null)
                    gap = range(MENU_RETURN_MIN, MENU_RETURN_MAX);
            }
            case DEAD -> {
                if (playing != null)
                    stop(out, DEATH_FADE, true);
            }
            case WORLD -> {
                if (prev == MusicSituation.Scene.MENU) {
                    // Трек меню доигрывает в мире, если миру подходит.
                    if (playingTrack != null && !tolerated(playingTrack, s))
                        stop(out, ARRIVE_FADE, false);
                    if (playing == null && !roll(Moment.ARRIVE))
                        gap = range(ARRIVE_GAP_MIN, ARRIVE_GAP_MAX);
                } else if (prev == MusicSituation.Scene.DEAD && playing == null) {
                    roll(Moment.RESPAWN);
                }
            }
        }
    }

    private void rollIfCrossed(float before, float now, float hold, Moment m) {
        if (pending == null && before < hold && now >= hold)
            roll(m);
    }

    /** Жребий момента; true — трек начнётся после задержки. */
    private boolean roll(Moment m) {
        Float last = lastRoll.get(m);
        if (last != null && clock - last < MOMENT_COOLDOWN)
            return false;
        if (!m.sceneChange() && clock - lastEnd < MIN_SILENCE)
            return false;
        lastRoll.put(m, clock);
        if (rnd.nextFloat() >= m.chance)
            return false;
        pending = m;
        pendingDelay = range(m.delayMin, m.delayMax);
        return true;
    }

    private static List<MusicMood> preferFor(Moment m, MusicSituation s) {
        return switch (m) {
            case ARRIVE -> s.prefer();
            case WAKE, DAWN -> List.of(MusicMood.DAWN, MusicMood.DAY);
            case DUSK -> List.of(MusicMood.DUSK, MusicMood.NIGHT);
            case RESPAWN, HOME -> List.of(MusicMood.HOME, s.dayPart());
            case CAVE -> List.of(MusicMood.CAVE);
            case EXPLORE -> List.of(MusicMood.EXPLORE, s.dayPart());
            case BUILD -> List.of(MusicMood.BUILD, s.dayPart());
            case FLIGHT -> List.of(MusicMood.FLIGHT, s.dayPart());
            case DANGER -> List.of(MusicMood.DANGER);
        };
    }

    private boolean start(MusicSituation s, List<MusicMood> prefer, Playback playback, Output out) {
        MusicTrack t = pick(s, prefer, playback);
        if (t == null)
            return false;
        out.play(t, FADE_IN);
        playing = t.id();
        playingTrack = t;
        mismatchFor = 0f;
        ducked = false;
        recent.remove(t.id());
        recent.addFirst(t.id());
        while (recent.size() > RECENT_COUNT)
            recent.removeLast();
        return true;
    }

    /**
     * Первое настроение списка, у которого есть треки, — жёсткий фильтр;
     * остальные только сдвигают вес внутри пула.
     */
    MusicTrack pick(MusicSituation s, List<MusicMood> prefer, Playback playback) {
        boolean world = s.scene() == MusicSituation.Scene.WORLD;
        List<MusicTrack> usable = new ArrayList<>();
        for (MusicTrack t : library.tracks()) {
            if (playback.failed(t.id()))
                continue;
            if (world && (!tolerated(t, s) || (s.danger() && !dangerSafe(t))))
                continue;
            usable.add(t);
        }
        List<MusicTrack> pool = new ArrayList<>();
        for (MusicMood mood : prefer) {
            for (MusicTrack t : usable)
                if (t.has(mood))
                    pool.add(t);
            if (!pool.isEmpty())
                break;
        }
        if (pool.isEmpty() && s.scene() == MusicSituation.Scene.MENU)
            pool.addAll(usable);   // меню не молчит, даже если треки меню удалили
        if (pool.isEmpty())
            return null;
        String last = recent.peekFirst();
        if (pool.size() > 1 && last != null)
            pool.removeIf(t -> t.id().equals(last));
        float[] weight = new float[pool.size()];
        float total = 0f;
        for (int i = 0; i < pool.size(); i++) {
            MusicTrack t = pool.get(i);
            float score = 0f;
            for (int k = 0; k < prefer.size(); k++)
                if (t.has(prefer.get(k)))
                    score += MusicSituation.PREFER_WEIGHTS[Math.min(k, MusicSituation.PREFER_WEIGHTS.length - 1)];
            if (recent.contains(t.id()))
                score *= RECENT_WEIGHT;
            weight[i] = Math.max(0.01f, score);
            total += weight[i];
        }
        float r = rnd.nextFloat() * total;
        for (int i = 0; i < pool.size(); i++) {
            r -= weight[i];
            if (r <= 0f)
                return pool.get(i);
        }
        return pool.get(pool.size() - 1);
    }

    private static boolean tolerated(MusicTrack t, MusicSituation s) {
        for (MusicMood m : s.tolerate())
            if (t.has(m))
                return true;
        return false;
    }

    /** Трек, который бой не глушит: гул опасности и пещер. */
    private static boolean dangerSafe(MusicTrack t) {
        return t.has(MusicMood.DANGER) || t.has(MusicMood.CAVE);
    }

    /** @param inWorld трек звучал в мире — после него держится тишина моментов */
    private void stop(Output out, float fade, boolean inWorld) {
        out.fadeOut(fade);
        clearPlaying();
        if (inWorld) {
            lastEnd = clock;
            gap = range(STOP_GAP_MIN, STOP_GAP_MAX);
        } else {
            gap = range(MENU_GAP_MIN, MENU_GAP_MAX);
        }
    }

    /** Трек доиграл сам или не заиграл вовсе. */
    private void trackEnded() {
        clearPlaying();
        if (scene == MusicSituation.Scene.MENU) {
            gap = range(MENU_GAP_MIN, MENU_GAP_MAX);
        } else {
            lastEnd = clock;
            gap = range(GAP_MIN, GAP_MAX);
        }
    }

    private void clearPlaying() {
        playing = null;
        playingTrack = null;
        mismatchFor = 0f;
        ducked = false;
    }

    private void mix(MusicSituation s, Output out) {
        float duck = 1f;
        if (s.paused())
            duck *= PAUSE_DUCK;
        if (s.underwater())
            duck *= WATER_DUCK;
        if (ducked)
            duck *= DANGER_DUCK;
        out.setDuck(duck);
        out.setMuffled(s.underwater());
    }

    private float range(float lo, float hi) {
        return lo + rnd.nextFloat() * (hi - lo);
    }

    // ---- для F3, консоли и тестов ----------------------------------------------

    /** Строка F3: что играет и почему, или сколько до следующего трека. */
    public String status(float positionSeconds) {
        String why = lastPrefer.isEmpty() ? "" : " " + lastPrefer.toString().replace(",", "");
        if (playing != null)
            return playing + " " + clockText(positionSeconds) + why;
        if (scene == MusicSituation.Scene.DEAD)
            return "silence (dead)";
        if (pending != null)
            return "silence, " + pending.name().toLowerCase(Locale.ROOT) + " in " + clockText(pendingDelay) + why;
        return "silence, next in " + clockText(Math.max(0f, gap)) + why;
    }

    private static String clockText(float seconds) {
        int s = Math.max(0, (int) seconds);
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    public String playingId() {
        return playing;
    }

    public float gap() {
        return gap;
    }

    public Moment pendingMoment() {
        return pending;
    }

    public boolean ducked() {
        return ducked;
    }
}

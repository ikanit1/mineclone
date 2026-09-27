package com.mineclone;

import com.mineclone.audio.RainAmbience;
import com.mineclone.audio.SeamlessLoop;
import com.mineclone.audio.StormAmbience;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Петли дождя и ветра не должны «дышать» на каждом круге.
 *
 * Каждая из восьми записей {@code rain*.ogg} длится около двух секунд, входит
 * из тишины за 200 мс и уходит в тишину за 250 мс: это разовые реплики, а не
 * петли. Пока OpenAL крутил одну такую запись, на каждом круге громкость
 * проваливалась примерно на 30 дБ и тот же рисунок повторялся заново. Ветер
 * грозы — четыре стереозаписи по 7,6–11,4 с, каждая один порыв из тишины в
 * тишину: по кругу это 2–3 с затишья. Здесь громкость меряется по всему кругу —
 * стык конца с началом включён.
 */
final class SeamlessLoopTests {
    private static final int RATE = 48_000;
    /** Длины настоящих записей, секунды. */
    private static final double[] RAIN_SECONDS = { 1.973, 1.979, 2.011, 2.011, 2.059, 2.085, 2.165, 2.192 };
    private static final double[] WIND_SECONDS = { 11.409, 9.354, 11.255, 7.569 };

    static void runAll(NetworkTests.Runner r) {
        r.run("a bed of rain recordings keeps its loudness through every seam and the wrap", SeamlessLoopTests::flat);
        r.run("the bed lasts many seconds instead of repeating one two-second recording", SeamlessLoopTests::lasting);
        r.run("one recording alone still loops without a dip", SeamlessLoopTests::single);
        r.run("stitching the same recordings twice gives the same bed", SeamlessLoopTests::deterministic);
        r.run("silence and scraps too short to crossfade are left out", SeamlessLoopTests::degenerate);
        r.run("storm wind never falls into a lull at a seam or the wrap", SeamlessLoopTests::windWithoutLull);
        r.run("storm wind spans all its recordings instead of repeating one gust", SeamlessLoopTests::windLasting);
        r.run("storm wind keeps its gusts and its stereo width", SeamlessLoopTests::windCharacter);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static short[] rain(List<short[]> clips) {
        return SeamlessLoop.stitch(clips, 1, RATE, RainAmbience.CROSSFADE_SECONDS);
    }

    private static short[] wind() {
        List<short[]> clips = new ArrayList<>();
        for (int i = 0; i < WIND_SECONDS.length; i++) clips.add(windLike(i + 31, WIND_SECONDS[i]));
        return SeamlessLoop.stitch(clips, 2, RATE, StormAmbience.CROSSFADE_SECONDS);
    }

    /** Похожа на rain*.ogg: шум около 0,043 полной шкалы, вход 200 мс и выход 250 мс из тишины. */
    private static short[] rainLike(long seed, double seconds) {
        int n = (int) Math.round(seconds * RATE), in = RATE / 5, out = RATE / 4;
        Random rng = new Random(seed);
        short[] clip = new short[n];
        for (int i = 0; i < n; i++) {
            double g = 1;
            if (i < in) g = Math.pow(Math.sin(Math.PI / 2 * i / in), 2);
            if (i >= n - out) g = Math.min(g, Math.pow(Math.sin(Math.PI / 2 * (n - 1 - i) / out), 2));
            clip[i] = (short) Math.round(rng.nextGaussian() * 1400 * g);
        }
        return clip;
    }

    private static List<short[]> rainClips() {
        List<short[]> clips = new ArrayList<>();
        for (int i = 0; i < RAIN_SECONDS.length; i++) clips.add(rainLike(i + 1, RAIN_SECONDS[i]));
        return clips;
    }

    /**
     * Похожа на wind*.ogg: стерео с корреляцией каналов около 0,5; подъём из
     * тишины за 1 с, спад в тишину за 2 с, а между ними ровный фон и три порыва
     * на +3…+7 дБ — так выглядит огибающая настоящих записей по окнам 250 мс.
     */
    private static short[] windLike(long seed, double seconds) {
        int n = (int) Math.round(seconds * RATE), in = RATE, out = 2 * RATE;
        Random rng = new Random(seed);
        double[] gustAt = new double[3], gustGain = new double[3];
        for (int k = 0; k < 3; k++) {
            gustAt[k] = in + (k + 0.3 + 0.4 * rng.nextDouble()) * (n - in - out) / 3.0;
            gustGain[k] = Math.pow(10, (3 + 4 * rng.nextDouble()) / 20);
        }
        double halfWidth = 0.6 * RATE;
        short[] clip = new short[2 * n];
        for (int i = 0; i < n; i++) {
            double g = 1;
            for (int k = 0; k < 3; k++) {
                double x = (i - gustAt[k]) / halfWidth;
                if (Math.abs(x) < 1) g = Math.max(g, 1 + (gustGain[k] - 1) * Math.pow(Math.cos(Math.PI / 2 * x), 2));
            }
            if (i < in) g *= Math.pow(Math.sin(Math.PI / 2 * i / in), 2);
            if (i >= n - out) g *= Math.pow(Math.sin(Math.PI / 2 * (n - 1 - i) / out), 2);
            double shared = rng.nextGaussian();
            clip[2 * i] = (short) Math.round((shared + rng.nextGaussian()) * 180 * g);
            clip[2 * i + 1] = (short) Math.round((shared + rng.nextGaussian()) * 180 * g);
        }
        return clip;
    }

    /** Громкость окон по кругу петли (каналы усредняются по мощности); последние окна идут через стык. */
    private static double[] levels(short[] loop, int channels, double windowSeconds, double stepSeconds) {
        int frames = loop.length / channels, w = (int) (windowSeconds * RATE), step = (int) (stepSeconds * RATE);
        double[] level = new double[Math.max(1, frames / step)];
        for (int k = 0; k < level.length; k++) {
            double sum = 0;
            for (int i = 0; i < w; i++)
                for (int c = 0; c < channels; c++) {
                    double v = loop[((k * step + i) % frames) * channels + c];
                    sum += v * v;
                }
            level[k] = Math.sqrt(sum / (w * channels));
        }
        return level;
    }

    private static double median(double[] level) {
        double[] sorted = level.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static double db(double ratio) {
        return 20 * Math.log10(ratio + 1e-12);
    }

    /** Худшее отклонение громкости от медианы, дБ: окна 50 мс с шагом 25 мс по всему кругу. */
    private static double worstSwingDb(short[] loop) {
        double[] level = levels(loop, 1, 0.05, 0.025);
        double median = median(level), worst = 0;
        for (double l : level) worst = Math.max(worst, Math.abs(db(l / median)));
        return worst;
    }

    private static String seconds(short[] loop, int channels) {
        return String.format(Locale.ROOT, "%.2f s", loop.length / (double) channels / RATE);
    }

    private static void flat() {
        short[] loop = rain(rainClips());
        check(loop.length > 0, "no bed was built from eight recordings");
        double swing = worstSwingDb(loop);
        check(swing <= 1.5, String.format(Locale.ROOT, "loudness swings %.1f dB around a %s loop", swing, seconds(loop, 1)));
    }

    private static void lasting() {
        short[] loop = rain(rainClips());
        check(loop.length >= 10 * RATE, "the rain repeats every " + seconds(loop, 1));
    }

    private static void single() {
        short[] loop = rain(List.of(rainLike(11, 2.0)));
        check(loop.length >= RATE, "one recording left only " + seconds(loop, 1));
        double swing = worstSwingDb(loop);
        check(swing <= 1.5, String.format(Locale.ROOT, "a single recording swings %.1f dB per turn", swing));
    }

    private static void deterministic() {
        check(Arrays.equals(rain(rainClips()), rain(rainClips())), "the same recordings stitched into different beds");
        check(Arrays.equals(wind(), wind()), "the same wind stitched into different beds");
    }

    private static void degenerate() {
        check(rain(List.of()).length == 0, "no recordings must give no bed");
        check(rain(List.of(new short[2 * RATE])).length == 0, "a silent recording is not a bed");
        short[] loop = rain(List.of(rainLike(21, 0.15), rainLike(22, 2.0)));
        check(loop.length >= RATE && worstSwingDb(loop) <= 1.5,
                "a 150 ms scrap must be skipped and the rest still loop, got " + seconds(loop, 1));
    }

    /**
     * Порыв ветра по своей природе стихает, поэтому ровности, как у дождя, здесь
     * не требуется. Требуется другое: не проваливаться в затишье. Одна запись по
     * кругу уходила в полную тишину и треть круга держалась тише −6 дБ; сшитая
     * петля из настоящих записей — не тише −7,6 дБ и ниже −6 дБ лишь 4 % времени.
     */
    private static void windWithoutLull() {
        short[] loop = wind();
        check(loop.length > 0 && loop.length % 2 == 0, "no stereo bed was built from four wind recordings");
        double[] level = levels(loop, 2, 0.25, 0.125);
        double median = median(level), lowest = Double.MAX_VALUE;
        int quiet = 0;
        for (double l : level) {
            lowest = Math.min(lowest, db(l / median));
            if (db(l / median) < -6) quiet++;
        }
        check(lowest >= -10, String.format(Locale.ROOT, "wind falls %.1f dB into a lull around a %s loop", -lowest, seconds(loop, 2)));
        check(quiet <= level.length / 10, String.format(Locale.ROOT, "wind spends %d%% of its loop below -6 dB", 100 * quiet / level.length));
    }

    private static void windLasting() {
        short[] loop = wind();
        check(loop.length / 2 >= 20 * RATE, "storm wind repeats every " + seconds(loop, 2));
    }

    private static void windCharacter() {
        short[] loop = wind();
        double[] level = levels(loop, 2, 0.25, 0.125);
        double median = median(level), highest = 0;
        for (double l : level) highest = Math.max(highest, db(l / median));
        check(highest >= 3, String.format(Locale.ROOT, "gusts flattened: the loudest window is only %.1f dB over the median", highest));
        double lr = 0, ll = 0, rr = 0;
        for (int i = 0; i + 1 < loop.length; i += 2) {
            lr += (double) loop[i] * loop[i + 1];
            ll += (double) loop[i] * loop[i];
            rr += (double) loop[i + 1] * loop[i + 1];
        }
        double correlation = lr / Math.sqrt(ll * rr);
        check(correlation < 0.9, String.format(Locale.ROOT, "stereo collapsed to mono: channel correlation %.2f", correlation));
    }
}

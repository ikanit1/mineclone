package com.mineclone.audio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Склеивает короткие записи в одну петлю без провала громкости на стыках.
 *
 * <p>Записи дождя — разовые реплики по две секунды: каждая входит из тишины и
 * уходит в тишину. Закольцованная одна такая запись проваливалась на каждом
 * круге примерно на 30 дБ и повторяла свой рисунок каждые две секунды. Здесь у
 * каждой записи отрезаются вход и выход, тела выравниваются по громкости и
 * сшиваются равномощными кроссфейдами, причём последний шов уходит обратно в
 * начало — {@code AL_LOOPING} крутит результат без стыка. Только арифметика, без
 * OpenAL, поэтому ровность петли проверяется обычным тестом.
 */
public final class SeamlessLoop {
    /** Тело записи — от первого до последнего окна, которое не тише медианы больше чем на 1 дБ. */
    public static final float BODY_THRESHOLD_DB = -1f;
    private static final float WINDOW_SECONDS = 0.01f;

    private SeamlessLoop() { }

    /**
     * Записи 16 бит с одной частотой и одним числом каналов (чередующиеся
     * сэмплы) → одна бесшовная петля. Тишина и обрывки короче двух швов
     * пропускаются; не осталось ничего — пустой массив.
     */
    public static short[] stitch(List<short[]> clips, int channels, int sampleRate, float crossfadeSeconds) {
        // Всё ниже меряется в кадрах: кадр — по сэмплу на каждый канал.
        int fade = Math.max(1, Math.round(crossfadeSeconds * sampleRate));
        List<float[]> bodies = new ArrayList<>();
        double levelSum = 0;
        for (short[] clip : clips) {
            float[] body = body(clip, channels, sampleRate);
            if (body == null || body.length / channels < 2 * fade)
                continue;
            bodies.add(body);
            levelSum += rms(body);
        }
        if (bodies.isEmpty())
            return new short[0];
        double level = levelSum / bodies.size();
        // Каждый шов съедает fade кадров; шов конца с началом — тоже.
        int total = -bodies.size() * fade;
        for (float[] body : bodies)
            total += body.length / channels;
        float[] mix = new float[total * channels];
        int at = 0;
        for (float[] body : bodies) {
            double gain = level / rms(body);
            int frames = body.length / channels, outStart = frames - fade;
            for (int k = 0; k < frames; k++) {
                // sin² + cos² = 1: у некоррелированного шума мощность на шве постоянна.
                double w = 1;
                if (k < fade)
                    w = Math.sin(Math.PI / 2 * (k + 0.5) / fade);
                else if (k >= outStart)
                    w = Math.cos(Math.PI / 2 * (k - outStart + 0.5) / fade);
                int to = (at + k) % total * channels;
                for (int c = 0; c < channels; c++)
                    mix[to + c] += (float) (body[k * channels + c] * gain * w);
            }
            at += outStart;
        }
        short[] out = new short[mix.length];
        for (int i = 0; i < mix.length; i++)
            out[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(mix[i])));
        return out;
    }

    /** Запись без входа и выхода, с запасом в одно окно с каждой стороны; null — тишина или обрывок. */
    private static float[] body(short[] clip, int channels, int sampleRate) {
        int w = Math.max(1, Math.round(WINDOW_SECONDS * sampleRate));
        int windows = clip.length / channels / w;
        if (windows < 3)
            return null;
        double[] level = new double[windows];
        for (int k = 0; k < windows; k++) {
            double sum = 0;
            for (int i = k * w * channels; i < (k + 1) * w * channels; i++)
                sum += (double) clip[i] * clip[i];
            level[k] = Math.sqrt(sum / (w * channels));
        }
        double[] sorted = level.clone();
        Arrays.sort(sorted);
        double threshold = sorted[windows / 2] * Math.pow(10, BODY_THRESHOLD_DB / 20);
        if (threshold <= 0)
            return null;
        int first = 0, last = windows - 1;
        while (level[first] < threshold)
            first++;
        while (level[last] < threshold)
            last--;
        int from = (first + 1) * w, to = last * w;
        if (to <= from)
            return null;
        float[] body = new float[(to - from) * channels];
        for (int i = 0; i < body.length; i++)
            body[i] = clip[from * channels + i];
        return body;
    }

    private static double rms(float[] pcm) {
        double sum = 0;
        for (float v : pcm)
            sum += (double) v * v;
        return Math.sqrt(sum / pcm.length);
    }
}

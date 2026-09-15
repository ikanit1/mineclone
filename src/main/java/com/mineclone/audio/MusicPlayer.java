package com.mineclone.audio;

import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.EXTEfx;
import org.lwjgl.system.MemoryUtil;

import java.io.File;
import java.io.IOException;
import java.nio.ShortBuffer;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;

/**
 * Потоковое воспроизведение музыки на собственном потоке.
 *
 * <p>Трек в три-четыре минуты — это 40 МБ PCM, поэтому он не грузится целиком,
 * а декодируется кусками по четверти секунды в очередь источника OpenAL.
 *
 * <p><b>Почему отдельный поток, а не кадр.</b> Главный поток подвисает на
 * сохранении, выгрузке мира и сборке мусора — до секунды. Музыка, которую
 * кормит кадр, рвалась бы именно в эти моменты. LWJGL ставит возможности AL на
 * весь процесс, а OpenAL Soft сам потокобезопасен, поэтому поток музыки вправе
 * вызывать AL; объекты у него свои — источники, буферы и фильтр.
 *
 * <p><b>Главный поток видит запрос сразу.</b> {@link #play} тут же запоминает
 * трек и номер поколения, а поток музыки сообщает только «поколение N
 * доиграло». Иначе режиссёр в кадре после {@code play()} видел бы старый
 * снимок — поток ещё не принял команду — и считал бы трек доигравшим.
 */
public final class MusicPlayer implements MusicDirector.Playback, MusicDirector.Output, AutoCloseable {

    /** Музыка ниже звуков: при всех ползунках на максимуме она фон, а не шум. */
    public static final float MUSIC_BASE = 0.7f;
    /** Буферов в очереди источника и длина каждого — вместе запас в 1,5 с. */
    public static final int BUFFERS = 6;
    public static final float CHUNK_SECONDS = 0.25f;
    /** Как часто поток музыки обслуживает очередь. */
    static final long TICK_NANOS = 10_000_000L;
    /** Приглушение боем опускается и возвращается за столько секунд. */
    public static final float DUCK_DOWN_TIME = 1.5f, DUCK_UP_TIME = 3f;
    /** Под водой: доля верхних частот и время перехода. */
    public static final float MUFFLE_HF = 0.3f, MUFFLE_TIME = 0.5f;
    /** Новый трек при играющем: старый уходит за столько. */
    public static final float REPLACE_FADE = 1f;

    private final boolean efx;
    private final Thread thread;
    private volatile boolean alive;
    private volatile boolean running = true;
    private final ConcurrentLinkedQueue<Runnable> commands = new ConcurrentLinkedQueue<>();
    private final Set<String> failed = ConcurrentHashMap.newKeySet();

    // ---- вид главного потока ----
    private String requestedId;
    private long requestedGeneration;
    private float sentDuck = -1f;
    private boolean sentMuffled;
    private boolean enabled = true;
    private volatile long endedGeneration;
    private volatile float position;

    // ---- состояние потока музыки ----
    private Stream current, fading;
    private float master = 1f, music = 1f;
    private float duck = 1f, duckTarget = 1f, duckSpeed;
    private boolean muffle;
    private float hf = 1f;
    private int filter = -1;

    /**
     * @param efxAvailable у драйвера есть EFX (см. {@link SoundEngine#hasReverb()}):
     *                     без него музыка под водой только тише, но не глуше
     */
    public MusicPlayer(boolean efxAvailable) {
        boolean context;
        try {
            context = ALC10.alcGetCurrentContext() != 0L;
        } catch (Throwable t) {
            context = false;
        }
        alive = context;
        efx = context && efxAvailable;
        if (context) {
            thread = new Thread(this::loop, "mineclone-music");
            thread.setDaemon(true);
            thread.start();
        } else {
            thread = null;
            System.err.println("music: no OpenAL context, music off");
        }
    }

    // ---- Playback -----------------------------------------------------------------

    @Override
    public boolean available() {
        return alive;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public String currentTrackId() {
        return requestedId != null && endedGeneration < requestedGeneration ? requestedId : null;
    }

    @Override
    public boolean failed(String trackId) {
        return failed.contains(trackId);
    }

    /** Позиция играющего трека, секунды. */
    public float position() {
        return currentTrackId() != null ? position : 0f;
    }

    // ---- Output -------------------------------------------------------------------

    @Override
    public void play(MusicTrack track, float fadeInSeconds) {
        if (!alive)
            return;
        long generation = ++requestedGeneration;
        requestedId = track.id();
        commands.add(() -> startStream(track, generation, fadeInSeconds));
    }

    @Override
    public void fadeOut(float seconds) {
        if (!alive)
            return;
        requestedId = null;
        commands.add(() -> fadeCurrent(seconds));
    }

    @Override
    public void setDuck(float level) {
        if (!alive || level == sentDuck)
            return;
        sentDuck = level;
        commands.add(() -> {
            duckTarget = level;
            duckSpeed = Math.abs(level - duck) / (level < duck ? DUCK_DOWN_TIME : DUCK_UP_TIME);
        });
    }

    @Override
    public void setMuffled(boolean muffled) {
        if (!alive || muffled == sentMuffled)
            return;
        sentMuffled = muffled;
        commands.add(() -> muffle = muffled);
    }

    /** Общая громкость и ползунок музыки; ноль у музыки — режиссёр ничего не начинает. */
    public void setVolume(float masterVolume, float musicVolume) {
        enabled = musicVolume > 0.001f;
        if (!alive)
            return;
        float m = clamp01(masterVolume), v = clamp01(musicVolume);
        commands.add(() -> {
            master = m;
            music = v;
        });
    }

    @Override
    public void close() {
        running = false;
        if (thread != null) {
            LockSupport.unpark(thread);
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        alive = false;
    }

    // ---- поток музыки -------------------------------------------------------------

    private void loop() {
        long last = System.nanoTime();
        try {
            if (efx)
                initFilter();
            while (running) {
                Runnable c;
                while ((c = commands.poll()) != null)
                    c.run();
                long now = System.nanoTime();
                float dt = Math.min(0.1f, (now - last) / 1e9f);
                last = now;
                stepMix(dt);
                current = service(current, dt);
                fading = service(fading, dt);
                position = current != null ? current.position() : 0f;
                LockSupport.parkNanos(TICK_NANOS);
            }
        } catch (Throwable t) {
            System.err.println("music: player stopped: " + t);
        } finally {
            if (current != null)
                current.close();
            if (fading != null)
                fading.close();
            current = null;
            fading = null;
            if (filter >= 0) {
                EXTEfx.alDeleteFilters(filter);
                filter = -1;
            }
            alive = false;
            endedGeneration = Long.MAX_VALUE;
        }
    }

    private void startStream(MusicTrack track, long generation, float fadeIn) {
        if (current != null) {
            if (fading != null)
                fading.close();
            current.fadeTo(0f, REPLACE_FADE);
            fading = current;
            current = null;
        }
        try {
            Stream s = new Stream(track, generation);
            s.fade = 0f;
            s.fadeTo(1f, fadeIn);
            s.prime();
            current = s;
        } catch (Throwable t) {
            System.err.println("music: cannot play " + track.id() + ": " + t);
            failed.add(track.id());
            endedGeneration = Math.max(endedGeneration, generation);
        }
    }

    private void fadeCurrent(float seconds) {
        if (current == null)
            return;
        if (fading != null)
            fading.close();
        current.fadeTo(0f, seconds);
        fading = current;
        current = null;
    }

    /** Шаг одного потока трека; null — поток закрыт. */
    private Stream service(Stream s, float dt) {
        if (s == null)
            return null;
        boolean keep;
        try {
            keep = s.pump(dt);
        } catch (Throwable t) {
            System.err.println("music: " + s.track.id() + " broke off: " + t);
            failed.add(s.track.id());
            keep = false;
        }
        if (keep)
            return s;
        s.close();
        endedGeneration = Math.max(endedGeneration, s.generation);
        return null;
    }

    private void stepMix(float dt) {
        if (duck != duckTarget)
            duck = approach(duck, duckTarget, Math.max(duckSpeed, 0.05f) * dt);
        float target = muffle ? MUFFLE_HF : 1f;
        if (hf != target) {
            hf = approach(hf, target, (1f - MUFFLE_HF) / MUFFLE_TIME * dt);
            applyFilter();
        }
    }

    private void initFilter() {
        filter = EXTEfx.alGenFilters();
        EXTEfx.alFilteri(filter, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
        if (AL10.alGetError() != AL10.AL_NO_ERROR) {
            EXTEfx.alDeleteFilters(filter);
            filter = -1;
        }
    }

    /** Параметры фильтра копируются в источник при подключении — подключаем заново. */
    private void applyFilter() {
        if (filter < 0)
            return;
        EXTEfx.alFilterf(filter, EXTEfx.AL_LOWPASS_GAIN, 1f);
        EXTEfx.alFilterf(filter, EXTEfx.AL_LOWPASS_GAINHF, hf);
        int value = hf < 0.999f ? filter : EXTEfx.AL_FILTER_NULL;
        if (current != null)
            AL10.alSourcei(current.source, EXTEfx.AL_DIRECT_FILTER, value);
        if (fading != null)
            AL10.alSourcei(fading.source, EXTEfx.AL_DIRECT_FILTER, value);
    }

    private float gainFor(Stream s) {
        return clamp01(master * music * MUSIC_BASE * s.track.gain() * s.fade * duck);
    }

    private static float approach(float value, float target, float step) {
        if (value < target)
            return Math.min(target, value + step);
        return Math.max(target, value - step);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    /** Один трек в очереди источника OpenAL. Живёт только на потоке музыки. */
    private final class Stream {
        final MusicTrack track;
        final long generation;
        final Mp3Stream pcm;
        final int source;
        final int format;
        final int channels;
        final int[] buffers = new int[BUFFERS];
        final int[] bufferFrames = new int[BUFFERS];
        final short[] scratch;
        final ShortBuffer upload;
        long playedFrames;
        float fade = 1f, fadeTarget = 1f, fadeSpeed;
        boolean decoderDone;

        Stream(MusicTrack track, long generation) throws IOException {
            this.track = track;
            this.generation = generation;
            pcm = new Mp3Stream(new File(track.path()));
            channels = pcm.channels();
            format = channels == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
            int frames = Math.max(1024, Math.round(pcm.sampleRate() * CHUNK_SECONDS));
            scratch = new short[frames * channels];
            upload = MemoryUtil.memAllocShort(scratch.length);
            source = AL10.alGenSources();
            AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
            AL10.alSource3f(source, AL10.AL_POSITION, 0f, 0f, 0f);
            AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 0f);
            AL10.alSourcef(source, AL10.AL_GAIN, 0f);
            if (filter >= 0 && hf < 0.999f)
                AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, filter);
            for (int i = 0; i < BUFFERS; i++)
                buffers[i] = AL10.alGenBuffers();
        }

        /** Заполнить очередь и начать играть с нулевой громкостью. */
        void prime() throws IOException {
            int queued = 0;
            for (int b : buffers) {
                if (!fill(b))
                    break;
                AL10.alSourceQueueBuffers(source, b);
                queued++;
            }
            if (queued == 0)
                throw new IOException("empty track");
            AL10.alSourcef(source, AL10.AL_GAIN, gainFor(this));
            AL10.alSourcePlay(source);
        }

        /** Декодирует кусок в буфер; false — трек кончился. */
        boolean fill(int buffer) throws IOException {
            if (decoderDone)
                return false;
            int n = pcm.read(scratch, 0, scratch.length);
            if (n < scratch.length)
                decoderDone = true;
            n -= Math.max(0, n) % channels;
            if (n <= 0)
                return false;
            upload.clear();
            upload.put(scratch, 0, n).flip();
            AL10.alBufferData(buffer, format, upload, pcm.sampleRate());
            bufferFrames[indexOf(buffer)] = n / channels;
            return true;
        }

        /** Фейд, подкачка буферов, конец. false — поток доиграл. */
        boolean pump(float dt) throws IOException {
            if (fadeSpeed > 0f) {
                fade = approach(fade, fadeTarget, fadeSpeed * dt);
                if (fade == fadeTarget)
                    fadeSpeed = 0f;
            }
            if (fadeTarget <= 0f && fade <= 0f)
                return false;
            int processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
            while (processed-- > 0) {
                int b = AL10.alSourceUnqueueBuffers(source);
                playedFrames += bufferFrames[indexOf(b)];
                if (fill(b))
                    AL10.alSourceQueueBuffers(source, b);
            }
            AL10.alSourcef(source, AL10.AL_GAIN, gainFor(this));
            if (AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) {
                if (AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED) == 0)
                    return false;
                // Недобор: очередь кончилась раньше подкачки — продолжаем с места.
                AL10.alSourcePlay(source);
            }
            return true;
        }

        void fadeTo(float target, float seconds) {
            fadeTarget = target;
            fadeSpeed = Math.abs(target - fade) / Math.max(0.01f, seconds);
            if (fadeSpeed == 0f)
                fade = target;
        }

        float position() {
            int offset = AL10.alGetSourcei(source, AL11.AL_SAMPLE_OFFSET);
            return (playedFrames + offset) / (float) pcm.sampleRate();
        }

        int indexOf(int buffer) {
            for (int i = 0; i < BUFFERS; i++)
                if (buffers[i] == buffer)
                    return i;
            return 0;
        }

        void close() {
            AL10.alSourceStop(source);
            AL10.alSourcei(source, AL10.AL_BUFFER, 0);
            AL10.alDeleteSources(source);
            for (int b : buffers)
                AL10.alDeleteBuffers(b);
            MemoryUtil.memFree(upload);
            try {
                pcm.close();
            } catch (IOException ignored) {
                // файл уже не нужен
            }
        }
    }
}

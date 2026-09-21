package com.mineclone.audio;

import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.EXTEfx;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;
import org.joml.Vector3f;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.File;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Minimal OpenAL wrapper: load OGG via stb_vorbis, fire one-shots, GC finished sources. */
public class SoundEngine {
    /** Distance curve shared by every world-space sound. */
    public static final float SPATIAL_REFERENCE_DISTANCE = 1.25f;
    public static final float SPATIAL_MAX_DISTANCE = 24f;

    private static final class LoopingSource {
        final int source;

        LoopingSource(int source) {
            this.source = source;
        }
    }

    private long device;
    private long context;
    private boolean ok;

    // --- реверберация ---
    /**
     * Слот вспомогательного эффекта и сам эффект реверберации.
     *
     * EFX есть не у каждого драйвера, поэтому всё это опционально: не
     * собралось — {@link #efx} остаётся false, источники играют как раньше и
     * ни одна строчка выше по стеку об этом не знает.
     */
    private boolean efx;
    private int effectSlot = -1;
    private int reverbEffect = -1;
    /** Текущая влажность 0..1 — сколько сигнала уходит в эффект. */
    private float reverbWet;
    /**
     * Фильтр нижних частот для звуков из-за стен. Один объект на все
     * источники: при подключении к источнику OpenAL копирует его параметры,
     * так что перенастраивать его под каждый звук безопасно.
     */
    private int lowpass = -1;

    private final Map<String, Integer> buffers = new HashMap<>();

    // ---- фоновое декодирование ----------------------------------------
    /** Что раскодировать. */
    private record DecodeJob(String path, boolean positional, String key) { }
    /** Что уже раскодировано и ждёт загрузки в OpenAL. */
    private record Decoded(String key, ShortBuffer pcm, int format, int rate) { }

    private final java.util.concurrent.BlockingQueue<DecodeJob> decodeQueue =
            new java.util.concurrent.LinkedBlockingQueue<>();
    private final java.util.concurrent.ConcurrentLinkedQueue<Decoded> decodedQueue =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    /** Ключи, уже поставленные в очередь или загруженные — чтобы не декодировать дважды. */
    private final java.util.Set<String> pending = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private Thread decoder;
    /**
     * Сколько буферов поднимается в OpenAL за кадр. Сама загрузка дешёвая
     * (память уже разжата), но сотня подряд на старте — тоже кадр.
     */
    private static final int UPLOADS_PER_FRAME = 4;
    private final List<Integer> activeSources = new ArrayList<>();
    /** Per-source wall occlusion. Underwater filtering is mixed on top of it. */
    private final Map<Integer, Float> sourceMuffle = new HashMap<>();
    /** Reusing native sources avoids driver allocations on every footstep or particle hit. */
    private final ArrayDeque<Integer> freeSources = new ArrayDeque<>();
    private static final int MAX_POOLED_SOURCES = 48;
    /** Long-lived ambient sources (water, fire, machinery), addressed by a stable game key. */
    private final Map<String, LoopingSource> loopingSources = new HashMap<>();
    private final Random rng = new Random();
    private float masterVolume = 1.0f;
    private float effectsVolume = 1.0f;
    private float underwaterMix;

    public void setMasterVolume(float v) { masterVolume = Math.max(0f, Math.min(1f, v)); }
    public void setEffectsVolume(float v) { effectsVolume = Math.max(0f, Math.min(1f, v)); }

    public void init() {
        try {
            device = ALC10.alcOpenDevice((java.nio.ByteBuffer) null);
            if (device == 0L) { System.err.println("OpenAL: no device, sound off"); return; }
            ALCCapabilities alcCaps = ALC.createCapabilities(device);
            context = ALC10.alcCreateContext(device, (IntBuffer) null);
            if (context == 0L) { System.err.println("OpenAL: no context, sound off"); return; }
            ALC10.alcMakeContextCurrent(context);
            AL.createCapabilities(alcCaps);
            // Inverse clamped never becomes silent: beyond max distance it
            // keeps an audible floor. Linear clamped gives a readable ramp
            // while approaching and reaches true silence at max distance.
            AL10.alDistanceModel(AL11.AL_LINEAR_DISTANCE_CLAMPED);
            AL10.alListener3f(AL10.AL_POSITION, 0f, 0f, 0f);
            AL10.alListener3f(AL10.AL_VELOCITY, 0f, 0f, 0f);
            ok = true;
            initReverb(alcCaps);
        } catch (Throwable t) {
            System.err.println("OpenAL init failed: " + t.getMessage());
            ok = false;
        }
    }

    /**
     * Поднимает реверберацию, если драйвер её умеет.
     *
     * Молча выключается при любой осечке: звук без эха лучше, чем игра,
     * падающая из-за звуковой карты.
     */
    private void initReverb(ALCCapabilities alcCaps) {
        try {
            if (!alcCaps.ALC_EXT_EFX) {
                System.out.println("OpenAL: no ALC_EXT_EFX, reverb off");
                return;
            }
            effectSlot = EXTEfx.alGenAuxiliaryEffectSlots();
            reverbEffect = EXTEfx.alGenEffects();
            EXTEfx.alEffecti(reverbEffect, EXTEfx.AL_EFFECT_TYPE, EXTEfx.AL_EFFECT_REVERB);
            if (AL10.alGetError() != AL10.AL_NO_ERROR)
                throw new IllegalStateException("reverb effect unsupported");
            applyReverbShape(0f);
            EXTEfx.alAuxiliaryEffectSloti(effectSlot, EXTEfx.AL_EFFECTSLOT_EFFECT, reverbEffect);
            EXTEfx.alAuxiliaryEffectSlotf(effectSlot, EXTEfx.AL_EFFECTSLOT_GAIN, 0f);
            efx = AL10.alGetError() == AL10.AL_NO_ERROR;
            if (efx) {
                lowpass = EXTEfx.alGenFilters();
                EXTEfx.alFilteri(lowpass, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
                if (AL10.alGetError() != AL10.AL_NO_ERROR) {
                    EXTEfx.alDeleteFilters(lowpass);
                    lowpass = -1;   // эхо есть, заглушения нет — тоже рабочий вариант
                }
            }
            System.out.println("OpenAL: reverb " + (efx ? "on" : "unavailable"));
        } catch (Throwable t) {
            efx = false;
            System.err.println("OpenAL EFX unavailable, reverb off: " + t.getMessage());
        }
    }

    /** Умеет ли текущий драйвер реверберацию. */
    public boolean hasReverb() {
        return efx;
    }

    /**
     * Настраивает эхо под замкнутость 0..1.
     *
     * Один непрерывный переход вместо набора пресетов: между полем и пещерой
     * игрок ходит плавно, и переключение «комната → пещера» ступенькой
     * слышно как щелчок.
     */
    public void setEnclosure(float enclosure) {
        if (!efx)
            return;
        float e = Math.max(0f, Math.min(1f, enclosure));
        if (Math.abs(e - reverbWet) < 0.01f)
            return;
        reverbWet = e;
        applyReverbShape(e);
        EXTEfx.alAuxiliaryEffectSloti(effectSlot, EXTEfx.AL_EFFECTSLOT_EFFECT, reverbEffect);
        EXTEfx.alAuxiliaryEffectSlotf(effectSlot, EXTEfx.AL_EFFECTSLOT_GAIN, e * 0.9f);
    }

    private void applyReverbShape(float e) {
        // Время затухания от 0.4 с (комната) до 4.2 с (каменный зал).
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_DECAY_TIME, 0.4f + e * 3.8f);
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_DENSITY, 0.55f + e * 0.45f);
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_DIFFUSION, 0.7f + e * 0.3f);
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_GAIN, 0.22f + e * 0.30f);
        // Камень глушит верх: чем теснее, тем глуше хвост.
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_GAINHF, 0.92f - e * 0.55f);
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_REFLECTIONS_DELAY, 0.007f + e * 0.02f);
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_LATE_REVERB_DELAY, 0.011f + e * 0.05f);
    }

    /** Подключает источник к эху. Без EFX — пустышка. */
    private void routeToReverb(int src) {
        if (!efx)
            return;
        AL11.alSource3i(src, EXTEfx.AL_AUXILIARY_SEND_FILTER, effectSlot, 0, EXTEfx.AL_FILTER_NULL);
    }

    /** Returns AL buffer id, or -1 if it is not decoded yet. */
    public int loadBuffer(String path) {
        return loadBuffer(path, false);
    }

    /**
     * Готовый буфер или −1, если звук ещё не раскодирован.
     *
     * <p>Раскодировать здесь нельзя: {@code stb_vorbis} разжимает файл целиком,
     * и это десятки миллисекунд прямо в кадре. Замер поймал 42 мс на подводном
     * фоне — один такой вход в воду и есть тот самый рывок. Поэтому первый
     * запрос только ставит файл в очередь фонового потока, а звук прозвучит со
     * следующего раза. Пропущенный первый шаг никто не услышит, замерший кадр
     * видят все.
     */
    public int loadBuffer(String path, boolean positional) {
        if (!ok) return -1;
        String key = positional ? path + "#positional" : path;
        Integer cached = buffers.get(key);
        if (cached != null) return cached;
        requestDecode(path, positional, key);
        return -1;
    }

    /** Ставит файлы в очередь фонового декодера заранее — до первого проигрывания. */
    public void preload(java.util.Collection<String> paths, boolean positional) {
        if (!ok || paths == null) return;
        for (String path : paths)
            requestDecode(path, positional, positional ? path + "#positional" : path);
    }

    private void requestDecode(String path, boolean positional, String key) {
        if (!pending.add(key))
            return;
        if (!new File(path).exists()) {
            pending.remove(key);
            return;
        }
        decodeQueue.add(new DecodeJob(path, positional, key));
        startDecoder();
    }

    /** Синхронное декодирование — только в фоновом потоке. */
    private Decoded decode(String path, boolean positional, String key) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer channels = stack.mallocInt(1);
            IntBuffer rate = stack.mallocInt(1);
            ShortBuffer pcm = STBVorbis.stb_vorbis_decode_filename(path, channels, rate);
            if (pcm == null) return null;
            int channelCount = channels.get(0);
            ShortBuffer upload = pcm;
            int format = (channelCount == 1 || positional) ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
            if (positional && channelCount > 1) {
                upload = MemoryUtil.memAllocShort(pcm.remaining() / channelCount);
                while (pcm.remaining() >= channelCount) {
                    int sum = 0;
                    for (int i = 0; i < channelCount; i++) {
                        sum += pcm.get();
                    }
                    upload.put((short) (sum / channelCount));
                }
                upload.flip();
            }
            if (upload != pcm)
                MemoryUtil.memFree(pcm);
            return new Decoded(key, upload, format, rate.get(0));
        } catch (Throwable t) {
            System.err.println("Sound load failed for " + path + ": " + t.getMessage());
            return null;
        }
    }

    public void updateListener(Vector3f position, Vector3f forward) {
        if (!ok) return;
        Vector3f f = new Vector3f(forward);
        if (f.lengthSquared() < 0.0001f) {
            f.set(0f, 0f, -1f);
        } else {
            f.normalize();
        }
        Vector3f right = new Vector3f(f).cross(0f, 1f, 0f);
        if (right.lengthSquared() < 0.0001f) {
            right.set(1f, 0f, 0f);
        } else {
            right.normalize();
        }
        Vector3f up = new Vector3f(right).cross(f).normalize();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer orientation = stack.floats(
                    f.x, f.y, f.z,
                    up.x, up.y, up.z);
            AL10.alListener3f(AL10.AL_POSITION, position.x, position.y, position.z);
            AL10.alListenerfv(AL10.AL_ORIENTATION, orientation);
        }
    }

    /**
     * Непозиционный 2D-звук: UI, собственный урон, подбор и погодный фон.
     * Он всегда сухой — пещерная посылка предназначена только источникам,
     * имеющим координату в мире. Иначе щелчок меню звучит как из тоннеля.
     */
    public void playOneOf(List<String> paths, float volume, float pitch) {
        if (!ok || paths == null || paths.isEmpty()) return;
        String chosen = paths.get(rng.nextInt(paths.size()));
        play(chosen, volume, pitch);
    }

    public void play(String path, float volume, float pitch) {
        if (!ok) return;
        int buffer = loadBuffer(path);
        if (buffer == -1) return;
        int src = acquireSource();
        AL10.alSourcei(src, AL10.AL_BUFFER, buffer);
        AL10.alSourcei(src, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
        AL10.alSource3f(src, AL10.AL_POSITION, 0f, 0f, 0f);
        AL10.alSourcef(src, AL10.AL_GAIN, volume * masterVolume * effectsVolume);
        AL10.alSourcef(src, AL10.AL_PITCH, pitch);
        AL10.alSourcePlay(src);
        activeSources.add(src);
    }

    /** Picks a random variant and plays it from a world-space position. */
    public void playOneOfAt(List<String> paths, Vector3f position, float volume, float pitch) {
        playOneOfAt(paths, position, volume, pitch, 0f);
    }

    /**
     * @param muffle 0..1 — насколько звук глухой из-за стен (см.
     *               {@link SoundOcclusion#muffle}); без EFX игнорируется
     */
    public void playOneOfAt(List<String> paths, Vector3f position, float volume, float pitch,
                            float muffle) {
        if (!ok || paths == null || paths.isEmpty()) return;
        String chosen = paths.get(rng.nextInt(paths.size()));
        playAt(chosen, position, volume, pitch, muffle);
    }

    public void playAt(String path, Vector3f position, float volume, float pitch) {
        playAt(path, position, volume, pitch, 0f);
    }

    public void playAt(String path, Vector3f position, float volume, float pitch, float muffle) {
        if (!ok) return;
        int buffer = loadBuffer(path, true);
        if (buffer == -1) return;
        int src = acquireSource();
        AL10.alSourcei(src, AL10.AL_BUFFER, buffer);
        configureSpatialSource(src, position, volume, pitch, muffle);
        routeToReverb(src);
        AL10.alSourcePlay(src);
        activeSources.add(src);
    }

    /**
     * Keeps one ambient sound alive at a world position. Repeated calls move
     * the same OpenAL source instead of restarting a short clip, so its gain
     * follows the listener continuously while they approach or walk away.
     */
    public void updateLoopOneOfAt(String key, List<String> paths, Vector3f position,
                                  float volume, float pitch, float muffle) {
        if (!ok || key == null || paths == null || paths.isEmpty() || position == null)
            return;
        LoopingSource loop = loopingSources.get(key);
        if (loop == null) {
            String chosen = paths.get(rng.nextInt(paths.size()));
            int buffer = loadBuffer(chosen, true);
            if (buffer == -1)
                return;
            int src = acquireSource();
            AL10.alSourcei(src, AL10.AL_BUFFER, buffer);
            AL10.alSourcei(src, AL10.AL_LOOPING, AL10.AL_TRUE);
            routeToReverb(src);
            loop = new LoopingSource(src);
            loopingSources.put(key, loop);
        }
        configureSpatialSource(loop.source, position, volume, pitch, muffle);
        if (AL10.alGetSourcei(loop.source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING)
            AL10.alSourcePlay(loop.source);
    }

    /**
     * Keeps a listener-relative ambient bed alive. Used for underwater ambience:
     * it follows the listener, does not pan, and is deliberately exempt from the
     * outside-world low-pass filter.
     */
    public void updateLoopOneOf(String key, List<String> paths, float volume, float pitch) {
        if (!ok || key == null || paths == null || paths.isEmpty())
            return;
        LoopingSource loop = loopingSources.get(key);
        if (loop == null) {
            String chosen = paths.get(rng.nextInt(paths.size()));
            int buffer = loadBuffer(chosen);
            if (buffer == -1)
                return;
            int src = acquireSource();
            AL10.alSourcei(src, AL10.AL_BUFFER, buffer);
            AL10.alSourcei(src, AL10.AL_LOOPING, AL10.AL_TRUE);
            loop = new LoopingSource(src);
            loopingSources.put(key, loop);
        }
        int src = loop.source;
        sourceMuffle.remove(src);
        AL10.alSourcei(src, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
        AL10.alSource3f(src, AL10.AL_POSITION, 0f, 0f, 0f);
        AL10.alSourcef(src, AL10.AL_GAIN, volume * masterVolume * effectsVolume);
        AL10.alSourcef(src, AL10.AL_PITCH, pitch);
        if (lowpass >= 0)
            AL10.alSourcei(src, EXTEfx.AL_DIRECT_FILTER, EXTEfx.AL_FILTER_NULL);
        if (AL10.alGetSourcei(src, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING)
            AL10.alSourcePlay(src);
    }

    /** Smoothly applies one underwater low-pass to every world-space sound. */
    public void setUnderwater(boolean underwater, float dt) {
        if (!ok)
            return;
        float target = underwater ? 1f : 0f;
        float blend = 1f - (float) Math.exp(-Math.max(0f, dt) / 0.22f);
        underwaterMix += (target - underwaterMix) * blend;
        if (Math.abs(target - underwaterMix) < 0.001f)
            underwaterMix = target;
        if (lowpass < 0)
            return;
        for (Map.Entry<Integer, Float> entry : sourceMuffle.entrySet())
            applyMuffle(entry.getKey(), entry.getValue());
    }

    /** Stops and releases a keyed ambient source. */
    public void stopLoop(String key) {
        if (!ok || key == null)
            return;
        LoopingSource loop = loopingSources.remove(key);
        if (loop != null) {
            recycleSource(loop.source);
        }
    }

    /** Stops all persistent world ambience when leaving a world. */
    public void stopAllLoops() {
        if (!ok)
            return;
        for (LoopingSource loop : loopingSources.values()) {
            recycleSource(loop.source);
        }
        loopingSources.clear();
    }

    private void configureSpatialSource(int src, Vector3f position, float volume,
                                        float pitch, float muffle) {
        AL10.alSourcei(src, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
        AL10.alSource3f(src, AL10.AL_POSITION, position.x, position.y, position.z);
        AL10.alSource3f(src, AL10.AL_VELOCITY, 0f, 0f, 0f);
        AL10.alSourcef(src, AL10.AL_REFERENCE_DISTANCE, SPATIAL_REFERENCE_DISTANCE);
        AL10.alSourcef(src, AL10.AL_MAX_DISTANCE, SPATIAL_MAX_DISTANCE);
        AL10.alSourcef(src, AL10.AL_ROLLOFF_FACTOR, 1f);
        AL10.alSourcef(src, AL10.AL_GAIN, volume * masterVolume * effectsVolume);
        AL10.alSourcef(src, AL10.AL_PITCH, pitch);
        sourceMuffle.put(src, Math.max(0f, Math.min(1f, muffle)));
        applyMuffle(src, muffle);
    }

    private void applyMuffle(int src, float wallMuffle) {
        float muffle = 1f - (1f - Math.max(0f, Math.min(1f, wallMuffle)))
                * (1f - underwaterMix);
        if (lowpass >= 0 && muffle > 0.01f) {
            // За стеной верх пропадает раньше громкости: камень глушит, а не
            // только ослабляет. Нижняя граница — чтобы гул всё же читался.
            EXTEfx.alFilterf(lowpass, EXTEfx.AL_LOWPASS_GAIN, 1f);
            EXTEfx.alFilterf(lowpass, EXTEfx.AL_LOWPASS_GAINHF, Math.max(0.04f, 1f - 0.93f * muffle));
            AL10.alSourcei(src, EXTEfx.AL_DIRECT_FILTER, lowpass);
        } else if (lowpass >= 0) {
            AL10.alSourcei(src, EXTEfx.AL_DIRECT_FILTER, EXTEfx.AL_FILTER_NULL);
        }
    }

    /** Pure counterpart of the OpenAL linear distance model, used by tests and tuning UI. */
    public static float spatialGain(float distance) {
        if (!Float.isFinite(distance))
            return 0f;
        if (distance <= SPATIAL_REFERENCE_DISTANCE)
            return 1f;
        if (distance >= SPATIAL_MAX_DISTANCE)
            return 0f;
        return 1f - (distance - SPATIAL_REFERENCE_DISTANCE)
                / (SPATIAL_MAX_DISTANCE - SPATIAL_REFERENCE_DISTANCE);
    }

    private int acquireSource() {
        Integer pooled = freeSources.pollFirst();
        int src = pooled != null ? pooled : AL10.alGenSources();
        sourceMuffle.remove(src);
        // A source may previously have been positional/reverberant/looping.
        // Reset everything that can leak into its next short sound.
        AL10.alSourceStop(src);
        AL10.alSourcei(src, AL10.AL_BUFFER, 0);
        AL10.alSourcei(src, AL10.AL_LOOPING, AL10.AL_FALSE);
        AL10.alSourcei(src, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
        AL10.alSourcef(src, AL10.AL_GAIN, 1f);
        AL10.alSourcef(src, AL10.AL_PITCH, 1f);
        if (lowpass >= 0)
            AL10.alSourcei(src, EXTEfx.AL_DIRECT_FILTER, EXTEfx.AL_FILTER_NULL);
        if (efx)
            AL11.alSource3i(src, EXTEfx.AL_AUXILIARY_SEND_FILTER,
                    EXTEfx.AL_EFFECTSLOT_NULL, 0, EXTEfx.AL_FILTER_NULL);
        return src;
    }

    private void recycleSource(int src) {
        sourceMuffle.remove(src);
        AL10.alSourceStop(src);
        AL10.alSourcei(src, AL10.AL_BUFFER, 0);
        if (freeSources.size() < MAX_POOLED_SOURCES)
            freeSources.addLast(src);
        else
            AL10.alDeleteSources(src);
    }

    /** Call once per frame to free sources that finished playback. */
    private synchronized void startDecoder() {
        if (decoder != null)
            return;
        decoder = new Thread(() -> {
            while (true) {
                try {
                    DecodeJob job = decodeQueue.take();
                    Decoded d = decode(job.path(), job.positional(), job.key());
                    if (d == null)
                        pending.remove(job.key());
                    else
                        decodedQueue.add(d);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable t) {
                    System.err.println("Sound decode failed: " + t.getMessage());
                }
            }
        }, "mineclone-audio-decode");
        decoder.setDaemon(true);
        // Ниже обычного: декодер не должен отбирать ядро у генерации чанков.
        decoder.setPriority(Thread.MIN_PRIORITY);
        decoder.start();
    }

    /** Поднимает раскодированное в OpenAL. Только главный поток. */
    private void uploadDecoded() {
        for (int i = 0; i < UPLOADS_PER_FRAME; i++) {
            Decoded d = decodedQueue.poll();
            if (d == null)
                return;
            int buffer = AL10.alGenBuffers();
            AL10.alBufferData(buffer, d.format(), d.pcm(), d.rate());
            MemoryUtil.memFree(d.pcm());
            buffers.put(d.key(), buffer);
        }
    }

    public void tick() {
        if (!ok) return;
        uploadDecoded();
        Iterator<Integer> it = activeSources.iterator();
        while (it.hasNext()) {
            int s = it.next();
            int state = AL10.alGetSourcei(s, AL10.AL_SOURCE_STATE);
            if (state != AL10.AL_PLAYING) {
                recycleSource(s);
                it.remove();
            }
        }
    }

    public void destroy() {
        if (!ok) return;
        if (decoder != null)
            decoder.interrupt();
        for (Decoded d = decodedQueue.poll(); d != null; d = decodedQueue.poll())
            MemoryUtil.memFree(d.pcm());
        stopAllLoops();
        for (int s : activeSources) AL10.alDeleteSources(s);
        activeSources.clear();
        sourceMuffle.clear();
        for (int s : freeSources) AL10.alDeleteSources(s);
        freeSources.clear();
        for (int b : buffers.values()) AL10.alDeleteBuffers(b);
        buffers.clear();
        if (efx) {
            EXTEfx.alDeleteAuxiliaryEffectSlots(effectSlot);
            EXTEfx.alDeleteEffects(reverbEffect);
            if (lowpass >= 0)
                EXTEfx.alDeleteFilters(lowpass);
            lowpass = -1;
            efx = false;
        }
        if (context != 0L) { ALC10.alcMakeContextCurrent(0L); ALC10.alcDestroyContext(context); }
        if (device != 0L) ALC10.alcCloseDevice(device);
        ok = false;
    }
}

package com.mineclone.audio;

import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.File;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Minimal OpenAL wrapper: load OGG via stb_vorbis, fire one-shots, GC finished sources. */
public class SoundEngine {
    private long device;
    private long context;
    private boolean ok;

    private final Map<String, Integer> buffers = new HashMap<>();
    private final List<Integer> activeSources = new ArrayList<>();
    private final Random rng = new Random();
    private float masterVolume = 1.0f;

    public void setMasterVolume(float v) { masterVolume = Math.max(0f, Math.min(1f, v)); }

    public void init() {
        try {
            device = ALC10.alcOpenDevice((java.nio.ByteBuffer) null);
            if (device == 0L) { System.err.println("OpenAL: no device, sound off"); return; }
            ALCCapabilities alcCaps = ALC.createCapabilities(device);
            context = ALC10.alcCreateContext(device, (IntBuffer) null);
            if (context == 0L) { System.err.println("OpenAL: no context, sound off"); return; }
            ALC10.alcMakeContextCurrent(context);
            AL.createCapabilities(alcCaps);
            ok = true;
        } catch (Throwable t) {
            System.err.println("OpenAL init failed: " + t.getMessage());
            ok = false;
        }
    }

    /** Returns AL buffer id or -1 on failure. Cached. */
    public int loadBuffer(String path) {
        if (!ok) return -1;
        Integer cached = buffers.get(path);
        if (cached != null) return cached;
        File f = new File(path);
        if (!f.exists()) return -1;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer channels = stack.mallocInt(1);
            IntBuffer rate = stack.mallocInt(1);
            ShortBuffer pcm = STBVorbis.stb_vorbis_decode_filename(path, channels, rate);
            if (pcm == null) return -1;
            int format = (channels.get(0) == 1) ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
            int buffer = AL10.alGenBuffers();
            AL10.alBufferData(buffer, format, pcm, rate.get(0));
            MemoryUtil.memFree(pcm);
            buffers.put(path, buffer);
            return buffer;
        } catch (Throwable t) {
            System.err.println("Sound load failed for " + path + ": " + t.getMessage());
            return -1;
        }
    }

    /** Picks a random variant from the provided paths and plays once. */
    public void playOneOf(List<String> paths, float volume, float pitch) {
        if (!ok || paths == null || paths.isEmpty()) return;
        String chosen = paths.get(rng.nextInt(paths.size()));
        play(chosen, volume, pitch);
    }

    public void play(String path, float volume, float pitch) {
        if (!ok) return;
        int buffer = loadBuffer(path);
        if (buffer == -1) return;
        int src = AL10.alGenSources();
        AL10.alSourcei(src, AL10.AL_BUFFER, buffer);
        AL10.alSourcef(src, AL10.AL_GAIN, volume * masterVolume);
        AL10.alSourcef(src, AL10.AL_PITCH, pitch);
        AL10.alSourcePlay(src);
        activeSources.add(src);
    }

    /** Call once per frame to free sources that finished playback. */
    public void tick() {
        if (!ok) return;
        Iterator<Integer> it = activeSources.iterator();
        while (it.hasNext()) {
            int s = it.next();
            int state = AL10.alGetSourcei(s, AL10.AL_SOURCE_STATE);
            if (state != AL10.AL_PLAYING) {
                AL10.alDeleteSources(s);
                it.remove();
            }
        }
    }

    public void destroy() {
        if (!ok) return;
        for (int s : activeSources) AL10.alDeleteSources(s);
        activeSources.clear();
        for (int b : buffers.values()) AL10.alDeleteBuffers(b);
        buffers.clear();
        if (context != 0L) { ALC10.alcMakeContextCurrent(0L); ALC10.alcDestroyContext(context); }
        if (device != 0L) ALC10.alcCloseDevice(device);
        ok = false;
    }
}

package com.mineclone.audio;

import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
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
            AL10.alDistanceModel(AL10.AL_INVERSE_DISTANCE_CLAMPED);
            AL10.alListener3f(AL10.AL_POSITION, 0f, 0f, 0f);
            AL10.alListener3f(AL10.AL_VELOCITY, 0f, 0f, 0f);
            ok = true;
        } catch (Throwable t) {
            System.err.println("OpenAL init failed: " + t.getMessage());
            ok = false;
        }
    }

    /** Returns AL buffer id or -1 on failure. Cached. */
    public int loadBuffer(String path) {
        return loadBuffer(path, false);
    }

    /** Returns AL buffer id or -1 on failure. Cached. Positional sounds are forced to mono. */
    public int loadBuffer(String path, boolean positional) {
        if (!ok) return -1;
        String key = positional ? path + "#positional" : path;
        Integer cached = buffers.get(key);
        if (cached != null) return cached;
        File f = new File(path);
        if (!f.exists()) return -1;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer channels = stack.mallocInt(1);
            IntBuffer rate = stack.mallocInt(1);
            ShortBuffer pcm = STBVorbis.stb_vorbis_decode_filename(path, channels, rate);
            if (pcm == null) return -1;
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
            int buffer = AL10.alGenBuffers();
            AL10.alBufferData(buffer, format, upload, rate.get(0));
            if (upload != pcm) {
                MemoryUtil.memFree(upload);
            }
            MemoryUtil.memFree(pcm);
            buffers.put(key, buffer);
            return buffer;
        } catch (Throwable t) {
            System.err.println("Sound load failed for " + path + ": " + t.getMessage());
            return -1;
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
        AL10.alSourcei(src, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
        AL10.alSource3f(src, AL10.AL_POSITION, 0f, 0f, 0f);
        AL10.alSourcef(src, AL10.AL_GAIN, volume * masterVolume);
        AL10.alSourcef(src, AL10.AL_PITCH, pitch);
        AL10.alSourcePlay(src);
        activeSources.add(src);
    }

    /** Picks a random variant and plays it from a world-space position. */
    public void playOneOfAt(List<String> paths, Vector3f position, float volume, float pitch) {
        if (!ok || paths == null || paths.isEmpty()) return;
        String chosen = paths.get(rng.nextInt(paths.size()));
        playAt(chosen, position, volume, pitch);
    }

    public void playAt(String path, Vector3f position, float volume, float pitch) {
        if (!ok) return;
        int buffer = loadBuffer(path, true);
        if (buffer == -1) return;
        int src = AL10.alGenSources();
        AL10.alSourcei(src, AL10.AL_BUFFER, buffer);
        AL10.alSourcei(src, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
        AL10.alSource3f(src, AL10.AL_POSITION, position.x, position.y, position.z);
        AL10.alSource3f(src, AL10.AL_VELOCITY, 0f, 0f, 0f);
        AL10.alSourcef(src, AL10.AL_REFERENCE_DISTANCE, 1.25f);
        AL10.alSourcef(src, AL10.AL_MAX_DISTANCE, 24f);
        AL10.alSourcef(src, AL10.AL_ROLLOFF_FACTOR, 0.9f);
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

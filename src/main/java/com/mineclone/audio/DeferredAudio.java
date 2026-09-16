package com.mineclone.audio;

import java.util.List;
import org.joml.Vector3f;

/** Bounded, reusable sound events; nearby duplicates coalesce before occlusion probes. */
public final class DeferredAudio {
    @FunctionalInterface public interface Player { void play(List<String> paths, Vector3f at, float volume, float pitch); }
    private static final int MAX = 128;
    private final Event[] events = new Event[MAX];
    private int count;
    private static final class Event {
        List<String> paths;
        final Vector3f at = new Vector3f();
        float volume, pitch;
    }
    public DeferredAudio() { for (int i = 0; i < MAX; i++) events[i] = new Event(); }
    public void add(List<String> paths, Vector3f at, float volume, float pitch) {
        if (paths == null || paths.isEmpty()) return;
        for (int i = 0; i < count; i++) {
            Event e = events[i];
            if (e.paths.equals(paths) && e.at.distanceSquared(at) < 1f) {
                e.volume = Math.max(e.volume, volume);
                return;
            }
        }
        if (count == MAX) return;
        Event e = events[count++];
        e.paths = paths; e.at.set(at); e.volume = volume; e.pitch = pitch;
    }
    public void flush(Player player) {
        try { for (int i = 0; i < count; i++) {
            Event e = events[i]; player.play(e.paths, e.at, e.volume, e.pitch);
        } } finally {
            for (int i = 0; i < count; i++) events[i].paths = null;
            count = 0;
        }
    }
}

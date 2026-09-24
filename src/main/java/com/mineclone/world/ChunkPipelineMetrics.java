package com.mineclone.world;

import java.util.Arrays;

/** Optional benchmark telemetry: enqueue-to-completion latency, with bounded storage. */
public final class ChunkPipelineMetrics {
    private final double[] generation = new double[65536];
    private final double[] mesh = new double[65536];
    private int generationCount, meshCount;
    private long dropped;

    public synchronized void generation(long elapsedNanos) {
        if (generationCount < generation.length) generation[generationCount++] = elapsedNanos / 1e6;
        else dropped++;
    }
    public synchronized void mesh(long elapsedNanos) {
        if (meshCount < mesh.length) mesh[meshCount++] = elapsedNanos / 1e6;
        else dropped++;
    }
    public synchronized void reset() { generationCount = meshCount = 0; dropped = 0; }
    public synchronized Snapshot snapshot() {
        return new Snapshot(Arrays.copyOf(generation, generationCount), Arrays.copyOf(mesh, meshCount), dropped);
    }
    public record Snapshot(double[] generationMillis, double[] meshMillis, long droppedSamples) {}
}

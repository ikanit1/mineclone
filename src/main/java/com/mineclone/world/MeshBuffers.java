package com.mineclone.world;

import java.util.Arrays;

/** Primitive growable buffers avoid boxing every meshed coordinate and index. */
final class FloatList {
    private float[] data;
    private int size;
    FloatList(int capacity) { data = new float[Math.max(8,capacity)]; }
    int size() { return size; }
    float get(int i) { return data[i]; }
    void add(float value) {
        if (size == data.length) data = Arrays.copyOf(data, size * 2);
        data[size++] = value;
    }
    float[] toArray() { return Arrays.copyOf(data, size); }
}
final class IntList {
    private int[] data;
    private int size;
    IntList(int capacity) { data = new int[Math.max(8,capacity)]; }
    int size() { return size; }
    int get(int i) { return data[i]; }
    void add(int value) {
        if (size == data.length) data = Arrays.copyOf(data, size * 2);
        data[size++] = value;
    }
    int[] toArray() { return Arrays.copyOf(data, size); }
}

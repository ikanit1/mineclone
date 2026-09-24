package com.mineclone.world.shape;

/**
 * What a block is made of, as boxes in its own cell (0..1 on each axis): what a
 * body collides with, and what the aim and the outline see (AC-05).
 *
 * <p>Boxes go into a caller's array — six floats each: {@code minX, minY, minZ,
 * maxX, maxY, maxZ} — so collision, which asks for every cell a body touches
 * every frame, allocates nothing. {@link #MAX_BOXES} bounds the array.
 *
 * <p>A shape depends on the block's meta only. A shape that looks at its
 * neighbours (a fence joining the next post) will take the world as well; the
 * callers already reach shapes through {@link Shapes#collision} and
 * {@link Shapes#outline}, which have it.
 */
public interface BlockShape {
    /** The most boxes any shape writes. */
    int MAX_BOXES = 2;
    /** Floats per box. */
    int STRIDE = 6;

    /** Boxes a body cannot enter; the count written. */
    int collision(byte meta, float[] out);

    /** Boxes the aim hits and the outline follows; the count written. */
    int outline(byte meta, float[] out);

    /**
     * The whole cell collides. Collision takes its old, box-free path for such
     * a cell, so every cube behaves exactly as it did before shapes existed.
     */
    default boolean fullCube(byte meta) {
        return false;
    }

    /** A buffer for any shape's boxes. */
    static float[] buffer() {
        return new float[MAX_BOXES * STRIDE];
    }

    /** Writes box number {@code at}; returns the count after it. */
    static int box(float[] out, int at, float x0, float y0, float z0, float x1, float y1, float z1) {
        int o = at * STRIDE;
        out[o] = x0; out[o + 1] = y0; out[o + 2] = z0;
        out[o + 3] = x1; out[o + 4] = y1; out[o + 5] = z1;
        return at + 1;
    }

    /** Whether box number {@code at} is the whole cell. */
    static boolean unit(float[] boxes, int at) {
        int o = at * STRIDE;
        return boxes[o] == 0f && boxes[o + 1] == 0f && boxes[o + 2] == 0f
                && boxes[o + 3] == 1f && boxes[o + 4] == 1f && boxes[o + 5] == 1f;
    }
}

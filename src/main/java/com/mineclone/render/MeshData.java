package com.mineclone.render;

/** Plain CPU-side mesh data, safe to build on background threads. */
public final class MeshData {
    public final float[] positions;
    public final float[] uvs;
    public final float[] light;
    public final int[] indices;

    public MeshData(float[] positions, float[] uvs, float[] light, int[] indices) {
        this.positions = positions;
        this.uvs = uvs;
        this.light = light;
        this.indices = indices;
    }

    public boolean isEmpty() { return indices.length == 0; }

    /** Must be called from the main OpenGL thread. */
    public Mesh upload() {
        return new Mesh(positions, uvs, light, indices);
    }
}

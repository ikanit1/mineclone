package com.mineclone.render;

public final class MeshData {
    public final float[] positions;
    public final float[] uvs;
    public final float[] light;
    public final float[] blockLight;
    public final int[] indices;

    public MeshData(float[] positions, float[] uvs, float[] light, float[] blockLight, int[] indices) {
        this.positions = positions;
        this.uvs = uvs;
        this.light = light;
        this.blockLight = blockLight;
        this.indices = indices;
    }

    public boolean isEmpty() { return indices.length == 0; }

    public Mesh upload() {
        return new Mesh(positions, uvs, light, blockLight, indices);
    }
}

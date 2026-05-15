package com.mineclone.world;

import com.mineclone.render.Mesh;
import com.mineclone.render.MeshData;
import com.mineclone.render.TextureAtlas;

import java.util.ArrayList;
import java.util.List;

/** Face culling mesher with vertex Ambient Occlusion. */
public class ChunkMesher {

    private static final int[][] FACE_DIRS = {
            {0, 0, 1},   // +Z (south)
            {0, 0, -1},  // -Z (north)
            {1, 0, 0},   // +X (east)
            {-1, 0, 0},  // -X (west)
            {0, 1, 0},   // +Y (top)
            {0, -1, 0},  // -Y (bottom)
    };

    private static final float[] FACE_LIGHT = {
            0.88f, 0.88f, 0.80f, 0.80f, 1.0f, 0.65f
    };

    /** AO darkening per occlusion level (0..3). Softened for gentler corner shading. */
    private static final float[] AO_TABLE = { 1.0f, 0.86f, 0.74f, 0.62f };

    private final World world;

    public ChunkMesher(World world) { this.world = world; }

    /** Convenience: build and upload on the calling (main) thread. */
    public Mesh build(Chunk chunk) {
        return buildData(chunk).upload();
    }

    /** CPU-only mesh build; safe to call from background threads. */
    public MeshData buildData(Chunk chunk) {
        List<Float> positions = new ArrayList<>(4096);
        List<Float> uvs = new ArrayList<>(2048);
        List<Float> light = new ArrayList<>(1024);
        List<Integer> indices = new ArrayList<>(4096);

        int baseX = chunk.cx * Chunk.SIZE_X;
        int baseZ = chunk.cz * Chunk.SIZE_Z;

        for (int x = 0; x < Chunk.SIZE_X; x++) {
            for (int y = 0; y < Chunk.SIZE_Y; y++) {
                for (int z = 0; z < Chunk.SIZE_Z; z++) {
                    BlockType b = chunk.get(x, y, z);
                    if (b == BlockType.AIR) continue;

                    for (int f = 0; f < 6; f++) {
                        int[] d = FACE_DIRS[f];
                        int nx = x + d[0], ny = y + d[1], nz = z + d[2];
                        BlockType neighbor;
                        if (ny < 0 || ny >= Chunk.SIZE_Y) {
                            neighbor = BlockType.AIR;
                        } else if (chunk.inBounds(nx, ny, nz)) {
                            neighbor = chunk.get(nx, ny, nz);
                        } else {
                            neighbor = world.getBlock(baseX + nx, ny, baseZ + nz);
                        }

                        if (shouldDrawFace(b, neighbor)) {
                            int tile;
                            if (f == 4) tile = b.topTile;
                            else if (f == 5) tile = b.bottomTile;
                            else tile = b.sideTile;
                            emitFace(chunk, positions, uvs, light, indices,
                                    x, y, z, baseX, baseZ, f, tile, FACE_LIGHT[f]);
                        }
                    }
                }
            }
        }

        float[] pa = toFloatArray(positions);
        float[] ua = toFloatArray(uvs);
        float[] la = toFloatArray(light);
        int[]   ia = toIntArray(indices);
        return new MeshData(pa, ua, la, ia);
    }

    private static boolean shouldDrawFace(BlockType self, BlockType neighbor) {
        if (neighbor == BlockType.AIR) return true;
        // Cutout blocks (leaves) have holes, so they don't fully occlude a neighbour's
        // face. Same-type internal faces are still skipped to avoid self-overdraw.
        if (neighbor.transparent || neighbor.cutout) return neighbor != self;
        return false;
    }

    private void emitFace(Chunk chunk,
                          List<Float> pos, List<Float> uvs, List<Float> light, List<Integer> idx,
                          int x, int y, int z, int baseX, int baseZ,
                          int face, int tileIndex, float lightVal) {
        float[] uv = TextureAtlas.uv(tileIndex);
        float u0 = uv[0], v0 = uv[1], u1 = uv[2], v1 = uv[3];
        int base = pos.size() / 3;

        int[][] quad;
        switch (face) {
            case 0: quad = new int[][]{{0,0,1},{1,0,1},{1,1,1},{0,1,1}}; break; // +Z
            case 1: quad = new int[][]{{1,0,0},{0,0,0},{0,1,0},{1,1,0}}; break; // -Z
            case 2: quad = new int[][]{{1,0,1},{1,0,0},{1,1,0},{1,1,1}}; break; // +X
            case 3: quad = new int[][]{{0,0,0},{0,0,1},{0,1,1},{0,1,0}}; break; // -X
            case 4: quad = new int[][]{{0,1,1},{1,1,1},{1,1,0},{0,1,0}}; break; // +Y top
            default: quad = new int[][]{{0,0,0},{1,0,0},{1,0,1},{0,0,1}}; break; // -Y bottom
        }

        float[][] uvQuad = {{u0, v1}, {u1, v1}, {u1, v0}, {u0, v0}};

        int[] dN = FACE_DIRS[face];
        int nAxis = (dN[0] != 0) ? 0 : (dN[1] != 0) ? 1 : 2;
        int nSign = dN[nAxis];
        int ta = (nAxis == 0) ? 1 : 0;
        int tb = (nAxis == 2) ? 1 : 2;

        float[] ao = new float[4];
        for (int i = 0; i < 4; i++) {
            int[] corner = quad[i];
            int sa = corner[ta] * 2 - 1;
            int sb = corner[tb] * 2 - 1;

            int[] off = {0,0,0};
            off[nAxis] = nSign;

            int s1x = off[0], s1y = off[1], s1z = off[2];
            int s2x = off[0], s2y = off[1], s2z = off[2];
            int cox = off[0], coy = off[1], coz = off[2];

            switch (ta) { case 0 -> { s1x += sa; cox += sa; } case 1 -> { s1y += sa; coy += sa; } case 2 -> { s1z += sa; coz += sa; } }
            switch (tb) { case 0 -> { s2x += sb; cox += sb; } case 1 -> { s2y += sb; coy += sb; } case 2 -> { s2z += sb; coz += sb; } }

            boolean side1 = isOpaqueWorld(chunk, x + s1x, y + s1y, z + s1z, baseX, baseZ);
            boolean side2 = isOpaqueWorld(chunk, x + s2x, y + s2y, z + s2z, baseX, baseZ);
            boolean cor   = isOpaqueWorld(chunk, x + cox, y + coy, z + coz, baseX, baseZ);

            int aoLevel = (side1 && side2) ? 3 : (b(side1) + b(side2) + b(cor));

            // Average sky light across the (up to 4) transparent blocks touching the vertex.
            int sum = skyAt(chunk, x + off[0], y + off[1], z + off[2], baseX, baseZ);
            int cnt = 1;
            if (!side1) { sum += skyAt(chunk, x + s1x, y + s1y, z + s1z, baseX, baseZ); cnt++; }
            if (!side2) { sum += skyAt(chunk, x + s2x, y + s2y, z + s2z, baseX, baseZ); cnt++; }
            if (!cor && !(side1 && side2)) {
                sum += skyAt(chunk, x + cox, y + coy, z + coz, baseX, baseZ); cnt++;
            }
            float sky = (sum / (float) cnt) / (float) Chunk.MAX_LIGHT;

            ao[i] = AO_TABLE[aoLevel] * lightVal * sky;
        }

        for (int i = 0; i < 4; i++) {
            float fx = x + quad[i][0], fy = y + quad[i][1], fz = z + quad[i][2];
            pos.add(fx); pos.add(fy); pos.add(fz);
            uvs.add(uvQuad[i][0]); uvs.add(uvQuad[i][1]);
            light.add(ao[i]);
        }

        // Anti-anisotropy flip: choose diagonal that keeps shading symmetric.
        boolean flip = (ao[0] + ao[2]) < (ao[1] + ao[3]);
        if (flip) {
            idx.add(base); idx.add(base + 1); idx.add(base + 3);
            idx.add(base + 1); idx.add(base + 2); idx.add(base + 3);
        } else {
            idx.add(base); idx.add(base + 1); idx.add(base + 2);
            idx.add(base); idx.add(base + 2); idx.add(base + 3);
        }
    }

    private int skyAt(Chunk chunk, int lx, int ly, int lz, int baseX, int baseZ) {
        if (ly < 0) return 0;
        if (ly >= Chunk.SIZE_Y) return Chunk.MAX_LIGHT;
        if (chunk.inBounds(lx, ly, lz)) return chunk.getSkyLight(lx, ly, lz);
        return world.getSkyLight(baseX + lx, ly, baseZ + lz);
    }

    private boolean isOpaqueWorld(Chunk chunk, int lx, int ly, int lz, int baseX, int baseZ) {
        if (ly < 0 || ly >= Chunk.SIZE_Y) return false;
        BlockType bt;
        if (chunk.inBounds(lx, ly, lz)) bt = chunk.get(lx, ly, lz);
        else bt = world.getBlock(baseX + lx, ly, baseZ + lz);
        return bt != BlockType.AIR && !bt.transparent && !bt.cutout;
    }

    private static int b(boolean v) { return v ? 1 : 0; }

    private static float[] toFloatArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        return arr;
    }

    private static int[] toIntArray(List<Integer> list) {
        int[] arr = new int[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        return arr;
    }
}

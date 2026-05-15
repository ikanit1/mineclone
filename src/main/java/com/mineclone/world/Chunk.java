package com.mineclone.world;

import java.util.ArrayDeque;

public class Chunk {
    public static final int SIZE_X = 16;
    public static final int SIZE_Y = 128;
    public static final int SIZE_Z = 16;
    public static final int MAX_LIGHT = 15;

    public final int cx, cz;
    private final byte[] blocks = new byte[SIZE_X * SIZE_Y * SIZE_Z];
    private final byte[] skyLight = new byte[SIZE_X * SIZE_Y * SIZE_Z];
    // blockLight is written on the main thread (flood fill) and read on mesh threads.
    // No explicit synchronization: worst-case is one mesh frame with stale values,
    // which self-corrects when the dirty flag triggers a rebuild — same trade-off as skyLight.
    private final byte[] blockLight = new byte[SIZE_X * SIZE_Y * SIZE_Z];
    public boolean dirty = true;

    public Chunk(int cx, int cz) {
        this.cx = cx;
        this.cz = cz;
    }

    public static int idx(int x, int y, int z) {
        return (y * SIZE_Z + z) * SIZE_X + x;
    }

    public boolean inBounds(int x, int y, int z) {
        return x >= 0 && x < SIZE_X && y >= 0 && y < SIZE_Y && z >= 0 && z < SIZE_Z;
    }

    public byte getRaw(int x, int y, int z) {
        if (!inBounds(x, y, z)) return 0;
        return blocks[idx(x, y, z)];
    }

    public BlockType get(int x, int y, int z) {
        return BlockType.byId(getRaw(x, y, z));
    }

    public void set(int x, int y, int z, BlockType t) {
        if (!inBounds(x, y, z)) return;
        blocks[idx(x, y, z)] = (byte) t.ordinal();
        dirty = true;
    }

    public int getSkyLight(int x, int y, int z) {
        if (!inBounds(x, y, z)) return MAX_LIGHT;
        return skyLight[idx(x, y, z)] & 0xFF;
    }

    public int getBlockLight(int x, int y, int z) {
        if (!inBounds(x, y, z)) return 0;
        return blockLight[idx(x, y, z)] & 0xFF;
    }

    public void setBlockLight(int x, int y, int z, int val) {
        if (!inBounds(x, y, z)) return;
        blockLight[idx(x, y, z)] = (byte) Math.max(0, Math.min(15, val));
    }

    /** Chunk-local sky light flood. Cheap and gives the closed-box-is-dark behavior. */
    public void computeSkyLight() {
        java.util.Arrays.fill(skyLight, (byte) 0);

        ArrayDeque<int[]> queue = new ArrayDeque<>(1024);

        // Vertical pass: each column gets 15 from the top down through transparent blocks.
        for (int x = 0; x < SIZE_X; x++) {
            for (int z = 0; z < SIZE_Z; z++) {
                for (int y = SIZE_Y - 1; y >= 0; y--) {
                    BlockType bt = get(x, y, z);
                    if (transparent(bt)) {
                        skyLight[idx(x, y, z)] = MAX_LIGHT;
                        queue.add(new int[]{x, y, z});
                    } else {
                        break;
                    }
                }
            }
        }

        // BFS horizontal propagation. Each step costs 1 light level.
        final int[][] dirs = {{1,0,0},{-1,0,0},{0,0,1},{0,0,-1},{0,1,0},{0,-1,0}};
        while (!queue.isEmpty()) {
            int[] p = queue.poll();
            int srcLight = skyLight[idx(p[0], p[1], p[2])] & 0xFF;
            int next = srcLight - 1;
            if (next <= 0) continue;
            for (int[] d : dirs) {
                int nx = p[0] + d[0], ny = p[1] + d[1], nz = p[2] + d[2];
                if (!inBounds(nx, ny, nz)) continue;
                if (!transparent(get(nx, ny, nz))) continue;
                int cur = skyLight[idx(nx, ny, nz)] & 0xFF;
                if (cur < next) {
                    skyLight[idx(nx, ny, nz)] = (byte) next;
                    queue.add(new int[]{nx, ny, nz});
                }
            }
        }

        smoothSkyLight(dirs);
    }

    /** One self-weighted blur pass over transparent cells to soften the BFS step gradient. */
    private void smoothSkyLight(int[][] dirs) {
        byte[] src = skyLight.clone();
        for (int x = 0; x < SIZE_X; x++) {
            for (int y = 0; y < SIZE_Y; y++) {
                for (int z = 0; z < SIZE_Z; z++) {
                    if (!transparent(get(x, y, z))) continue;
                    int sum = 2 * (src[idx(x, y, z)] & 0xFF);
                    int cnt = 2;
                    for (int[] d : dirs) {
                        int nx = x + d[0], ny = y + d[1], nz = z + d[2];
                        if (!inBounds(nx, ny, nz)) continue;
                        if (!transparent(get(nx, ny, nz))) continue;
                        sum += src[idx(nx, ny, nz)] & 0xFF;
                        cnt++;
                    }
                    skyLight[idx(x, y, z)] = (byte) ((sum + cnt / 2) / cnt);
                }
            }
        }
    }

    private static boolean transparent(BlockType bt) {
        return bt == BlockType.AIR || bt.transparent;
    }
}

package com.mineclone.world;

import java.util.BitSet;
import java.util.LinkedHashMap;

/** Merges only constant-lit cube faces; light gradients keep their original vertices. */
final class GreedyFaces {
    private record Key(int face, int plane, int tile, int material, float sky, float block) {}
    private final LinkedHashMap<Key, BitSet> planes = new LinkedHashMap<>();

    void add(int face, int x, int y, int z, int tile, int material, float sky, float block) {
        int plane = face < 2 ? z : face < 4 ? x : y;
        int u = face < 2 || face >= 4 ? x : z;
        int v = face < 4 ? y : z;
        planes.computeIfAbsent(new Key(face, plane, tile, material, sky, block), k -> new BitSet())
                .set(v * 16 + u);
    }

    void emit(FloatList pos, FloatList uv, FloatList sky, FloatList block,
              IntList indices, FloatList repeat) {
        for (var entry : planes.entrySet()) {
            Key k = entry.getKey();
            BitSet cells = entry.getValue();
            for (int start = cells.nextSetBit(0); start >= 0; start = cells.nextSetBit(0)) {
                int u = start % 16, v = start / 16, w = 1, h = 1;
                while (u + w < 16 && cells.get(start + w)) w++;
                outer: while (v + h < (k.face < 4 ? Chunk.SIZE_Y : 16)) {
                    for (int dx = 0; dx < w; dx++)
                        if (!cells.get((v + h) * 16 + u + dx)) break outer;
                    h++;
                }
                for (int dy = 0; dy < h; dy++) cells.clear((v + dy) * 16 + u, (v + dy) * 16 + u + w);
                int x = k.face < 2 || k.face >= 4 ? u : k.plane;
                int y = k.face < 4 ? v : k.plane;
                int z = k.face < 2 ? k.plane : k.face < 4 ? u : v;
                int[][] corners = switch (k.face) {
                    case 0 -> new int[][]{{x,y,z+1},{x+w,y,z+1},{x+w,y+h,z+1},{x,y+h,z+1}};
                    case 1 -> new int[][]{{x+w,y,z},{x,y,z},{x,y+h,z},{x+w,y+h,z}};
                    case 2 -> new int[][]{{x+1,y,z+w},{x+1,y,z},{x+1,y+h,z},{x+1,y+h,z+w}};
                    case 3 -> new int[][]{{x,y,z},{x,y,z+w},{x,y+h,z+w},{x,y+h,z}};
                    case 4 -> new int[][]{{x,y+1,z+h},{x+w,y+1,z+h},{x+w,y+1,z},{x,y+1,z}};
                    default -> new int[][]{{x,y,z},{x+w,y,z},{x+w,y,z+h},{x,y,z+h}};
                };
                float[] tileUv = com.mineclone.render.TextureAtlas.uv(k.tile);
                int base = pos.size() / 3;
                for (int i = 0; i < 4; i++) {
                    for (int a = 0; a < 3; a++) pos.add((float) corners[i][a]);
                    boolean right = i == 1 || i == 2, bottom = i < 2;
                    uv.add(tileUv[right ? 2 : 0]); uv.add(tileUv[bottom ? 3 : 1]);
                    repeat.add(right ? (float) w : 0f); repeat.add(bottom ? (float) h : 0f);
                    repeat.add((float) k.tile + 1);
                    sky.add(k.sky); block.add(k.block);
                }
                for (int i : new int[]{0,1,2,0,2,3}) indices.add(base + i);
            }
        }
    }
}

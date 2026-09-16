package com.mineclone.world;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;

/** Connectivity check for heavy player-built structures. */
public final class StructureStability {
    public static final int SEARCH_LIMIT = 384;
    private StructureStability() {}

    public static boolean heavy(BlockType b) {
        return b == BlockType.STONE || b == BlockType.COBBLE || b == BlockType.IRON_ORE
                || b == BlockType.GOLD_ORE || b == BlockType.DIAMOND_ORE || b == BlockType.OBSIDIAN;
    }

    /** True when a connected heavy component reaches bedrock or natural ground. */
    public static boolean supported(World world, int sx, int sy, int sz) {
        if (!heavy(world.getBlock(sx, sy, sz)))
            return true;
        ArrayDeque<int[]> open = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        open.add(new int[] { sx, sy, sz });
        int[][] dirs = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        while (!open.isEmpty() && seen.size() < SEARCH_LIMIT) {
            int[] p = open.removeFirst();
            long key = key(p[0], p[1], p[2]);
            if (!seen.add(key))
                continue;
            if (p[1] <= 1)
                return true;
            BlockType below = world.getBlock(p[0], p[1] - 1, p[2]);
            if (below == BlockType.BEDROCK || below == BlockType.DIRT || below == BlockType.GRASS
                    || below == BlockType.SNOWY_GRASS || below == BlockType.SAND)
                return true;
            for (int[] d : dirs) {
                int nx = p[0] + d[0], ny = p[1] + d[1], nz = p[2] + d[2];
                if (heavy(world.getBlock(nx, ny, nz)) && !seen.contains(key(nx, ny, nz)))
                    open.addLast(new int[] { nx, ny, nz });
            }
        }
        return false;
    }

    /** Упакованный ключ поставленного игроком блока. */
    public static long placementKey(int x, int y, int z) { return key(x, y, z); }

    /**
     * Возвращает только поставленную игроком тяжёлую компоненту, если после
     * снятия опоры она больше не касается ни земли, ни внешнего solid-блока.
     */
    public static List<int[]> unstablePlaced(World world, Set<Long> placed, int sx, int sy, int sz) {
        if (!placed.contains(key(sx, sy, sz)) || !heavy(world.getBlock(sx, sy, sz)))
            return List.of();
        ArrayDeque<int[]> open = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        List<int[]> component = new ArrayList<>();
        open.add(new int[] { sx, sy, sz });
        int[][] dirs = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        boolean supported = false;
        while (!open.isEmpty() && component.size() < SEARCH_LIMIT) {
            int[] p = open.removeFirst();
            long pk = key(p[0], p[1], p[2]);
            if (!seen.add(pk)) continue;
            component.add(p);
            if (p[1] <= 1) supported = true;
            for (int[] d : dirs) {
                int nx=p[0]+d[0], ny=p[1]+d[1], nz=p[2]+d[2];
                BlockType neighbour = world.getBlock(nx, ny, nz);
                long nk = key(nx, ny, nz);
                if (placed.contains(nk) && heavy(neighbour)) {
                    if (!seen.contains(nk)) open.addLast(new int[]{nx,ny,nz});
                } else if (neighbour.solid && !neighbour.transparent) {
                    supported = true;
                }
            }
        }
        return supported || component.size() >= SEARCH_LIMIT ? List.of() : component;
    }

    private static long key(int x, int y, int z) {
        return ((long)(x & 0x3FFFFFF) << 34) ^ ((long)(z & 0x3FFFFFF) << 8) ^ (y & 255L);
    }
}

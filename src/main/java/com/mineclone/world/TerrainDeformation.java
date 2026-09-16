package com.mineclone.world;

/** Persistent, voxel-native footprints: snow is compressed and wet dirt turns to mud. */
public final class TerrainDeformation {
    private TerrainDeformation() {}

    /** @return true when the terrain geometry/material changed. */
    public static boolean footprint(World world, int x, int feetY, int z, float weight, boolean wet) {
        BlockType cover = world.getBlock(x, feetY, z);
        if (cover == BlockType.SNOW_LAYER) {
            int level = world.getBlockMeta(x, feetY, z) & 7;
            int press = weight >= 1.2f ? 2 : 1;
            int next = Math.max(0, level - press);
            // meta=0 всё ещё рисует слой высотой 1/8 блока. Тонкий покров
            // под тяжёлой ногой должен исчезнуть, иначе след визуально не
            // углубляется именно в самый частый момент.
            if (next == 0)
                world.setBlock(x, feetY, z, BlockType.AIR);
            else
                world.setSnowLevel(x, feetY, z, next);
            return true;
        }
        BlockType ground = world.getBlock(x, feetY - 1, z);
        if (wet && ground == BlockType.DIRT) {
            world.setBlock(x, feetY - 1, z, BlockType.MUD);
            return true;
        }
        return false;
    }
}

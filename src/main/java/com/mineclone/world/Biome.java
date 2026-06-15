package com.mineclone.world;

/**
 * World-generation parameters per biome. Selected by {@link BiomeProvider}
 * from climate noise; never persisted (recomputed from the seed on demand).
 */
public enum Biome {
    //       baseHeight             amplitude  surface                 filler           trees/128  treeType
    PLAINS  (World.SEA_LEVEL + 6,   0.4,       BlockType.GRASS,        BlockType.DIRT,  1,         TreeType.OAK),
    FOREST  (World.SEA_LEVEL + 6,   0.7,       BlockType.GRASS,        BlockType.DIRT,  8,         TreeType.OAK),
    DESERT  (World.SEA_LEVEL + 6,   0.5,       BlockType.SAND,         BlockType.SAND,  2,         TreeType.CACTUS),
    TUNDRA  (World.SEA_LEVEL + 7,   0.8,       BlockType.SNOWY_GRASS,  BlockType.DIRT,  3,         TreeType.SPRUCE),
    OCEAN   (World.SEA_LEVEL - 18,  0.3,       BlockType.SAND,         BlockType.SAND,  0,         TreeType.NONE);

    public enum TreeType { OAK, SPRUCE, CACTUS, NONE }

    /** Base surface height in blocks. */
    public final int baseHeight;
    /** Multiplier for the terrain noise amplitude (1.0 = full 22-block swing). */
    public final double amplitude;
    public final BlockType surfaceBlock;
    /** Block used for the 4 layers under the surface (above stone). */
    public final BlockType fillerBlock;
    /** Vegetation density: a column spawns a tree when (hash & 0x7F) < treesPer128. */
    public final int treesPer128;
    public final TreeType treeType;

    Biome(int baseHeight, double amplitude, BlockType surfaceBlock, BlockType fillerBlock,
            int treesPer128, TreeType treeType) {
        this.baseHeight = baseHeight;
        this.amplitude = amplitude;
        this.surfaceBlock = surfaceBlock;
        this.fillerBlock = fillerBlock;
        this.treesPer128 = treesPer128;
        this.treeType = treeType;
    }
}

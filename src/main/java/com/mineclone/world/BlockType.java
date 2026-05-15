package com.mineclone.world;

public enum BlockType {
    AIR    (false, true,  false, -1,-1,-1, 0f,   0f,   0f,   0),
    GRASS  (true,  false, false,  1, 0, 2, 0.40f,0.55f,0.28f,0),
    DIRT   (true,  false, false,  2, 2, 2, 0.47f,0.33f,0.22f,0),
    STONE  (true,  false, false,  3, 3, 3, 0.47f,0.47f,0.47f,0),
    SAND   (true,  false, false,  4, 4, 4, 0.86f,0.78f,0.55f,0),
    WOOD   (true,  false, false,  5, 6, 6, 0.37f,0.26f,0.15f,0),
    LEAVES (true,  false, true,   7, 7, 7, 0.20f,0.47f,0.16f,0),
    WATER  (false, true,  true,   8, 8, 8, 0.16f,0.35f,0.78f,0),
    BEDROCK(true,  false, false,  9, 9, 9, 0.20f,0.20f,0.20f,0),
    COBBLE (true,  false, false, 10,10,10, 0.45f,0.45f,0.45f,0),
    PLANKS (true,  false, false, 11,11,11, 0.60f,0.45f,0.27f,0),
    TORCH  (false, true,  false, 12,12,12, 1.0f, 0.85f,0.40f,15);

    public final boolean solid;
    public final boolean transparent;
    public final boolean cutout;
    public final int sideTile, topTile, bottomTile;
    public final float[] particleColor;
    public final int emittedLight;

    BlockType(boolean solid, boolean transparent, boolean cutout,
              int side, int top, int bottom,
              float pr, float pg, float pb, int emittedLight) {
        this.solid = solid;
        this.transparent = transparent;
        this.cutout = cutout;
        this.sideTile = side;
        this.topTile = top;
        this.bottomTile = bottom;
        this.particleColor = new float[]{pr, pg, pb};
        this.emittedLight = emittedLight;
    }

    public static final BlockType[] VALUES = values();
    public static BlockType byId(byte id) { return VALUES[id & 0xFF]; }
}

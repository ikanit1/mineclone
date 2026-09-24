package com.mineclone.world;

/** World play mode. CREATIVE = infinite blocks + flight; SURVIVAL = drops/stacks. */
public enum GameMode {
    CREATIVE,
    SURVIVAL;

    public boolean canBreak(BlockType block) {
        return block != null && block != BlockType.AIR && block != BlockType.WATER && block != BlockType.WATER_FLOW && block != BlockType.LAVA
                && (this == CREATIVE || (block.hardness > 0f && block.hardness < Float.MAX_VALUE));
    }

    public static GameMode byOrdinalSafe(int i) {
        GameMode[] v = values();
        return (i >= 0 && i < v.length) ? v[i] : CREATIVE;
    }
}

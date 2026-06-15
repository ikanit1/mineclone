package com.mineclone.world;

/** World play mode. CREATIVE = infinite blocks + flight; SURVIVAL = drops/stacks. */
public enum GameMode {
    CREATIVE,
    SURVIVAL;

    public static GameMode byOrdinalSafe(int i) {
        GameMode[] v = values();
        return (i >= 0 && i < v.length) ? v[i] : CREATIVE;
    }
}

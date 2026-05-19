package com.mineclone.save;

import com.mineclone.world.BlockType;

import java.util.Arrays;

/** Everything stored in level.dat. Immutable. */
public final class LevelData {
    public final long seed;
    public final double px, py, pz;
    public final double spawnX, spawnY, spawnZ;
    public final float yaw, pitch;
    public final float timeOfDay;
    public final int selectedSlot;
    public final BlockType[] inventory;

    public LevelData(long seed, double px, double py, double pz,
                     float yaw, float pitch, float timeOfDay, int selectedSlot) {
        this(seed, px, py, pz, 8.5, 80.0, 8.5, yaw, pitch, timeOfDay, selectedSlot);
    }

    public LevelData(long seed, double px, double py, double pz,
                     double spawnX, double spawnY, double spawnZ,
                     float yaw, float pitch, float timeOfDay, int selectedSlot) {
        this(seed, px, py, pz, spawnX, spawnY, spawnZ, yaw, pitch, timeOfDay, selectedSlot,
                defaultInventory());
    }

    public LevelData(long seed, double px, double py, double pz,
                     double spawnX, double spawnY, double spawnZ,
                     float yaw, float pitch, float timeOfDay, int selectedSlot,
                     BlockType[] inventory) {
        this.seed = seed;
        this.px = px; this.py = py; this.pz = pz;
        this.spawnX = spawnX; this.spawnY = spawnY; this.spawnZ = spawnZ;
        this.yaw = yaw; this.pitch = pitch;
        this.timeOfDay = timeOfDay;
        this.selectedSlot = selectedSlot;
        this.inventory = normalizeInventory(inventory);
    }

    public static BlockType[] defaultInventory() {
        BlockType[] inv = new BlockType[36];
        BlockType[] hotbar = {
                BlockType.STONE, BlockType.DIRT, BlockType.GRASS, BlockType.PLANKS,
                BlockType.GLASS, BlockType.DOOR_CLOSED, BlockType.STAIRS, BlockType.TORCH, BlockType.WATER
        };
        System.arraycopy(hotbar, 0, inv, 0, hotbar.length);
        return inv;
    }

    private static BlockType[] normalizeInventory(BlockType[] src) {
        BlockType[] inv = defaultInventory();
        if (src == null)
            return inv;
        Arrays.fill(inv, BlockType.AIR);
        int n = Math.min(inv.length, src.length);
        for (int i = 0; i < n; i++) {
            inv[i] = src[i] == null ? BlockType.AIR : src[i];
        }
        return inv;
    }
}

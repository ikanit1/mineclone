package com.mineclone.save;

import com.mineclone.world.BlockType;
import com.mineclone.world.GameMode;
import com.mineclone.world.ItemStack;

/** Everything stored in level.dat. Immutable. */
public final class LevelData {
    public final String name;
    public final long seed;
    public final double px, py, pz;
    public final double spawnX, spawnY, spawnZ;
    public final float yaw, pitch;
    public final float timeOfDay;
    public final int selectedSlot;
    /** 36 slots; null = empty. */
    public final ItemStack[] inventory;
    public final GameMode gameMode;
    /** Unix-millisecond timestamp of the last save; 0 for pre-v5 saves. */
    public final long lastPlayed;

    public LevelData(long seed, double px, double py, double pz,
                     float yaw, float pitch, float timeOfDay, int selectedSlot) {
        this(seed, px, py, pz, 8.5, 80.0, 8.5, yaw, pitch, timeOfDay, selectedSlot);
    }

    public LevelData(long seed, double px, double py, double pz,
                     double spawnX, double spawnY, double spawnZ,
                     float yaw, float pitch, float timeOfDay, int selectedSlot) {
        this(seed, px, py, pz, spawnX, spawnY, spawnZ, yaw, pitch, timeOfDay, selectedSlot,
                creativeInventory());
    }

    public LevelData(long seed, double px, double py, double pz,
                     double spawnX, double spawnY, double spawnZ,
                     float yaw, float pitch, float timeOfDay, int selectedSlot,
                     ItemStack[] inventory) {
        this("", seed, px, py, pz, spawnX, spawnY, spawnZ,
             yaw, pitch, timeOfDay, selectedSlot, inventory);
    }

    public LevelData(String name, long seed, double px, double py, double pz,
                     double spawnX, double spawnY, double spawnZ,
                     float yaw, float pitch, float timeOfDay, int selectedSlot,
                     ItemStack[] inventory) {
        this(name, seed, px, py, pz, spawnX, spawnY, spawnZ,
             yaw, pitch, timeOfDay, selectedSlot, inventory,
             GameMode.CREATIVE, System.currentTimeMillis());
    }

    public LevelData(String name, long seed, double px, double py, double pz,
                     double spawnX, double spawnY, double spawnZ,
                     float yaw, float pitch, float timeOfDay, int selectedSlot,
                     ItemStack[] inventory, GameMode gameMode, long lastPlayed) {
        this.name = name != null ? name : "";
        this.seed = seed;
        this.px = px; this.py = py; this.pz = pz;
        this.spawnX = spawnX; this.spawnY = spawnY; this.spawnZ = spawnZ;
        this.yaw = yaw; this.pitch = pitch;
        this.timeOfDay = timeOfDay;
        this.selectedSlot = selectedSlot;
        this.inventory = normalizeInventory(inventory);
        this.gameMode = gameMode != null ? gameMode : GameMode.CREATIVE;
        this.lastPlayed = lastPlayed;
    }

    /** Empty inventory (survival start). */
    public static ItemStack[] emptyInventory() {
        return new ItemStack[com.mineclone.world.Inventory.SIZE];
    }

    /** Default creative hotbar set (sandbox convenience). */
    public static ItemStack[] creativeInventory() {
        ItemStack[] inv = emptyInventory();
        BlockType[] hotbar = {
                BlockType.STONE, BlockType.DIRT, BlockType.GRASS, BlockType.PLANKS,
                BlockType.GLASS, BlockType.DOOR_CLOSED, BlockType.STAIRS, BlockType.TORCH, BlockType.WATER
        };
        for (int i = 0; i < hotbar.length; i++)
            inv[i] = new ItemStack(hotbar[i], 1);
        return inv;
    }

    private static ItemStack[] normalizeInventory(ItemStack[] src) {
        ItemStack[] inv = emptyInventory();
        if (src == null) return inv;
        int n = Math.min(inv.length, src.length);
        for (int i = 0; i < n; i++) inv[i] = src[i]; // null preserved
        return inv;
    }
}

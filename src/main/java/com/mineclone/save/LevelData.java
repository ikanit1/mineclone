package com.mineclone.save;

import com.mineclone.world.BlockType;
import com.mineclone.world.GameMode;
import com.mineclone.world.ItemStack;

/** Everything stored in level.dat. Immutable. */
public final class LevelData {
    /** Canonical player checkpoint; compatibility fields below are detached views. */
    public final PlayerRecord player;
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
    public final float health;
    /** Сытость 0..20; для сейвов до v9 — полная. */
    public final float hunger;
    /**
     * Стопки, которым некуда лечь: курсор открытого окна на момент выхода.
     *
     * <p>Курсор — это предметы игрока, просто ни в одном слоте. Потерять их
     * на выходе из мира нельзя, а класть в инвентарь на записи поздно: он мог
     * быть полон. При загрузке они уходят в инвентарь, остаток — к ногам.
     */
    public final ItemStack[] pending;
    /**
     * Секции level.dat, которых эта версия игры не знает.
     *
     * <p>Хранятся байтами и пишутся обратно нетронутыми: мир, открытый старой
     * сборкой, не имеет права терять то, что записала новая. Порядок
     * сохраняется, чтобы файл не менялся от одного лишь чтения.
     */
    public final java.util.Map<String, byte[]> extraSections;

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
        this(name, seed, px, py, pz, spawnX, spawnY, spawnZ, yaw, pitch,
                timeOfDay, selectedSlot, inventory, gameMode, lastPlayed, 20f);
    }

    public LevelData(String name, long seed, double px, double py, double pz,
                     double spawnX, double spawnY, double spawnZ,
                     float yaw, float pitch, float timeOfDay, int selectedSlot,
                     ItemStack[] inventory, GameMode gameMode, long lastPlayed, float health) {
        this(name, seed, px, py, pz, spawnX, spawnY, spawnZ, yaw, pitch, timeOfDay,
                selectedSlot, inventory, gameMode, lastPlayed, health, 20f);
    }

    public LevelData(String name, long seed, double px, double py, double pz,
                     double spawnX, double spawnY, double spawnZ,
                     float yaw, float pitch, float timeOfDay, int selectedSlot,
                     ItemStack[] inventory, GameMode gameMode, long lastPlayed, float health,
                     float hunger) {
        this(name, seed, px, py, pz, spawnX, spawnY, spawnZ, yaw, pitch, timeOfDay,
                selectedSlot, inventory, gameMode, lastPlayed, health, hunger, null, null);
    }

    public LevelData(String name, long seed, double px, double py, double pz,
                     double spawnX, double spawnY, double spawnZ,
                     float yaw, float pitch, float timeOfDay, int selectedSlot,
                     ItemStack[] inventory, GameMode gameMode, long lastPlayed, float health,
                     float hunger, ItemStack[] pending,
                     java.util.Map<String, byte[]> extraSections) {
        this(name,seed,px,py,pz,spawnX,spawnY,spawnZ,yaw,pitch,timeOfDay,selectedSlot,
                inventory,gameMode,lastPlayed,health,hunger,pending,extraSections,null);
    }

    public LevelData(String name, long seed, double spawnX, double spawnY, double spawnZ,
                     float timeOfDay, GameMode gameMode, long lastPlayed, PlayerRecord player,
                     java.util.Map<String, byte[]> extraSections) {
        this(name,seed,player.pose().x(),player.pose().y(),player.pose().z(),spawnX,spawnY,spawnZ,
                player.pose().yaw(),player.pose().pitch(),timeOfDay,player.pose().selected(),player.inventory(),
                gameMode,lastPlayed,player.vitals().health(),player.vitals().hunger(),player.pending(),extraSections,player);
    }

    private LevelData(String name, long seed, double px, double py, double pz,
                     double spawnX, double spawnY, double spawnZ, float yaw, float pitch,
                     float timeOfDay, int selectedSlot, ItemStack[] inventory, GameMode gameMode,
                     long lastPlayed, float health, float hunger, ItemStack[] pending,
                     java.util.Map<String, byte[]> extraSections, PlayerRecord record) {
        this.pending = pending == null ? new ItemStack[0] : PlayerRecord.copy(pending);
        this.extraSections = extraSections == null || extraSections.isEmpty()
                ? java.util.Map.of()
                : PlayerRecord.copySections(extraSections);
        this.hunger = Float.isFinite(hunger) ? Math.max(0f, Math.min(20f, hunger)) : 20f;
        this.health = Float.isFinite(health) ? Math.max(0f, Math.min(20f, health)) : 20f;
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
        this.player = record != null ? record : PlayerRecord.builder()
                .pose(px,py,pz,yaw,pitch,Math.floorMod(selectedSlot,9))
                .vitals(this.health,this.hunger,5,20).inventory(this.inventory).pending(this.pending)
                .progress(this.extraSections.get(PlayerRecord.LEGACY_PROGRESS_SECTION)).build();
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

    /** Та же запись, но с другими отложенными стопками — для выхода из мира. */
    public LevelData withPending(ItemStack[] newPending) {
        return new LevelData(name,seed,spawnX,spawnY,spawnZ,timeOfDay,gameMode,lastPlayed,
                player.toBuilder().pending(newPending).build(),extraSections);
    }

    public LevelData withName(String newName) {
        return new LevelData(newName,seed,spawnX,spawnY,spawnZ,timeOfDay,gameMode,lastPlayed,player,extraSections);
    }

    private static ItemStack[] normalizeInventory(ItemStack[] src) {
        ItemStack[] inv = emptyInventory();
        if (src == null) return inv;
        int n = Math.min(inv.length, src.length);
        for (int i = 0; i < n; i++) inv[i] = src[i] == null ? null : src[i].copy();
        return inv;
    }
}

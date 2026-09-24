package com.mineclone.world;

import com.mineclone.item.ToolClass;

/** Behavioral properties, indexed by the immutable save-format block ordinal. */
public record BlockProps(boolean cross, boolean layered, boolean gravity, boolean soil,
        boolean flammable, float grip, float walkSpeedMultiplier,
        ToolClass preferredTool, int requiredToolLevel) {
    private static final int CROSS = 1, LAYER = 2, GRAVITY = 4, SOIL = 8, FLAMMABLE = 16;
    private static final BlockProps[] TABLE = java.util.Arrays.stream(BlockType.values())
            .map(BlockProps::define).toArray(BlockProps[]::new);

    public static BlockProps of(BlockType type) { return TABLE[type.ordinal()]; }

    private static BlockProps p(int flags, float grip, float speed, ToolClass tool, int level) {
        return new BlockProps((flags & CROSS) != 0, (flags & LAYER) != 0,
                (flags & GRAVITY) != 0, (flags & SOIL) != 0, (flags & FLAMMABLE) != 0,
                grip, speed, tool, level);
    }

    /** Exhaustive: adding a block also requires deciding its behavior here. */
    private static BlockProps define(BlockType type) {
        return switch (type) {
            case AIR, WATER, WATER_FLOW, LAVA, CACTUS, CHEST, FURNACE, GLASS ->
                    p(0, 1f, 1f, null, 0);
            case GRASS, DIRT, SNOWY_GRASS, PODZOL, DRY_GRASS ->
                    p(SOIL, 1f, 1f, ToolClass.SHOVEL, 0);
            case STONE, COBBLE, MOSSY_COBBLE, COAL_ORE, TERRACOTTA, LIMESTONE, BASALT ->
                    p(0, 1f, 1f, ToolClass.PICKAXE, 1);
            case IRON_ORE -> p(0, 1f, 1f, ToolClass.PICKAXE, 2);
            case GOLD_ORE, DIAMOND_ORE, OBSIDIAN -> p(0, 1f, 1f, ToolClass.PICKAXE, 3);
            case BEDROCK -> p(0, 1f, 1f, ToolClass.PICKAXE, 0);
            case SAND, RED_SAND, GRAVEL -> p(GRAVITY, 1f, 1f, ToolClass.SHOVEL, 0);
            case ASH -> p(GRAVITY, 1f, .9f, ToolClass.SHOVEL, 0);
            case WOOD, PLANKS, LEAVES, CRAFTING_TABLE -> p(FLAMMABLE, 1f, 1f, ToolClass.AXE, 0);
            case DOOR_CLOSED, DOOR_OPEN, STAIRS -> p(0, 1f, 1f, ToolClass.AXE, 0);
            case TORCH, FIRE, WEB -> p(CROSS, 1f, 1f, null, 0);
            case SNOW_LAYER -> p(LAYER, 1f, 1f, ToolClass.SHOVEL, 0);
            case BEDROLL -> p(LAYER, 1f, 1f, null, 0);
            case ICE, THIN_ICE -> p(0, .22f, 1f, ToolClass.PICKAXE, 0);
            case MUD -> p(SOIL, .58f, .8f, ToolClass.SHOVEL, 0);
            case PEAT -> p(SOIL, .58f, .72f, ToolClass.SHOVEL, 0);
            case ROPE -> p(CROSS | FLAMMABLE, 1f, 1f, ToolClass.AXE, 0);
            case CHAIN -> p(CROSS, 1f, 1f, ToolClass.PICKAXE, 0);
            case JOURNAL -> p(CROSS | FLAMMABLE, 1f, 1f, null, 0);
        };
    }
}

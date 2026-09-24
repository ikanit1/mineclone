package com.mineclone.world.behavior;

import com.mineclone.world.BlockType;

/**
 * Every block's behaviour (BLK-03). The switch is exhaustive, like
 * {@code BlockProps} and {@code Shapes}: a new block does not compile until
 * someone decides what it does.
 */
public final class Behaviors {
    /** No rules of its own: placed anywhere, used by no one, held up by nothing. */
    public static final BlockBehavior DEFAULT = new BlockBehavior() { };

    private static final BlockBehavior CHEST = new ChestBehavior(), FURNACE = new FurnaceBehavior(),
            CRAFTING = new CraftingTableBehavior(), BEDROLL = new BedrollBehavior(), DOOR = new DoorBehavior(),
            TORCH = new TorchBehavior(), STAIRS = new StairsBehavior(), SNOW = new SnowLayerBehavior();

    private static final BlockBehavior[] TABLE = new BlockBehavior[BlockType.VALUES.length];

    static {
        for (BlockType type : BlockType.VALUES)
            TABLE[type.ordinal()] = define(type);
    }

    private Behaviors() {}

    public static BlockBehavior of(BlockType type) {
        return TABLE[type.ordinal()];
    }

    private static BlockBehavior define(BlockType type) {
        return switch (type) {
            case CHEST -> CHEST;
            case FURNACE -> FURNACE;
            case CRAFTING_TABLE -> CRAFTING;
            case BEDROLL -> BEDROLL;
            case DOOR_CLOSED, DOOR_OPEN -> DOOR;
            case TORCH -> TORCH;
            case STAIRS -> STAIRS;
            case SNOW_LAYER -> SNOW;
            case AIR, GRASS, DIRT, STONE, SAND, WOOD, LEAVES, WATER, BEDROCK, COBBLE, PLANKS, GLASS,
                    WATER_FLOW, SNOWY_GRASS, CACTUS, COAL_ORE, IRON_ORE, GOLD_ORE, DIAMOND_ORE, FIRE, ICE,
                    MUD, ASH, MOSSY_COBBLE, LAVA, OBSIDIAN, THIN_ICE, ROPE, CHAIN, WEB, JOURNAL, PODZOL, PEAT,
                    DRY_GRASS, RED_SAND, TERRACOTTA, LIMESTONE, BASALT, GRAVEL -> DEFAULT;
        };
    }

    /** A torch's mount for a click on a face with this normal: 0 floor, 1..4 walls. */
    public static byte torchMount(int nx, int ny, int nz) {
        return TorchBehavior.mount(nx, ny, nz);
    }
}

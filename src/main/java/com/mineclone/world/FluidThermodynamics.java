package com.mineclone.world;

/** Rules shared by fluid ticking, particles and tests. */
public final class FluidThermodynamics {
    public enum Reaction { NONE, STEAM_AND_STONE, STEAM_AND_OBSIDIAN }

    private FluidThermodynamics() {}

    /** Relative horizontal propagation delay. */
    public static float viscosity(BlockType fluid) {
        return fluid == BlockType.LAVA ? 5f : 1f;
    }

    public static boolean isWater(BlockType b) {
        return b == BlockType.WATER || b == BlockType.WATER_FLOW;
    }

    public static boolean isFluid(BlockType b) {
        return isWater(b) || b == BlockType.LAVA;
    }

    public static Reaction reaction(BlockType a, BlockType b, boolean lavaSource) {
        boolean contact = (a == BlockType.LAVA && isWater(b)) || (b == BlockType.LAVA && isWater(a));
        if (!contact)
            return Reaction.NONE;
        return lavaSource ? Reaction.STEAM_AND_OBSIDIAN : Reaction.STEAM_AND_STONE;
    }

    /** Applies one water/lava contact. The lava cell is the solidified side. */
    public static Reaction react(World world, int lavaX, int lavaY, int lavaZ,
                                 int waterX, int waterY, int waterZ) {
        if (world.getBlock(lavaX, lavaY, lavaZ) != BlockType.LAVA
                || !isWater(world.getBlock(waterX, waterY, waterZ)))
            return Reaction.NONE;
        boolean source = (world.getBlockMeta(lavaX, lavaY, lavaZ) & 0x7) == 0;
        Reaction r = reaction(BlockType.LAVA, world.getBlock(waterX, waterY, waterZ), source);
        world.setBlock(lavaX, lavaY, lavaZ, source ? BlockType.OBSIDIAN : BlockType.STONE);
        return r;
    }
}

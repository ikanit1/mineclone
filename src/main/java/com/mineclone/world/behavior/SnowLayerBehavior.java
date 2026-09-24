package com.mineclone.world.behavior;

import com.mineclone.world.World;

/** A snow layer lies on something solid and goes when that is dug out. */
final class SnowLayerBehavior implements BlockBehavior {
    @Override
    public boolean canPlaceAt(World world, int x, int y, int z, PlaceContext ctx) {
        return world.getBlock(x, y - 1, z).solid;
    }

    @Override public boolean needsSupport() { return true; }

    @Override
    public boolean canSurvive(World world, int x, int y, int z, byte meta) {
        return world.getBlock(x, y - 1, z).solid;
    }
}

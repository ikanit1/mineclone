package com.mineclone.world.behavior;

import com.mineclone.world.World;

/**
 * A torch stands on a block or leans from a wall (meta 1..4, the mount the
 * mesher tilts it by) and falls, as an item, when that block goes. It does
 * not hang from a ceiling.
 */
final class TorchBehavior implements BlockBehavior {
    @Override
    public boolean canPlaceAt(World world, int x, int y, int z, PlaceContext ctx) {
        if (ctx.faceY() < 0)
            return false;
        return supported(world, x, y, z, mount(ctx.faceX(), ctx.faceY(), ctx.faceZ()));
    }

    @Override
    public byte placementMeta(PlaceContext ctx) {
        return mount(ctx.faceX(), ctx.faceY(), ctx.faceZ());
    }

    @Override public boolean needsSupport() { return true; }

    @Override
    public boolean canSurvive(World world, int x, int y, int z, byte meta) {
        return supported(world, x, y, z, meta);
    }

    /** 0 = floor; 1/2 = the wall at -X/+X; 3/4 = the wall at -Z/+Z. */
    public static byte mount(int nx, int ny, int nz) {
        if (ny > 0) return 0;
        if (nx > 0) return 1;
        if (nx < 0) return 2;
        if (nz > 0) return 3;
        if (nz < 0) return 4;
        return 0;
    }

    private static boolean supported(World world, int x, int y, int z, int meta) {
        return switch (meta & 0x7) {
            case 1 -> world.getBlock(x - 1, y, z).solid;
            case 2 -> world.getBlock(x + 1, y, z).solid;
            case 3 -> world.getBlock(x, y, z - 1).solid;
            case 4 -> world.getBlock(x, y, z + 1).solid;
            default -> world.getBlock(x, y - 1, z).solid;
        };
    }
}

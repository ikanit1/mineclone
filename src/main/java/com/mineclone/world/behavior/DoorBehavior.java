package com.mineclone.world.behavior;

import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.World;

/**
 * A door is two blocks: the lower half (meta bit 2 clear) stands on something
 * solid, the upper half (bit 2 set) on the lower. Placing one places both;
 * opening one opens both; when either goes, the other follows through the
 * neighbour updates — so it goes whichever way the first half went, and the
 * door drops once.
 */
final class DoorBehavior implements BlockBehavior {
    static final int UPPER = 0x4;

    @Override
    public boolean canPlaceAt(World world, int x, int y, int z, PlaceContext ctx) {
        return y + 1 < Chunk.SIZE_Y && world.getBlock(x, y + 1, z) == BlockType.AIR
                && world.getBlock(x, y - 1, z).solid;
    }

    /** The door's own rule: a carried meta would break the upper half's bit. */
    @Override
    public byte placementMeta(PlaceContext ctx) {
        float ax = Math.abs(ctx.lookX()), az = Math.abs(ctx.lookZ());
        if (ax > az)
            return (byte) (ctx.lookX() > 0 ? 1 : 3);
        return (byte) (ctx.lookZ() > 0 ? 0 : 2);
    }

    @Override
    public void onPlaced(World world, int x, int y, int z, byte meta) {
        world.setBlock(x, y + 1, z, world.getBlock(x, y, z), (byte) (meta | UPPER));
    }

    @Override public boolean interactive() { return true; }

    @Override
    public UseResult use(World world, int x, int y, int z, byte meta, UseContext ctx) {
        BlockType self = world.getBlock(x, y, z);
        BlockType next = self == BlockType.DOOR_CLOSED ? BlockType.DOOR_OPEN : BlockType.DOOR_CLOSED;
        world.setBlock(x, y, z, next, meta);
        int otherY = (meta & UPPER) != 0 ? y - 1 : y + 1;
        if (isDoor(world.getBlock(x, otherY, z)))
            world.setBlock(x, otherY, z, next, world.getBlockMeta(x, otherY, z));
        return UseResult.CONSUMED;
    }

    @Override public boolean needsSupport() { return true; }

    @Override
    public boolean canSurvive(World world, int x, int y, int z, byte meta) {
        if ((meta & UPPER) != 0)
            return isDoor(world.getBlock(x, y - 1, z)) && (world.getBlockMeta(x, y - 1, z) & UPPER) == 0;
        return isDoor(world.getBlock(x, y + 1, z)) && (world.getBlockMeta(x, y + 1, z) & UPPER) != 0
                && world.getBlock(x, y - 1, z).solid;
    }

    /** The first half to go drops the door; the one that follows drops nothing. */
    @Override
    public void collapse(World world, int x, int y, int z, byte meta, DropSink drops) {
        BlockType type = world.getBlock(x, y, z);
        int otherY = (meta & UPPER) != 0 ? y - 1 : y + 1;
        boolean whole = isDoor(world.getBlock(x, otherY, z));
        world.setBlock(x, y, z, BlockType.AIR);
        if (whole)
            BlockBehavior.dropLoot(world, type, x, y, z, drops);
    }

    static boolean isDoor(BlockType type) {
        return type == BlockType.DOOR_CLOSED || type == BlockType.DOOR_OPEN;
    }
}

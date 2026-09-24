package com.mineclone.world.behavior;

import com.mineclone.world.World;

/**
 * A bedroll is laid half a block high and slept in. Whether the night may
 * pass is the sleeper's rule ({@code SleepRules}), not the block's.
 */
final class BedrollBehavior implements BlockBehavior {
    /** Height in meta: (3 + 1) / 8, half a block — with zero it would be a film. */
    static final byte META = 3;

    @Override public boolean interactive() { return true; }

    @Override
    public byte placementMeta(PlaceContext ctx) {
        return ctx.carriesMeta() ? (byte) ctx.carriedMeta() : META;
    }

    @Override
    public UseResult use(World world, int x, int y, int z, byte meta, UseContext ctx) {
        return UseResult.SLEEP;
    }
}

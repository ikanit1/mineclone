package com.mineclone.world.behavior;

/** A stair's step faces away from the placer: it is climbed from where they stand. */
final class StairsBehavior implements BlockBehavior {
    @Override
    public byte placementMeta(PlaceContext ctx) {
        if (ctx.carriesMeta())
            return (byte) ctx.carriedMeta();
        return facing(ctx.lookX(), ctx.lookZ());
    }

    /** 0 = step at low Z, 1 = high X, 2 = high Z, 3 = low X (see {@code Shapes.STAIRS}). */
    static byte facing(float lookX, float lookZ) {
        if (Math.abs(lookX) > Math.abs(lookZ))
            return (byte) (lookX > 0 ? 1 : 3);
        return (byte) (lookZ > 0 ? 2 : 0);
    }
}

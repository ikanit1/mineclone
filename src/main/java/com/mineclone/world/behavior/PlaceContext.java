package com.mineclone.world.behavior;

/**
 * How a block is being placed.
 *
 * @param faceX       normal of the face that was clicked, pointing to the new cell
 * @param lookX       the placer's horizontal look direction (need not be unit)
 * @param carriedMeta meta carried by the item (a picked block's state), or -1
 */
public record PlaceContext(int faceX, int faceY, int faceZ, float lookX, float lookZ, int carriedMeta) {
    public static final int NO_META = -1;

    public boolean carriesMeta() {
        return carriedMeta >= 0;
    }
}

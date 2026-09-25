package com.mineclone.world.structure;

import com.mineclone.world.Chunk;

/**
 * A box of blocks, both corners inclusive, in world coordinates. What a
 * structure piece occupies, and the column of a chunk it is cut to.
 */
public record BoundingBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    public BoundingBox {
        if (minX > maxX || minY > maxY || minZ > maxZ)
            throw new IllegalArgumentException("empty box " + minX + "," + minY + "," + minZ
                    + " .. " + maxX + "," + maxY + "," + maxZ);
    }

    /** A box from a corner and a size in blocks. */
    public static BoundingBox sized(int x, int y, int z, int width, int height, int depth) {
        return new BoundingBox(x, y, z, x + width - 1, y + height - 1, z + depth - 1);
    }

    /** The whole column of chunk {@code (cx, cz)}. */
    public static BoundingBox chunk(int cx, int cz) {
        int x = cx * Chunk.SIZE_X, z = cz * Chunk.SIZE_Z;
        return new BoundingBox(x, 0, z, x + Chunk.SIZE_X - 1, Chunk.SIZE_Y - 1, z + Chunk.SIZE_Z - 1);
    }

    public boolean intersects(BoundingBox o) {
        return minX <= o.maxX && maxX >= o.minX && minY <= o.maxY && maxY >= o.minY
                && minZ <= o.maxZ && maxZ >= o.minZ;
    }

    /** The shared part, or null when the boxes do not meet. */
    public BoundingBox intersection(BoundingBox o) {
        if (!intersects(o))
            return null;
        return new BoundingBox(Math.max(minX, o.minX), Math.max(minY, o.minY), Math.max(minZ, o.minZ),
                Math.min(maxX, o.maxX), Math.min(maxY, o.maxY), Math.min(maxZ, o.maxZ));
    }

    /** The smallest box holding both. */
    public BoundingBox union(BoundingBox o) {
        return new BoundingBox(Math.min(minX, o.minX), Math.min(minY, o.minY), Math.min(minZ, o.minZ),
                Math.max(maxX, o.maxX), Math.max(maxY, o.maxY), Math.max(maxZ, o.maxZ));
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public long volume() {
        return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
    }
}

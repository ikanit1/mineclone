package com.mineclone.world.shape;

import com.mineclone.world.BlockType;
import com.mineclone.world.World;
import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3f;

/** Finds a nearby horizontal, collision-free pose for a body clipped by a block edit or crowd. */
public final class BodyCollision {
    private static final float EPS = 1e-4f;
    private static final ThreadLocal<float[]> BOXES = ThreadLocal.withInitial(BlockShape::buffer);

    private BodyCollision() {}

    /**
     * Only repairs a small overlap. A body sealed inside solid blocks stays in place
     * until the obstruction is removed; it must never escape through a ceiling.
     */
    public static boolean recoverHorizontal(World world, Vector3f pos, float width, float height) {
        if (!overlaps(world, pos.x, pos.y, pos.z, width, height))
            return false;
        float half = width * 0.5f;
        float reach = Math.min(0.5f, half + 0.15f);
        List<Float> xs = new ArrayList<>();
        List<Float> zs = new ArrayList<>();
        xs.add(pos.x);
        zs.add(pos.z);
        forEachOverlap(world, pos.x, pos.y, pos.z, width, height, (x0, z0, x1, z1) -> {
            xs.add(x0 - half - EPS);
            xs.add(x1 + half + EPS);
            zs.add(z0 - half - EPS);
            zs.add(z1 + half + EPS);
        });

        float bestX = pos.x, bestZ = pos.z, bestDistance = Float.POSITIVE_INFINITY;
        for (float x : xs) for (float z : zs) {
            float dx = x - pos.x, dz = z - pos.z;
            if (Math.abs(dx) > reach || Math.abs(dz) > reach)
                continue;
            float distance = dx * dx + dz * dz;
            if (distance >= bestDistance || overlaps(world, x, pos.y, z, width, height))
                continue;
            bestDistance = distance;
            bestX = x;
            bestZ = z;
        }
        if (!Float.isFinite(bestDistance))
            return false;
        pos.x = bestX;
        pos.z = bestZ;
        return true;
    }

    /** Exact AABB/shape overlap, excluding a face that merely touches the body. */
    public static boolean overlaps(World world, float x, float y, float z, float width, float height) {
        float half = width * 0.5f;
        int minX = (int) Math.floor(x - half), maxX = (int) Math.floor(x + half);
        int minY = (int) Math.floor(y), maxY = (int) Math.floor(y + height);
        int minZ = (int) Math.floor(z - half), maxZ = (int) Math.floor(z + half);
        float[] boxes = BOXES.get();
        for (int bx = minX; bx <= maxX; bx++)
            for (int by = minY; by <= maxY; by++)
                for (int bz = minZ; bz <= maxZ; bz++) {
                    BlockType block = world.getBlock(bx, by, bz);
                    if (!block.solid) continue;
                    BlockShape shape = Shapes.of(block);
                    byte meta = Shapes.meta(world, shape, bx, by, bz);
                    int count = shape.collision(meta, boxes);
                    for (int i = 0; i < count; i++) {
                        int o = i * BlockShape.STRIDE;
                        if (x + half > bx + boxes[o] && x - half < bx + boxes[o + 3]
                                && y + height > by + boxes[o + 1] && y < by + boxes[o + 4]
                                && z + half > bz + boxes[o + 2] && z - half < bz + boxes[o + 5])
                            return true;
                    }
                }
        return false;
    }

    private interface Overlap { void accept(float x0, float z0, float x1, float z1); }

    private static void forEachOverlap(World world, float x, float y, float z, float width, float height,
                                       Overlap action) {
        float half = width * 0.5f;
        int minX = (int) Math.floor(x - half), maxX = (int) Math.floor(x + half);
        int minY = (int) Math.floor(y), maxY = (int) Math.floor(y + height);
        int minZ = (int) Math.floor(z - half), maxZ = (int) Math.floor(z + half);
        float[] boxes = BOXES.get();
        for (int bx = minX; bx <= maxX; bx++)
            for (int by = minY; by <= maxY; by++)
                for (int bz = minZ; bz <= maxZ; bz++) {
                    BlockType block = world.getBlock(bx, by, bz);
                    if (!block.solid) continue;
                    BlockShape shape = Shapes.of(block);
                    byte meta = Shapes.meta(world, shape, bx, by, bz);
                    int count = shape.collision(meta, boxes);
                    for (int i = 0; i < count; i++) {
                        int o = i * BlockShape.STRIDE;
                        float x0 = bx + boxes[o], y0 = by + boxes[o + 1], z0 = bz + boxes[o + 2];
                        float x1 = bx + boxes[o + 3], y1 = by + boxes[o + 4], z1 = bz + boxes[o + 5];
                        if (x + half <= x0 || x - half >= x1 || y + height <= y0 || y >= y1
                                || z + half <= z0 || z - half >= z1)
                            continue;
                        // A shallow floor/ceiling contact belongs to vertical collision,
                        // not to the horizontal recovery used for a body inside a wall.
                        if (Math.min(y + height, y1) - Math.max(y, y0) <= 0.2f)
                            continue;
                        action.accept(x0, z0, x1, z1);
                    }
                }
    }
}

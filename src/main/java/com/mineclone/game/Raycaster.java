package com.mineclone.game;

import com.mineclone.world.BlockType;
import com.mineclone.world.World;
import com.mineclone.world.entity.EntityPhysics;
import com.mineclone.world.shape.BlockShape;
import com.mineclone.world.shape.Shapes;
import org.joml.Vector3f;

/**
 * Voxel DDA raycaster (Amanatides & Woo) over the block shapes (BLK-02).
 *
 * <p>The DDA walks cells; a cell is hit where the ray meets one of its outline
 * boxes ({@link Shapes}), and the normal is the face of that box. A cube is
 * decided exactly as before shapes existed. Air and fluids have no outline, so
 * the ray passes through water and lava alike: you cannot "stand on" either
 * with the cursor, right-clicking the surface places the block at the fluid's
 * cell, and lava is never a block to break (TD-50). A ray through an open
 * doorway misses the door's panel and reaches the block behind it.
 */
public final class Raycaster {

    public static final class Hit {
        public final int x, y, z;
        public final int nx, ny, nz;
        /**
         * Along the ray to the point it entered the target; 0 when the eye
         * starts inside it, negative only if rounding missed a cube the walk hit.
         */
        public final float distance;

        public Hit(int x, int y, int z, int nx, int ny, int nz, float distance) {
            this.x = x; this.y = y; this.z = z;
            this.nx = nx; this.ny = ny; this.nz = nz;
            this.distance = distance;
        }
    }

    private Raycaster() {}

    public static Hit cast(World world, Vector3f origin, Vector3f dir, float maxDist) {
        int x = (int) Math.floor(origin.x);
        int y = (int) Math.floor(origin.y);
        int z = (int) Math.floor(origin.z);

        int stepX = (int) Math.signum(dir.x);
        int stepY = (int) Math.signum(dir.y);
        int stepZ = (int) Math.signum(dir.z);
        if (stepX == 0) stepX = 1;
        if (stepY == 0) stepY = 1;
        if (stepZ == 0) stepZ = 1;

        float tDeltaX = Math.abs(1f / (dir.x == 0 ? 1e-9f : dir.x));
        float tDeltaY = Math.abs(1f / (dir.y == 0 ? 1e-9f : dir.y));
        float tDeltaZ = Math.abs(1f / (dir.z == 0 ? 1e-9f : dir.z));

        float tMaxX = ((stepX > 0 ? (x + 1) : x) - origin.x) / (dir.x == 0 ? 1e-9f : dir.x);
        float tMaxY = ((stepY > 0 ? (y + 1) : y) - origin.y) / (dir.y == 0 ? 1e-9f : dir.y);
        float tMaxZ = ((stepZ > 0 ? (z + 1) : z) - origin.z) / (dir.z == 0 ? 1e-9f : dir.z);

        float[] boxes = null;
        int nx = 0, ny = 0, nz = 0;
        float t = 0;
        while (t <= maxDist) {
            BlockType bt = world.getBlock(x, y, z);
            BlockShape shape = Shapes.of(bt);
            if (shape == Shapes.FULL) {
                return new Hit(x, y, z, nx, ny, nz, cubeDistance(origin, dir, x, y, z));
            }
            if (shape != Shapes.EMPTY) {
                if (boxes == null)
                    boxes = BlockShape.buffer();
                int n = shape.outline(Shapes.meta(world, shape, x, y, z), boxes);
                if (n == 1 && BlockShape.unit(boxes, 0))
                    return new Hit(x, y, z, nx, ny, nz, cubeDistance(origin, dir, x, y, z));
                Hit hit = hitBoxes(origin, dir, maxDist, x, y, z, boxes, n, nx, ny, nz);
                if (hit != null)
                    return hit;
            }
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                x += stepX; t = tMaxX; tMaxX += tDeltaX; nx = -stepX; ny = 0; nz = 0;
            } else if (tMaxY < tMaxZ) {
                y += stepY; t = tMaxY; tMaxY += tDeltaY; nx = 0; ny = -stepY; nz = 0;
            } else {
                z += stepZ; t = tMaxZ; tMaxZ += tDeltaZ; nx = 0; ny = 0; nz = -stepZ;
            }
        }
        return null;
    }

    /** How far the ray travels to a whole cell, measured as the aim at mobs always measured it. */
    private static float cubeDistance(Vector3f o, Vector3f d, int x, int y, int z) {
        return EntityPhysics.rayAabbDistance(o.x, o.y, o.z, d.x, d.y, d.z, x, y, z, x + 1f, y + 1f, z + 1f);
    }

    /**
     * The nearest of a cell's boxes along the ray, with the face it entered by.
     * An eye already inside a box hits it at once, with the normal of the cell
     * face the ray came through ({@code cellN*}; zero in the starting cell).
     */
    private static Hit hitBoxes(Vector3f o, Vector3f d, float maxDist, int x, int y, int z,
                                float[] boxes, int n, int cellNx, int cellNy, int cellNz) {
        float best = Float.MAX_VALUE;
        int bestAxis = -1;
        boolean bestInside = false;
        for (int i = 0; i < n; i++) {
            int b = i * BlockShape.STRIDE;
            float tNear = 0f, tFar = Float.MAX_VALUE;
            int nearAxis = -1;
            boolean miss = false;
            for (int axis = 0; axis < 3 && !miss; axis++) {
                float origin = axis == 0 ? o.x : axis == 1 ? o.y : o.z;
                float dirA = axis == 0 ? d.x : axis == 1 ? d.y : d.z;
                float cell = axis == 0 ? x : axis == 1 ? y : z;
                float lo = cell + boxes[b + axis], hi = cell + boxes[b + 3 + axis];
                if (Math.abs(dirA) < 1e-8f) {
                    if (origin < lo || origin > hi) miss = true;
                    continue;
                }
                float t1 = (lo - origin) / dirA, t2 = (hi - origin) / dirA;
                if (t1 > t2) { float s = t1; t1 = t2; t2 = s; }
                if (t1 > tNear) { tNear = t1; nearAxis = axis; }
                if (t2 < tFar) tFar = t2;
                if (tNear > tFar) miss = true;
            }
            if (miss || tNear > maxDist || tNear >= best)
                continue;
            best = tNear;
            bestAxis = nearAxis;
            bestInside = nearAxis < 0;
        }
        if (best == Float.MAX_VALUE)
            return null;
        if (bestInside)
            return new Hit(x, y, z, cellNx, cellNy, cellNz, 0f);
        float dirA = bestAxis == 0 ? d.x : bestAxis == 1 ? d.y : d.z;
        int s = dirA > 0 ? -1 : 1;
        return new Hit(x, y, z, bestAxis == 0 ? s : 0, bestAxis == 1 ? s : 0, bestAxis == 2 ? s : 0, best);
    }
}

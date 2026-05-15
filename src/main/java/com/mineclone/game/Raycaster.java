package com.mineclone.game;

import com.mineclone.world.BlockType;
import com.mineclone.world.World;
import org.joml.Vector3f;

/**
 * Voxel DDA raycaster (Amanatides & Woo).
 *
 * Water (source + flow) is treated as empty for interaction: the ray passes
 * through it. This matches Minecraft — you can't "stand on" water with the
 * cursor, and right-clicking the surface places the block AT the water cell
 * (replacing it) rather than perched on top.
 */
public final class Raycaster {

    public static final class Hit {
        public final int x, y, z;
        public final int nx, ny, nz;
        public Hit(int x, int y, int z, int nx, int ny, int nz) {
            this.x = x; this.y = y; this.z = z;
            this.nx = nx; this.ny = ny; this.nz = nz;
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

        int nx = 0, ny = 0, nz = 0;
        float t = 0;
        while (t <= maxDist) {
            BlockType bt = world.getBlock(x, y, z);
            if (bt != BlockType.AIR
                    && bt != BlockType.WATER
                    && bt != BlockType.WATER_FLOW) {
                return new Hit(x, y, z, nx, ny, nz);
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
}

package com.mineclone;

import com.mineclone.game.Raycaster;
import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.World;
import com.mineclone.world.entity.EntityPhysics;
import java.util.Random;
import org.joml.Vector3f;

/**
 * BLK-02: the aim hits a block's shape, not its cell. Cubes are hit exactly as
 * before; stairs by their slab and step, doors by their panel, and fluids —
 * lava included (TD-50) — not at all.
 */
final class RaycastShapeTests {
    static void runAll(TestMain.Runner r) {
        r.run("cubes are aimed at exactly as the old walk did", RaycastShapeTests::cubeParity);
        r.run("the aim lands on a stair's slab and on its step", RaycastShapeTests::stairTops);
        r.run("a stair's step is hit from the front and from behind", RaycastShapeTests::stairSides);
        r.run("a ray over the slab reaches the block behind the stair", RaycastShapeTests::overTheSlab);
        r.run("lava is not a target; the block under it is", RaycastShapeTests::lava);
        r.run("an open doorway is aimed through; its panel is hit on its face", RaycastShapeTests::doorway);
        r.run("a torch is hit by its stick, not its cell", RaycastShapeTests::torch);
        r.run("snow is hit on its top and passed over above it", RaycastShapeTests::snow);
    }

    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    private static final int FLOOR = 40;

    /** Stone to {@link #FLOOR}, air above, over x and z in -16..31. */
    private static World ground() {
        World w = new World(7L);
        for (int cx = -1; cx <= 1; cx++)
            for (int cz = -1; cz <= 1; cz++) {
                Chunk c = w.getChunk(cx, cz);
                for (int x = 0; x < Chunk.SIZE_X; x++)
                    for (int z = 0; z < Chunk.SIZE_Z; z++)
                        for (int y = 0; y < Chunk.SIZE_Y; y++)
                            c.set(x, y, z, y <= FLOOR ? BlockType.STONE : BlockType.AIR);
            }
        return w;
    }

    private static Raycaster.Hit cast(World w, float ox, float oy, float oz, float dx, float dy, float dz) {
        Vector3f dir = new Vector3f(dx, dy, dz).normalize();
        return Raycaster.cast(w, new Vector3f(ox, oy, oz), dir, 6f);
    }

    private static void hit(Raycaster.Hit h, int x, int y, int z, int nx, int ny, int nz, float distance, String what) {
        check(h != null, what + ": missed");
        check(h.x == x && h.y == y && h.z == z, what + ": hit " + h.x + "," + h.y + "," + h.z);
        check(h.nx == nx && h.ny == ny && h.nz == nz, what + ": normal " + h.nx + "," + h.ny + "," + h.nz);
        if (!Float.isNaN(distance))
            check(Math.abs(h.distance - distance) < 1e-4f, what + ": distance " + h.distance + ", not " + distance);
    }

    /** The walk before shapes, transcribed: the first cell that is not air or water. */
    private static int[] legacy(World world, Vector3f origin, Vector3f dir, float maxDist) {
        int x = (int) Math.floor(origin.x), y = (int) Math.floor(origin.y), z = (int) Math.floor(origin.z);
        int stepX = (int) Math.signum(dir.x), stepY = (int) Math.signum(dir.y), stepZ = (int) Math.signum(dir.z);
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
            if (bt != BlockType.AIR && bt != BlockType.WATER && bt != BlockType.WATER_FLOW)
                return new int[] { x, y, z, nx, ny, nz };
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

    private static void cubeParity() {
        Random rnd = new Random(20260924L);
        BlockType[] cubes = { BlockType.STONE, BlockType.DIRT, BlockType.GLASS, BlockType.LEAVES,
                BlockType.CHEST, BlockType.FIRE };
        int compared = 0, hits = 0;
        for (int world = 0; world < 10; world++) {
            World w = new World(world);
            Chunk c = w.getChunk(0, 0);
            for (int x = 0; x < Chunk.SIZE_X; x++)
                for (int z = 0; z < Chunk.SIZE_Z; z++)
                    for (int y = 0; y < Chunk.SIZE_Y; y++) {
                        double roll = rnd.nextDouble();
                        c.set(x, y, z, y < 58 || y > 74 ? BlockType.AIR
                                : roll < 0.12 ? cubes[rnd.nextInt(cubes.length)]
                                : roll < 0.18 ? BlockType.WATER : BlockType.AIR);
                    }
            for (int i = 0; i < 100; i++) {
                Vector3f origin = new Vector3f(2 + rnd.nextFloat() * 12, 60 + rnd.nextFloat() * 12,
                        2 + rnd.nextFloat() * 12);
                Vector3f dir = new Vector3f(rnd.nextFloat() * 2 - 1, rnd.nextFloat() * 2 - 1,
                        rnd.nextFloat() * 2 - 1);
                if (i % 10 == 0) dir.set(rnd.nextInt(3) - 1, rnd.nextInt(3) - 1, rnd.nextInt(3) - 1);
                if (dir.lengthSquared() < 1e-4f) dir.set(0, -1, 0);
                dir.normalize();
                int[] old = legacy(w, origin, dir, 6f);
                Raycaster.Hit now = Raycaster.cast(w, origin, dir, 6f);
                compared++;
                check((old == null) == (now == null), "ray " + compared + ": hit changed");
                if (old == null) continue;
                hits++;
                check(now.x == old[0] && now.y == old[1] && now.z == old[2]
                                && now.nx == old[3] && now.ny == old[4] && now.nz == old[5],
                        "ray " + compared + " at " + origin + " dir " + dir + ": a cube is aimed differently");
                float t = EntityPhysics.rayAabbDistance(origin.x, origin.y, origin.z, dir.x, dir.y, dir.z,
                        now.x, now.y, now.z, now.x + 1f, now.y + 1f, now.z + 1f);
                check(now.distance == t, "a cube's distance is the one the aim at mobs used");
            }
        }
        check(compared == 1000 && hits > 300, "the rays must mostly hit something: " + hits);
    }

    /** Stair facing 1: slab over the whole cell, step on the high-X half. */
    private static World stair(int facing) {
        World w = ground();
        w.setBlock(5, FLOOR + 1, 5, BlockType.STAIRS, (byte) facing);
        return w;
    }

    private static void stairTops() {
        World w = stair(1);
        float eye = FLOOR + 3.5f;
        hit(cast(w, 5.25f, eye, 5.5f, 0, -1, 0), 5, FLOOR + 1, 5, 0, 1, 0, eye - (FLOOR + 1.5f), "slab top");
        hit(cast(w, 5.75f, eye, 5.5f, 0, -1, 0), 5, FLOOR + 1, 5, 0, 1, 0, eye - (FLOOR + 2f), "step top");
        // The bottom of the slab, from a cave under it.
        w.setBlock(5, FLOOR, 5, BlockType.AIR);
        w.setBlock(5, FLOOR - 1, 5, BlockType.AIR);
        hit(cast(w, 5.25f, FLOOR - 0.5f, 5.5f, 0, 1, 0), 5, FLOOR + 1, 5, 0, -1, 0, 1.5f, "slab bottom");
    }

    private static void stairSides() {
        // Facing 1: the step's riser faces -X at x = 5.5, its back is the cell's +X face.
        World w = stair(1);
        float y = FLOOR + 1.75f;
        hit(cast(w, 3.0f, y, 5.5f, 1, 0, 0), 5, FLOOR + 1, 5, -1, 0, 0, 2.5f, "riser from the front");
        hit(cast(w, 8.0f, y, 5.5f, -1, 0, 0), 5, FLOOR + 1, 5, 1, 0, 0, 2.0f, "step from behind");
        // Facing 0: the step is on the low-Z half; from behind (-Z) the ray meets the cell face.
        World w0 = stair(0);
        hit(cast(w0, 5.5f, y, 2.5f, 0, 0, 1), 5, FLOOR + 1, 5, 0, 0, -1, 2.5f, "facing 0 from behind");
        hit(cast(w0, 5.5f, y, 8.5f, 0, 0, -1), 5, FLOOR + 1, 5, 0, 0, 1, 3.0f, "facing 0 riser");
    }

    private static void overTheSlab() {
        // Facing 0 (step at low Z): at y + 0.75 the high-Z half of the cell is open.
        World w = stair(0);
        w.setBlock(8, FLOOR + 1, 5, BlockType.STONE);
        Raycaster.Hit h = cast(w, 3.0f, FLOOR + 1.75f, 5.75f, 1, 0, 0);
        hit(h, 8, FLOOR + 1, 5, -1, 0, 0, 5.0f, "over the slab to the stone behind");
        // The same ray through the step's half is stopped by the stair.
        hit(cast(w, 3.0f, FLOOR + 1.75f, 5.25f, 1, 0, 0), 5, FLOOR + 1, 5, -1, 0, 0, 2.0f, "into the step");
    }

    private static void lava() {
        World w = ground();
        w.setBlock(5, FLOOR, 5, BlockType.LAVA);
        w.setBlock(5, FLOOR + 1, 5, BlockType.LAVA);
        hit(cast(w, 5.5f, FLOOR + 3.5f, 5.5f, 0, -1, 0), 5, FLOOR - 1, 5, 0, 1, 0, 3.5f,
                "the aim goes through lava to the stone under it");
        // Water, as before.
        w.setBlock(5, FLOOR, 5, BlockType.WATER);
        w.setBlock(5, FLOOR + 1, 5, BlockType.WATER);
        hit(cast(w, 5.5f, FLOOR + 3.5f, 5.5f, 0, -1, 0), 5, FLOOR - 1, 5, 0, 1, 0, 3.5f, "water");
    }

    private static void doorway() {
        World w = ground();
        // Open door facing 0: the panel swings to the cell's high-X edge.
        w.setBlock(5, FLOOR + 1, 5, BlockType.DOOR_OPEN, (byte) 0);
        w.setBlock(5, FLOOR + 1, 8, BlockType.STONE);
        hit(cast(w, 5.4f, FLOOR + 1.5f, 3.0f, 0, 0, 1), 5, FLOOR + 1, 8, 0, 0, -1, 5.0f,
                "through the doorway to the wall behind");
        float th = 3f / 16f;
        hit(cast(w, 3.0f, FLOOR + 1.5f, 5.5f, 1, 0, 0), 5, FLOOR + 1, 5, -1, 0, 0, 2f + (1f - th),
                "the open panel on its face");
        // An eye inside the door's cell but outside the panel still hits the panel's face.
        hit(cast(w, 5.2f, FLOOR + 1.5f, 5.5f, 1, 0, 0), 5, FLOOR + 1, 5, -1, 0, 0, 0.8f - th,
                "the panel from inside the doorway");
        // A closed door facing 0 stands at the high-Z edge.
        w.setBlock(5, FLOOR + 1, 5, BlockType.DOOR_CLOSED, (byte) 0);
        hit(cast(w, 5.5f, FLOOR + 1.5f, 3.0f, 0, 0, 1), 5, FLOOR + 1, 5, 0, 0, -1, 2f + (1f - th),
                "a closed panel from the far side of its cell");
    }

    private static void torch() {
        World w = ground();
        w.setBlock(5, FLOOR + 1, 5, BlockType.TORCH, (byte) 0);
        w.setBlock(5, FLOOR + 1, 8, BlockType.STONE);
        hit(cast(w, 5.1f, FLOOR + 1.3f, 3.0f, 0, 0, 1), 5, FLOOR + 1, 8, 0, 0, -1, 5.0f,
                "past the torch through its empty cell");
        float front = 0.5f - 2.5f / 16f;
        hit(cast(w, 5.5f, FLOOR + 1.3f, 3.0f, 0, 0, 1), 5, FLOOR + 1, 5, 0, 0, -1, 2f + front, "the stick");
        // An eye inside the stick's box hits it at once, with no face.
        hit(cast(w, 5.5f, FLOOR + 1.3f, 5.5f, 0, 0, 1), 5, FLOOR + 1, 5, 0, 0, 0, 0f, "from inside the stick");
    }

    private static void snow() {
        World w = ground();
        w.setBlock(5, FLOOR + 1, 5, BlockType.SNOW_LAYER, (byte) 1);
        w.setBlock(8, FLOOR + 1, 5, BlockType.STONE);
        hit(cast(w, 5.5f, FLOOR + 3f, 5.5f, 0, -1, 0), 5, FLOOR + 1, 5, 0, 1, 0, 2f - 0.25f, "a quarter-block of snow");
        hit(cast(w, 3.0f, FLOOR + 1.5f, 5.5f, 1, 0, 0), 8, FLOOR + 1, 5, -1, 0, 0, 5.0f, "over the snow");
        hit(cast(w, 3.0f, FLOOR + 1.1f, 5.5f, 1, 0, 0), 5, FLOOR + 1, 5, -1, 0, 0, 2.0f, "into its side");
    }
}

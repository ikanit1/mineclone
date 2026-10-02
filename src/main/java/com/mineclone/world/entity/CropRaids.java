package com.mineclone.world.entity;

import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.World;
import com.mineclone.world.behavior.Behaviors;
import com.mineclone.world.shape.BodyCollision;
import org.joml.Vector3f;

import java.util.List;

/** Bounded, loaded-terrain crop seeking; a raid consumes a plant only after physically reaching it. */
final class CropRaids {
    private static final int RANGE = 16;
    private static final int CELL_BUDGET = 192;
    private static final int PATH_BUDGET = 2;
    private static final int CANDIDATES = 6;
    private static final float SEARCH_INTERVAL = 1.5f;
    private static final float MEAL_REACH_SQUARED = 0.7f * 0.7f;
    private static final float SATED_MIN = 45f, SATED_MAX = 90f;
    private static final float RETRY_DELAY = 8f;
    private final int[] candidateX = new int[CANDIDATES];
    private final int[] candidateY = new int[CANDIDATES];
    private final int[] candidateZ = new int[CANDIDATES];
    private final float[] candidateDistance = new float[CANDIDATES];
    private final int[] explorationX = new int[PATH_BUDGET], explorationY = new int[PATH_BUDGET],
            explorationZ = new int[PATH_BUDGET], explorationNext = new int[PATH_BUDGET];
    private final int[] rejectedX = new int[32], rejectedY = new int[32], rejectedZ = new int[32];
    private final float[] rejectedUntil = new float[32];
    private final Chunk[] chunks = new Chunk[9];
    private final Vector3f goal = new Vector3f();
    private float searchTimer, elapsed, blocked;
    private int scanCursor, rejectedCursor, candidateCount, explorationCount;
    private int x, y, z;
    private boolean active;

    CropRaids(Mob mob) {
        long hash = mob.id ^ (long) Math.floor(mob.position.x) * 0x9e3779b97f4a7c15L
                ^ (long) Math.floor(mob.position.z) * 0xbf58476d1ce4e5b9L;
        hash ^= hash >>> 30;
        hash *= 0xbf58476d1ce4e5b9L;
        hash ^= hash >>> 27;
        searchTimer = (hash & 1023) / 1024f * SEARCH_INTERVAL;
    }

    boolean active() { return active; }

    void cancel(Mob mob) {
        if (active) mob.clearCropPath();
        active = false;
        blocked = 0f;
    }

    /** True while following a raid or after eating, so wandering cannot override its movement. */
    boolean tick(Mob mob, MobContext context) {
        elapsed += context.dt;
        searchTimer -= context.dt;
        if (active && !edible(context.world, x, y, z)) cancel(mob);
        if (!active && searchTimer <= 0f) {
            searchTimer = SEARCH_INTERVAL;
            seek(mob, context.world);
        }
        if (!active) return false;

        float dx = goal.x - mob.position.x, dz = goal.z - mob.position.z;
        if (dx * dx + dz * dz <= MEAL_REACH_SQUARED
                && Math.abs(goal.y - mob.position.y) < 0.8f
                && !BodyCollision.overlaps(context.world, mob.position.x, mob.position.y,
                        mob.position.z, mob.type.width, mob.type.height)
                && mouthLineClear(context.world, mob.position.x, mob.position.y + 0.32f,
                        mob.position.z, goal.x, goal.y + 0.32f, goal.z)) {
            BlockType crop = context.world.getBlock(x, y, z);
            // Same-type metadata edits neither remove the plant nor invoke harvest drops.
            context.world.setBlock(x, y, z, crop, (byte) 0);
            Behaviors.of(crop).onPlaced(context.world, x, y, z, (byte) 0);
            mob.hungerTimer = SATED_MIN + mob.rnd.nextFloat() * (SATED_MAX - SATED_MIN);
            cancel(mob);
            mob.enterIdle();
            return true;
        }

        blocked = mob.hitWall ? blocked + context.dt : Math.max(0f, blocked - context.dt);
        if (blocked >= 1f) {
            reject(x, y, z);
            cancel(mob);
            mob.enterIdle();
            return true;
        }
        mob.state = Mob.State.WANDER;
        if (!mob.followCropPath()) {
            // A route ending inside the plant's cell may finish its final fraction.
            // Never turn an unreachable crop into a direct walk through its enclosure.
            if ((int) Math.floor(mob.position.x) == x && (int) Math.floor(mob.position.z) == z)
                mob.setMoveDirection(dx, dz);
            else {
                reject(x, y, z);
                cancel(mob);
                mob.enterIdle();
            }
        }
        return true;
    }

    private void seek(Mob mob, World world) {
        int centreX = (int) Math.floor(mob.position.x) >> 4;
        int centreZ = (int) Math.floor(mob.position.z) >> 4;
        int count = 0, total = 0;
        for (int cx = centreX - 1; cx <= centreX + 1; cx++)
            for (int cz = centreZ - 1; cz <= centreZ + 1; cz++) {
                Chunk chunk = world.getChunkIfExists(cx, cz);
                if (chunk == null || chunk.isReadOnly() || chunk.cropCellCount() == 0) continue;
                chunks[count++] = chunk;
                total += chunk.cropCellCount();
            }
        if (total == 0) return;
        candidateCount = 0;
        explorationCount = 0;
        scanCursor = Math.floorMod(scanCursor, total);
        int currentChunk = 0, local = scanCursor;
        while (local >= chunks[currentChunk].cropCellCount())
            local -= chunks[currentChunk++].cropCellCount();
        int inspect = Math.min(CELL_BUDGET, total);
        for (int n = 0; n < inspect; n++) {
            Chunk chunk = chunks[currentChunk];
            int cell = chunk.cropCellAt(local);
            int cx = chunk.cx * 16 + cell % 16;
            int cy = cell / 256;
            int cz = chunk.cz * 16 + cell / 16 % 16;
            float dx = cx + 0.5f - mob.position.x, dz = cz + 0.5f - mob.position.z;
            float distance = dx * dx + dz * dz;
            if (distance <= RANGE * RANGE && Math.abs(cy - mob.position.y) <= 4f
                    && (chunk.getMeta(cell % 16, cy, cell / 16 % 16) & 7) >= 4
                    && !rejected(cx, cy, cz) && edible(world, cx, cy, cz)) {
                candidate(cx, cy, cz, distance);
                if (explorationCount < PATH_BUDGET) {
                    explorationX[explorationCount] = cx;
                    explorationY[explorationCount] = cy;
                    explorationZ[explorationCount] = cz;
                    explorationNext[explorationCount++] = (scanCursor + n + 1) % total;
                }
            }
            if (++local == chunk.cropCellCount()) {
                local = 0;
                currentChunk = (currentChunk + 1) % count;
            }
        }
        // Keep the first attempt near, but reserve the second for rotating exploration.
        // Advancing past that plant (also when inspect == total) prevents expiring
        // rejections from endlessly recycling the nearest inaccessible cluster.
        int exploration = 0;
        if (candidateCount > 0 && explorationCount > 0
                && explorationX[0] == candidateX[0] && explorationY[0] == candidateY[0]
                && explorationZ[0] == candidateZ[0]) exploration = 1;
        if (exploration < explorationCount) scanCursor = explorationNext[exploration];
        else if (explorationCount > 0) scanCursor = explorationNext[0];
        else scanCursor = (scanCursor + (inspect == total ? 1 : inspect)) % total;
        for (int n = 0; n < PATH_BUDGET && candidateCount > 0; n++) {
            if (n > 0 && exploration >= explorationCount) break;
            int cx = n == 0 ? candidateX[0] : explorationX[exploration];
            int cy = n == 0 ? candidateY[0] : explorationY[exploration];
            int cz = n == 0 ? candidateZ[0] : explorationZ[exploration];
            if (!edible(world, cx, cy, cz)) continue;
            Vector3f target = new Vector3f(cx + 0.5f, cy - 1f + 15f / 16f, cz + 0.5f);
            if (BodyCollision.overlaps(world, target.x, target.y, target.z,
                    mob.type.width, mob.type.height)) {
                reject(cx, cy, cz);
                continue;
            }
            List<Vector3f> route = PathFinder.find(world,
                    (int) Math.floor(mob.position.x), (int) Math.floor(mob.position.y + 0.1f),
                    (int) Math.floor(mob.position.z), cx, cy, cz,
                    Math.max(1, (int) Math.ceil(mob.type.height)));
            if (route == null || !routeClear(world, mob, route)) {
                reject(cx, cy, cz);
                continue;
            }
            x = cx; y = cy; z = cz;
            goal.set(target);
            active = true;
            blocked = 0f;
            mob.beginCropPath(route, goal);
            return;
        }
    }

    private static boolean edible(World world, int x, int y, int z) {
        Chunk chunk = world.getChunkIfExists(x >> 4, z >> 4);
        if (chunk == null || chunk.isReadOnly()) return false;
        BlockType crop = world.getBlock(x, y, z);
        return (crop == BlockType.WHEAT || crop == BlockType.CARROTS || crop == BlockType.POTATOES)
                && (world.getBlockMeta(x, y, z) & 7) >= 4
                && world.getBlock(x, y - 1, z) == BlockType.FARMLAND;
    }

    private static boolean routeClear(World world, Mob mob, List<Vector3f> route) {
        // A pathfinder cell is not enough when a neighbouring shape projects into the body.
        for (Vector3f point : route)
            if (BodyCollision.overlaps(world, point.x, point.y + 0.001f, point.z,
                    mob.type.width, mob.type.height)) return false;
        return true;
    }

    private static boolean mouthLineClear(World world, float x0, float y0, float z0,
                                           float x1, float y1, float z1) {
        float dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        int steps = Math.max(1, (int) Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz) / 0.08f));
        for (int n = 0; n <= steps; n++) {
            float t = n / (float) steps;
            if (BodyCollision.overlaps(world, x0 + dx * t, y0 + dy * t, z0 + dz * t,
                    0.025f, 0.025f)) return false;
        }
        return true;
    }

    private void candidate(int cx, int cy, int cz, float distance) {
        int index = 0;
        while (index < candidateCount && candidateDistance[index] <= distance) index++;
        if (index == CANDIDATES) return;
        for (int n = Math.min(candidateCount, CANDIDATES - 1); n > index; n--) {
            candidateX[n] = candidateX[n - 1];
            candidateY[n] = candidateY[n - 1];
            candidateZ[n] = candidateZ[n - 1];
            candidateDistance[n] = candidateDistance[n - 1];
        }
        candidateX[index] = cx; candidateY[index] = cy; candidateZ[index] = cz;
        candidateDistance[index] = distance;
        candidateCount = Math.min(CANDIDATES, candidateCount + 1);
    }

    private boolean rejected(int cx, int cy, int cz) {
        for (int n = 0; n < rejectedUntil.length; n++)
            if (rejectedUntil[n] > elapsed && rejectedX[n] == cx && rejectedY[n] == cy
                    && rejectedZ[n] == cz) return true;
        return false;
    }

    private void reject(int cx, int cy, int cz) {
        rejectedX[rejectedCursor] = cx;
        rejectedY[rejectedCursor] = cy;
        rejectedZ[rejectedCursor] = cz;
        rejectedUntil[rejectedCursor] = elapsed + RETRY_DELAY;
        rejectedCursor = (rejectedCursor + 1) % rejectedUntil.length;
    }
}

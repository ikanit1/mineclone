package com.mineclone.game;

import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.World;
import com.mineclone.world.entity.EntityPhysics;
import com.mineclone.world.shape.BlockShape;
import com.mineclone.world.shape.Shapes;
import java.util.Random;
import org.joml.Vector3f;

/**
 * BLK-02 against the collision it replaced (roadmap risk R10: the stair
 * teleport of {@code 99a10a4} must not come back).
 *
 * <p>The old code is transcribed here as it stood before shapes. Cubes must
 * collide bit for bit as they did. Stairs must collide as they did wherever
 * the old model was right about which half is underfoot; it decided that by
 * the body's centre, so on a band of the stair it stood the player inside the
 * step, let a head into a stair from below and snapped a body back to the
 * cell's edge — those three are checked to be fixed, on purpose, below.
 *
 * <p>There is no step-up, then or now: {@link PlayerPhysicsTests} holds the
 * user's request that walking into a stair never lifts the player.
 */
public final class CollisionParityTests {

    public interface Check { void run() throws Exception; }
    public interface Runner { void run(String name, Check check); }

    private CollisionParityTests() {}

    public static void runAll(Runner r) {
        r.run("cubes collide bit for bit as before shapes: 1000 random moves", CollisionParityTests::cubeParity);
        r.run("stairs collide as before wherever the old model saw the right half: 1000 random moves",
                CollisionParityTests::stairParity);
        r.run("where the old stair model was wrong, the boxes are right", CollisionParityTests::stairFixes);
        r.run("mobs collide with cubes bit for bit as before: 1000 random steps", CollisionParityTests::mobCubeParity);
        r.run("a mob stands on a stair's slab and bumps into its step", CollisionParityTests::mobOnStairs);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    // --- the player ---------------------------------------------------------

    /** The player's collision before BLK-02, transcribed from {@code Player} at 6680ec6. */
    static final class Legacy {
        static final float WIDTH = Player.WIDTH, HEIGHT = Player.HEIGHT;
        final Vector3f position = new Vector3f(), velocity = new Vector3f();
        boolean onGround, isSprinting;

        void moveAxis(World world, float dx, float dy, float dz) {
            float previousBottom = position.y;
            float previousTop = previousBottom + HEIGHT;
            position.x += dx;
            position.y += dy;
            position.z += dz;

            float hw = WIDTH / 2f;
            float minX = position.x - hw, maxX = position.x + hw;
            float minY = position.y, maxY = position.y + HEIGHT;
            float minZ = position.z - hw, maxZ = position.z + hw;

            int x0 = (int) Math.floor(minX), x1 = (int) Math.floor(maxX);
            int y0 = (int) Math.floor(minY), y1 = (int) Math.floor(maxY);
            int z0 = (int) Math.floor(minZ), z1 = (int) Math.floor(maxZ);

            for (int x = x0; x <= x1; x++)
                for (int y = y0; y <= y1; y++)
                    for (int z = z0; z <= z1; z++) {
                        BlockType b = world.getBlock(x, y, z);
                        if (!b.solid)
                            continue;
                        if (maxX <= x || minX >= x + 1 || maxY <= y || minY >= y + 1
                                || maxZ <= z || minZ >= z + 1)
                            continue;
                        if (b == BlockType.STAIRS) {
                            resolveStairs(world, x, y, z, dx, dy, dz, hw);
                            minX = position.x - hw;
                            maxX = position.x + hw;
                            minY = position.y;
                            maxY = position.y + HEIGHT;
                            minZ = position.z - hw;
                            maxZ = position.z + hw;
                            continue;
                        }
                        if (dx > 0) {
                            position.x = x - hw - 1e-4f;
                            velocity.x = 0;
                            isSprinting = false;
                        } else if (dx < 0) {
                            position.x = x + 1 + hw + 1e-4f;
                            velocity.x = 0;
                            isSprinting = false;
                        }
                        if (dy > 0 && previousTop <= y + 1e-4f) {
                            position.y = y - HEIGHT - 1e-4f;
                            velocity.y = 0;
                        } else if (dy < 0 && previousBottom >= y + 1 - 1e-4f) {
                            position.y = y + 1 + 1e-4f;
                            velocity.y = 0;
                            onGround = true;
                        }
                        if (dz > 0) {
                            position.z = z - hw - 1e-4f;
                            velocity.z = 0;
                            isSprinting = false;
                        } else if (dz < 0) {
                            position.z = z + 1 + hw + 1e-4f;
                            velocity.z = 0;
                            isSprinting = false;
                        }
                        minX = position.x - hw;
                        maxX = position.x + hw;
                        minY = position.y;
                        maxY = position.y + HEIGHT;
                        minZ = position.z - hw;
                        maxZ = position.z + hw;
                    }

            if (dy >= 0)
                onGround = false;
            if (dy == 0 && !onGround) {
                float py = position.y;
                int yb = (int) Math.floor(py - 1e-3);
                int xa = (int) Math.floor(position.x - hw + 1e-3);
                int xb = (int) Math.floor(position.x + hw - 1e-3);
                int za = (int) Math.floor(position.z - hw + 1e-3);
                int zb = (int) Math.floor(position.z + hw - 1e-3);
                outer: for (int xi = xa; xi <= xb; xi++)
                    for (int zi = za; zi <= zb; zi++) {
                        BlockType bl = world.getBlock(xi, yb, zi);
                        if (bl == BlockType.STAIRS) {
                            byte m = world.getBlockMeta(xi, yb, zi);
                            int f2 = m & 0x3;
                            float rx = position.x - xi, rz = position.z - zi;
                            boolean step = switch (f2) {
                                case 0 -> rz < 0.5f;
                                case 1 -> rx >= 0.5f;
                                case 2 -> rz >= 0.5f;
                                default -> rx < 0.5f;
                            };
                            float top = step ? yb + 1.0f : yb + 0.5f;
                            if (Math.abs(py - top) < 0.1f) {
                                onGround = true;
                                break outer;
                            }
                        } else if (bl.solid) {
                            onGround = true;
                            break outer;
                        }
                    }
            }
        }

        private void resolveStairs(World world, int bx, int by, int bz, float dx, float dy, float dz, float hw) {
            byte meta = world.getBlockMeta(bx, by, bz);
            int facing = meta & 0x3;
            float relX = position.x - bx;
            float relZ = position.z - bz;
            boolean overStep = switch (facing) {
                case 0 -> relZ < 0.5f;
                case 1 -> relX >= 0.5f;
                case 2 -> relZ >= 0.5f;
                default -> relX < 0.5f;
            };
            float topSurface = overStep ? by + 1.0f : by + 0.5f;
            if (dy < 0) {
                float prevY = position.y - dy;
                if (prevY >= topSurface - 1e-3f && position.y < topSurface) {
                    position.y = topSurface + 1e-4f;
                    velocity.y = 0;
                    onGround = true;
                }
            } else if (dy > 0) {
                if (overStep && position.y + HEIGHT >= by + 1.0f - 1e-3f
                        && position.y + HEIGHT <= by + 1.0f + HEIGHT) {
                    position.y = by + 1.0f - HEIGHT - 1e-4f;
                    velocity.y = 0;
                }
            } else {
                if (position.y < topSurface && position.y + HEIGHT > by) {
                    if (dx > 0) {
                        position.x = bx - hw - 1e-4f;
                        velocity.x = 0;
                    } else if (dx < 0) {
                        position.x = bx + 1 + hw + 1e-4f;
                        velocity.x = 0;
                    }
                    if (dz > 0) {
                        position.z = bz - hw - 1e-4f;
                        velocity.z = 0;
                    } else if (dz < 0) {
                        position.z = bz + 1 + hw + 1e-4f;
                        velocity.z = 0;
                    }
                }
            }
        }
    }

    /** Stone to y = 40, air above, over one chunk. */
    private static World flat(long seed) {
        World w = new World(seed);
        Chunk c = w.getChunk(0, 0);
        for (int x = 0; x < Chunk.SIZE_X; x++)
            for (int z = 0; z < Chunk.SIZE_Z; z++)
                for (int y = 0; y < Chunk.SIZE_Y; y++)
                    c.set(x, y, z, y <= 40 ? BlockType.STONE : BlockType.AIR);
        return w;
    }

    /** One random single-axis move, as {@code Player.update} makes them; a zero move is the ground check alone. */
    private static float[] move(Random rnd) {
        float[] d = new float[3];
        if (rnd.nextInt(5) > 0)
            d[rnd.nextInt(3)] = (rnd.nextFloat() * 2f - 1f) * 0.2f;
        return d;
    }

    private static void start(Random rnd, Player now, Legacy old, float x, float y, float z, boolean sprinting) {
        float vx = rnd.nextFloat() * 8 - 4, vy = rnd.nextFloat() * 16 - 8, vz = rnd.nextFloat() * 8 - 4;
        boolean ground = rnd.nextBoolean(), sprint = sprinting && rnd.nextBoolean();
        now.position.set(x, y, z);
        now.velocity.set(vx, vy, vz);
        now.onGround = ground;
        now.isSprinting = sprint;
        old.position.set(x, y, z);
        old.velocity.set(vx, vy, vz);
        old.onGround = ground;
        old.isSprinting = sprint;
    }

    private static boolean same(Player now, Legacy old, float tolerance) {
        if (tolerance == 0f)
            return Float.floatToIntBits(now.position.x) == Float.floatToIntBits(old.position.x)
                    && Float.floatToIntBits(now.position.y) == Float.floatToIntBits(old.position.y)
                    && Float.floatToIntBits(now.position.z) == Float.floatToIntBits(old.position.z)
                    && now.velocity.equals(old.velocity)
                    && now.onGround == old.onGround && now.isSprinting == old.isSprinting;
        return Math.abs(now.position.x - old.position.x) <= tolerance
                && Math.abs(now.position.y - old.position.y) <= tolerance
                && Math.abs(now.position.z - old.position.z) <= tolerance
                && now.velocity.distance(old.velocity) <= tolerance
                && now.onGround == old.onGround && now.isSprinting == old.isSprinting;
    }

    private static String state(Player now, Legacy old) {
        return "now " + now.position + " v" + now.velocity + " ground " + now.onGround + " sprint " + now.isSprinting
                + " / before " + old.position + " v" + old.velocity + " ground " + old.onGround
                + " sprint " + old.isSprinting;
    }

    /** Random cubes — a closed door among them, which still fills its cell — and random moves. */
    private static void cubeParity() {
        Random rnd = new Random(1_000_001L);
        BlockType[] cubes = { BlockType.STONE, BlockType.GLASS, BlockType.LEAVES, BlockType.DOOR_CLOSED,
                BlockType.CHEST, BlockType.ICE };
        int moves = 0, blocked = 0;
        for (int world = 0; world < 10; world++) {
            World w = flat(world);
            for (int x = 2; x <= 13; x++)
                for (int z = 2; z <= 13; z++)
                    for (int y = 41; y <= 45; y++)
                        if (rnd.nextFloat() < 0.22f)
                            w.setBlock(x, y, z, cubes[rnd.nextInt(cubes.length)], (byte) rnd.nextInt(16));
            for (int i = 0; i < 100; i++) {
                Player now = new Player();
                Legacy old = new Legacy();
                start(rnd, now, old, 4 + rnd.nextFloat() * 8, 41.0001f + rnd.nextFloat() * 3, 4 + rnd.nextFloat() * 8,
                        true);
                float[] d = move(rnd);
                float wantedX = now.position.x + d[0], wantedY = now.position.y + d[1], wantedZ = now.position.z + d[2];
                now.moveAxis(w, d[0], d[1], d[2]);
                old.moveAxis(w, d[0], d[1], d[2]);
                moves++;
                if (now.position.x != wantedX || now.position.y != wantedY || now.position.z != wantedZ) blocked++;
                check(same(now, old, 0f), "move " + moves + " " + java.util.Arrays.toString(d) + ": " + state(now, old));
            }
        }
        check(moves == 1000 && blocked > 150, "the moves must run into blocks often enough: " + blocked);
    }

    /** Along the stair's split axis: where the old centre rule and the boxes disagree about the half underfoot. */
    private static boolean inBand(int facing, float relX, float relZ) {
        float c = facing == 0 || facing == 2 ? relZ : relX;
        boolean stepLow = facing == 0 || facing == 3;
        float margin = 1e-3f;
        return stepLow ? c >= 0.5f - margin && c < 0.8f + margin : c > 0.2f - margin && c < 0.5f + margin;
    }

    /** Whether the footprint reaches the stair's column across the split axis. */
    private static boolean acrossColumn(int facing, float relX, float relZ) {
        float o = facing == 0 || facing == 2 ? relX : relZ;
        return o + 0.3f > 0f && o - 0.3f < 1f;
    }

    private static boolean ambiguous(int facing, float x, float z) {
        return inBand(facing, x - 5, z - 5) && acrossColumn(facing, x - 5, z - 5);
    }

    private static boolean overlapsBoxes(float[] boxes, int n, int cx, int cy, int cz, float x, float y, float z) {
        float hw = Player.WIDTH / 2f;
        for (int i = 0; i < n; i++) {
            int o = i * BlockShape.STRIDE;
            if (x + hw > cx + boxes[o] && x - hw < cx + boxes[o + 3] && y + Player.HEIGHT > cy + boxes[o + 1]
                    && y < cy + boxes[o + 4] && z + hw > cz + boxes[o + 2] && z - hw < cz + boxes[o + 5])
                return true;
        }
        return false;
    }

    /** Whether either model had to look at the stair: the moved body enters its cell, or stands on it. */
    private static boolean touches(float x, float y, float z, float[] d) {
        float hw = Player.WIDTH / 2f;
        boolean column = x + hw > 5 && x - hw < 6 && z + hw > 5 && z - hw < 6;
        boolean groundCheck = d[1] == 0f && Math.floor(y - 1e-3) == 41;
        return column && (y < 42f || groundCheck);
    }

    /**
     * Situations play puts a body in around a stair at (5, 41, 5), in the
     * stair's own terms — {@code c} along the axis that splits slab from step,
     * {@code o} across it: resting on the slab, resting on the step, walking
     * into it from the floor, falling onto it, and anywhere at all.
     *
     * @return {x, y, z, dx, dy, dz}
     */
    private static float[] situation(Random rnd, int facing) {
        boolean stepLow = facing == 0 || facing == 3, splitX = facing == 1 || facing == 3;
        float c, o = 0.2f + rnd.nextFloat() * 0.6f, y;
        float[] d = move(rnd);
        switch (rnd.nextInt(5)) {
            case 0 -> {             // on the slab, clear of the step
                c = stepLow ? 0.8f + rnd.nextFloat() * 0.5f : -0.3f + rnd.nextFloat() * 0.5f;
                y = 41.5001f;
            }
            case 1 -> {             // on the step
                c = stepLow ? -0.3f + rnd.nextFloat() * 0.8f : 0.5f + rnd.nextFloat() * 0.8f;
                y = 42.0001f;
            }
            case 2 -> {             // on the floor, a stride from one of its four sides, walking in
                float gap = 0.3002f + rnd.nextFloat() * 0.15f, speed = rnd.nextFloat() * 0.2f;
                int side = rnd.nextInt(4);
                c = side == 0 ? -gap : side == 1 ? 1 + gap : 0.1f + rnd.nextFloat() * 0.8f;
                o = side == 2 ? -gap : side == 3 ? 1 + gap : 0.1f + rnd.nextFloat() * 0.8f;
                d = new float[3];
                int along = side < 2 ? (splitX ? 0 : 2) : (splitX ? 2 : 0);
                d[along] = side % 2 == 0 ? speed : -speed;
                y = 41.0001f;
            }
            case 3 -> {             // falling onto it
                c = -0.25f + rnd.nextFloat() * 1.5f;
                y = 41.55f + rnd.nextFloat();
                d = new float[] { 0, -rnd.nextFloat() * 0.2f, 0 };
            }
            default -> {            // anywhere around it
                c = -0.6f + rnd.nextFloat() * 2.2f;
                o = -0.6f + rnd.nextFloat() * 2.2f;
                y = 41.0001f + rnd.nextFloat() * 1.6f;
            }
        }
        return new float[] { 5 + (splitX ? c : o), y, 5 + (splitX ? o : c), d[0], d[1], d[2] };
    }

    private static void stairParity() {
        Random rnd = new Random(2_000_002L);
        float[] boxes = BlockShape.buffer();
        World[] worlds = new World[16];
        int compared = 0, touching = 0, attempts = 0;
        int[] perFacing = new int[4];
        while (compared < 1000) {
            check(++attempts < 20_000, "could not draw 1000 comparable stair moves");
            int facing = rnd.nextInt(4);
            byte meta = (byte) (facing | rnd.nextInt(4) << 2);
            if (worlds[meta] == null) {
                worlds[meta] = flat(7L);
                worlds[meta].setBlock(5, 41, 5, BlockType.STAIRS, meta);
            }
            World w = worlds[meta];
            int n = Shapes.STAIRS.collision(meta, boxes);
            float[] at = situation(rnd, facing);
            float x = at[0], y = at[1], z = at[2];
            float[] d = { at[3], at[4], at[5] };
            if (overlapsBoxes(boxes, n, 5, 41, 5, x, y, z))
                continue;                               // no body starts inside a block
            if (ambiguous(facing, x, z) || ambiguous(facing, x + d[0], z + d[2]))
                continue;                               // the old model's centre saw the other half
            Player now = new Player();
            Legacy old = new Legacy();
            // Nobody sprints here: the old stair kept a sprint through a bump, which
            // is the fourth of the fixes below, not a disagreement about geometry.
            start(rnd, now, old, x, y, z, false);
            now.moveAxis(w, d[0], d[1], d[2]);
            old.moveAxis(w, d[0], d[1], d[2]);
            compared++;
            perFacing[facing]++;
            if (touches(x + d[0], y + d[1], z + d[2], d)) touching++;
            check(same(now, old, 1e-4f), "facing " + facing + " from " + x + "," + y + "," + z + " by "
                    + java.util.Arrays.toString(d) + ": " + state(now, old));
        }
        check(touching > 600, "most compared moves must touch the stair: " + touching);
        for (int f = 0; f < 4; f++)
            check(perFacing[f] > 150, "facing " + f + " compared only " + perFacing[f] + " times");
    }

    private static void stairFixes() {
        // 1. The centre on the slab's side, the body over the step: the old model
        //    stood the player at the slab's top — inside the step. Now: on the step.
        {
            World w = flat(3L);
            w.setBlock(5, 41, 5, BlockType.STAIRS, (byte) 1);   // step on the high-X half
            Player now = new Player();
            Legacy old = new Legacy();
            now.position.set(5.35f, 42.3f, 5.5f);
            old.position.set(5.35f, 42.3f, 5.5f);
            for (int i = 0; i < 8; i++) {
                now.moveAxis(w, 0, -0.2f, 0);
                old.moveAxis(w, 0, -0.2f, 0);
            }
            check(Math.abs(old.position.y - 41.5001f) < 1e-3f, "the transcription must reproduce the old sink: " + old.position);
            check(Math.abs(now.position.y - 42.0001f) < 1e-3f && now.onGround, "stand on the step: " + now.position);
        }
        // 2. A stair overhead: the old model let the head into it from below.
        {
            World w = flat(3L);
            w.setBlock(5, 45, 5, BlockType.STAIRS, (byte) 2);
            Player now = new Player();
            Legacy old = new Legacy();
            now.position.set(5.5f, 43.1f, 5.5f);
            old.position.set(5.5f, 43.1f, 5.5f);
            now.velocity.y = old.velocity.y = 8f;
            now.moveAxis(w, 0, 0.2f, 0);
            old.moveAxis(w, 0, 0.2f, 0);
            check(old.position.y + Player.HEIGHT > 45f, "the transcription must reproduce the old head-in-stair");
            check(Math.abs(now.position.y + Player.HEIGHT - 45f) < 1e-3f && now.velocity.y == 0,
                    "the slab's underside is a ceiling: " + now.position);
        }
        // 3. Walking on the slab into the step: the old model let the body into the
        //    step, then threw it back to the far edge of the cell, most of a block
        //    away. Now the step's riser stops it where it stands.
        {
            World w = flat(3L);
            w.setBlock(5, 41, 5, BlockType.STAIRS, (byte) 1);
            Player now = new Player();
            Legacy old = new Legacy();
            now.position.set(5.15f, 41.5001f, 5.5f);
            old.position.set(5.15f, 41.5001f, 5.5f);
            now.onGround = old.onGround = true;
            now.moveAxis(w, 0.2f, 0, 0);
            old.moveAxis(w, 0.2f, 0, 0);
            check(Math.abs(old.position.x - 5.35f) < 1e-3f, "the transcription must walk into the step: " + old.position);
            now.moveAxis(w, 0.2f, 0, 0);
            old.moveAxis(w, 0.2f, 0, 0);
            check(old.position.x < 4.71f, "and then snap back to the cell's edge: " + old.position);
            float riser = 5.5f - Player.WIDTH / 2f - 1e-4f;
            check(Math.abs(now.position.x - riser) < 1e-4f && now.position.y == 41.5001f,
                    "the riser stops the body where it stands: " + now.position);
        }
        // 4. Running into a stair ends a sprint, as running into any block does;
        //    the old stair let the sprint go on against it.
        {
            World w = flat(3L);
            w.setBlock(5, 41, 5, BlockType.STAIRS, (byte) 3);
            Player now = new Player();
            Legacy old = new Legacy();
            now.position.set(4.6f, 41.0001f, 5.5f);
            old.position.set(4.6f, 41.0001f, 5.5f);
            now.isSprinting = old.isSprinting = true;
            now.moveAxis(w, 0.2f, 0, 0);
            old.moveAxis(w, 0.2f, 0, 0);
            check(old.isSprinting && old.position.x < 4.71f, "the transcription must keep the old sprint");
            check(!now.isSprinting && Math.abs(now.position.x - old.position.x) < 1e-6f,
                    "the same stop, and the sprint ends: " + now.position);
        }
    }

    // --- mobs and items ---------------------------------------------------

    /** {@code EntityPhysics.step} before BLK-02, transcribed. */
    private static EntityPhysics.Contact legacyStep(World world, Vector3f pos, Vector3f vel,
                                                    float width, float height, float dt, float maxFallSpeed) {
        if (dt > 0.025f) {
            int steps = (int) Math.ceil(dt / 0.025f);
            EntityPhysics.Contact last = null;
            boolean wall = false;
            for (int i = 0; i < steps; i++) {
                last = legacyStep(world, pos, vel, width, height, dt / steps, maxFallSpeed);
                wall |= last.hitWall();
            }
            return new EntityPhysics.Contact(last.onGround(), wall, last.inWater());
        }
        boolean inWater = legacyWater(world, pos, width, height);
        if (inWater) {
            vel.y += EntityPhysics.GRAVITY * 0.25f * dt;
            if (vel.y < 0.6f)
                vel.y += EntityPhysics.BUOYANCY * dt;
            vel.y = Math.max(-1.2f, Math.min(vel.y, 1.6f));
        } else {
            vel.y += EntityPhysics.GRAVITY * dt;
            if (vel.y < maxFallSpeed)
                vel.y = maxFallSpeed;
        }
        boolean hitWall = false, onGround = false;
        pos.x += vel.x * dt;
        if (legacyResolve(world, pos, width, height, 0, vel.x)) { vel.x = 0f; hitWall = true; }
        pos.z += vel.z * dt;
        if (legacyResolve(world, pos, width, height, 2, vel.z)) { vel.z = 0f; hitWall = true; }
        pos.y += vel.y * dt;
        if (legacyResolve(world, pos, width, height, 1, vel.y)) {
            if (vel.y < 0f) onGround = true;
            vel.y = 0f;
        }
        if (!onGround) {
            float hw = width / 2f;
            int y = (int) Math.floor(pos.y - 1e-3f);
            int xa = (int) Math.floor(pos.x - hw + 1e-3f), xb = (int) Math.floor(pos.x + hw - 1e-3f);
            int za = (int) Math.floor(pos.z - hw + 1e-3f), zb = (int) Math.floor(pos.z + hw - 1e-3f);
            for (int x = xa; x <= xb && !onGround; x++)
                for (int z = za; z <= zb; z++)
                    if (world.isSolid(x, y, z)) { onGround = true; break; }
        }
        return new EntityPhysics.Contact(onGround, hitWall, inWater);
    }

    private static boolean legacyResolve(World world, Vector3f pos, float width, float height, int axis, float dir) {
        if (dir == 0f) return false;
        float eps = 1e-4f, hw = width / 2f;
        int x0 = (int) Math.floor(pos.x - hw), x1 = (int) Math.floor(pos.x + hw);
        int y0 = (int) Math.floor(pos.y), y1 = (int) Math.floor(pos.y + height);
        int z0 = (int) Math.floor(pos.z - hw), z1 = (int) Math.floor(pos.z + hw);
        boolean hit = false;
        for (int x = x0; x <= x1; x++)
            for (int y = y0; y <= y1; y++)
                for (int z = z0; z <= z1; z++) {
                    if (!world.isSolid(x, y, z)) continue;
                    hit = true;
                    switch (axis) {
                        case 0 -> pos.x = dir > 0 ? x - hw - eps : x + 1 + hw + eps;
                        case 1 -> pos.y = dir > 0 ? y - height - eps : y + 1 + eps;
                        default -> pos.z = dir > 0 ? z - hw - eps : z + 1 + hw + eps;
                    }
                }
        return hit;
    }

    private static boolean legacyWater(World world, Vector3f pos, float width, float height) {
        float hw = width / 2f;
        int x0 = (int) Math.floor(pos.x - hw), x1 = (int) Math.floor(pos.x + hw - 0.01f);
        int y0 = (int) Math.floor(pos.y + 0.1f), y1 = (int) Math.floor(pos.y + height * 0.75f);
        int z0 = (int) Math.floor(pos.z - hw), z1 = (int) Math.floor(pos.z + hw - 0.01f);
        for (int x = x0; x <= x1; x++)
            for (int y = y0; y <= y1; y++)
                for (int z = z0; z <= z1; z++) {
                    BlockType b = world.getBlock(x, y, z);
                    if (b == BlockType.WATER || b == BlockType.WATER_FLOW) return true;
                }
        return false;
    }

    private static void mobCubeParity() {
        Random rnd = new Random(3_000_003L);
        BlockType[] cubes = { BlockType.STONE, BlockType.GLASS, BlockType.DOOR_CLOSED, BlockType.WATER,
                BlockType.SAND };
        float[][] bodies = { { 0.6f, 1.95f }, { 0.9f, 1.4f }, { 1.4f, 0.9f }, { 0.25f, 0.25f } };
        float[] dts = { 1f / 60f, 0.05f, 0.1f };
        int steps = 0, walls = 0;
        for (int world = 0; world < 10; world++) {
            World w = flat(100 + world);
            for (int x = 2; x <= 13; x++)
                for (int z = 2; z <= 13; z++)
                    for (int y = 41; y <= 44; y++)
                        if (rnd.nextFloat() < 0.2f)
                            w.setBlock(x, y, z, cubes[rnd.nextInt(cubes.length)]);
            for (int i = 0; i < 100; i++) {
                float[] body = bodies[rnd.nextInt(bodies.length)];
                float dt = dts[rnd.nextInt(dts.length)];
                Vector3f pos = new Vector3f(4 + rnd.nextFloat() * 8, 41.0001f + rnd.nextFloat() * 3,
                        4 + rnd.nextFloat() * 8);
                Vector3f vel = new Vector3f(rnd.nextFloat() * 12 - 6, rnd.nextFloat() * 28 - 20, rnd.nextFloat() * 12 - 6);
                Vector3f oldPos = new Vector3f(pos), oldVel = new Vector3f(vel);
                EntityPhysics.Contact now = EntityPhysics.step(w, pos, vel, body[0], body[1], dt, -40f);
                EntityPhysics.Contact old = legacyStep(w, oldPos, oldVel, body[0], body[1], dt, -40f);
                steps++;
                if (old.hitWall()) walls++;
                check(now.equals(old) && Float.floatToIntBits(pos.x) == Float.floatToIntBits(oldPos.x)
                                && Float.floatToIntBits(pos.y) == Float.floatToIntBits(oldPos.y)
                                && Float.floatToIntBits(pos.z) == Float.floatToIntBits(oldPos.z) && vel.equals(oldVel),
                        "step " + steps + ": now " + pos + " " + vel + " " + now + " / before " + oldPos + " "
                                + oldVel + " " + old);
            }
        }
        check(steps == 1000 && walls > 100, "the steps must hit walls often enough: " + walls);
    }

    private static void mobOnStairs() {
        World w = flat(9L);
        w.setBlock(5, 41, 5, BlockType.STAIRS, (byte) 1);      // step on the high-X half
        float width = 0.6f, height = 1.95f, eps = 1e-4f;

        // Falling over the slab's half lands on the slab, not on an imagined cube.
        Vector3f pos = new Vector3f(5.15f, 43f, 5.5f), vel = new Vector3f();
        EntityPhysics.Contact c = null;
        for (int i = 0; i < 60; i++)
            c = EntityPhysics.step(w, pos, vel, width, height, 1f / 60f, -40f);
        check(Math.abs(pos.y - (41.5f + eps)) < 1e-4f && c.onGround(), "a mob lands on the slab: " + pos);

        // Standing there, it walks into the step's riser and says so.
        boolean wall = false;
        for (int i = 0; i < 30; i++) {
            vel.x = 3f;
            wall |= EntityPhysics.step(w, pos, vel, width, height, 1f / 60f, -40f).hitWall();
        }
        check(wall && Math.abs(pos.x - (5.5f - width / 2f - eps)) < 1e-4f, "the riser stops it: " + pos);
        check(Math.abs(pos.y - (41.5f + eps)) < 1e-4f, "and it does not climb: " + pos);

        // From the floor, the slab is a wall half a block high: no step-up, a jump is the AI's to make.
        Vector3f low = new Vector3f(4.2f, 41.0001f, 5.5f), lowVel = new Vector3f();
        wall = false;
        for (int i = 0; i < 30; i++) {
            lowVel.x = 3f;
            wall |= EntityPhysics.step(w, low, lowVel, width, height, 1f / 60f, -40f).hitWall();
        }
        check(wall && Math.abs(low.x - (5f - width / 2f - eps)) < 1e-4f && low.y < 41.01f,
                "the slab stops a mob on the floor: " + low);

        // Over the step's half it stands on the step.
        Vector3f high = new Vector3f(5.8f, 43f, 5.5f), highVel = new Vector3f();
        for (int i = 0; i < 60; i++)
            c = EntityPhysics.step(w, high, highVel, width, height, 1f / 60f, -40f);
        check(Math.abs(high.y - (42f + eps)) < 1e-4f && c.onGround(), "a mob lands on the step: " + high);
    }
}
